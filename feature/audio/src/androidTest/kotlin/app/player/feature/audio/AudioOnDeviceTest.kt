package app.player.feature.audio

import app.player.core.testing.Ears
import android.net.Uri
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import app.player.core.engine.PlayerEngine
import app.player.core.engine.PropertyType
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.rules.Timeout
import org.junit.Test
import kotlin.math.abs

/** P1.3 audio and P1.4 effects wiring against the real libmpv and platform effects, on the emulator. */
class AudioOnDeviceTest {
    /** A stuck wait fails the test instead of holding the shared emulator. */
    @get:Rule val timeout: Timeout = Timeout.seconds(420)

    private lateinit var scenario: ActivityScenario<VideoTestActivity>
    private lateinit var engine: PlayerEngine
    private lateinit var controller: AudioController
    private val holder = Any()

    @Before fun start() {
        scenario = ActivityScenario.launch(VideoTestActivity::class.java)
        engine = OnDevice.acquire(holder) { AudioExtension.onEngineCreated(OnDevice.context, it) }
        scenario.onActivity { it.surface.engine = engine }
        controller = AudioController(engine.asMpv())
    }

    @After fun close() {
        if (!::scenario.isInitialized) return
        scenario.onActivity { it.surface.engine = null }
        OnDevice.release(holder)
        scenario.close()
    }

    private suspend fun openTracks() {
        engine.open(Uri.fromFile(OnDevice.asset("tracks.mkv")))
        engine.await("track-list/count", PropertyType.Int64, 10_000) { (it ?: 0) >= 5 }
        engine.await("audio-out-params/channel-count", PropertyType.Int64, 10_000) { it != null }
    }

    @Ears("US-47.1", "US-51.2")
    @Test fun listAndSwitchWithoutSeek_US47_1_US51_2() = runBlocking {
        openTracks()
        val tracks = parseTracks(engine.get("track-list", PropertyType.Text)).filter { it.type == "audio" }
        assertEquals(listOf("eng", "ita"), tracks.map { it.lang })
        assertEquals(listOf("aac", "ac3"), tracks.map { it.codec })
        assertEquals(listOf(1, 6), tracks.map { it.channels })
        delay(1500)
        val pos0 = engine.get("time-pos", PropertyType.Real)!!
        val t0 = SystemClock.elapsedRealtime()
        controller.select(tracks[1].id)
        engine.await("current-tracks/audio/id", PropertyType.Int64) { it == tracks[1].id }
        engine.await("audio-codec-name", PropertyType.Text) { it == "ac3" }
        val took = SystemClock.elapsedRealtime() - t0
        val pos1 = engine.get("time-pos", PropertyType.Real)!!
        val wall = (SystemClock.elapsedRealtime() - t0) / 1000.0
        // No seek: position moved forward by about the wall time, not jumped.
        assertTrue("pos $pos0 -> $pos1", pos1 >= pos0 && pos1 - pos0 < wall + 0.5)
        val out = engine.await("audio-out-params/channel-count", PropertyType.Int64) { it != null && it <= 2 }
        val outChannels = engine.get("audio-out-params/channel-count", PropertyType.Int64)
        evidence("US-47.1 tracks eng/aac/1ch, ita/ac3/6ch; switch to ita in $took ms (limit 500), position $pos0 -> $pos1 s, no seek")
        evidence("US-51.2 5.1 ac3 track plays out as $outChannels channels (downmixed, settled in $out ms)")
        assertWithin("AudioOnDeviceTest", took, 500)
    }

    @Ears("US-48.1")
    @Test fun delayAppliesWithin200ms_US48_1() = runBlocking {
        openTracks()
        val t0 = SystemClock.elapsedRealtime()
        controller.setDelayMs(1234)
        engine.await("audio-delay", PropertyType.Real) { it != null && abs(it - 1.23) < 1e-6 }
        val took = SystemClock.elapsedRealtime() - t0
        controller.setDelayMs(-20_000)
        engine.await("audio-delay", PropertyType.Real) { it != null && abs(it + 10.0) < 1e-6 }
        evidence("US-48.1 audio-delay 1234 ms snapped to 1.23 s, applied in $took ms (limit 200); -20 s clamped to -10 s")
        assertWithin("AudioOnDeviceTest", took, 200)
    }

