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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.charset.Charset

/**
 * P1.33: the libmpv build has the FFmpeg filters and the charset detection the app relies on.
 * mpv drops a filter it cannot create from the chain, so a filter still in `af`/`vf` after the
 * chain rebuilt is one the build has. The unknown filter is the control that proves the check bites.
 */
@RunWith(AndroidJUnit4::class)
class LibmpvBuildTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var engine: PlayerEngine

    @Before fun open(): Unit = runBlocking {
        val clip = File(context.cacheDir, "clip.mp4")
        InstrumentationRegistry.getInstrumentation().context.assets.open("clip.mp4").use { input ->
            clip.outputStream().use { input.copyTo(it) }
        }
        engine = PlayerEngine(context)
        engine.setDecoder(DecoderMode.SW)
        engine.open(Uri.fromFile(clip))
        withTimeout(10_000) { engine.state.first { it.loaded } }
    }

    @After fun close() = engine.release()

    private suspend fun chainHas(property: String, label: String, timeoutMs: Long = 3000): Boolean {
        delay(timeoutMs)
        val chain = engine.get(property, PropertyType.Text).orEmpty()
        Log.i("P1.33", "$property = $chain")
        return chain.contains("@$label")
    }

    @Test fun reverseStereoWithPan_US51_1() = runBlocking {
        engine.command("af", "add", "@rev:lavfi=[pan=stereo|c0=c1|c1=c0]")
        assertTrue("pan missing from the build", chainHas("af", "rev"))
    }

    @Test fun fadeIn_US52() = runBlocking {
        engine.command("af", "add", "@fade:lavfi=[afade=t=in:d=2]")
        assertTrue("afade missing from the build", chainHas("af", "fade"))
    }

    @Test fun deinterlaceWithBwdif_US38() = runBlocking {
        engine.command("vf", "add", "@di:lavfi=[bwdif]")
        assertTrue("bwdif missing from the build", chainHas("vf", "di"))
    }

    @Test fun unknownFilterIsDroppedControl() = runBlocking {
        engine.command("vf", "add", "@bogus:lavfi=[nosuchfilter]")
        assertFalse("mpv kept a filter that does not exist, so the check above proves nothing", chainHas("vf", "bogus"))
    }

    @Test fun subtitleCharsetDetectedWithUchardet_US44_1() = runBlocking {
        val text = "Привет, мир. Это проверка кодировки субтитров."
        val srt = File(context.cacheDir, "cp1251.srt")
        srt.writeBytes("1\n00:00:00,000 --> 00:00:04,000\n$text\n\n".toByteArray(Charset.forName("windows-1251")))
        engine.set("sub-codepage", "auto")
        engine.command("sub-add", srt.path, "select")
        engine.seekTo(1000)
        delay(1500)
        val shown = engine.get("sub-text", PropertyType.Text)
        Log.i("P1.33", "sub-text = $shown")
        assertEquals(text, shown)
    }
}
