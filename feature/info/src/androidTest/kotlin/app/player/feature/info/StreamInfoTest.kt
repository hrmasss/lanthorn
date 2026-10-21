package app.player.feature.info

import android.net.Uri
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.player.core.engine.PlayerEngine
import app.player.core.engine.PropertyType
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * US-37.1 on the real engine: the info reader lists an external subtitle as External. The player
 * UI run shows the embedded case; subtitle files are loaded by subs-audio, so here the test adds
 * the file itself with `sub-add`.
 */
@RunWith(AndroidJUnit4::class)
class StreamInfoTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val engine = PlayerEngine(context)

    @After fun close() = engine.release()

    @Test fun externalSubtitleListedAsExternal_US37_1(): Unit = runBlocking {
        val clip = File(context.cacheDir, "clip.mp4")
        InstrumentationRegistry.getInstrumentation().context.assets.open("clip.mp4").use { i -> clip.outputStream().use { i.copyTo(it) } }
        val srt = File(context.cacheDir, "clip.fr.srt").apply { writeText("1\n00:00:00,000 --> 00:00:04,000\nSous-titre externe\n\n") }
        engine.set("sub-auto", "no") // mpv would also load clip.fr.srt by itself and list it twice
        engine.open(Uri.fromFile(clip))
        withTimeout(20_000) { engine.state.first { it.loaded } }
        engine.command("sub-add", srt.path, "select", "external", "fr")
        withTimeout(20_000) { while ((engine.get("track-list/count", PropertyType.Int64) ?: 0) < 3) delay(100) }

        val info = StreamInfoReader.read(context, engine)
        Log.i("P1.1", "subtitles ${info.subtitles}")
        Log.i("P1.1", "audio ${info.audio}")
        assertEquals(1, info.subtitles.size)
        val sub = info.subtitles.single().toMap()
        assertEquals("External", sub["Source"])
        assertEquals("SRT", sub["Format"])
        assertEquals("fr", sub["Language"])
        assertEquals("Stereo", info.audio.single().toMap()["Channels"])
    }
}