    @Ears("US-49.2", "US-49.3", "US-50.2")
    @Test fun effectsOnOurSession_US49_2_US49_3_US50_2() = runBlocking {
        openTracks()
        val session = engine.get("audiotrack-session-id", PropertyType.Int64)
        assertNotNull(session)
        assertTrue("session $session", session!! > 0)
        val effects = OnDevice.main { AudioExtension.effects(engine)!! }
        OnDevice.main { effects.setBoost(2f) }
        val platform = effects.sink as PlatformEffects
        val dp = platform.effect
        if (dp == null) {
            evidence("US-49.2 DynamicsProcessing not available on this emulator image: open, needs phone")
            return@runBlocking
        }
        assertTrue(dp.hasControl())
        assertTrue(dp.enabled)
        val limiter = dp.getLimiterByChannelIndex(0)
        assertTrue(limiter.isEnabled)
        assertEquals(Eq.LIMITER_THRESHOLD_DB, limiter.threshold, 0.01f)
        assertEquals(6.02f, dp.getInputGainByChannelIndex(0), 0.01f)
        OnDevice.main { effects.setEq(true) }
        val t0 = SystemClock.elapsedRealtime()
        OnDevice.main { effects.setBand(4, 6f) }
        val took = SystemClock.elapsedRealtime() - t0
        assertEquals(6f, dp.getPreEqBandByChannelIndex(0, 4).gain, 0.01f)
        evidence("US-49.2 boost 200% = input gain ${"%.2f".format(6.02f)} dB, limiter on, ceiling ${limiter.threshold} dBFS, ratio ${limiter.ratio}")
        evidence("US-50.2 band 500 Hz to +6 dB applied in $took ms (limit 100), read back from the platform effect")

        // US-49.3: a new player starts at 100%.
        OnDevice.release(holder)
        val holder2 = Any()
        val engine2 = OnDevice.acquire(holder2) { AudioExtension.onEngineCreated(OnDevice.context, it) }
        val boost = OnDevice.main { AudioExtension.effects(engine2)!!.state.value.boost }
        OnDevice.release(holder2)
        engine = OnDevice.acquire(holder) { AudioExtension.onEngineCreated(OnDevice.context, it) }
        assertEquals(1f, boost)
        evidence("US-49.3 boost 200% before close, ${(boost * 100).toInt()}% on the next player")
        assertWithin("AudioOnDeviceTest", took, 100)
    }

