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
    @get:Rule val timeout: Timeout = Timeout.seconds(300) // the hosted CI emulator is slow; the first font scan alone takes a while

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

    /**
     * Another activity on top (panel, system picker) can destroy the video activity on the
     * emulator; mpv then has no surface and keeps video off. In the app the player is only
     * stopped and gets its surface back. Bring the video activity back and wait for video.
     */
    private suspend fun showVideoAgain() {
        val gone = scenario.state == androidx.lifecycle.Lifecycle.State.DESTROYED
        if (!gone && engine.get("vid", PropertyType.Text) != "no") return
        runCatching { scenario.close() }
        scenario = ActivityScenario.launch(VideoTestActivity::class.java)
        scenario.onActivity { it.surface.engine = engine }
        // With no file open there is no video track to wait for.
        if (engine.state.value.loaded) engine.await("vid", PropertyType.Text, 10_000) { it != null && it != "no" }
    }

    /** What mpv has, for a test that waits on subtitle text after another screen was on top. */
    private suspend fun dumpState(tag: String) {
        for (name in listOf("vid", "sid", "current-tracks/sub/id", "current-tracks/sub/title", "time-pos", "pause", "video-params/w", "track-list/count")) {
            android.util.Log.i("evidence", "$tag state $name = ${engine.get(name, PropertyType.Text)}")
        }
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

    /** US-39.1: one clip per format, each with one subtitle file beside it; each loads and turns on. */
    @Test fun eachFormatLoadsOnDevice_US39_1() = runBlocking {
        data class Case(val label: String, val files: Map<String, String>, val text: String?, val codec: String)
        val cases = listOf(
            Case("ass", mapOf("fmt.ass" to "Clip.ass"), "ASS format line", "ass"),
            Case("vtt", mapOf("fmt.vtt" to "Clip.vtt"), "VTT format line", "webvtt"),
            Case("microdvd", mapOf("fmt-microdvd.sub" to "Clip.sub"), "MicroDVD format line", "microdvd"),
            Case("vobsub", mapOf("fmt-vobsub.idx" to "Clip.idx", "fmt-vobsub.sub" to "Clip.sub"), null, "dvd_subtitle"),
            Case("pgs", mapOf("fmt-pgs.sup" to "Clip.sup"), null, "hdmv_pgs_subtitle"),
        )
        val assets = OnDevice.instrumentation.context.assets
        for (c in cases) {
            val dir = File(OnDevice.context.cacheDir, "formats-${c.label}").apply { deleteRecursively(); mkdirs() }
            assets.open("formats/nosubs.mkv").use { input -> File(dir, "Clip.mkv").outputStream().use { input.copyTo(it) } }
            c.files.forEach { (from, to) -> assets.open("formats/$from").use { input -> File(dir, to).outputStream().use { input.copyTo(it) } } }
            engine.open(Uri.fromFile(File(dir, "Clip.mkv")))
            // The clip has video only; the subtitle file is the second track once auto-load adds it.
            engine.await("track-list/count", PropertyType.Int64, 10_000) { (it ?: 0) >= 1 }
            engine.awaitAutoLoad()
            val external = parseTracks(engine.get("track-list", PropertyType.Text)).filter { it.type == "sub" && it.external }
            assertEquals("${c.label}: external tracks ${external.map { it.title to it.codec }}", 1, external.size)
            assertEquals(c.label, c.codec, external[0].codec)
            assertEquals(c.label, external[0].id, selected()?.id)
            if (c.text != null) {
                engine.pause()
                engine.seekTo(1000)
                delay(1500)
                engine.await("sub-text", PropertyType.Text, 5_000) { it == c.text }
            } else {
                // Bitmap subtitles: play from the start into the line, as a viewer does, then hold the frame.
                engine.seekTo(0)
                engine.play()
                engine.await("time-pos", PropertyType.Real, 10_000) { (it ?: 0.0) >= 1.2 }
                engine.pause()
                delay(1500)
            }
            shotTo("us39-format-${c.label}")
            evidence("US-39.1 ${c.label}: ${external[0].title} loaded as ${external[0].codec} and on${c.text?.let { ", shows '$it'" } ?: " (bitmap, screenshot)"}")
            engine.play()
        }
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

    /** US-40: "Open subtitle file" starts in the video's folder; the picked file comes back with the video. */
    @Test fun zyOpenByHandStartsInTheVideoFolder_US40() = runBlocking {
        fun step(s: String) = android.util.Log.i("evidence", "open-by-hand step: $s")
        OnDevice.instrumentation.uiAutomation.grantRuntimePermission(OnDevice.context.packageName, android.Manifest.permission.READ_MEDIA_VIDEO)
        val resolver = OnDevice.context.contentResolver
        val dir = "Movies/LanthornOpen"
        val video = resolver.insert(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "Holiday.mkv")
            put(MediaStore.MediaColumns.RELATIVE_PATH, dir)
        })!!
        resolver.openOutputStream(video)!!.use { out -> OnDevice.asset("wide.mkv").inputStream().use { it.copyTo(out) } }
        // A name that does not start with the video's: auto-load leaves it, so it must be opened by hand.
        val srt = resolver.insert(MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "Picked by hand.srt")
            put(MediaStore.MediaColumns.MIME_TYPE, "application/x-subrip")
            put(MediaStore.MediaColumns.RELATIVE_PATH, dir)
        })!!
        resolver.openOutputStream(srt)!!.use { it.write("1\n00:00:00,000 --> 00:00:10,000\nOpened by hand\n".toByteArray()) }
        try {
            engine.open(video)
            engine.await("track-list/count", PropertyType.Int64, 10_000) { (it ?: 0) >= 2 }
            engine.awaitAutoLoad()
            step("opened")
            PanelTestActivity.panelId = SubtitlesExtension.TRACKS_PANEL
            PanelTestActivity.withVideo = false
            val panel = ActivityScenario.launch(PanelTestActivity::class.java)
            val device = UiDevice.getInstance(OnDevice.instrumentation)
            device.wait(Until.findObject(By.text("Open subtitle file")), 5_000)?.click() ?: throw AssertionError("no open button")
            val inFolder = device.wait(Until.findObject(By.textContains("LanthornOpen")), 10_000)
            step("picker shows the folder: ${inFolder != null}")
            assertTrue("picker did not start in the video's folder", inFolder != null)
            val listed = device.wait(Until.findObject(By.text("Picked by hand.srt")), 10_000) ?: throw AssertionError("file not listed")
            delay(500) // the list settles before the shot
            shotTo("us40-picker-in-video-folder")
            listed.click()
            delay(2000)
            panel.close()
            PanelTestActivity.panelId = SubtitlesExtension.STYLE_PANEL
            PanelTestActivity.withVideo = true
            showVideoAgain()
            engine.pause()
            engine.seekTo(1000)
            delay(1000)
            dumpState("open-by-hand after pick")
            engine.await("sub-text", PropertyType.Text, 10_000) { it == "Opened by hand" }
            step("picked and on")
            // US-40.2: another file, then this video again: the picked file is back and on.
            openClip(Uri.fromFile(OnDevice.asset("wide.mkv")), tracks = 2)
            engine.open(video)
            engine.await("track-list/count", PropertyType.Int64, 10_000) { (it ?: 0) >= 2 }
            engine.awaitAutoLoad()
            engine.pause()
            engine.seekTo(1000)
            engine.await("sub-text", PropertyType.Text, 10_000) { it == "Opened by hand" }
            delay(1500) // the paused frame redraws with the line
            shotTo("us40-picked-file-back")
            evidence("US-40.1 picker opened in Movies/LanthornOpen, 'Picked by hand.srt' chosen and on; US-40.2 after another file, reopening Holiday.mkv brought it back on")
        } finally {
            PerFileChoices(OnDevice.context).forget(video.toString())
            resolver.delete(video, null, null)
            resolver.delete(srt, null, null)
            resolver.persistedUriPermissions.forEach { resolver.releasePersistableUriPermission(it.uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }
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
            // The track panel offers to look in this folder.
            PanelTestActivity.panelId = SubtitlesExtension.TRACKS_PANEL
            PanelTestActivity.withVideo = false
            val panel = ActivityScenario.launch(PanelTestActivity::class.java)
            val offer = UiDevice.getInstance(OnDevice.instrumentation).wait(Until.findObject(By.text("Find subtitles in this folder")), 5_000)
            shotTo("us39-panel-offers-folder")
            panel.close()
            PanelTestActivity.panelId = SubtitlesExtension.STYLE_PANEL
            PanelTestActivity.withVideo = true
            assertTrue("no offer in the track panel", offer != null)
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
            showVideoAgain()
            loader.onFileLoaded(engine, video.toString())
            step("rescanned")
            val after = loader.lastResult!!
            assertTrue(after.folderGranted)
            assertEquals(listOf("Grant.srt"), after.found.map { it.name })
            // The picker took time; the 10 s clip may have played past its line. Look at 1 s.
            engine.pause()
            engine.seekTo(1000)
            delay(1000)
            dumpState("grant after rescan")
            engine.await("sub-text", PropertyType.Text, 10_000) { it == "Found through the folder grant" }
            engine.pause()
            delay(1500)
            shotTo("us39-grant-srt-drawn")
            evidence("US-39.1 shared storage: Movies/LanthornGrant not listable before the grant; after one grant in the system picker, Grant.srt loaded and on")
        } finally {
            resolver.delete(video, null, null)
            resolver.delete(srt, null, null)
            resolver.persistedUriPermissions.forEach { resolver.releasePersistableUriPermission(it.uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }
    }

    /** US-39.1 "or the set subtitle folder": a folder picked in Subtitle settings is searched for every video. */
    @Test fun zzzSubtitleFolderSetting_US39_1() = runBlocking {
        fun step(s: String) = android.util.Log.i("evidence", "subtitle folder step: $s")
        val resolver = OnDevice.context.contentResolver
        val srt = resolver.insert(MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "Away.en.srt")
            put(MediaStore.MediaColumns.MIME_TYPE, "application/x-subrip")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/LanthornSubs")
        })!!
        resolver.openOutputStream(srt)!!.use { it.write("1\n00:00:00,000 --> 00:00:10,000\nFrom the subtitle folder\n".toByteArray()) }
        // The video sits in a folder with no subtitles of its own.
        val away = File(OnDevice.context.cacheDir, "away").apply { deleteRecursively(); mkdirs() }
        OnDevice.asset("wide.mkv").copyTo(File(away, "Away.mkv"), overwrite = true)
        try {
            GrantTestActivity.start = android.provider.DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:Download/LanthornSubs")
            GrantTestActivity.result = java.util.concurrent.CompletableFuture()
            val picker = ActivityScenario.launch(GrantTestActivity::class.java)
            val device = UiDevice.getInstance(OnDevice.instrumentation)
            device.wait(Until.findObject(By.textContains("LanthornSubs")), 10_000)
            device.wait(Until.findObject(By.text("USE THIS FOLDER").clazz("android.widget.Button")), 5_000)?.click()
                ?: device.wait(Until.findObject(By.text("Use this folder")), 5_000)?.click()
            (device.wait(Until.findObject(By.text("ALLOW")), 5_000) ?: device.wait(Until.findObject(By.text("Allow")), 5_000))?.click()
            val tree = runCatching { GrantTestActivity.result.get(10, TimeUnit.SECONDS) }.getOrNull()
            picker.close()
            step("tree: $tree")
            assertTrue("no folder picked", tree != null)
            prefs.setFolder(tree)
            showVideoAgain()
            openClip(Uri.fromFile(File(away, "Away.mkv")), tracks = 3)
            assertEquals("Away.en.srt", selected()?.title)
            engine.pause()
            engine.seekTo(1000)
            engine.await("sub-text", PropertyType.Text, 10_000) { it == "From the subtitle folder" }
            delay(1500)
            shotTo("us39-subtitle-folder")
            evidence("US-39.1 subtitle folder: Download/LanthornSubs picked in the system picker and set; Away.mkv in another folder loaded Away.en.srt from it and turned it on")
        } finally {
            prefs.setFolder(null)
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
