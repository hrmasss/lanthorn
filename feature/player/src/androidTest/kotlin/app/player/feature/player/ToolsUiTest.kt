package app.player.feature.player

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import app.player.core.engine.PlayerEngine
import app.player.core.engine.PlayerSessions
import app.player.core.engine.PropertyType
import app.player.feature.player.settings.PlayerPrefs
import app.player.feature.player.settings.PlayerSettings
import app.player.feature.player.tools.SleepTimer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

/** P1.13 menus on a real engine (emulator). */
class ToolsUiTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var scenario: ActivityScenario<PlayerActivity>
    private lateinit var engine: PlayerEngine

    private fun open(asset: String) {
        scenario = ActivityScenario.launch(PlayerActivity.intent(context, copy(asset)))
        waitUntil(10_000) { (PlayerSessions.current.value?.state?.value?.positionMs ?: 0) > 300 }
        engine = PlayerSessions.current.value!!
    }

    @After fun close() {
        SleepTimer.cancel()
        PlayerSettings.get(context).update { PlayerPrefs() }
        scenario.close()
    }

    private fun showControls() {
        compose.waitForIdle()
        if (!shown("more")) compose.onNodeWithTag("video").performClick()
        compose.waitUntil(2_000) { shown("more") }
    }

    private fun menu(item: String) {
        // Controls may hide between the check and the tap (auto-hide); show them again if so.
        runCatching { showControls(); compose.onNodeWithTag("more").performClick() }
            .onFailure { showControls(); compose.onNodeWithTag("more").performClick() }
        compose.waitUntil(2_000) { shown("menu-$item") }
        compose.onNodeWithTag("menu-$item").performClick()
    }

    @Test fun speedFromMenuShowsInTopBar_US23() = runBlocking<Unit> {
        open("clip.mp4")
        menu("speed")
        compose.waitUntil(2_000) { shown("speed-panel") }
        compose.onNodeWithTag("speed-1.5").performClick()
        waitUntil(1_000) { runBlocking { engine.get("speed", PropertyType.Real) } == 1.5 }
        shot("p113-speed-panel")
        compose.onNodeWithTag("panel-outside").performClick()
        showControls()
        compose.waitUntil(2_000) { shown("speed-badge") }
        shot("p113-speed-badge")
    }

    @Test fun defaultSpeedForNewVideos_US23_2() = runBlocking<Unit> {
        PlayerSettings.get(context).update { it.copy(defaultSpeed = 1.25) }
        SystemClock.sleep(500)
        open("clip.mp4")
        waitUntil(2_000) { runBlocking { engine.get("speed", PropertyType.Real) } == 1.25 }
        log("default speed 1.25: new video started at ${engine.get("speed", PropertyType.Real)}")
    }

    @Test fun abMarksOnSeekBar_US25_2() = runBlocking<Unit> {
        open("clip.mp4")
        menu("ab-repeat")
        compose.waitUntil(2_000) { shown("ab-panel") }
        compose.onNodeWithTag("ab-set-a").performClick()
        SystemClock.sleep(2_000)
        compose.onNodeWithTag("ab-set-b").performClick()
        SystemClock.sleep(300)
        shot("p113-ab-panel")
        compose.onNodeWithTag("panel-outside").performClick()
        showControls()
        compose.waitUntil(2_000) { shown("seek-marks") }
        shot("p113-ab-marks")
        val a = engine.get("ab-loop-a", PropertyType.Text)
        val b = engine.get("ab-loop-b", PropertyType.Text)
        log("A-B from menu: a=$a b=$b")
        assertTrue(a != "no" && b != "no")
    }

    @Test fun chaptersInMenuAndOnBar_US27() = runBlocking<Unit> {
        open("chapters.mkv")
        menu("chapters")
        compose.waitUntil(2_000) { shown("chapters-panel") }
        shot("p113-chapters")
        compose.onNodeWithTag("chapter-2").performClick()
        waitUntil(2_000) { engine.state.value.positionMs in 12_000..13_500 }
        compose.onNodeWithTag("panel-outside").performClick()
        showControls()
        compose.waitUntil(2_000) { shown("seek-marks") }
        shot("p113-chapter-ticks")
    }

    @Test fun sleepTimerPausesWhenItRunsOut_US26_1() {
        open("clip.mp4")
        // A 1-minute timer started 58 s ago runs out in 2 s.
        SleepTimer.start(1, finishVideo = false, now = SystemClock.elapsedRealtime() - 58_000)
        menu("sleep-timer")
        compose.waitUntil(2_000) { shown("sleep-left") }
        shot("p113-sleep-running")
        val t0 = SystemClock.elapsedRealtime()
        waitUntil(5_000) { engine.state.value.paused }
        log("sleep timer paused playback ${SystemClock.elapsedRealtime() - t0} ms after the check started (due at about 2 s)")
        assertEquals(null, SleepTimer.running.value)
    }

    @Test fun sleepTimerFinishesCurrentVideo_US26_2() {
        open("clip.mp4")
        engine.seekTo(15_000)
        SystemClock.sleep(500)
        SleepTimer.start(1, finishVideo = true, now = SystemClock.elapsedRealtime() - 60_000)
        SystemClock.sleep(1_000)
        assertTrue("still playing after the timer ran out", !engine.state.value.paused)
        waitUntil(8_000) { engine.state.value.ended || engine.state.value.paused }
        log("finish-current-video: paused at ${engine.state.value.positionMs} ms of ${engine.state.value.durationMs}")
        assertTrue(engine.state.value.positionMs > 19_000)
    }

    @Test fun screenshotOffersShare_US33_2() {
        open("clip.mp4")
        menu("screenshot")
        compose.waitUntil(3_000) { shown("note-action") }
        shot("p113-screenshot-note")
    }

    private fun shown(tag: String) = compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun waitUntil(ms: Long, check: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + ms
        while (!check()) {
            if (SystemClock.elapsedRealtime() > end) throw AssertionError("timed out after $ms ms")
            SystemClock.sleep(20)
        }
    }

    private fun copy(asset: String): Uri {
        val file = File(context.cacheDir, asset)
        if (!file.exists()) {
            InstrumentationRegistry.getInstrumentation().context.assets.open(asset).use { input ->
                file.outputStream().use { input.copyTo(it) }
            }
        }
        return Uri.fromFile(file)
    }

    private fun shot(name: String) {
        SystemClock.sleep(400)
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot() ?: return
        val dir = File(context.getExternalFilesDir(null), "evidence").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun log(line: String) {
        android.util.Log.i("ToolsUiTest", line)
        val dir = File(context.getExternalFilesDir(null), "evidence").apply { mkdirs() }
        File(dir, "numbers.txt").appendText(line + "\n")
    }
}
