package app.player.feature.player

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import app.player.core.engine.ActiveDecoder
import app.player.core.engine.DecoderMode
import app.player.core.engine.PlayerEngine
import app.player.core.engine.PlayerSessions
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

/** US-35.4 on the emulator: the deinterlace panel while HW (zero-copy) runs. */
class DecoderFeatureUiTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var scenario: ActivityScenario<PlayerActivity>
    private lateinit var engine: PlayerEngine

    @After fun close() = scenario.close()

    @Test fun deinterlaceDisabledOnHwWithSwitch_US35_4() {
        scenario = ActivityScenario.launch(PlayerActivity.intent(context, copy("interlaced.mp4")))
        waitUntil(10_000) { (PlayerSessions.current.value?.state?.value?.positionMs ?: 0) > 300 }
        engine = PlayerSessions.current.value!!
        if (engine.state.value.activeDecoder != ActiveDecoder.HW) {
            engine.setDecoder(DecoderMode.HW)
            runCatching { waitUntil(3_000) { engine.state.value.activeDecoder == ActiveDecoder.HW } }
        }
        log("US-35.4 decoder before opening the panel: ${engine.state.value.activeDecoder}")
        assumeTrue("no hardware decoder on this emulator run", engine.state.value.activeDecoder == ActiveDecoder.HW)
        compose.onNodeWithTag("video").performClick()
        compose.waitUntil(2_000) { shown("more") }
        compose.onNodeWithTag("more").performClick()
        compose.waitUntil(2_000) { shown("menu-deinterlace") }
        compose.onNodeWithTag("menu-deinterlace").performScrollTo().performClick()
        compose.waitUntil(2_000) { shown("deint-switch") }
        shot("p15-deint-disabled-hw")
        val before = engine.state.value.positionMs
        val t0 = SystemClock.elapsedRealtime()
        compose.onNodeWithTag("deint-switch").performClick()
        waitUntil(3_000) { engine.state.value.activeDecoder != ActiveDecoder.HW }
        val after = engine.state.value.positionMs
        log("US-35.4 switch pressed: now ${engine.state.value.activeDecoder} after ${SystemClock.elapsedRealtime() - t0} ms, position $before -> $after")
        compose.waitUntil(2_000) { !shown("deint-switch") }
        shot("p15-deint-enabled-after-switch")
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
        InstrumentationRegistry.getInstrumentation().context.assets.open(asset).use { input ->
            file.outputStream().use { input.copyTo(it) }
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
        android.util.Log.i("DecoderFeatureUiTest", line)
        val dir = File(context.getExternalFilesDir(null), "evidence").apply { mkdirs() }
        File(dir, "numbers.txt").appendText(line + "\n")
    }
}
