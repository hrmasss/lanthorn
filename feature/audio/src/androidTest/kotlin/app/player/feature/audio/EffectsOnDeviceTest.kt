package app.player.feature.audio

import android.net.Uri
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import app.player.core.engine.PlayerEngine
import app.player.core.testing.Ears
import app.player.core.testing.evidenceShot
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

/** P1.32: presets, the equalizer kept across players, reverse stereo, the volume-boost hook. */
class EffectsOnDeviceTest {
    @get:Rule val timeout: Timeout = Timeout.seconds(300)

    private lateinit var scenario: ActivityScenario<VideoTestActivity>
    private lateinit var engine: PlayerEngine
    private var holder = Any()
    private val store = AudioPrefsStore(OnDevice.context)

    @Before fun start() = runBlocking {
        store.reset()
        scenario = ActivityScenario.launch(VideoTestActivity::class.java)
        engine = OnDevice.acquire(holder) { AudioExtension.onEngineCreated(OnDevice.context, it) }
        scenario.onActivity { it.surface.engine = engine }
        engine.open(Uri.fromFile(OnDevice.asset("tracks.mkv")))
        engine.await("track-list/count", PropertyType.Int64, 10_000) { (it ?: 0) >= 5 }
        engine.await("audio-out-params/channel-count", PropertyType.Int64, 15_000) { it != null }
    }

    @After fun close() = runBlocking {
        if (!::scenario.isInitialized) return@runBlocking
        runCatching { scenario.onActivity { it.surface.engine = null } }
        OnDevice.release(holder)
        scenario.close()
        store.reset()
    }

    private suspend fun effects(): AudioEffects {
        val t0 = SystemClock.elapsedRealtime()
        while (SystemClock.elapsedRealtime() - t0 < 5_000) {
            OnDevice.main { AudioExtension.effects(engine) }?.let { return it }
            delay(20)
        }
        throw AssertionError("no effects for the engine")
    }

    /** US-50.1 from the real effects panel; US-50.3 the next player starts with the same equalizer. */
    @Ears("US-50.1", "US-50.3")
    @Test fun presetsFromThePanelKeptForTheNextPlayer_US50_1_US50_3() = runBlocking {
        val effects = effects()
        PanelTestActivity.withVideo = false
        val panel = ActivityScenario.launch(PanelTestActivity::class.java)
        val device = UiDevice.getInstance(OnDevice.instrumentation)
        device.wait(Until.findObject(By.res("audio-eq")), 5_000)?.click() ?: throw AssertionError("no equalizer switch")
        device.wait(Until.findObject(By.res("audio-preset-Classical")), 5_000)?.click() ?: throw AssertionError("no Classical preset")
        val t0 = SystemClock.elapsedRealtime()
        while (OnDevice.main { effects.state.value.preset } != EqPreset.Classical && SystemClock.elapsedRealtime() - t0 < 5_000) delay(10)
        delay(800)
        shot("us50-presets-classical")
        panel.close()
        val state = OnDevice.main { effects.state.value }
        assertTrue(state.eqOn)
        assertEquals(EqPreset.Classical, state.preset)
        assertEquals(EqPreset.Classical.bandsDb, state.bandsDb)
        (effects.sink as PlatformEffects).effect?.let { dp ->
            (0 until Eq.BANDS).forEach { i -> assertEquals("band $i", EqPreset.Classical.bandsDb!![i], dp.getPreEqBandByChannelIndex(0, i).gain, 0.01f) }
        }
        // A hand-moved band makes the curve Custom; that is what is saved.
        OnDevice.main { effects.setBand(0, -6f) }
        delay(1000) // saves are debounced
        val saved = OnDevice.main { effects.state.value.saved }

        // The next player, as after a restart: the saved equalizer from the store.
        scenario.onActivity { it.surface.engine = null }
        OnDevice.release(holder)
        holder = Any()
        engine = OnDevice.acquire(holder) { AudioExtension.onEngineCreated(OnDevice.context, it) }
        scenario.onActivity { it.surface.engine = engine }
        val next = effects()
        val t1 = SystemClock.elapsedRealtime()
        while (OnDevice.main { next.state.value.saved } != saved && SystemClock.elapsedRealtime() - t1 < 5_000) delay(20)
        val restored = OnDevice.main { next.state.value }
        assertEquals(saved, restored.saved)
        assertEquals(EqPreset.Custom, restored.preset)
        assertEquals(-6f, restored.bandsDb[0])
        evidence("US-50.1 Classical tapped in the effects panel: all ten bands set, read back from the platform effect; screenshot")
        evidence("US-50.3 band 31 Hz moved to -6 dB (Custom), saved; the next player started with equalizer on, Custom, ${restored.bandsDb}")
    }

    @Ears("US-51.1")
    @Test fun reverseWithin200ms_US51_1() = runBlocking {
        val effects = effects()
        val chain = engine.observe("af", PropertyType.Text)
        val out = engine.observe("audio-out-params/samplerate", PropertyType.Int64)
        delay(500)
        val t0 = SystemClock.elapsedRealtime()
        OnDevice.main { effects.setStereo(StereoMode.Reverse) }
        while (chain.value?.contains("pan") != true) delay(1)
        val filterMs = SystemClock.elapsedRealtime() - t0
        while (out.value == null) delay(1)
        val tookMs = SystemClock.elapsedRealtime() - t0
        val chainText = chain.value
        OnDevice.main { effects.setStereo(StereoMode.Stereo) }
        engine.await("af", PropertyType.Text) { it?.contains("subsaudio-stereo") != true }
        evidence("US-51.1 reverse: filter in the chain ($chainText) after $filterMs ms, audio out after $tookMs ms (limit 200)")
        assertWithin("EffectsOnDeviceTest", tookMs, 200)
    }

    /** US-49.1, this module's half: the hook player-ui's volume gesture calls. The gesture is P1.10's. */
    @Ears("US-49.1")
    @Test fun boostHookRaisesGainAndFollowsTheSetting_US49_1() = runBlocking {
        val effects = effects()
        val boost = AudioExtension.volumeBoost
        assertTrue(boost.enabled.value)
        OnDevice.main { boost.setGain(1.5f) }
        assertEquals(1.5f, OnDevice.main { effects.state.value.boost })
        val t0 = SystemClock.elapsedRealtime()
        while (boost.gain.value != 1.5f && SystemClock.elapsedRealtime() - t0 < 2_000) delay(10)
        assertEquals(1.5f, boost.gain.value)
        OnDevice.main { boost.setGain(5f) }
        assertEquals(2f, OnDevice.main { effects.state.value.boost })
        // Setting off: back to 100%, and the gesture can no longer raise it.
        store.setVolumeBoost(false)
        val t1 = SystemClock.elapsedRealtime()
        while (boost.enabled.value && SystemClock.elapsedRealtime() - t1 < 5_000) delay(10)
        assertFalse(boost.enabled.value)
        assertEquals(1f, OnDevice.main { effects.state.value.boost })
        OnDevice.main { boost.setGain(1.8f) }
        assertEquals(1f, OnDevice.main { effects.state.value.boost })
        evidence("US-49.1 hook: setGain 1.5 -> boost 150%, 5 -> clamped 200%; setting off -> 100% and setGain ignored")
    }

    private fun shot(name: String) {
        evidenceShot(name)
    }
}
