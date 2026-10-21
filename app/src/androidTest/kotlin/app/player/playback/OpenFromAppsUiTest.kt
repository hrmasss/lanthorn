package app.player.playback

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.StrictMode
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import app.player.core.data.history.ResumePoints
import app.player.core.testing.Ears
import app.player.feature.playback.OrientationMode
import app.player.feature.playback.PlaybackPrefs
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.FixMethodOrder
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import java.io.File

/**
 * P1.11, opening from other apps (US-13): what the player is offered for, a media link that plays
 * from a local HTTP server, and back returning to the app that opened it. Other apps are played by
 * the shell (`am start`), so the intents go through the system's resolution like any other app's.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class OpenFromAppsUiTest {
    private val ui = PlaybackUi("P1.11")
    private val context = ui.context
    private val device = ui.device

    @get:Rule val onFailure = object : TestWatcher() {
        override fun failed(e: Throwable?, description: Description) = failedShot(ui, description)
    }

    @Before fun setUp() {
        StrictMode.setVmPolicy(StrictMode.VmPolicy.Builder().build())
        device.wakeUp()
        device.setOrientationNatural()
        // Portrait throughout: wide videos would turn the player to landscape, where a centre tap is
        // not reliable on this branch. "As the system is set" with the rotation frozen keeps portrait.
        runBlocking { ResumePoints(context).clear(); PlaybackPrefs(context).reset(); PlaybackPrefs(context).setOrientation(OrientationMode.System) }
    }

    @After fun tearDown() {
        ui.closePlayer()
        runBlocking { PlaybackPrefs(context).reset() }
        device.unfreezeRotation()
    }

    private fun offered(uri: String, type: String? = null, browsable: Boolean = false): Boolean {
        val i = Intent(Intent.ACTION_VIEW)
        if (type != null) i.setDataAndType(Uri.parse(uri), type) else i.data = Uri.parse(uri)
        if (browsable) i.addCategory(Intent.CATEGORY_BROWSABLE)
        val found = context.packageManager.queryIntentActivities(i, PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong()))
            .any { it.activityInfo.packageName == context.packageName }
        ui.log("offered for $uri ${type ?: ""}${if (browsable) " (browsable)" else ""}: $found")
        return found
    }

    @Ears("US-13.1")
    @Test fun a_videoAudioAndStreamFilesFromAnotherApp() {
        for (t in listOf("video/mp4", "video/x-matroska", "audio/mpeg", "audio/mp4", "application/vnd.apple.mpegurl")) {
            assertTrue("not offered for content $t", offered("content://x/y", t))
        }
        // An audio file, sent by another app (the shell) as a view intent with a content uri.
        val song = TestMedia.song()
        val out = ui.shell("am start -a android.intent.action.VIEW -p ${context.packageName} -t audio/mp4 -d $song --grant-read-uri-permission")
        ui.log("am start: ${out.trim()}")
        assertTrue("audio file never opened, state ${ui.state()}", ui.waitFor(30_000) { ui.state()?.uri == song.toString() })
        assertTrue("audio file did not play past 1.5 s, state ${ui.state()}", ui.waitFor(30_000) { ui.state()!!.positionMs >= 1_500 })
        assertNull("audio file failed", ui.state()!!.error)
        // The audio-only screen shows the file's name, not a black screen.
        assertNotNull("no title on the audio-only screen", device.wait(Until.findObject(By.textContains("Song.m4a")), 10_000))
        ui.shot("01-audio-from-other-app")
        // A video file the same way.
        ui.closePlayer()
        val (video) = TestMedia.videos("Single" to "Long.mp4")
        ui.shell("am start -a android.intent.action.VIEW -p ${context.packageName} -t video/mp4 -d $video")
        assertTrue("video from another app showed no frame, state ${ui.state()}", ui.waitFor(30_000) {
            ui.state()?.let { it.uri == video.toString() && it.video != null && it.firstFrameMs != null } == true
        })
        ui.shot("02-video-from-other-app")
    }

    @Ears("US-13.2")
    @Test fun b_mediaLinksOfferedWhileTheSettingIsOn() {
        val links = listOf(
            "http://example.com/v/film.mp4", "https://example.com/a/b/show.mkv", "https://example.com/live/index.m3u8",
            "https://example.com/d/manifest.mpd", "https://example.com/song.mp3",
        )
        for (u in links) assertTrue("not offered for $u", offered(u, browsable = true))
        assertTrue("not offered for a link the server types video/mp4", offered("https://example.com/watch", "video/mp4", browsable = true))
        assertTrue("offered for a web page", !offered("https://example.com/page.html", browsable = true))
        assertTrue("open media links not on by default", runBlocking { PlaybackPrefs(context).settings.first().openLinks })
        // Turned off in the app's own settings.
        ui.openPlaybackSettings()
        val row = ui.findText("Open media links")
        assertNotNull("no Open media links setting", row)
        row!!.click()
        assertTrue("setting did not turn off", ui.waitFor(5_000) { runBlocking { !PlaybackPrefs(context).settings.first().openLinks } })
        ui.shot("03-open-links-off")
        try {
            assertTrue("still offered for media links with the setting off", ui.waitFor(5_000) { !offered(links[0], browsable = true) })
        } finally {
            ui.findText("Open media links")!!.click()
            assertTrue("setting did not turn back on", ui.waitFor(5_000) { runBlocking { PlaybackPrefs(context).settings.first().openLinks } })
        }
        assertTrue("not offered again with the setting back on", ui.waitFor(5_000) { offered(links[0], browsable = true) })
        device.pressBack(); device.pressBack()
    }

    @Ears("US-13.2")
    @Test fun c_mediaLinkPlaysAsANetworkStream() {
        val f = File(context.cacheDir, "clip.mp4")
        ui.instr.context.assets.open("clip420.mp4").use { i -> f.outputStream().use { i.copyTo(it) } }
        LocalHttpServer(f).use { server ->
            val url = "http://127.0.0.1:${server.port}/clip.mp4"
            val out = ui.shell("am start -a android.intent.action.VIEW -c android.intent.category.BROWSABLE -p ${context.packageName} -d $url")
            ui.log("am start: ${out.trim()}")
            assertTrue("link never showed video, state ${ui.state()}", ui.waitFor(40_000) {
                ui.state()?.let { it.uri == url && it.video != null && it.firstFrameMs != null } == true
            })
            assertTrue("stream did not play past 3 s, state ${ui.state()}", ui.waitFor(40_000) { ui.state()!!.positionMs >= 3_000 })
            assertNull("stream failed", ui.state()!!.error)
            ui.log("served ${server.requests} requests")
            assertTrue("the player did not fetch the link over HTTP", server.requests > 0)
            ui.shot("04-link-playing")
            ui.closePlayer()
        }
    }

    @Ears("US-13.4")
    @Test fun d_backReturnsToTheAppThatOpenedIt() {
        val (video) = TestMedia.videos("Single" to "Long.mp4")
        // The library once, so a stale library task could take the back if the player went there.
        ui.openPlaybackSettings()
        device.pressHome()
        ui.shell("am start -W -a android.settings.SETTINGS")
        assertTrue("system settings not in front", device.wait(Until.hasObject(By.pkg("com.android.settings")), 10_000))
        ui.shell("am start -a android.intent.action.VIEW -p ${context.packageName} -t video/mp4 -d $video")
        assertTrue("player never showed the video", ui.waitFor(30_000) { ui.state()?.let { it.uri == video.toString() && it.firstFrameMs != null } == true })
        SystemClock.sleep(1_000)
        ui.shot("05-opened-over-settings")
        ui.backOutOfPlayer()
        assertTrue("player still open after back", ui.waitFor(5_000) { ui.player() == null })
        assertTrue("back did not land in the app that opened the player: ${device.currentPackageName}",
            ui.waitFor(5_000) { device.currentPackageName == "com.android.settings" })
        ui.shot("06-back-lands-in-settings")
        assertEquals("com.android.settings", device.currentPackageName)
        device.pressHome()
    }
}

/** The screen and window tree when a test fails, next to its evidence. */
internal fun failedShot(ui: PlaybackUi, description: Description) {
    val dir = File(ui.context.getExternalFilesDir(null), "p25e").apply { mkdirs() }
    val name = "FAILED-${description.testClass.simpleName}-${description.methodName}"
    ui.device.takeScreenshot(File(dir, "$name.png"))
    ui.device.dumpWindowHierarchy(File(dir, "$name.xml"))
}