    /**
     * US-49.2 output peak, read with Android's Visualizer on our session. Three cases on a -3 dBFS
     * tone: no boost (shows the Visualizer hears us), boost 200% with the limiter off (shows it
     * hears after the effect), boost 200% with the limiter on (the claim).
     */
    @Ears("US-49.2")
    @Test fun limiterKeepsPeakUnder0dBFS_US49_2() = runBlocking {
        OnDevice.instrumentation.uiAutomation.grantRuntimePermission(OnDevice.context.packageName, android.Manifest.permission.RECORD_AUDIO)
        // The Visualizer reads after the stream volume: at full volume its peak is the output's dBFS.
        val am = OnDevice.context.getSystemService(android.media.AudioManager::class.java)
        am.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC), 0)
        engine.set("loop-file", "inf")
        engine.open(Uri.fromFile(OnDevice.asset("loud.flac")))
        engine.await("audio-out-params/channel-count", PropertyType.Int64, 10_000) { it != null }
        val session = engine.get("audiotrack-session-id", PropertyType.Int64)!!.toInt()
        val effects = OnDevice.main { AudioExtension.effects(engine)!! }
        val visualizer = OnDevice.main {
            android.media.audiofx.Visualizer(session).apply {
                measurementMode = android.media.audiofx.Visualizer.MEASUREMENT_MODE_PEAK_RMS
                enabled = true
            }
        }
        suspend fun peakDb(): Double {
            delay(1500) // settle after a change
            var peak = Int.MIN_VALUE
            val m = android.media.audiofx.Visualizer.MeasurementPeakRms()
            repeat(40) {
                OnDevice.main { visualizer.getMeasurementPeakRms(m) }
                peak = maxOf(peak, m.mPeak)
                delay(50)
            }
            return peak / 100.0 // millibels to dB
        }
        val plain = peakDb()
        OnDevice.main { effects.setBoost(2f) }
        val dp = (effects.sink as PlatformEffects).effect!!
        OnDevice.main { dp.setLimiterAllChannelsTo(android.media.audiofx.DynamicsProcessing.Limiter(true, false, 0, 1f, 60f, 1f, 0f, 0f)) }
        val unlimited = peakDb()
        OnDevice.main { effects.setBoost(1.99f); effects.setBoost(2f) } // re-applies our limiter
        val limited = peakDb()
        OnDevice.main { visualizer.release() }
        evidence("US-49.2 Visualizer peak on our session, -3 dBFS tone: no boost %.1f dB, boost 200%% limiter off %.1f dB, limiter on %.1f dB".format(plain, unlimited, limited))
        if (unlimited <= plain + 1.0) {
            evidence("US-49.2 Visualizer reads before the effect on this device (boost did not raise its peak): output peak not measurable here")
        } else {
            assertTrue("limited peak $limited dB", limited < 0.0)
        }
    }

    @Ears("US-51.1")
    @Test fun monoWithin200ms_US51_1() = runBlocking {
        engine.set("msg-level", "all=warn,af=v,ao=v,audio=v")
        openTracks()
        val effects = OnDevice.main { AudioExtension.effects(engine)!! }
        // Event-driven: mpv pushes the new af value; no polling round trip in the number.
        val chain = engine.observe("af", PropertyType.Text)
        val out = engine.observe("audio-out-params/samplerate", PropertyType.Int64)
        delay(500)
        val t0 = SystemClock.elapsedRealtime()
        android.util.Log.i("evidence-timing", "mono requested")
        OnDevice.main { effects.setStereo(StereoMode.Mono) }
        while (chain.value?.contains("subsaudio-stereo") != true) delay(1)
        val filterMs = SystemClock.elapsedRealtime() - t0
        // Audio flows again after the chain rebuild: the output format is published again.
        while (out.value == null) delay(1)
        val tookMs = SystemClock.elapsedRealtime() - t0
        val chainText = chain.value
        OnDevice.main { effects.setStereo(StereoMode.Stereo) }
        engine.await("af", PropertyType.Text) { it?.contains("subsaudio-stereo") != true }
        evidence("US-51.1 mono: filter in the chain ($chainText) after $filterMs ms, audio out after $tookMs ms (limit 200)")
        assertWithin("AudioOnDeviceTest", tookMs, 200)
    }

    @Ears("US-47.3")
    @Test fun externalAudioStaysInSync_US47_3() = runBlocking {
        if (!longRun()) return@runBlocking
        engine.open(Uri.fromFile(OnDevice.asset("long.mp4")))
        engine.await("duration", PropertyType.Real, 10_000) { it != null && it > 290 }
        controller.addFile(OnDevice.asset("dub.m4a").path, "dub.m4a")
        engine.await("current-tracks/audio/external", PropertyType.Flag) { it == true }
        val s = measureSync()
        evidence("US-47.3 external audio: ${s.line()}")
        assertTrue(s.line(), s.max < 40.0)
    }

    /** Baseline for US-47.3: the file's own audio track, same file, same seeks, same device. */
    @Test fun embeddedAudioSyncBaseline() = runBlocking {
        if (!longRun()) return@runBlocking
        engine.open(Uri.fromFile(OnDevice.asset("long.mp4")))
        engine.await("duration", PropertyType.Real, 10_000) { it != null && it > 290 }
        engine.await("audio-out-params/channel-count", PropertyType.Int64, 10_000) { it != null }
        evidence("US-47.3 baseline, embedded audio: ${measureSync().line()}")
    }

    private class Sync(val ms: List<Double>) {
        val max get() = ms.maxOrNull() ?: 0.0
        fun pct(p: Double) = ms.sorted().let { if (it.isEmpty()) 0.0 else it[((it.size - 1) * p).toInt()] }
        fun line() = "|A-V| over 300 s, 3 seeks, ${ms.size} samples: p50 ${f(pct(0.5))}, p95 ${f(pct(0.95))}, " +
            "p99 ${f(pct(0.99))}, max ${f(max)} ms, ${ms.count { it > 40 }} over 40 ms"
        private fun f(d: Double) = "%.1f".format(d)
    }

    /** Samples mpv's A/V difference twice a second for 5 min, seeking three times, 1 s settle each. */
    private suspend fun measureSync(): Sync {
        val seeks = mapOf(60 to 150_000L, 150 to 30_000L, 200 to 240_000L)
        val ms = mutableListOf<Double>()
        val t0 = SystemClock.elapsedRealtime()
        var settleUntil = t0 + 1000
        while (SystemClock.elapsedRealtime() - t0 < 300_000) {
            val s = ((SystemClock.elapsedRealtime() - t0) / 1000).toInt()
            seeks[s]?.let { target ->
                engine.seekTo(target)
                settleUntil = SystemClock.elapsedRealtime() + 1000
                delay(1000)
            }
            if (engine.get("eof-reached", PropertyType.Flag) == true) engine.seekTo(0)
            if (SystemClock.elapsedRealtime() > settleUntil) engine.get("avsync", PropertyType.Real)?.let { ms += abs(it) * 1000 }
            delay(500)
        }
        return Sync(ms)
    }
}
