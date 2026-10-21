package app.player.feature.audio

import app.player.core.testing.evidenceShot
import app.player.core.testing.Ears
import android.graphics.Bitmap
import android.net.Uri
import androidx.test.core.app.ActivityScenario
import app.player.core.engine.PlayerEngine
import app.player.core.engine.PropertyType
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
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

    @Before fun start(): Unit = runBlocking {
        store.reset()
        scenario = ActivityScenario.launch(VideoTestActivity::class.java)
        engine = OnDevice.acquire(holder) { AudioExtension.onEngineCreated(OnDevice.context, it) }
        scenario.onActivity { it.surface.engine = engine }
        Unit
    }

    @After fun close(): Unit = runBlocking {
        store.reset()
        if (!::scenario.isInitialized) return@runBlocking
        runCatching { scenario.onActivity { it.surface.engine = null } }
        OnDevice.release(holder)
        scenario.close()
    }

    private suspend fun open(name: String) {
        engine.open(Uri.fromFile(OnDevice.asset(name)))
        engine.await("track-list/count", PropertyType.Int64, 10_000) { (it ?: 0) >= 2 }
        engine.await("current-tracks/audio/id", PropertyType.Int64, 10_000) { it != null }
    }

    @Ears("US-47.2")
    @Test fun preferredLanguagePicksTrack_US47_2() = runBlocking {
        open("tracks.mkv")
        val first = engine.get("current-tracks/audio/lang", PropertyType.Text)
        store.setLanguage("it")
        engine.await("alang", PropertyType.Text) { it == "it,ita" }
        open("tracks.mkv")
        // The previous file's track values linger until the new file has loaded: wait for the pick, bounded.
        val picked = runCatching { engine.await("current-tracks/audio/lang", PropertyType.Text, 5_000) { it == "ita" } }
            .map { "ita" }.getOrElse { engine.get("current-tracks/audio/lang", PropertyType.Text) }
        assertEquals("ita", picked)
        evidence("US-47.2 no preference: '$first' (the default track); preferred Italian: '$picked' picked at open (alang it,ita)")
    }

    @Ears("US-48.3")
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

    @Ears("US-47.4")
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
        // "Remember choices" off: the file opens on its default track again.
        val remember = app.player.core.data.choice.RememberPerFile(OnDevice.context)
        remember.set(false)
        open("long.mp4")
        open("tracks.mkv")
        delay(1000)
        val whenOff = engine.get("current-tracks/audio/lang", PropertyType.Text)
        remember.set(true)
        app.player.core.data.choice.PerFileChoices(OnDevice.context).forget(Uri.fromFile(OnDevice.asset("tracks.mkv")).toString())
        assertEquals("eng", whenOff)
        evidence("US-47.4 picked the Italian track, opened another file, reopened: Italian restored; with remember choices off it opened on 'eng'")
    }

    @Ears("US-47.2")
    @Test fun defaultFlaggedTrackWithoutPreference_US47_2() = runBlocking {
        engine.open(Uri.fromFile(OnDevice.asset("default-second.mkv")))
        engine.await("track-list/count", PropertyType.Int64, 10_000) { (it ?: 0) >= 3 }
        engine.await("current-tracks/audio/lang", PropertyType.Text, 10_000) { it != null }
        val tracks = parseTracks(engine.get("track-list", PropertyType.Text)).filter { it.type == "audio" }
        assertEquals(listOf("eng" to false, "ita" to true), tracks.map { it.lang to it.default })
        assertEquals("ita", engine.get("current-tracks/audio/lang", PropertyType.Text))
        evidence("US-47.2 no preference, default flag on the second track (ita), first is eng: opened on 'ita'")
    }

    @Test fun groupResetExportImport() = runBlocking {
        store.setLanguage("it")
        store.setDefaultDelay(120)
        store.setBluetoothDelay(200)
        val exported = AudioSettings.export(OnDevice.context)
        AudioSettings.reset(OnDevice.context)
        assertEquals(AudioPrefs(), store.prefs.first())
        AudioSettings.import(OnDevice.context, org.json.JSONObject(exported.toString()))
        assertEquals(AudioPrefs("it", 120, 200), store.prefs.first())
        evidence("Audio group: export $exported; reset gave the defaults; import brought back it/120/200")
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
        evidenceShot(name)
    }
}
