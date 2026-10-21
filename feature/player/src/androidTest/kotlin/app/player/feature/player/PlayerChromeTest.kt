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
        // The hosted emulator can drop the first tap while the player is still starting: tap again.
        runCatching { compose.waitUntil(3_000) { shown("back") } }.onFailure {
            compose.onNodeWithTag("video").performClick()
            compose.waitUntil(3_000) { shown("back") }
        }
        val shownAt = SystemClock.elapsedRealtime()
        // No screenshot while the overlay is about to animate out: taking one during a layout
        // pass made Espresso's idle check fail on the hosted emulator. 01-controls comes from
        // controlsStayWhilePaused instead.
        // Effects run on the rule's clock, which does not move by itself while nothing draws:
        // move it past the 3 s delay. The exact 3 s is proven by the unit test AutoHideTest.
        compose.mainClock.advanceTimeBy(2_500)
        compose.waitForIdle()
        assertTrue("still shown before 3 s", shown("back"))
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitUntil(3_000) { !shown("back") }
        val hiddenAfter = SystemClock.elapsedRealtime() - shownAt
        log("auto-hide: shown at 2.5 s, hidden after 3.5 s on the test clock ($hiddenAfter ms wall clock)")
        shot("02-hidden")
    }

    @Test fun controlsStayWhilePaused_US16_3() {
        showControls("back")
        compose.onNodeWithTag("play-pause").performClick()
        // Past the 3 s auto-hide on the rule's clock, and in wall time.
        compose.mainClock.advanceTimeBy(5_000)
        SystemClock.sleep(2_000)
        compose.waitForIdle()
        assertTrue(shown("back"))
        shot("01-controls")
        shot("03-paused")
    }

    @Test fun systemBarsFollowControls_US16_5() {
        showControls("back")
        SystemClock.sleep(500)
        assertTrue("bars visible with controls", barsVisible())
        compose.onNodeWithTag("video").performClick()
        compose.waitUntil(2_000) { !shown("back") }
        SystemClock.sleep(500)
        assertFalse("bars hidden without controls", barsVisible())
    }

    @Test fun screenOnWhilePlayingAndPaused_US60_1() {
        // Read from the window manager (dumpsys), the state the system acts on.
        fun step(name: String, want: Boolean) {
            // compose.waitUntil lets the test rule's frame clock run, so the screen recomposes
            // while we wait; a plain sleep loop would freeze it (the earlier failures).
            runCatching { compose.waitUntil(8_000) { keepScreenOn() == want } }
                .onFailure { log("US-60.1 $name: wanted $want, window block: ${windowBlock().take(1_200)}") }
                .getOrThrow()
            log("US-60.1 $name: keep-screen-on ${keepScreenOn()}")
        }
        SystemClock.sleep(1_000)
        step("playing", true)
        // Preconditions, logged so a failure says which side is wrong.
        waitUntil(3_000) { !PlayerSettings.get(context).prefs.value.keepScreenOnPaused }
        // Pause the way a viewer does: the play/pause button.
        showControls("play-pause")
        compose.onNodeWithTag("play-pause").performClick()
        waitUntil(3_000) { mpvPaused() == true }
        log("US-60.1 after the pause tap: mpv pause=${mpvPaused()}, setting keepScreenOnPaused=${PlayerSettings.get(context).prefs.value.keepScreenOnPaused}")
        step("paused, setting off", false)
        PlayerSettings.get(context).update { it.copy(keepScreenOnPaused = true) }
        step("paused, setting on", true)
    }

    @Test fun talkBackActions_US69_2() {
        val engine = PlayerSessions.current.value!!
        val audio = context.getSystemService(android.media.AudioManager::class.java)
        val max = audio.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
        audio.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, max / 2, 0)
        fun run(label: String) {
            val actions = compose.onNodeWithTag("video").fetchSemanticsNode()
                .config[androidx.compose.ui.semantics.SemanticsActions.CustomActions]
            compose.runOnUiThread { actions.first { it.label == label }.action() }
            SystemClock.sleep(700)
        }
        val labels = compose.onNodeWithTag("video").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsActions.CustomActions]
        log("US-69.2 actions on the video: ${labels.map { it.label }}")
        assertEquals(6, labels.size)

        val p0 = engine.state.value.positionMs
        run("Forward 10 seconds")
        val p1 = engine.state.value.positionMs
        run("Back 10 seconds")
        val p2 = engine.state.value.positionMs
        log("US-69.2 seek: $p0 -> forward $p1 -> back $p2 ms")
        assertTrue(p1 - p0 in 9_000..11_500)
        assertTrue(p1 - p2 in 8_500..11_500)

        val v0 = audio.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
        run("Volume up")
        val v1 = audio.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
        run("Volume down")
        val v2 = audio.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
        log("US-69.2 volume steps: $v0 -> up $v1 -> down $v2 of $max")
        assertEquals(v0 + 1, v1)
        assertEquals(v0, v2)

        fun brightness(): Float { var b = 0f; scenario.onActivity { b = it.window.attributes.screenBrightness }; return b }
        run("Brightness up")
        val b1 = brightness()
        run("Brightness down")
        val b2 = brightness()
        log("US-69.2 window brightness: up $b1 -> down $b2")
        assertTrue(b1 in 0f..1f && b2 in 0f..1f && b1 - b2 > 0.05f)
    }

    @Test fun remainingTimeSurvivesReopen_US16_4() {
        repeat(3) { if (!shown("end-time")) { compose.onNodeWithTag("video").performClick(); runCatching { compose.waitUntil(3_000) { shown("end-time") } } } }
        compose.onNodeWithTag("end-time").performClick()
        waitUntil(2_000) { PlayerSettings.get(context).prefs.value.showRemaining }
        scenario.close()
        scenario = ActivityScenario.launch(PlayerActivity.intent(context, clip()))
        // Through the compose rule, so the new window is laid out before the first tap.
        compose.waitUntil(10_000) { (PlayerSessions.current.value?.state?.value?.positionMs ?: 0) > 500 }
        compose.waitForIdle()
        // Show the controls and read at once; tap again if auto-hide got there first.
        fun endTime() = runCatching {
            if (!shown("end-time")) compose.onNodeWithTag("video").performClick()
            compose.waitUntil(2_000) { shown("end-time") }
            compose.onNodeWithTag("end-time", useUnmergedTree = true).fetchSemanticsNode()
        }
        val desc = (endTime().getOrNull() ?: endTime().getOrThrow())
            .config[androidx.compose.ui.semantics.SemanticsProperties.ContentDescription].joinToString()
        log("US-16.4 after reopen the end time reads: $desc")
        assertTrue(desc.startsWith("Remaining"))
        shot("09-remaining-after-reopen")
    }

    @Test fun backCallbackOnlyWithTheSetting_US78_2() {
        var enabled = true
        scenario.onActivity { enabled = it.onBackPressedDispatcher.hasEnabledCallbacks() }
        log("US-78.2 setting off: back callback enabled = $enabled")
        assertFalse(enabled)
        PlayerSettings.get(context).update { it.copy(backTwice = true) }
        waitUntil(2_000) { PlayerSettings.get(context).prefs.value.backTwice }
        compose.waitForIdle()
        SystemClock.sleep(300)
        scenario.onActivity { enabled = it.onBackPressedDispatcher.hasEnabledCallbacks() }
        log("US-78.2 setting on: back callback enabled = $enabled (so the system draws no back preview on the first press)")
        assertTrue(enabled)
    }

    @Test fun decoderSwitchKeepsPosition_US35_2() {
        val engine = PlayerSessions.current.value!!
        SystemClock.sleep(2_000)
        // The emulator has a hardware path only sometimes. Start from HW when it can run, so the
        // picker switches between two decoders that both work here.
        if (engine.state.value.activeDecoder == ActiveDecoder.SW) {
            engine.setDecoder(app.player.core.engine.DecoderMode.HW)
            runCatching { waitUntil(3_000) { engine.state.value.activeDecoder == ActiveDecoder.HW } }
            log("decoder: SW at start, asked HW, now ${engine.state.value.activeDecoder}")
            org.junit.Assume.assumeTrue("no hardware decoder on this emulator run", engine.state.value.activeDecoder == ActiveDecoder.HW)
            SystemClock.sleep(1_000)
        }
        showControls("decoder-badge")
        compose.onNodeWithTag("decoder-badge").performClick()
        compose.waitUntil(2_000) { shown("decoder-picker") }
        shot("04-decoder-picker")
        log("decoder before switch: ${engine.state.value.activeDecoder} (${engine.state.value.decoderMode})")
        val before = engine.state.value.positionMs
        val t0 = SystemClock.elapsedRealtime()
        // Pick a decoder other than the one running, so the switch is real.
        val from = engine.state.value.activeDecoder
        val target = if (from == ActiveDecoder.SW) "HW" else "SW"
        compose.onNodeWithTag("decoder-$target").performClick()
        waitUntil(3_000) { engine.state.value.activeDecoder != from }
        val switchMs = SystemClock.elapsedRealtime() - t0
        // US-35.2: continues from the same position. Read the first position once the new decoder
        // runs; playback may stall during the switch, which the criterion allows.
        val after = engine.state.value.positionMs
        SystemClock.sleep(500)
        log("decoder switch to $target: playback restarted after $switchMs ms, position $before -> $after (difference ${after - before} ms), now running ${engine.state.value.activeDecoder}")
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
        // mpv's own flag: PlaybackState.paused can read true while playing (engine bug, reported).
        val pausedNow = kotlinx.coroutines.runBlocking { PlayerSessions.current.value?.get("pause", app.player.core.engine.PropertyType.Flag) }
        assertTrue("still playing", pausedNow == false)
        // The checks and the shot above can outlast the 2 s window on the hosted emulator, so the
        // leaving pair is pressed fresh: one press arms again, the next within the window leaves.
        SystemClock.sleep(BackTwice.WINDOW_MS + 200)
        back()
        back()
        val t0 = SystemClock.elapsedRealtime()
        var left = false
        while (!left && SystemClock.elapsedRealtime() - t0 < 3_000) {
            left = scenario.state == androidx.lifecycle.Lifecycle.State.DESTROYED || run {
                var f = false
                scenario.onActivity { f = it.isFinishing }
                f
            }
            if (!left) SystemClock.sleep(100)
        }
        assertTrue("second back within the window left the player", left)
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
        SystemClock.sleep(1_500)
        shot("06-kept-status")
    }

    @Test fun menuOpensSideSheetWithSettings_US65_3() {
        showControls("more")
        compose.onNodeWithTag("more").performClick()
        compose.waitUntil(2_000) { shown("menu-player-settings") }
        shot("07-menu")
        compose.onNodeWithTag("menu-player-settings").performClick()
        compose.waitUntil(2_000) { shown("player-settings") }
        shot("08-player-settings")
        assertTrue("playback continued", PlayerSessions.current.value?.state?.value?.paused == false)
    }

    /** Taps the video until [tag] shows: the hosted emulator can drop a tap while the player starts. */
    private fun showControls(tag: String) {
        repeat(3) {
            if (shown(tag)) return
            compose.onNodeWithTag("video").performClick()
            if (runCatching { compose.waitUntil(3_000) { shown(tag) } }.isSuccess) return
        }
        compose.waitUntil(3_000) { shown(tag) }
    }

    private fun back() = scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }

    private fun shown(tag: String): Boolean =
        compose.onAllNodes(androidx.compose.ui.test.hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun barsVisible(): Boolean {
        var visible = false
        scenario.onActivity { visible = it.window.decorView.rootWindowInsets.isVisible(WindowInsets.Type.statusBars()) }
        return visible
    }

    /** KEEP_SCREEN_ON on the player's window, as the window manager reports it. */
    private fun mpvPaused(): Boolean? =
        kotlinx.coroutines.runBlocking { PlayerSessions.current.value?.get("pause", app.player.core.engine.PropertyType.Flag) }

    private fun keepScreenOn(): Boolean = Regex("fl=[^ ]*KEEP_SCREEN_ON").containsMatchIn(windowBlock())

    /** The player window's entry in `dumpsys window windows`, from its header to the next window. */
    private fun windowBlock(): String {
        val out = InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand("dumpsys window windows")
            .let { pfd -> android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd).bufferedReader().readText() }
        // The newest player window: windows of players closed by earlier tests can still be listed.
        val start = Regex("Window #\\d+ Window\\{[^}]*PlayerActivity\\}").findAll(out)
            .filter { m -> out.substring(m.range.first, (m.range.first + 3_000).coerceAtMost(out.length)).contains("mViewVisibility=0x0") }
            .lastOrNull()?.range?.first ?: return ""
        val next = Regex("Window #\\d+ ").find(out, start + 10)?.range?.first ?: out.length
        return out.substring(start, next)
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
        InstrumentationRegistry.getInstrumentation().context.assets.open("clip.mp4").use { input ->
            file.outputStream().use { input.copyTo(it) }
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
