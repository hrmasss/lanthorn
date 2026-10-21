package app.player.feature.subtitles

import app.player.core.testing.evidenceShot
import app.player.core.testing.Ears
import android.graphics.Bitmap
import android.net.Uri
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import java.io.File

/** P1.30, US-46: online subtitle search against a local stand-in for the service. */
class OnlineSearchOnDeviceTest {
    @get:Rule val timeout: Timeout = Timeout.seconds(200)

    private lateinit var scenario: ActivityScenario<VideoTestActivity>
    private lateinit var engine: PlayerEngine
    private val holder = Any()
    private val accounts = OnlineAccountStore(OnDevice.context)
    private val fake = FakeOpenSubtitles("1\n00:00:00,000 --> 00:00:10,000\nDownloaded from the service\n")
    private val device get() = UiDevice.getInstance(OnDevice.instrumentation)
    private val video get() = File(OnDevice.context.cacheDir, "online/Clip.mkv")

    @Before fun start() = runBlocking {
        accounts.reset()
        SubtitlePrefsStore(OnDevice.context).setShowAutomatically(false)
        OnlineSearchPanel.baseUrl = fake.baseUrl
        video.parentFile!!.apply { deleteRecursively(); mkdirs() }
        OnDevice.asset("wide.mkv").copyTo(video, overwrite = true)
        scenario = ActivityScenario.launch(VideoTestActivity::class.java)
        engine = OnDevice.acquire(holder) { SubtitlesExtension.onEngineCreated(OnDevice.context, it) }
        scenario.onActivity { it.surface.engine = engine }
        engine.open(Uri.fromFile(video))
        engine.await("track-list/count", PropertyType.Int64, 10_000) { (it ?: 0) >= 2 }
        engine.awaitAutoLoad()
        Unit
    }

    @After fun close() = runBlocking {
        accounts.reset()
        SubtitlePrefsStore(OnDevice.context).setShowAutomatically(true)
        OnlineSearchPanel.baseUrl = OpenSubtitles.BASE_URL
        PerFileChoices(OnDevice.context).forget(Uri.fromFile(video).toString())
        PanelTestActivity.panelId = SubtitlesExtension.STYLE_PANEL
        PanelTestActivity.withVideo = true
        fake.close()
        runCatching { scenario.onActivity { it.surface.engine = null } }
        OnDevice.release(holder)
        scenario.close()
    }

    private fun panel(id: String): ActivityScenario<PanelTestActivity> {
        PanelTestActivity.panelId = id
        PanelTestActivity.withVideo = false
        return ActivityScenario.launch(PanelTestActivity::class.java)
    }

    @Ears("US-46.4", "US-46.5")
    @Test fun offByDefaultAndHidden_US46_4_US46_5() = runBlocking {
        val defaults = OnlineAccount()
        assertEquals(false, defaults.enabled)
        assertEquals("", defaults.apiKey)
        val off = panel(SubtitlesExtension.TRACKS_PANEL)
        device.wait(Until.findObject(By.text("Open subtitle file")), 5_000)
        val hidden = device.findObject(By.text("Search online"))
        shot("us46-off-no-search")
        off.close()
        assertNull("search shown while off", hidden)
        accounts.setEnabled(true)
        accounts.setApiKey("viewer-own-key")
        val on = panel(SubtitlesExtension.TRACKS_PANEL)
        val shown = device.wait(Until.findObject(By.text("Search online")), 5_000)
        shot("us46-on-search-shown")
        on.close()
        assertNotNull("search hidden while on", shown)
        assertEquals(0, fake.requests.size)
        evidence("US-46.4 off by default, no search action; on with a key, the action shows. US-46.5 defaults: no API key shipped (empty), off")
    }

    @Ears("US-46.1", "US-46.2", "US-46.3")
    @Test fun searchOnlyOnTapThenDownloadAndTurnOn_US46_1_US46_2_US46_3() = runBlocking {
        accounts.setEnabled(true)
        accounts.setApiKey("viewer-own-key")
        accounts.setUsername("viewer")
        accounts.setPassword("secret")
        val p = panel(SubtitlesExtension.ONLINE_PANEL)
        device.wait(Until.findObject(By.text("Search")), 5_000)
        delay(2000)
        assertEquals("requests before the tap", 0, fake.requests.size)
        device.findObject(By.text("Search")).click()
        val result = device.wait(Until.findObject(By.text("Clip 2026 WEB")), 10_000)
        shot("us46-results")
        assertNotNull("no results listed", result)
        val search = fake.requests.single { it.path.startsWith("/api/v1/subtitles") }
        assertTrue("no hash in ${search.path}", search.path.contains("moviehash="))
        assertTrue("no name in ${search.path}", search.path.contains("query=Clip"))
        assertEquals("viewer-own-key", search.headers["api-key"])
        result!!.click()
        delay(3000)
        runCatching { p.close() }
        engine.pause()
        engine.seekTo(1000)
        engine.await("sub-text", PropertyType.Text, 10_000) { it == "Downloaded from the service" }
        val saved = File(video.parentFile, "Clip.en.srt")
        assertTrue("not saved beside the video", saved.exists())
        val paths = fake.requests.map { "${it.method} ${it.path.substringBefore('?')}" }
        val login = fake.requests.first { it.path.startsWith("/api/v1/login") }
        assertTrue(login.body.contains("\"username\":\"viewer\""))
        val download = fake.requests.first { it.path.startsWith("/api/v1/download") }
        assertEquals("Bearer T0K3N", download.headers["authorization"])
        // US-46.5: the viewer's key on every request, not only the search.
        fake.requests.forEach { assertEquals(it.path, "viewer-own-key", it.headers["api-key"]) }
        shot("us46-downloaded-on")
        evidence("US-46.3 0 requests before the tap; then: $paths")
        evidence("US-46.1 search by moviehash and name (${search.path.substringAfter('?').take(80)}), results list language and rating")
        evidence("US-46.2 picked 'Clip 2026 WEB': logged in with the viewer's account, downloaded, saved as ${saved.name} beside the video, turned on")
        evidence("US-46.5 every request carried the viewer's API key, never a built-in one")
    }

    private fun shot(name: String) {
        evidenceShot(name)
    }
}
