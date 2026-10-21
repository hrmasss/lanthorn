package app.player.feature.subtitles

import android.net.Uri
import androidx.test.core.app.ActivityScenario
import app.player.core.engine.PlayerEngine
import app.player.core.engine.PropertyType
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import java.io.File
import java.text.Normalizer

/** P1.26 US-44.1: every encoding the spec names shows without garbling, with encoding left on auto. */
class EncodingsOnDeviceTest {
    @get:Rule val timeout: Timeout = Timeout.seconds(300)

    private lateinit var scenario: ActivityScenario<VideoTestActivity>
    private lateinit var engine: PlayerEngine
    private val holder = Any()

    @Before fun start() = runBlocking {
        SubtitlePrefsStore(OnDevice.context).setShowAutomatically(false)
        scenario = ActivityScenario.launch(VideoTestActivity::class.java)
        engine = OnDevice.acquire(holder) { SubtitlesExtension.onEngineCreated(OnDevice.context, it) }
        scenario.onActivity { it.surface.engine = engine }
        Unit
    }

    @After fun close() = runBlocking {
        SubtitlePrefsStore(OnDevice.context).setShowAutomatically(true)
        runCatching { scenario.onActivity { it.surface.engine = null } }
        OnDevice.release(holder)
        scenario.close()
    }

    @Test fun everyNamedEncodingShowsOnAuto_US44_1() = runBlocking {
        val assets = OnDevice.instrumentation.context.assets
        val expected = assets.open("encodings/expected.tsv").bufferedReader().readLines()
            .filter { it.isNotBlank() }.map { it.substringBefore('\t') to it.substringAfter('\t') }
        val dir = File(OnDevice.context.cacheDir, "encodings").apply { deleteRecursively(); mkdirs() }
        val files = SubtitleFiles(OnDevice.context)
        val controller = SubtitleController(engine.asMpv())
        assertEquals("auto", engine.get("sub-codepage", PropertyType.Text))
        val failures = mutableListOf<String>()
        for ((name, line) in expected) {
            val file = File(dir, "$name.srt")
            assets.open("encodings/$name.srt").use { input -> file.outputStream().use { input.copyTo(it) } }
            engine.open(Uri.fromFile(OnDevice.asset("wide.mkv")))
            engine.await("track-list/count", PropertyType.Int64, 10_000) { (it ?: 0) >= 2 }
            engine.awaitAutoLoad()
            engine.pause()
            val prepared = files.prepare(Uri.fromFile(file))
            controller.addFile(prepared.path, prepared.title)
            engine.seekTo(1000)
            delay(800)
            val shown = runCatching { engine.await("sub-text", PropertyType.Text, 15_000) { it != null && it.isNotEmpty() && it != "Inside or outside the picture" } }
                .map { engine.get("sub-text", PropertyType.Text) }.getOrNull()
            val ok = shown != null && Normalizer.normalize(shown, Normalizer.Form.NFC) == line
            evidence("US-44.1 $name: ${if (ok) "ok" else "GARBLED"} '${shown?.take(60)}'")
            if (!ok) failures += "$name: '$shown'"
        }
        evidence("US-44.1 ${expected.size - failures.size} of ${expected.size} encodings shown right on auto")
        assertEquals("garbled: $failures", emptyList<String>(), failures)
    }
}
