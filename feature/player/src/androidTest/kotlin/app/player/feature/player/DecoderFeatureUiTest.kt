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
import app.player.core.testing.Ears
import app.player.core.testing.evidenceShot
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

    @Ears("US-35.4")
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
        // The hosted emulator can drop the first tap while the player starts: tap until it shows.
        repeat(3) { if (!shown("more")) { compose.onNodeWithTag("video").performClick(); runCatching { compose.waitUntil(3_000) { shown("more") } } } }
        compose.onNodeWithTag("more").performClick()
        compose.waitUntil(3_000) { shown("menu-deinterlace") }
        compose.onNodeWithTag("menu-deinterlace").performScrollTo().performClick()
        compose.waitUntil(3_000) { shown("deint-switch") }
        shot("p15-deint-disabled-hw")
        val before = engine.state.value.positionMs
        val t0 = SystemClock.elapsedRealtime()
        compose.onNodeWithTag("deint-switch").performClick()
        // Engine judges a pick once mpv reports the decoder, up to 5 s (3789810).
        compose.waitUntil(8_000) { engine.state.value.activeDecoder != ActiveDecoder.HW }
        val after = engine.state.value.positionMs
        log("US-35.4 switch pressed: now ${engine.state.value.activeDecoder} after ${SystemClock.elapsedRealtime() - t0} ms, position $before -> $after")
        compose.waitUntil(8_000) { !shown("deint-switch") }
        // US-35.1 with engine's notice: the badge names the decoder that runs, the same one the
        // notice names when the pick fell back (this emulator has no working HW+).
        SystemClock.sleep(1_000)
        val running = engine.state.value.activeDecoder.label
        val notice = engine.state.value.decoderNotice
        val badge = compose.onNodeWithTag("decoder-badge", useUnmergedTree = true).fetchSemanticsNode()
            .config[androidx.compose.ui.semantics.SemanticsProperties.ContentDescription].joinToString()
        log("US-35.1 after picking HW+: badge \"$badge\", notice \"$notice\", running $running")
        shot("p15-deint-enabled-after-switch")
        org.junit.Assert.assertTrue("badge names the running decoder", badge.contains("Decoder $running."))
        if (notice != null) org.junit.Assert.assertTrue("notice names the same decoder", notice.endsWith("Playing with $running."))
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

    /** Saved per test by core/testing, so the EARS report links it to the criterion. */
    private fun shot(name: String) {
        SystemClock.sleep(400)
        evidenceShot(name)
    }

    private fun log(line: String) {
        android.util.Log.i("DecoderFeatureUiTest", line)
        val dir = File(context.getExternalFilesDir(null), "evidence").apply { mkdirs() }
        File(dir, "numbers.txt").appendText(line + "\n")
    }
}
