package app.player.feature.player

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.pinch
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import app.player.core.engine.PlayerEngine
import app.player.core.engine.PlayerSessions
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Gestures on a real engine (emulator): readouts on screen while the finger is down. */
class GestureUiTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var scenario: ActivityScenario<PlayerActivity>
    private lateinit var engine: PlayerEngine

    private fun open() {
        scenario = ActivityScenario.launch(PlayerActivity.intent(context, copy("clip.mp4")))
        waitUntil(10_000) { (PlayerSessions.current.value?.state?.value?.positionMs ?: 0) > 300 }
        engine = PlayerSessions.current.value!!
        compose.onNodeWithTag("video").performTouchInput { click(center) }
        compose.waitUntil(2_000) { shown("back") }
        compose.onNodeWithTag("video").performTouchInput { click(center) }
        compose.waitUntil(2_000) { !shown("back") }
    }

    @After fun close() = scenario.close()

    @Test fun seekSwipeShowsTargetAndDelta_US17_1() {
        open()
        val before = engine.state.value.positionMs
        compose.onNodeWithTag("video").performTouchInput {
            down(Offset(width * 0.3f, height * 0.5f))
            repeat(10) { moveBy(Offset(width * 0.03f, 0f)) }
        }
        compose.waitUntil(1_000) { shown("readout-seek") }
        shot("p110-seek-readout")
        compose.onNodeWithTag("video").performTouchInput { up() }
        SystemClock.sleep(800)
        val after = engine.state.value.positionMs
        log("seek swipe 30% of width: $before -> $after ms (expect about +27 s)")
        assertTrue(after - before in 20_000..34_000 || after >= engine.state.value.durationMs - 1_000)
    }

    @Test fun volumeSwipeShowsLevel_US18_2() {
        open()
        compose.onNodeWithTag("video").performTouchInput {
            down(Offset(width * 0.8f, height * 0.7f))
            repeat(10) { moveBy(Offset(0f, -height * 0.03f)) }
        }
        compose.waitUntil(1_000) { shown("readout-level") }
        shot("p110-volume-readout")
        compose.onNodeWithTag("video").performTouchInput { up() }
    }

    @Test fun brightnessSwipeShowsLevel_US18_1() {
        open()
        compose.onNodeWithTag("video").performTouchInput {
            down(Offset(width * 0.2f, height * 0.3f))
            repeat(10) { moveBy(Offset(0f, height * 0.03f)) }
        }
        compose.waitUntil(1_000) { shown("readout-level") }
        shot("p110-brightness-readout")
        var level = -1f
        scenario.onActivity { level = it.window.attributes.screenBrightness }
        log("brightness after swipe down: window $level")
        compose.onNodeWithTag("video").performTouchInput { up() }
        assertTrue(level in 0f..1f)
    }

    @Test fun doubleTapRightSkips_US19_2() {
        open()
        val before = engine.state.value.positionMs
        compose.onNodeWithTag("video").performTouchInput { doubleClick(Offset(width * 0.85f, height * 0.5f)) }
        compose.waitUntil(1_000) { shown("readout-skip") }
        shot("p110-skip-readout")
        SystemClock.sleep(500)
        log("double tap right: $before -> ${engine.state.value.positionMs} ms")
    }

    @Test fun holdPlaysFast_US20() {
        open()
        compose.onNodeWithTag("video").performTouchInput {
            down(center)
            advanceEventTime(800)
            moveBy(Offset(1f, 0f))
        }
        compose.waitUntil(2_000) { shown("readout-fast") }
        shot("p110-fast-readout")
        compose.onNodeWithTag("video").performTouchInput { up() }
    }

    @Test fun pinchShowsZoom_US55_1() {
        open()
        compose.onNodeWithTag("video").performTouchInput {
            pinch(center - Offset(50f, 0f), center + Offset(50f, 0f), center - Offset(300f, 0f), center + Offset(300f, 0f), 400)
        }
        SystemClock.sleep(100)
        shot("p110-pinch-after")
    }

    private fun shown(tag: String) = compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    /** Waits through the compose rule, so frames run while we wait. */
    private fun waitUntil(ms: Long, check: () -> Boolean) = compose.waitUntil(ms) { check() }

    private fun copy(asset: String): Uri {
        val file = File(context.cacheDir, asset)
        InstrumentationRegistry.getInstrumentation().context.assets.open(asset).use { input ->
            file.outputStream().use { input.copyTo(it) }
        }
        return Uri.fromFile(file)
    }

    private fun shot(name: String) {
        SystemClock.sleep(150)
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot() ?: return
        val dir = File(context.getExternalFilesDir(null), "evidence").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun log(line: String) {
        android.util.Log.i("GestureUiTest", line)
        val dir = File(context.getExternalFilesDir(null), "evidence").apply { mkdirs() }
        File(dir, "numbers.txt").appendText(line + "\n")
    }
}
