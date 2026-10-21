package app.player.feature.subtitles

import android.net.Uri
import androidx.test.core.app.ActivityScenario
import app.player.core.engine.PlayerEngine
import app.player.core.engine.PropertyType
import app.player.core.testing.Ears
import app.player.core.testing.evidenceShot
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import java.io.File

/** P1.29: the subtitle-gesture hook against real libmpv. The finger side is player-ui's (P1.10). */
class GesturesOnDeviceTest {
    @get:Rule val timeout: Timeout = Timeout.seconds(300)

    private lateinit var scenario: ActivityScenario<VideoTestActivity>
    private lateinit var engine: PlayerEngine
    private val holder = Any()
    private val gestures = SubtitlesExtension.subtitleGestures

    @Before fun open(): Unit = runBlocking {
        SubtitlePrefsStore(OnDevice.context).setShowAutomatically(false)
        scenario = ActivityScenario.launch(VideoTestActivity::class.java)
        engine = OnDevice.acquire(holder) { SubtitlesExtension.onEngineCreated(OnDevice.context, it) }
        OnDevice.main { SubtitlesExtension.styles(OnDevice.context).reset() }
        scenario.onActivity { it.surface.engine = engine }
        engine.open(Uri.fromFile(OnDevice.asset("wide.mkv")))
        engine.await("track-list/count", PropertyType.Int64, 15_000) { (it ?: 0) >= 2 }
        engine.awaitAutoLoad()
        val srt = File(OnDevice.context.cacheDir, "lines.srt").apply {
            writeText("1\n00:00:01,000 --> 00:00:03,000\nFirst line\n\n2\n00:00:04,000 --> 00:00:06,000\nSecond line\n\n3\n00:00:07,000 --> 00:00:09,000\nThird line\n")
        }
        engine.pause()
        SubtitleController(engine.asMpv()).addFile(srt.path, "lines.srt")
        engine.seekTo(4500)
        engine.await("sub-text", PropertyType.Text) { it == "Second line" }
        // The saved style loads from disk after the engine starts and can land after the reset
        // above (the drag test saves a moved, scaled one): reset again now that it has loaded.
        OnDevice.main { SubtitlesExtension.styles(OnDevice.context).reset() }
        engine.await("sub-scale", PropertyType.Real) { it == 1.0 }
        delay(800)
    }

    @After fun close() {
        runBlocking { SubtitlePrefsStore(OnDevice.context).setShowAutomatically(true) }
        if (!::scenario.isInitialized) return
        OnDevice.main { SubtitlesExtension.styles(OnDevice.context).reset() }
        // Saves are debounced (300 ms): let the reset reach disk before the next test loads it.
        Thread.sleep(800)
        runCatching { scenario.onActivity { it.surface.engine = null } }
        OnDevice.release(holder)
        scenario.close()
    }

    private fun viewSize(): Pair<Int, Int> {
        var size = 0 to 0
        scenario.onActivity { size = it.surface.width to it.surface.height }
        return size
    }

    @Ears("US-45.1")
    @Test fun touchOnTheLineSteps_US45_1() = runBlocking {
        val (w, h) = viewSize()
        // The line sits low and centred; the top of the picture is not subtitle text.
        val onLine = (h * 3 / 4 until h step 4).any { y -> OnDevice.main { gestures.hit(w / 2f, y.toFloat(), w, h) } }
        assertTrue("no hit on the shown line: ${OnDevice.main { SubtitlesExtension.touch?.describe(w, h) }}", onLine)
        assertFalse(OnDevice.main { gestures.hit(w / 2f, h * 0.1f, w, h) })
        evidenceShot("us45-line-shown")
        OnDevice.main { gestures.step(1) }
        engine.await("sub-text", PropertyType.Text) { it == "Third line" }
        val next = engine.get("time-pos", PropertyType.Real)!!
        OnDevice.main { gestures.step(-1) }
        engine.await("sub-text", PropertyType.Text) { it == "Second line" }
        val back = engine.get("time-pos", PropertyType.Real)!!
        assertEquals(7.0, next, 0.1)
        assertEquals(4.0, back, 0.1)
        evidence("US-45.1 hit on the shown line, not on the top of the picture; next line at ${"%.2f".format(next)} s, previous at ${"%.2f".format(back)} s")
    }

    @Ears("US-45.2", "US-45.3")
    @Test fun dragMovesAndPinchScales_US45_2_US45_3() = runBlocking {
        val (_, h) = viewSize()
        val before = engine.get("sub-margin-y", PropertyType.Int64)!!
        OnDevice.main { gestures.drag(-h * 0.2f, h, end = false) }
        OnDevice.main { gestures.drag(0f, h, end = true) }
        engine.await("sub-margin-y", PropertyType.Int64) { (it ?: 0) > before + 100 }
        delay(800)
        evidenceShot("us45-dragged-up")
        val margin = OnDevice.main { SubtitlesExtension.styles(OnDevice.context).current().bottomMarginPercent }
        OnDevice.main { gestures.scale(2f) }
        engine.await("sub-scale", PropertyType.Real) { it == 2.0 }
        OnDevice.main { gestures.scale(4f) }
        engine.await("sub-scale", PropertyType.Real) { it == 3.0 }
        OnDevice.main { gestures.scale(0.01f) }
        engine.await("sub-scale", PropertyType.Real) { it == 0.5 }
        OnDevice.main { gestures.scale(3f) }
        engine.await("sub-scale", PropertyType.Real) { it == 1.5 }
        delay(800)
        evidenceShot("us45-pinched-150")
        // Kept: the style store has it, so the next file and the next start use it.
        assertEquals(margin, OnDevice.main { SubtitlesExtension.styles(OnDevice.context).current().bottomMarginPercent })
        evidence("US-45.2 drag up 20% of the view: sub-margin-y $before -> ${engine.get("sub-margin-y", PropertyType.Int64)}, margin kept at $margin%; US-45.3 pinch 2x -> 200%, 4x -> 300% (cap), 0.01x -> 50% (floor), 3x -> 150%")
    }
}
