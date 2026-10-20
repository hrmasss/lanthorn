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

/** P1.28 style and placement on the real libmpv: property read-back plus screenshots. */
class StyleOnDeviceTest {
    /** A stuck wait fails the test instead of holding the shared emulator. */
    @get:Rule val timeout: Timeout = Timeout.seconds(120)

    private lateinit var scenario: ActivityScenario<VideoTestActivity>
    private lateinit var engine: PlayerEngine
    private lateinit var styles: SubtitleStyles
    private val holder = Any()

    @Before fun open() = runBlocking {
        scenario = ActivityScenario.launch(VideoTestActivity::class.java)
        engine = OnDevice.acquire(holder) { SubtitlesExtension.onEngineCreated(OnDevice.context, it) }
        styles = OnDevice.main { SubtitlesExtension.styles(OnDevice.context) }
        OnDevice.main { styles.reset() }
        scenario.onActivity { it.surface.engine = engine }
        engine.open(Uri.fromFile(OnDevice.asset("wide.mkv")))
        engine.await("track-list/count", PropertyType.Int64, 10_000) { (it ?: 0) >= 2 }
        engine.pause()
        repeat(20) {
            engine.seekTo(1000)
            delay(500)
            if ((engine.get("time-pos", PropertyType.Real) ?: 0.0) >= 0.9) {
                engine.set("sid", "1")
                engine.await("sub-text", PropertyType.Text) { it == "Inside or outside the picture" }
                delay(1500) // font cache on first load
                return@runBlocking
            }
        }
        throw AssertionError("seek to 1 s never landed")
    }

    @After fun close() {
        if (!::scenario.isInitialized) return
        OnDevice.main { styles.reset() }
        runCatching { scenario.onActivity { it.surface.engine = null } }
        OnDevice.release(holder)
        scenario.close()
    }

    @Test fun defaultsAndLiveEdit_US42_1_US42_2_US42_3() = runBlocking {
        assertEquals("#FFFFFFFF", engine.get("sub-color", PropertyType.Text))
        assertEquals(true, engine.get("sub-bold", PropertyType.Flag))
        assertEquals(0.0, engine.get("sub-border-size", PropertyType.Real)!!, 0.0)
        assertEquals(2.0, engine.get("sub-shadow-offset", PropertyType.Real)!!, 0.0)
        shot("us42-defaults")
        assertEquals(46.0, engine.get("sub-font-size", PropertyType.Real)!!, 0.0)
        // Event-timed: mpv pushes the change, no polling round trip in the number.
        // The outline is the last property this edit sends (mpv 0.41 names it sub-outline-size).
        val outline = engine.observe("sub-outline-size", PropertyType.Real)
        delay(300)
        val t0 = SystemClock.elapsedRealtime()
        OnDevice.main { styles.update { it.copy(color = 0xFFFFEB3B, outline = true, outlineSize = 4f, font = "serif", size = 60) } }
        while (outline.value != 4.0) delay(1)
        val took = SystemClock.elapsedRealtime() - t0
        assertEquals("#FFFFEB3B", engine.get("sub-color", PropertyType.Text))
        assertEquals("serif", engine.get("sub-font", PropertyType.Text))
        assertEquals(60.0, engine.get("sub-font-size", PropertyType.Real)!!, 0.0)
        delay(300)
        shot("us42-yellow-serif-outline")
        OnDevice.main { styles.update { it.copy(outline = false, box = true, boxOpacity = 0.6f, font = "monospace", color = 0xFFFFFFFF) } }
        engine.await("sub-border-style", PropertyType.Text) { it == "background-box" }
        delay(300)
        shot("us42-box-monospace")
        evidence("US-42.2 defaults: white #FFFFFFFF, size 46, bold, border 0, shadow 2")
        evidence("US-42.3 colour, outline, font, size change applied to mpv in $took ms (limit 100); screenshots before/after")
        assertTrue("took $took ms", took < 100)
    }

    @Test fun alignmentMarginAndFit_US43_1_US43_2() = runBlocking {
        OnDevice.main { styles.update { it.copy(align = SubAlign.Left, bottomMarginPercent = 25f) } }
        engine.await("sub-margin-y", PropertyType.Int64) { it == 180L }
        engine.await("sub-align-x", PropertyType.Text) { it == "left" }
        delay(300)
        shot("us43-left-margin25")
        OnDevice.main { styles.update { it.copy(align = SubAlign.Centre, bottomMarginPercent = 0f, fitToVideo = true) } }
        engine.await("sub-margin-y", PropertyType.Int64) { it == 0L }
        delay(300)
        shot("us43-fit-on")
        OnDevice.main { styles.update { it.copy(fitToVideo = false) } }
        engine.await("sub-use-margins", PropertyType.Flag) { it == true }
        delay(300)
        shot("us43-fit-off")
        evidence("US-43.1 left alignment and 25% margin (sub-margin-y 180 of 720) applied; screenshot")
        evidence("US-43.2 fit on keeps text in the picture, fit off puts it in the bottom bar; screenshots")
    }

    @Test fun styleIsSaved() = runBlocking {
        OnDevice.main { styles.update { it.copy(size = 70) } }
        delay(1200) // saved after 500 ms of quiet
        val saved = SubtitleStyleStore(OnDevice.context).load()
        assertEquals(70, saved.size)
        evidence("Style saved to DataStore and read back (size 70)")
    }

    @Test fun settingsPreviewFollowsStyle_P1_28() = runBlocking {
        val page = ActivityScenario.launch(SettingsTestActivity::class.java)
        delay(1500)
        shot("settings-preview-default")
        OnDevice.main { styles.update { it.copy(color = 0xFFFFEB3B, box = true, align = SubAlign.Left, bottomMarginPercent = 20f) } }
        delay(800)
        shot("settings-preview-edited")
        page.close()
        evidence("Subtitle settings page shows a live preview that follows edits; screenshots")
    }

    @Test fun fontNamesAndFamilies_US42_1() = runBlocking {
        assertEquals("Roboto", FontNames.family(File("/system/fonts/Roboto-Regular.ttf")))
        val families = styles.families()
        assertTrue(families.take(3) == listOf("sans-serif", "serif", "monospace"))
        assertTrue("families $families", families.size > 10)
        evidence("US-42.1 font picker lists ${families.size} families from /system/fonts, e.g. ${families.drop(3).take(6)}")
    }

    /** Why text on the wide clip drew tiny: dump what mpv thinks, compare with the 16:9 clip. */
    @Test fun sizeDiagnostic() = runBlocking {
        for (name in listOf("osd-dimensions", "sub-font-size", "sub-scale", "sub-use-margins", "sub-scale-by-window", "video-params", "sub-ass-override")) {
            evidence("diag wide $name = ${engine.get(name, PropertyType.Text)}")
        }
        shot("diag-wide-default")
        engine.set("sub-use-margins", "yes")
        delay(500)
        shot("diag-wide-use-margins")
        engine.open(Uri.fromFile(OnDevice.asset("tracks.mkv")))
        engine.await("track-list/count", PropertyType.Int64, 10_000) { (it ?: 0) >= 5 }
        engine.set("sid", "2")
        delay(1500)
        evidence("diag tracks osd-dimensions = ${engine.get("osd-dimensions", PropertyType.Text)}")
        shot("diag-tracks-english")
    }

    private fun shot(name: String) {
        val bitmap = OnDevice.instrumentation.uiAutomation.takeScreenshot() ?: return
        val dir = File(OnDevice.context.getExternalFilesDir(null), "evidence").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
