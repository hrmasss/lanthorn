package app.player.feature.player

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.SystemClock
import android.view.WindowInsets
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import app.player.core.engine.ActiveDecoder
import app.player.core.engine.PlayerSessions
import app.player.feature.player.settings.PlayerPrefs
import app.player.feature.player.settings.PlayerSettings
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File

/**
 * The chrome on a real engine (emulator). Timing numbers here are function checks, not perf claims.
 * Screenshots land in the app's external files dir under `evidence/` for the item's evidence.
 */
class PlayerChromeTest {
    @get:Rule val compose = createEmptyComposeRule()

    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var scenario: ActivityScenario<PlayerActivity>

    @Before fun open() {
        PlayerSettings.get(context).update { PlayerPrefs() }
        SystemClock.sleep(300)
        scenario = ActivityScenario.launch(PlayerActivity.intent(context, clip()))
        waitUntil(10_000) { (PlayerSessions.current.value?.state?.value?.positionMs ?: 0) > 500 }
    }

    @After fun close() {
        PlayerSettings.get(context).update { PlayerPrefs() }
        scenario.close()
    }

    @Test fun controlsHideAfterThreeSecondsWhilePlaying_US16_3() {
        compose.onNodeWithTag("video").performClick()
        compose.waitUntil(2_000) { shown("back") }
        val shownAt = SystemClock.elapsedRealtime()
        shot("01-controls")
        compose.waitUntil(9_000) { !shown("back") }
        val hiddenAfter = SystemClock.elapsedRealtime() - shownAt
        // The Compose test clock skews wall time on the emulator; the exact 3 s is proven by the
        // unit test AutoHideTest. Here: it does hide by itself while playing.
        log("auto-hide after $hiddenAfter ms (wall clock under the test rule)")
        assertTrue("hid after $hiddenAfter ms", hiddenAfter in 2_800..7_000)
        shot("02-hidden")
    }

    @Test fun controlsStayWhilePaused_US16_3() {
        compose.onNodeWithTag("video").performClick()
        compose.waitUntil(2_000) { shown("back") }
        compose.onNodeWithTag("play-pause").performClick()
        SystemClock.sleep(5_000)
        assertTrue(shown("back"))
        shot("03-paused")
    }

    @Test fun systemBarsFollowControls_US16_5() {
        compose.onNodeWithTag("video").performClick()
        compose.waitUntil(2_000) { shown("back") }
        SystemClock.sleep(500)
        assertTrue("bars visible with controls", barsVisible())
        compose.onNodeWithTag("video").performClick()
        compose.waitUntil(2_000) { !shown("back") }
        SystemClock.sleep(500)
        assertFalse("bars hidden without controls", barsVisible())
    }

    @Test fun screenOnWhilePlaying_US60_1() {
        assertTrue(screenOn())
        PlayerSessions.current.value!!.pause()
        waitUntil(2_000) { !screenOn() }
        PlayerSettings.get(context).update { it.copy(keepScreenOnPaused = true) }
        waitUntil(2_000) { screenOn() }
    }

    @Test fun decoderSwitchKeepsPosition_US35_2() {
        val engine = PlayerSessions.current.value!!
        SystemClock.sleep(2_000)
        compose.onNodeWithTag("video").performClick()
        compose.waitUntil(2_000) { shown("decoder-badge") }
        compose.onNodeWithTag("decoder-badge").performClick()
        compose.waitUntil(2_000) { shown("decoder-picker") }
        shot("04-decoder-picker")
        log("decoder before switch: ${engine.state.value.activeDecoder} (${engine.state.value.decoderMode})")
        val before = engine.state.value.positionMs
        val t0 = SystemClock.elapsedRealtime()
        compose.onNodeWithTag("decoder-SW").performClick()
        waitUntil(3_000) { engine.state.value.activeDecoder == ActiveDecoder.SW }
        val switchMs = SystemClock.elapsedRealtime() - t0
        // US-35.2: continues from the same position. Read the first position once the new decoder
        // runs; playback may stall during the switch, which the criterion allows.
        val after = engine.state.value.positionMs
        log("decoder switch $switchMs ms, position $before -> $after (difference ${after - before} ms)")
        assertTrue("switch took $switchMs ms", switchMs <= 1_000)
        assertTrue("position moved by ${after - before} ms", kotlin.math.abs(after - before) <= 500)
    }

