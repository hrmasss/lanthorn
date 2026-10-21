package app.player.feature.subtitles

import android.graphics.Bitmap
import android.net.Uri
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import app.player.core.engine.PlayerEngine
import app.player.core.engine.PropertyType
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.rules.Timeout
import org.junit.Test
import java.io.File
import java.nio.charset.Charset

/** P1.3 subtitle wiring against the real libmpv, on the emulator. */
class SubtitlesOnDeviceTest {
    /** A stuck wait fails the test instead of holding the shared emulator. */
    @get:Rule val timeout: Timeout = Timeout.seconds(120)

    private lateinit var scenario: ActivityScenario<VideoTestActivity>
    private lateinit var engine: PlayerEngine
    private lateinit var controller: SubtitleController
    private val holder = Any()

    @Before fun open() = runBlocking {
        // These tests pick tracks themselves: keep P1.25's automatic pick out of the way.
        SubtitlePrefsStore(OnDevice.context).setShowAutomatically(false)
        scenario = ActivityScenario.launch(VideoTestActivity::class.java)
        engine = OnDevice.acquire(holder) { SubtitlesExtension.onEngineCreated(OnDevice.context, it) }
        scenario.onActivity { it.surface.engine = engine }
        controller = SubtitleController(engine.asMpv())
        engine.open(Uri.fromFile(OnDevice.asset("tracks.mkv")))
        engine.await("track-list/count", PropertyType.Int64, 10_000) { (it ?: 0) >= 5 }
        engine.awaitAutoLoad()
        // Keep the subtitle on screen for the checks: pause one second in.
        engine.pause()
        // A seek sent while mpv is still starting the file can be dropped: resend until it lands.
        repeat(20) {
            engine.seekTo(1000)
            delay(500)
            if ((engine.get("time-pos", PropertyType.Real) ?: 0.0) >= 0.9) return@runBlocking
        }
        throw AssertionError("seek to 1 s never landed")
    }

    @After fun close() {
        runBlocking { SubtitlePrefsStore(OnDevice.context).setShowAutomatically(true) }
        if (!::scenario.isInitialized) return
        scenario.onActivity { it.surface.engine = null }
        OnDevice.release(holder)
        scenario.close()
    }

    @Test fun twoTracksAtOnceAndNone_US39_3() = runBlocking {
        val tracks = parseTracks(engine.get("track-list", PropertyType.Text)).filter { it.type == "sub" }
        assertEquals(listOf("Karaoke", "English"), tracks.map { it.title })
        assertEquals(listOf("eng", "eng"), tracks.map { it.lang })
        val both = SubSelection().toggle(tracks[0].id).toggle(tracks[1].id)
        controller.select(both)
        engine.await("sid", PropertyType.Text) { it == tracks[0].id.toString() }
        engine.await("secondary-sid", PropertyType.Text) { it == tracks[1].id.toString() }
        engine.await("secondary-sub-text", PropertyType.Text) { it == "Embedded English line" }
        delay(1500) // first subtitle load builds the font cache
        shot("us39-two-tracks")
        controller.select(SubSelection())
        engine.await("sid", PropertyType.Text) { it == "no" }
        engine.await("secondary-sid", PropertyType.Text) { it == "no" }
        evidence("US-39.3 two tracks on (sid ${both.primary}, secondary ${both.secondary}), then none: ok")
    }

    @Test fun delayAppliesWithin100ms_US41_1() = runBlocking {
        selectEnglish()
        engine.await("sub-text", PropertyType.Text) { it == "Embedded English line" }
        val t0 = SystemClock.elapsedRealtime()
        controller.setDelayMs(20_000) // the line (0 to 10 s) moves to 20 to 30 s: gone at 1 s
        engine.await("sub-text", PropertyType.Text) { it.isNullOrEmpty() }
        val took = SystemClock.elapsedRealtime() - t0
        assertEquals(20.0, engine.get("sub-delay", PropertyType.Real)!!, 1e-6)
        controller.setDelayMs(-50)
        engine.await("sub-delay", PropertyType.Real) { it != null && kotlin.math.abs(it + 0.05) < 1e-6 }
        evidence("US-41.1 sub-delay +20 s hid the line in $took ms (limit 100); -50 ms step read back -0.05")
        assertWithin("SubtitlesOnDeviceTest", took, 100)
    }

    @Test fun speedStretchesTiming_US41_2() = runBlocking {
        selectEnglish()
        controller.setSpeed(0.5) // the 0 to 10 s line becomes 0 to 5 s
        engine.seekTo(7000)
        engine.await("time-pos", PropertyType.Real) { it != null && it >= 6.9 }
        engine.await("sub-text", PropertyType.Text) { it.isNullOrEmpty() }
        controller.setSpeed(1.0)
        engine.await("sub-text", PropertyType.Text) { it == "Embedded English line" }
        evidence("US-41.2 sub-speed 0.5 ended the 10 s line by 7 s; 1.0 brought it back")
    }

