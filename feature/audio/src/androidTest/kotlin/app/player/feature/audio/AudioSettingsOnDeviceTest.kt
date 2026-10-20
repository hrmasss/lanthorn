package app.player.feature.audio

import android.graphics.Bitmap
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
import kotlin.math.abs

/** P1.31: the Audio settings group drives what the player does at open. */
class AudioSettingsOnDeviceTest {
    /** A stuck wait fails the test instead of holding the shared emulator. */
    @get:Rule val timeout: Timeout = Timeout.seconds(120)

    private lateinit var scenario: ActivityScenario<VideoTestActivity>
    private lateinit var engine: PlayerEngine
    private val holder = Any()
    private val store = AudioPrefsStore(OnDevice.context)

    @Before fun start() = runBlocking {
        store.reset()
        scenario = ActivityScenario.launch(VideoTestActivity::class.java)
        engine = OnDevice.acquire(holder) { AudioExtension.onEngineCreated(OnDevice.context, it) }
        scenario.onActivity { it.surface.engine = engine }
        Unit
    }

    @After fun close() = runBlocking {
        store.reset()
        if (!::scenario.isInitialized) return@runBlocking
        runCatching { scenario.onActivity { it.surface.engine = null } }
        OnDevice.release(holder)
        scenario.close()
    }

    private suspend fun open(name: String) {
        engine.open(Uri.fromFile(OnDevice.asset(name)))
        engine.await("track-list/count", PropertyType.Int64, 10_000) { (it ?: 0) >= 3 }
        engine.await("current-tracks/audio/id", PropertyType.Int64, 10_000) { it != null }
    }

    @Test fun preferredLanguagePicksTrack_US47_2() = runBlocking {
        open("tracks.mkv")
        val first = engine.get("current-tracks/audio/lang", PropertyType.Text)
        store.setLanguage("it")
        engine.await("alang", PropertyType.Text) { it == "it,ita" }
        open("tracks.mkv")
        val picked = engine.get("current-tracks/audio/lang", PropertyType.Text)
        assertEquals("ita", picked)
        evidence("US-47.2 no preference: '$first' (the default track); preferred Italian: '$picked' picked at open (alang it,ita)")
    }

    @Test fun defaultDelayOnEveryNewFile_US48_3() = runBlocking {
        store.setDefaultDelay(250)
        delay(300)
        open("tracks.mkv")
        engine.await("audio-delay", PropertyType.Real) { it != null && abs(it - 0.25) < 1e-6 }
        OnDevice.main { AudioExtension.delay(engine)!!.setBase(-500) }
        engine.await("audio-delay", PropertyType.Real) { it != null && abs(it + 0.5) < 1e-6 }
        open("long.mp4")
        engine.await("audio-delay", PropertyType.Real) { it != null && abs(it - 0.25) < 1e-6 }
        evidence("US-48.3 default 250 ms applied on open; changed to -500 ms; the next file started at 250 ms again")
    }

    @Test fun remembersTrackPerFile_US47_4() = runBlocking {
        open("tracks.mkv")
        val ita = parseTracks(engine.get("track-list", PropertyType.Text)).first { it.type == "audio" && it.lang == "ita" }.id
        OnDevice.main {
            AudioController(engine.asMpv()).select(ita)
            AudioExtension.rememberTrack(OnDevice.context, engine, ita)
        }
        delay(500)
        open("long.mp4")
        open("tracks.mkv")
        engine.await("current-tracks/audio/lang", PropertyType.Text, 10_000) { it == "ita" }
        app.player.core.data.choice.PerFileChoices(OnDevice.context).forget(Uri.fromFile(OnDevice.asset("tracks.mkv")).toString())
        evidence("US-47.4 picked the Italian track, opened another file, reopened: Italian restored")
    }

    @Test fun settingsPage_P1_31() = runBlocking {
        store.setLanguage("it")
        store.setDefaultDelay(120)
        store.setBluetoothDelay(200)
        val page = ActivityScenario.launch(SettingsTestActivity::class.java)
        delay(3000)
        shot("audio-settings-page")
        page.close()
        evidence("Audio settings page: language Italian, default +0.12 s, Bluetooth +0.20 s; screenshot")
    }

    private fun shot(name: String) {
        val bitmap = OnDevice.instrumentation.uiAutomation.takeScreenshot() ?: return
        val dir = File(OnDevice.context.getExternalFilesDir(null), "evidence").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
