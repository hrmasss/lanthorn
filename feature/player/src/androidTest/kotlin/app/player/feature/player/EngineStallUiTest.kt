package app.player.feature.player

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import app.player.core.engine.ActiveDecoder
import app.player.core.engine.PlayerEngine
import app.player.core.engine.PlayerSessions
import app.player.core.testing.Ears
import app.player.core.testing.evidenceShot
import app.player.feature.player.settings.PlayerPrefs
import app.player.feature.player.settings.PlayerSettings
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File

/**
 * P2.6: a hardware decoder that stops answering inside mpv holds the engine thread. The player must
 * not freeze: the UI thread keeps answering, Back still leaves, and the watchdog replaces the engine
 * with one on SW at the same place (NF-5 mid-playback). The stall is injected: the engine thread is
 * held, as a stuck MediaCodec call would hold it. A real stall is a phone check (perf/phone-only.md).
 */
class EngineStallUiTest {
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
        // Let the held engine thread finish before the next test class starts its own player.
        SystemClock.sleep((heldUntil - SystemClock.elapsedRealtime()).coerceAtLeast(0) + 500)
    }

    private var heldUntil = 0L

    private fun hold(engine: PlayerEngine, ms: Long) {
        heldUntil = SystemClock.elapsedRealtime() + ms
        engine.blockEngineThreadForTest(ms)
    }

    @Ears("NF-5")
    @Test fun stalledHardwareDecoderGoesToSwAndTheUiStaysLive_NF5() {
        val old = onHardware()
        val at = old.state.value.positionMs
        val t0 = SystemClock.elapsedRealtime()
        hold(old, 12_000)

        // The UI thread answers while the engine thread is held.
        val worst = mainThreadWorstMs(2_000)
        compose.onNodeWithTag("video").performClick()
        compose.waitUntil(3_000) { shown("back") }
        shot("stall-controls-while-stuck")

        // The watchdog replaces the engine; the new one plays the same file on SW from the same place.
        waitUntil(REPLACED_WITHIN_MS) { PlayerSessions.current.value.let { it != null && it !== old } }
        val replacedMs = SystemClock.elapsedRealtime() - t0
        val fresh = PlayerSessions.current.value!!
        waitUntil(10_000) { fresh.state.value.let { it.firstFrameMs != null && it.positionMs > at } }
        val s = fresh.state.value
        shot("stall-recovered-sw")
        log("NF-5 stall: main thread worst $worst ms while stuck; engine replaced after $replacedMs ms; " +
            "now ${s.activeDecoder.label} at ${s.positionMs} ms (was $at ms), notice '${s.decoderNotice}'")
        assertTrue("main thread answered in $worst ms", worst < 500)
        assertEquals(ActiveDecoder.SW, s.activeDecoder)
        assertEquals(old.state.value.uri, s.uri)
        assertTrue("resumed at ${s.positionMs} ms, was $at ms", s.positionMs >= at - 1_000)
        assertNotNull(s.decoderNotice)
        assertTrue(s.decoderNotice!!, s.decoderNotice!!.endsWith("stopped responding. Playing with SW."))
    }

    @Ears("NF-5")
    @Test fun backLeavesWhileTheEngineIsStuck_NF5() {
        val old = onHardware()
        hold(old, 8_000)
        SystemClock.sleep(500)
        // What decides an ANR is how long the main thread cannot take input: sample it throughout.
        var worst = 0L
        val sampling = Thread {
            while (!Thread.currentThread().isInterrupted) {
                val t = SystemClock.elapsedRealtime()
                InstrumentationRegistry.getInstrumentation().runOnMainSync {}
                worst = maxOf(worst, SystemClock.elapsedRealtime() - t)
                try { Thread.sleep(50) } catch (_: InterruptedException) { break }
            }
        }.apply { start() }
        val t0 = SystemClock.elapsedRealtime()
        // Back twice: the second press leaves when the player asks for a confirming press.
        key(4)
        SystemClock.sleep(300)
        if (scenario.state != Lifecycle.State.DESTROYED) key(4)
        waitUntil(8_000) { scenario.state == Lifecycle.State.DESTROYED }
        val ms = SystemClock.elapsedRealtime() - t0
        SystemClock.sleep(300)
        sampling.interrupt()
        sampling.join(3_000)
        log("NF-5 stall: Back left the player in $ms ms (shell key events included) while the engine thread was held; main thread blocked at most $worst ms")
        // Android reports an ANR after 5 s without input handling. A stuck engine holds the main
        // thread 300 ms per surface hand-back; the rest is the hosted emulator's own tear-down
        // (it skips 40 to 80 frames destroying the activity, stall or not). 3 s keeps well clear of 5.
        assertTrue("main thread blocked $worst ms", worst < 3_000)
    }

    private fun onHardware(): PlayerEngine {
        val engine = PlayerSessions.current.value!!
        // Playing, past the first frame: before it the watchdog allows the longer open bound.
        waitUntil(10_000) { engine.state.value.let { (it.activeDecoder == ActiveDecoder.HW || it.activeDecoder == ActiveDecoder.HW_PLUS) && it.firstFrameMs != null } }
        return engine
    }

    /** Longest wait for an empty main-thread task, sampled every 100 ms for [ms]. */
    private fun mainThreadWorstMs(ms: Long): Long {
        val end = SystemClock.elapsedRealtime() + ms
        var worst = 0L
        while (SystemClock.elapsedRealtime() < end) {
            val t = SystemClock.elapsedRealtime()
            InstrumentationRegistry.getInstrumentation().runOnMainSync {}
            worst = maxOf(worst, SystemClock.elapsedRealtime() - t)
            SystemClock.sleep(100)
        }
        return worst
    }

    private fun key(code: Int) {
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("input keyevent $code").close()
    }

    private fun shown(tag: String): Boolean =
        compose.onAllNodes(androidx.compose.ui.test.hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

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
        evidenceShot(name)
    }

    private fun log(line: String) {
        android.util.Log.i("EngineStallUiTest", line)
        val dir = File(context.getExternalFilesDir(null), "evidence").apply { mkdirs() }
        File(dir, "numbers.txt").appendText(line + "\n")
    }

    private companion object {
        /** From the stall: the watchdog's 3 s bound, the UI checks above, and the hand-over. */
        const val REPLACED_WITHIN_MS = 10_000L
    }
}