    @Test fun assKeepsStylingUnlessOverride_US42_4_NF7() = runBlocking {
        val karaoke = parseTracks(engine.get("track-list", PropertyType.Text)).first { it.title == "Karaoke" }
        controller.select(SubSelection(karaoke.id))
        engine.await("sid", PropertyType.Text) { it == karaoke.id.toString() }
        assertEquals("scale", engine.get("sub-ass-override", PropertyType.Text))
        engine.seekTo(1500)
        delay(500)
        shot("us42-ass-file-styling")
        controller.setOverrideStyles(true)
        engine.await("sub-ass-override", PropertyType.Text) { it == "strip" }
        delay(500)
        shot("us42-ass-override")
        // Karaoke over 8 s of playback: count dropped frames (function only; emulator numbers make no NF claim).
        controller.setOverrideStyles(false)
        val dropped0 = engine.get("frame-drop-count", PropertyType.Int64) ?: 0
        engine.seekTo(0)
        engine.play()
        delay(2500)
        shot("nf7-karaoke-playing")
        delay(5500)
        val dropped = (engine.get("frame-drop-count", PropertyType.Int64) ?: 0) - dropped0
        evidence("US-42.4 sub-ass-override scale by default, strip on override (plain text in our style)")
        evidence("NF-7 emulator: karaoke ASS rendered 8 s, frame-drop-count +$dropped (swiftshader, no claim)")
    }

    @Test fun externalFileAndEncodingReload_US44_2() = runBlocking {
        val files = SubtitleFiles(OnDevice.context)
        val ext = files.prepare(Uri.fromFile(OnDevice.asset("external.srt")))
        controller.addFile(ext.path, ext.title)
        engine.await("sub-text", PropertyType.Text) { it == "External UTF-8 line" }

        val cz = files.prepare(Uri.fromFile(OnDevice.asset("cp1250.srt")))
        controller.addFile(cz.path, cz.title)
        val expected = "Příliš žluťoučký kůň"
        engine.await("sub-text", PropertyType.Text) { !it.isNullOrEmpty() && it != "External UTF-8 line" }
        val before = engine.get("sub-text", PropertyType.Text)
        delay(1000) // let the paused frame redraw with the new track
        shot("us44-before-reload")
        val t0 = SystemClock.elapsedRealtime()
        files.reencode(cz.path, Charset.forName("windows-1250"))
        val id = parseTracks(engine.get("track-list", PropertyType.Text)).first { it.externalFilename == cz.path }.id
        controller.reload(id)
        engine.await("sub-text", PropertyType.Text) { it == expected }
        val took = SystemClock.elapsedRealtime() - t0
        delay(100)
        shot("us44-after-reload-100ms")
        delay(900)
        shot("us44-after-reload")
        evidence("US-44.2 '$before' -> '$expected' after picking windows-1250 in $took ms (limit 1000)")
        assertWithin("SubtitlesOnDeviceTest", took, 1000)
    }

    @Test fun ignoreEmbeddedFonts_US42_4() = runBlocking {
        openFonts()
        delay(1500)
        shot("us42-embedded-font-used")
        controller.setIgnoreEmbeddedFonts(true)
        engine.await("embeddedfonts", PropertyType.Flag) { it == false }
        // mpv reads fonts when a file loads: open it again, as the switch's note says.
        openFonts()
        delay(1500)
        shot("us42-embedded-font-ignored")
        controller.setIgnoreEmbeddedFonts(false)
        evidence("US-42.4 embedded DejaVu Sans Mono used by default, ignored after the switch and a reopen; screenshots")
    }

    /** The ASS track has no default flag, so mpv does not pick it by itself. */
    private suspend fun openFonts() {
        engine.open(Uri.fromFile(OnDevice.asset("fonts.mkv")))
        engine.await("track-list/count", PropertyType.Int64, 10_000) { (it ?: 0) >= 2 }
        engine.awaitAutoLoad()
        engine.set("sid", "1")
        engine.await("sub-text", PropertyType.Text, 10_000) { it == "iiii WWWW embedded font" }
    }

    private suspend fun selectEnglish() {
        val english = parseTracks(engine.get("track-list", PropertyType.Text)).first { it.title == "English" }
        controller.select(SubSelection(english.id))
        engine.await("sid", PropertyType.Text) { it == english.id.toString() }
    }

    private fun shot(name: String) {
        val bitmap = OnDevice.instrumentation.uiAutomation.takeScreenshot() ?: return
        val dir = File(OnDevice.context.getExternalFilesDir(null), "evidence").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
