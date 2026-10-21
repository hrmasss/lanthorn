package app.player.feature.subtitles

import android.content.ContentValues
import android.net.Uri
import android.provider.MediaStore
import androidx.test.core.app.ActivityScenario
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import app.player.core.data.choice.PerFileChoices
import app.player.core.engine.PlayerEngine
import app.player.core.engine.PropertyType
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import java.io.File
import java.util.concurrent.TimeUnit

/** P1.25: subtitles beside the video load by themselves; the right one turns on. */
@org.junit.FixMethodOrder(org.junit.runners.MethodSorters.NAME_ASCENDING)
class AutoLoadOnDeviceTest {
    @get:Rule val timeout: Timeout = Timeout.seconds(150)

    private lateinit var scenario: ActivityScenario<VideoTestActivity>
    private lateinit var engine: PlayerEngine
    private val holder = Any()
    private val prefs = SubtitlePrefsStore(OnDevice.context)
    private val folder = File(OnDevice.context.cacheDir, "autoload")
    private val clip get() = File(folder, "Clip.mkv")

    @Before fun start() = runBlocking {
        prefs.reset()
        folder.deleteRecursively()
        folder.mkdirs()
        OnDevice.asset("tracks.mkv").copyTo(clip, overwrite = true)
        File(folder, "Clip.de.srt").writeText("1\n00:00:00,000 --> 00:00:10,000\nDeutsche Zeile\n")
        File(folder, "Clip.it.srt").writeText("1\n00:00:00,000 --> 00:00:10,000\nRiga italiana\n")
        File(folder, "Other.srt").writeText("1\n00:00:00,000 --> 00:00:10,000\nNot this one\n")
        PerFileChoices(OnDevice.context).forget(Uri.fromFile(clip).toString())
        scenario = ActivityScenario.launch(VideoTestActivity::class.java)
        engine = OnDevice.acquire(holder) { SubtitlesExtension.onEngineCreated(OnDevice.context, it) }
        scenario.onActivity { it.surface.engine = engine }
        Unit
    }

    @After fun close() = runBlocking {
        prefs.reset()
        PerFileChoices(OnDevice.context).forget(Uri.fromFile(clip).toString())
        runCatching { scenario.onActivity { it.surface.engine = null } }
        OnDevice.release(holder)
        scenario.close()
    }

    /** Opens the clip and waits until the auto-load picked (sid settles after the two files load). */
    private suspend fun openClip(uri: Uri = Uri.fromFile(clip), tracks: Int = 7): List<Track> {
        engine.open(uri)
        engine.await("track-list/count", PropertyType.Int64, 10_000) { (it ?: 0) >= tracks }
        delay(1500)
        return parseTracks(engine.get("track-list", PropertyType.Text)).filter { it.type == "sub" }
    }

    private suspend fun selected(): Track? {
        val sid = engine.get("sid", PropertyType.Text)?.toLongOrNull() ?: return null
        return parseTracks(engine.get("track-list", PropertyType.Text)).firstOrNull { it.type == "sub" && it.id == sid }
    }

    @Test fun matchingFilesLoad_firstExternalOn_US39_1_US39_2() = runBlocking {
        val subs = openClip()
        val external = subs.filter { it.external }.map { it.title }
        assertEquals(listOf("Clip.de.srt", "Clip.it.srt"), external)
        assertEquals("Clip.de.srt", selected()?.title)
        evidence("US-39.1 beside Clip.mkv: loaded ${external} (Other.srt left out), embedded ${subs.filter { !it.external }.map { it.title }}")
        evidence("US-39.2 no preferred language: first external file on (${selected()?.title})")
    }

    @Test fun reopeningTheSameFileLoadsItsFilesAgain_US39_1() = runBlocking {
        openClip()
        engine.awaitAutoLoad()
        val again = openClip()
        engine.awaitAutoLoad()
        val external = parseTracks(engine.get("track-list", PropertyType.Text)).filter { it.type == "sub" && it.external }.map { it.title }
        assertEquals(listOf("Clip.de.srt", "Clip.it.srt"), external)
        evidence("US-39.1 the same file opened twice: its two subtitle files load again (${external}), ${again.size} sub tracks")
    }

    @Test fun preferredLanguageWins_US39_2() = runBlocking {
        prefs.setLanguage("it")
        openClip()
        assertEquals("Clip.it.srt", selected()?.title)
        prefs.setLanguage("en")
        openClip(Uri.fromFile(OnDevice.asset("wide.mkv")), tracks = 2) // another file between
        openClip()
        val english = selected()
        assertEquals("eng", english?.lang)
        assertFalse(english!!.external)
        evidence("US-39.2 preferred Italian: Clip.it.srt on; preferred English: embedded '${english.title}' (eng) on")
    }

