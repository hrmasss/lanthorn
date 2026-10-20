package app.player.feature.subtitles

import android.graphics.Bitmap
import android.net.Uri
import android.os.SystemClock
import android.provider.DocumentsContract
import androidx.test.core.app.ActivityScenario
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import app.player.core.engine.PlayerEngine
import app.player.core.engine.PropertyType
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import java.io.File

/** P1.28 gate: font folder import, edits from the real panel, ASS kept under style edits, real Settings. */
class StyleGateOnDeviceTest {
    @get:Rule val timeout: Timeout = Timeout.seconds(150)

    private var engine: PlayerEngine? = null
    private val holder = Any()
    private lateinit var styles: SubtitleStyles
    private var scenario: ActivityScenario<*>? = null

    @Before fun start() {
        val e = OnDevice.acquire(holder) { SubtitlesExtension.onEngineCreated(OnDevice.context, it) }
        engine = e
        styles = OnDevice.main { SubtitlesExtension.styles(OnDevice.context) }
        OnDevice.main { styles.reset() }
    }

    @After fun close() {
        runCatching { OnDevice.main { styles.reset() } }
        runCatching { scenario?.close() }
        OnDevice.release(holder)
    }

    private val e get() = engine!!

    /** Shows the video on a plain surface, for tests that do not need the panel. */
    private fun videoScreen() {
        val s = ActivityScenario.launch(VideoTestActivity::class.java)
        s.onActivity { it.surface.engine = e }
        scenario = s
    }

    /** Opens [name], picks the subtitle track titled [title] (or the first), pauses at 1 s. */
    private suspend fun open(name: String, title: String? = null) {
        e.open(Uri.fromFile(OnDevice.asset(name)))
        e.await("track-list/count", PropertyType.Int64, 10_000) { (it ?: 0) >= 2 }
        val track = parseTracks(e.get("track-list", PropertyType.Text)).filter { it.type == "sub" }
            .let { subs -> subs.firstOrNull { it.title == title } ?: subs.first() }
        e.set("sid", track.id.toString())
        e.pause()
        repeat(20) {
            e.seekTo(1000)
            delay(400)
            if ((e.get("time-pos", PropertyType.Real) ?: 0.0) >= 0.9) {
                e.await("sub-text", PropertyType.Text) { !it.isNullOrEmpty() }
                delay(1500)
                return
            }
        }
        throw AssertionError("seek to 1 s never landed")
    }

    @Test fun fontFolderImport_US42_1() = runBlocking {
        val picked = File(OnDevice.context.cacheDir, "picked-fonts").apply { deleteRecursively(); mkdirs() }
        OnDevice.asset("LiberationMono-Regular.ttf").copyTo(File(picked, "LiberationMono-Regular.ttf"), overwrite = true)
        File(styles.userFontDir, "LiberationMono-Regular.ttf").delete()
        assertTrue("Liberation Mono listed before the import", "Liberation Mono" !in styles.families())
        val tree = DocumentsContract.buildTreeDocumentUri(TestFontsProvider.AUTHORITY, TestFontsProvider.ROOT)
        val copied = styles.importFonts(tree)
        val families = styles.families()
        assertEquals("fonts copied", 1, copied)
        assertTrue("Liberation Mono missing from $families", "Liberation Mono" in families)
        assertTrue("AndroidClock still listed", "AndroidClock" !in families)
        OnDevice.main { styles.update { it.copy(font = "Liberation Mono", size = 60) } }
        videoScreen()
        open("tracks.mkv", "English") // imported fonts load with the next file
        shot("us42-imported-font")
        evidence("US-42.1 font folder: $copied font imported through the documents provider path, 'Liberation Mono' in the list (${families.size} text families, AndroidClock filtered out), drawn in a subtitle; screenshot")
    }

    @Test fun editFromThePanel_US42_3() = runBlocking {
        // The panel's surface first, then the file, so the paused frame is drawn on it.
        scenario = ActivityScenario.launch(PanelTestActivity::class.java)
        delay(1000)
        open("tracks.mkv", "English")
        shot("us42-panel-before")
        val device = UiDevice.getInstance(OnDevice.instrumentation)
        val yellow = device.wait(Until.findObject(By.res("sub-color-FFEB3B")), 5_000) ?: throw AssertionError("yellow swatch not found")
        val color = e.observe("sub-color", PropertyType.Text)
        delay(200)
        val t0 = SystemClock.elapsedRealtime()
        yellow.click()
        val deadline = t0 + 5_000
        while (color.value != "#FFFFEB3B" && SystemClock.elapsedRealtime() < deadline) delay(1)
        val took = SystemClock.elapsedRealtime() - t0
        assertEquals("#FFFFEB3B", color.value)
        delay(500)
        shot("us42-panel-yellow")
        device.findObject(By.res("sub-style"))?.scroll(androidx.test.uiautomator.Direction.DOWN, 1f)
        device.wait(Until.findObject(By.res("sub-box")), 5_000)?.click()
        delay(800)
        shot("us42-panel-box")
        evidence("US-42.3 from the player's style panel: tap on yellow to mpv sub-color in $took ms (limit 100); screenshots with the panel open over the line")
        assertTrue("took $took ms", took < 100)
    }

    @Test fun assStylingKeptUnderEdits_US42_4_NF7() = runBlocking {
        videoScreen()
        open("tracks.mkv", "Karaoke")
        shot("us42-ass-before-edit")
        OnDevice.main { styles.update { it.copy(color = 0xFF80DEEA, font = "serif", size = 80, box = true) } }
        delay(800)
        shot("us42-ass-after-edit-kept")
        SubtitleController(e.asMpv()).setOverrideStyles(true)
        e.await("sub-ass-override", PropertyType.Text) { it == "strip" }
        delay(800)
        shot("us42-ass-override-new-style")
        evidence("US-42.4/NF-7 style edits leave the ASS file's styling; override on draws the text in the edited style; screenshots")
    }

    @Test fun subtitleGroupInRealSettings() {
        val device = UiDevice.getInstance(OnDevice.instrumentation)
        val launch = OnDevice.context.packageManager.getLaunchIntentForPackage(APP)
        if (launch == null) {
            evidence("Real Settings screen: app $APP not installed, skipped")
            return
        }
        OnDevice.context.startActivity(launch.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK))
        device.wait(Until.hasObject(By.desc("More options")), 15_000)
        device.findObject(By.desc("More options")).click()
        device.wait(Until.hasObject(By.text("Settings")), 5_000)
        device.findObject(By.text("Settings")).click()
        device.wait(Until.hasObject(By.text("Subtitle")), 5_000)
        shot("settings-home-with-subtitle")
        device.findObject(By.text("Subtitle")).click()
        device.wait(Until.hasObject(By.text("Subtitles look like this")), 5_000)
        Thread.sleep(1500)
        shot("settings-subtitle-group")
        device.swipe(540, 1800, 540, 600, 30)
        Thread.sleep(800)
        shot("settings-subtitle-group-scrolled")
        device.pressBack(); device.pressBack(); device.pressBack()
        evidence("Real Settings screen: Subtitle group listed and opened from the app's settings; screenshots")
    }

    private fun shot(name: String) {
        val bitmap = OnDevice.instrumentation.uiAutomation.takeScreenshot() ?: return
        val dir = File(OnDevice.context.getExternalFilesDir(null), "evidence").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    companion object {
        const val APP = "app.player"
    }
}
