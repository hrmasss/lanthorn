package app.player.feature.subtitles

import android.net.Uri
import androidx.test.core.app.ActivityScenario
import app.player.core.engine.PlayerEngine
import app.player.core.engine.PropertyType
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import kotlin.math.abs

/** P1.27: the default subtitle delay from settings applies to every new file (US-41.3). */
class TimingOnDeviceTest {
    /** A stuck wait fails the test instead of holding the shared emulator. */
    @get:Rule val timeout: Timeout = Timeout.seconds(120)

    private lateinit var scenario: ActivityScenario<VideoTestActivity>
    private lateinit var engine: PlayerEngine
    private val holder = Any()
    private val store = SubtitleStyleStore(OnDevice.context)

    @Before fun start() = runBlocking {
        store.resetDelay()
        scenario = ActivityScenario.launch(VideoTestActivity::class.java)
        engine = OnDevice.acquire(holder) { SubtitlesExtension.onEngineCreated(OnDevice.context, it) }
        scenario.onActivity { it.surface.engine = engine }
        Unit
    }

    @After fun close() = runBlocking {
        store.resetDelay()
        if (!::scenario.isInitialized) return@runBlocking
        runCatching { scenario.onActivity { it.surface.engine = null } }
        OnDevice.release(holder)
        scenario.close()
    }

    private suspend fun near(seconds: Double) =
        engine.await("sub-delay", PropertyType.Real, 10_000) { it != null && abs(it - seconds) < 1e-6 }

    @Test fun defaultDelayOnEveryNewFile_US41_3() = runBlocking {
        store.setDefaultDelay(320) // snaps to 300
        engine.open(Uri.fromFile(OnDevice.asset("tracks.mkv")))
        near(0.3)
        SubtitleController(engine.asMpv()).setDelayMs(-1000)
        near(-1.0)
        engine.open(Uri.fromFile(OnDevice.asset("wide.mkv")))
        near(0.3)
        evidence("US-41.3 default 320 ms snapped to 300 ms, applied on open; changed to -1 s; the next file started at 300 ms again")
    }

    @Test fun perFileDelayRemembered_US41_4() = runBlocking {
        store.setDefaultDelay(300)
        val tracks = Uri.fromFile(OnDevice.asset("tracks.mkv"))
        engine.open(tracks)
        near(0.3)
        OnDevice.main {
            SubtitleController(engine.asMpv()).setDelayMs(1250)
            SubtitlesExtension.rememberDelay(OnDevice.context, engine, 1250)
        }
        near(1.25)
        engine.open(Uri.fromFile(OnDevice.asset("wide.mkv")))
        near(0.3)
        engine.open(tracks)
        near(1.25)
        // "Remember choices" off: the file gets the default again.
        val remember = app.player.core.data.choice.RememberPerFile(OnDevice.context)
        remember.set(false)
        engine.open(Uri.fromFile(OnDevice.asset("wide.mkv")))
        near(0.3)
        engine.open(tracks)
        near(0.3)
        remember.set(true)
        app.player.core.data.choice.PerFileChoices(OnDevice.context).forget(tracks.toString())
        evidence("US-41.4 set 1.25 s on one file; another file got the 300 ms default; reopening the first restored 1.25 s; with remember choices off it got 300 ms")
    }
}