    @Test fun autoOffShowsNone_US39_2() = runBlocking {
        prefs.setShowAutomatically(false)
        openClip()
        assertEquals(null, selected())
        evidence("US-39.2 show automatically off: tracks loaded, none on (sid ${engine.get("sid", PropertyType.Text)})")
    }

    @Test fun rememberedTrackComesBack_US39_4() = runBlocking {
        prefs.setLanguage("en")
        val subs = openClip()
        val italian = subs.first { it.title == "Clip.it.srt" }
        SubtitleController(engine.asMpv()).select(SubSelection(italian.id))
        SubtitlesExtension.autoLoad(OnDevice.context).remember(Uri.fromFile(clip).toString(), italian)
        openClip(Uri.fromFile(OnDevice.asset("wide.mkv")), tracks = 2)
        openClip()
        assertEquals("Clip.it.srt", selected()?.title)
        evidence("US-39.4 picked Clip.it.srt by hand; reopened with English preferred: Clip.it.srt restored")
    }

    /**
     * Shared storage under scoped storage: the video's folder is not listable until the viewer
     * allows it once in the system folder picker. Drives the real picker.
     */
    @Test fun zzFolderGrantFindsSubtitles_US39_1() = runBlocking {
        fun step(s: String) = android.util.Log.i("evidence", "grant step: $s")
        OnDevice.instrumentation.uiAutomation.grantRuntimePermission(OnDevice.context.packageName, android.Manifest.permission.READ_MEDIA_VIDEO)
        step("permission")
        val resolver = OnDevice.context.contentResolver
        val dir = "Movies/LanthornGrant"
        val video = resolver.insert(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "Grant.mkv")
            put(MediaStore.MediaColumns.RELATIVE_PATH, dir)
        })!!
        resolver.openOutputStream(video)!!.use { out -> OnDevice.asset("wide.mkv").inputStream().use { it.copyTo(out) } }
        val srt = resolver.insert(MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "Grant.srt")
            put(MediaStore.MediaColumns.MIME_TYPE, "application/x-subrip")
            put(MediaStore.MediaColumns.RELATIVE_PATH, dir)
        })!!
        resolver.openOutputStream(srt)!!.use { it.write("1\n00:00:00,000 --> 00:00:10,000\nFound through the folder grant\n".toByteArray()) }
        step("files written: $video $srt")
        try {
            val loader = SubtitlesExtension.autoLoad(OnDevice.context)
            engine.open(video)
            engine.await("track-list/count", PropertyType.Int64, 10_000) { (it ?: 0) >= 2 }
            delay(1500)
            step("opened")
            val before = loader.lastResult ?: throw AssertionError("no scan result")
            step("scan: granted=${before.folderGranted} found=${before.found.size} place=${before.place}")
            assertFalse(before.folderGranted)
            assertTrue(before.found.isEmpty())
            // The panel's offer: the system picker, opened at the video's folder.
            GrantTestActivity.start = loader.folderUri(before.place!!)
            GrantTestActivity.result = java.util.concurrent.CompletableFuture()
            val picker = ActivityScenario.launch(GrantTestActivity::class.java)
            val device = UiDevice.getInstance(OnDevice.instrumentation)
            device.wait(Until.findObject(By.textContains("LanthornGrant")), 10_000)
            step("picker up")
            shotTo("grant-picker")
            device.wait(Until.findObject(By.text("USE THIS FOLDER").clazz("android.widget.Button")), 5_000)?.click()
                ?: device.wait(Until.findObject(By.text("Use this folder")), 5_000)?.click()
            (device.wait(Until.findObject(By.text("ALLOW")), 5_000) ?: device.wait(Until.findObject(By.text("Allow")), 5_000))?.click()
            shotTo("grant-after-allow")
            val tree = runCatching { GrantTestActivity.result.get(10, TimeUnit.SECONDS) }.getOrNull()
            step("tree: $tree")
            picker.close()
            assertTrue("no grant", tree != null)
            loader.onFileLoaded(engine, video.toString())
            step("rescanned")
            val after = loader.lastResult!!
            assertTrue(after.folderGranted)
            assertEquals(listOf("Grant.srt"), after.found.map { it.name })
            engine.await("sub-text", PropertyType.Text, 5_000) { it == "Found through the folder grant" }
            evidence("US-39.1 shared storage: Movies/LanthornGrant not listable before the grant; after one grant in the system picker, Grant.srt loaded and on")
        } finally {
            resolver.delete(video, null, null)
            resolver.delete(srt, null, null)
            resolver.persistedUriPermissions.forEach { resolver.releasePersistableUriPermission(it.uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }
    }

    private fun shotTo(name: String) {
        val bitmap = OnDevice.instrumentation.uiAutomation.takeScreenshot() ?: return
        val dir = File(OnDevice.context.getExternalFilesDir(null), "evidence").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }
}
