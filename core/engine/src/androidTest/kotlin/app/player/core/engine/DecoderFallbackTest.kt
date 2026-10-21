package app.player.core.engine

import android.net.Uri
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import app.player.core.testing.Ears

/**
 * P1.1 against the real libmpv: the one-line notice when the chosen decoder cannot play the file
 * (US-35.3). Emulator MediaCodec refuses H.264 4:4:4, which stands in for a phone's hardware
 * refusing a stream. A hardware failure in the middle of playback cannot be produced on the
 * emulator: a 4:2:0 to 4:4:4 stream is refused from its first frame, and a jump to 7680x4320
 * crashes the emulator's host-side decoder. It is a phone check (work/evidence/P1.1).
 */
@RunWith(AndroidJUnit4::class)
class DecoderFallbackTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val engine = PlayerEngine(context)

    @After fun close() = engine.release()

    private fun asset(name: String): Uri {
        val f = File(context.cacheDir, name)
        InstrumentationRegistry.getInstrumentation().context.assets.open(name).use { i -> f.outputStream().use { i.copyTo(it) } }
        return Uri.fromFile(f)
    }

    @Ears("US-35.3")
    @Test fun chosenHwThatCannotPlaySaysSoInOneLine_US35_3(): Unit = runBlocking {
        engine.open(asset("h444.mp4"), decoder = DecoderMode.HW)
        val s = withTimeout(10_000) { engine.state.first { it.decoderNotice != null } }
        Log.i("P1.1", "notice '${s.decoderNotice}', running ${s.activeDecoder.label}, error ${s.error}")
        assertEquals("HW cannot play this video. Playing with SW.", s.decoderNotice)
        assertEquals(ActiveDecoder.SW, s.activeDecoder)
        assertEquals(null, s.error)
    }

    /** NF-5: a file HW refuses plays on SW within 1 s of opening, in the same player. */
    @Ears("NF-5")
    @Test fun autoFallsBackToSwWithinOneSecond_NF5(): Unit = runBlocking {
        val t0 = android.os.SystemClock.elapsedRealtime()
        engine.open(asset("h444.mp4"))
        val s = withTimeout(10_000) { engine.state.first { it.activeDecoder == ActiveDecoder.SW && it.firstFrameMs != null } }
        val ms = android.os.SystemClock.elapsedRealtime() - t0
        Log.i("P1.1", "auto on 4:4:4: SW running with a frame after $ms ms, first frame ${s.firstFrameMs} ms, error ${s.error}")
        assertEquals(null, s.error)
        // The hosted emulator makes no timing claim (timing=lenient): there the number is only logged.
        val lenient = InstrumentationRegistry.getArguments().getString("timing") == "lenient"
        if (!lenient) org.junit.Assert.assertTrue("SW took $ms ms", ms <= 1000)
    }

    /** P2.6: a call into mpv that does not return on a hardware decoder is reported within the bound (NF-5). */
    @Ears("NF-5")
    @Test fun watchdogReportsAStuckEngineOnHardware_NF5(): Unit = runBlocking {
        engine.open(asset("clip.mp4"))
        withTimeout(10_000) { engine.state.first { it.activeDecoder == ActiveDecoder.HW || it.activeDecoder == ActiveDecoder.HW_PLUS } }
        val t0 = android.os.SystemClock.elapsedRealtime()
        engine.blockEngineThreadForTest(10_000)
        val stall = withTimeout(PlayerEngine.STALL_MS + 2_000) { engine.stalled.first { it != null } }!!
        val ms = android.os.SystemClock.elapsedRealtime() - t0
        Log.i("P1.1", "watchdog: stall reported after $ms ms: $stall")
        assertTrue("reported after $ms ms", ms >= PlayerEngine.STALL_MS)
        assertEquals(engine.state.value.uri, stall.uri)
    }

    /** Software decoding has nothing to fall to: a busy engine there is logged, not replaced. */
    @Ears("NF-5")
    @Test fun watchdogLeavesSoftwareAlone_NF5(): Unit = runBlocking {
        engine.open(asset("clip.mp4"), decoder = DecoderMode.SW)
        withTimeout(10_000) { engine.state.first { it.activeDecoder == ActiveDecoder.SW } }
        engine.blockEngineThreadForTest(PlayerEngine.STALL_MS + 1_500)
        delay(PlayerEngine.STALL_MS + 1_000)
        assertEquals(null, engine.stalled.value)
    }

    @Ears("US-35.3")
    @Test fun autoNeedsNoNotice_US35_3(): Unit = runBlocking {
        engine.open(asset("h444.mp4"), decoder = DecoderMode.AUTO)
        withTimeout(10_000) { engine.state.first { it.video != null } }
        delay(500)
        assertEquals(null, engine.state.value.decoderNotice)
    }

    /** A live switch to HW+: if it comes up, no notice at any point while mpv tries it; if not, notice and badge agree (P1.9 finding). */
    @Ears("US-35.3")
    @Test fun switchToHwPlusNoticeMatchesBadge_US35_3(): Unit = runBlocking {
        // The hosted emulator's MediaCodec can stop answering after the switch, and mpv then holds
        // the engine thread: neither outcome can show. Try once more on a fresh engine before
        // giving up on this run.
        if (hwPlusSwitch(engine)) return@runBlocking
        val second = PlayerEngine(context)
        try {
            if (hwPlusSwitch(second)) return@runBlocking
        } finally {
            second.release()
        }
        // The watchdog caught both (in the app, PlayerSessions then replaces the engine on SW:
        // EngineStallUiTest). The notice judgement itself is not tested on this run, so the test
        // is skipped, with a warning, not passed.
        assumeTrue("WARNING: HW+ decoder stalled after the switch twice; the watchdog caught it", false)
    }

    /** One SW to HW+ switch on [e]. False when the decoder stalled, so nothing could be judged. */
    private suspend fun hwPlusSwitch(e: PlayerEngine): Boolean = coroutineScope {
        e.open(asset("clip.mp4"), decoder = DecoderMode.SW)
        withTimeout(10_000) { e.state.first { it.video != null } }
        // Every state the switch passes through, so a failure says what mpv did.
        val seen = CopyOnWriteArrayList<String>()
        val watch = launch(Dispatchers.Default) {
            e.state.collect { s -> "${s.activeDecoder.label}/${s.decoderNotice}".let { if (seen.lastOrNull() != it) seen += it } }
        }
        e.setDecoder(DecoderMode.HW_PLUS)
        val cameUp = withTimeoutOrNull(10_000) { e.state.first { it.activeDecoder == ActiveDecoder.HW_PLUS } } != null
        delay(2000)
        watch.cancel()
        Log.i("P1.1", "switch SW to HW+: $seen")
        val notice = e.state.value.decoderNotice
        if (cameUp) {
            assertEquals("a notice showed though HW+ came up: $seen", emptyList<String>(), seen.filterNot { it.endsWith("/null") })
        } else if (notice == null && e.get("pause", PropertyType.Flag) == null) {
            withTimeout(PlayerEngine.OPEN_STALL_MS + 2_000) { e.stalled.first { it != null } }
            Log.w("P1.1", "WARNING: HW+ decoder stalled after the switch, watchdog reported ${e.stalled.value}; states $seen")
            return@coroutineScope false
        } else {
            // HW+ cannot play this file here: then the notice must say so and the badge must agree.
            assertEquals("states $seen", "HW+ cannot play this video. Playing with SW.", notice)
            assertEquals("states $seen", ActiveDecoder.SW, e.state.value.activeDecoder)
        }
        true
    }
}
