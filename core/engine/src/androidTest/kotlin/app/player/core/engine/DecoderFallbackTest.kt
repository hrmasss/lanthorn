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
}