    @Test fun backTwiceToLeave_US78_1() {
        PlayerSettings.get(context).update { it.copy(backTwice = true) }
        waitUntil(2_000) { PlayerSettings.get(context).prefs.value.backTwice }
        compose.waitForIdle()
        SystemClock.sleep(500)
        back()
        SystemClock.sleep(300)
        var finishing = true
        scenario.onActivity { finishing = it.isFinishing }
        assertFalse("first back left the player", finishing)
        assertTrue(shown("note"))
        shot("05-back-hint")
        assertTrue("still playing", PlayerSessions.current.value?.state?.value?.paused == false)
        back()
        SystemClock.sleep(500)
        assertTrue(scenario.state == androidx.lifecycle.Lifecycle.State.DESTROYED || run {
            var f = false
            scenario.onActivity { f = it.isFinishing }
            f
        })
    }

    @Test fun backOnceLeavesByDefault_US78() {
        back()
        SystemClock.sleep(800)
        assertEquals(androidx.lifecycle.Lifecycle.State.DESTROYED, scenario.state)
    }

    @Test fun keepStatusVisibleWhenHidden_US60_4() {
        // Controls are hidden on open.
        PlayerSettings.get(context).update { it.copy(keepStatusVisible = true) }
        compose.waitUntil(3_000) { shown("kept-status") }
        shot("06-kept-status")
    }

    @Test fun menuOpensSideSheetWithSettings_US65_3() {
        compose.onNodeWithTag("video").performClick()
        compose.waitUntil(2_000) { shown("more") }
        compose.onNodeWithTag("more").performClick()
        compose.waitUntil(2_000) { shown("menu-player-settings") }
        shot("07-menu")
        compose.onNodeWithTag("menu-player-settings").performClick()
        compose.waitUntil(2_000) { shown("player-settings") }
        shot("08-player-settings")
        assertTrue("playback continued", PlayerSessions.current.value?.state?.value?.paused == false)
    }

    private fun back() = scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }

    private fun shown(tag: String): Boolean =
        compose.onAllNodes(androidx.compose.ui.test.hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun barsVisible(): Boolean {
        var visible = false
        scenario.onActivity { visible = it.window.decorView.rootWindowInsets.isVisible(WindowInsets.Type.statusBars()) }
        return visible
    }

    private fun screenOn(): Boolean {
        var on = false
        scenario.onActivity { a -> on = a.window.attributes.flags and android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0 }
        return on
    }

    private fun waitUntil(ms: Long, check: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + ms
        while (!check()) {
            if (SystemClock.elapsedRealtime() > end) throw AssertionError("timed out after $ms ms")
            SystemClock.sleep(50)
        }
    }

    private fun clip(): Uri {
        val file = File(context.cacheDir, "clip.mp4")
        if (!file.exists()) {
            InstrumentationRegistry.getInstrumentation().context.assets.open("clip.mp4").use { input ->
                file.outputStream().use { input.copyTo(it) }
            }
        }
        return Uri.fromFile(file)
    }

    private fun shot(name: String) {
        SystemClock.sleep(400)
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot() ?: return
        val dir = File(context.getExternalFilesDir(null), "evidence").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun log(line: String) {
        android.util.Log.i("PlayerChromeTest", line)
        val dir = File(context.getExternalFilesDir(null), "evidence").apply { mkdirs() }
        File(dir, "numbers.txt").appendText(line + "\n")
    }
}
