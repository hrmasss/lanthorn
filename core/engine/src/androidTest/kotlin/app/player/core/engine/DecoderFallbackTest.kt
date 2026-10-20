package app.player.core.engine

import android.net.Uri
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * P1.1 against the real libmpv: the one-line notice when the chosen decoder cannot play the file
 * (US-35.3). Emulator MediaCodec refuses H.264 4:4:4, which stands in for a phone's hardware
 * refusing a stream. A hardware failure in the middle of playback cannot be produced on the
 * emulator; it is a phone check (work/evidence/P1.1).
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

    @Test fun chosenHwThatCannotPlaySaysSoInOneLine_US35_3(): Unit = runBlocking {
        engine.open(asset("h444.mp4"), decoder = DecoderMode.HW)
        val s = withTimeout(10_000) { engine.state.first { it.decoderNotice != null } }
        Log.i("P1.1", "notice '${s.decoderNotice}', running ${s.activeDecoder.label}, error ${s.error}")
        assertEquals("HW cannot play this video. Playing with SW.", s.decoderNotice)
        assertEquals(ActiveDecoder.SW, s.activeDecoder)
        assertEquals(null, s.error)
    }

    @Test fun autoNeedsNoNotice_US35_3(): Unit = runBlocking {
        engine.open(asset("h444.mp4"), decoder = DecoderMode.AUTO)
        withTimeout(10_000) { engine.state.first { it.video != null } }
        delay(500)
        assertEquals(null, engine.state.value.decoderNotice)
    }

    /**
     * NF-5 mid-playback: 3 s of 640x360 the hardware decodes, then 7680x4320, beyond the emulator
     * MediaCodec's limits. The decoder must switch to SW inside the player and keep the clock going.
     * (No surface in this test, so the hardware path is HW+, copy-back.)
     */
    @Test fun hardwareFailsMidStreamSoftwareTakesOver_NF5(): Unit = runBlocking {
        engine.open(asset("midswitch8k.ts"), decoder = DecoderMode.AUTO)
        val first = withTimeout(10_000) { engine.state.first { it.video != null && it.activeDecoder != ActiveDecoder.NONE } }
        Log.i("P1.1", "mid-stream: started on ${first.activeDecoder.label} at ${first.video?.width}x${first.video?.height}")
        val t0 = android.os.SystemClock.elapsedRealtime()
        val sw = withTimeout(15_000) { engine.state.first { it.activeDecoder == ActiveDecoder.SW || it.ended || it.error != null } }
        val took = android.os.SystemClock.elapsedRealtime() - t0
        val end = withTimeout(10_000) { engine.state.first { it.ended || it.error != null || it.positionMs > 3300 } }
        Log.i(
            "P1.1",
            "mid-stream: ${first.activeDecoder.label} -> ${sw.activeDecoder.label} at ${sw.positionMs} ms " +
                "(${sw.video?.width}x${sw.video?.height}), $took ms after the first frame; then position ${end.positionMs} ms, " +
                "ended ${end.ended}, error ${end.error}",
        )
        org.junit.Assert.assertNotEquals("hardware never started, nothing to fall back from", ActiveDecoder.SW, first.activeDecoder)
        assertEquals(ActiveDecoder.SW, sw.activeDecoder)
        assertEquals(null, end.error)
    }
}
