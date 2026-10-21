package app.player.feature.audio

import app.player.core.testing.Ears
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class AudioTest {
    private val mpv = FakeMpv()

    private class FakeSink : EffectsSink {
        val applied = mutableListOf<EffectsState>()
        val bands = mutableListOf<Pair<Int, Float>>()
        var released = false
        override fun apply(state: EffectsState) { applied += state }
        override fun setBand(index: Int, gainDb: Float) { bands += index to gainDb }
        override fun release() { released = true }
    }

    private val sink = FakeSink()
    private val effects = AudioEffects(sink, StereoFilter(mpv))

    @Ears("US-47.1")
    @Test fun `audio tracks list with channels and switch by aid (US-47_1)`() = runTest {
        val c = AudioController(mpv)
        mpv.emit("track-list", """[{"id":1,"type":"audio","lang":"eng","codec":"ac3","demux-channel-count":6},{"id":2,"type":"audio","lang":"ita","codec":"aac","demux-channel-count":2},{"id":1,"type":"sub"}]""")
        mpv.emit("aid", "2")
        val tracks = c.tracks.first()
        assertEquals(listOf(1L, 2L), tracks.map { it.id })
        assertEquals(6, tracks[0].channels)
        assertEquals(2L, c.selected.first())
        c.select(1)
        assertEquals("set aid=1", mpv.calls.last())
    }

    @Ears("US-47.3")
    @Test fun `external audio is added and selected (US-47_3)`() {
        AudioController(mpv).addFile("fdclose://42", "dub.m4a")
        assertEquals("audio-add fdclose://42 select dub.m4a", mpv.calls.last())
    }

    @Ears("US-48.1")
    @Test fun `audio delay snaps to 10 ms and clamps to 10 s (US-48_1)`() {
        val c = AudioController(mpv)
        c.setDelayMs(123)
        c.setDelayMs(-12_000)
        assertEquals(listOf("set audio-delay=0.12", "set audio-delay=-10.0"), mpv.calls)
    }

    @Ears("US-49.2")
    @Test fun `boost is 100 to 200 percent and feeds the input gain (US-49_2)`() {
        effects.setBoost(3f)
        assertEquals(2f, sink.applied.last().boost)
        assertTrue(abs(sink.applied.last().inputGainDb - 6.0206f) < 0.001f)
        effects.setBoost(0.5f)
        assertEquals(1f, sink.applied.last().boost)
        assertFalse(sink.applied.last().processing)
    }

    @Ears("US-49.2")
    @Test fun `worst-case gain stays under 0 dBFS after the limiter (US-49_2)`() {
        // Full-scale input, preamp +12, boost +6, a band +12: output peak is threshold + over/ratio.
        val peak = Eq.LIMITER_THRESHOLD_DB + Eq.WORST_CASE_OVER_DB / Eq.LIMITER_RATIO
        assertTrue("peak $peak dBFS", peak < 0f)
    }

    @Ears("US-49.3")
    @Test fun `reset puts boost back to 100 percent (US-49_3)`() {
        effects.setBoost(1.8f)
        effects.resetBoost()
        assertEquals(1f, effects.state.value.boost)
    }

    @Ears("US-50.2")
    @Test fun `ten bands, moving one sends only that band (US-50_2)`() {
        effects.setEq(true)
        val before = sink.applied.size
        effects.setBand(3, 20f)
        assertEquals(listOf(3 to 12f), sink.bands)
        assertEquals(before, sink.applied.size)
        assertEquals(12f, effects.state.value.bandsDb[3])
        assertEquals(10, Eq.CUTOFFS_HZ.size)
    }

    @Test fun `band moves while eq is off are kept but not sent`() {
        effects.setBand(0, 5f)
        assertTrue(sink.bands.isEmpty())
        effects.setEq(true)
        assertEquals(5f, sink.applied.last().bandGainDb(0))
    }

    @Test fun `eq cutoffs rise and sit between centres`() {
        for (i in 0 until Eq.BANDS - 1) {
            assertTrue(Eq.CUTOFFS_HZ[i] > Eq.CENTRES_HZ[i] && Eq.CUTOFFS_HZ[i] < Eq.CENTRES_HZ[i + 1])
        }
    }

    @Ears("US-51.1")
    @Test fun `mono uses a labelled filter, stereo removes it (US-51_1)`() {
        effects.setStereo(StereoMode.Mono)
        effects.setStereo(StereoMode.Stereo)
        assertEquals(
            listOf(
                "af remove @subsaudio-stereo",
                "af add @subsaudio-stereo:format=channels=mono",
                "af remove @subsaudio-stereo",
            ),
            mpv.calls,
        )
    }

    @Ears("US-51.1")
    @Test fun `reverse swaps the channels through pan, stereo removes it (US-51_1)`() {
        effects.setStereo(StereoMode.Reverse)
        effects.setStereo(StereoMode.Stereo)
        assertEquals(
            listOf(
                "af remove @subsaudio-stereo",
                "af add @subsaudio-stereo:lavfi=[pan=stereo|c0=c1|c1=c0]",
                "af remove @subsaudio-stereo",
            ),
            mpv.calls,
        )
    }

    @Ears("US-50.1")
    @Test fun `nine named presets and a custom one, ten bands each (US-50_1)`() {
        val named = EqPreset.entries.filter { it != EqPreset.Custom }
        assertEquals(9, named.size)
        named.forEach { p -> assertEquals(p.name, Eq.BANDS, p.bandsDb!!.size); assertTrue(p.bandsDb.all { it in Eq.MIN_DB..Eq.MAX_DB }) }
        assertEquals(EqPreset.Flat, EffectsState().preset)
        assertFalse(EffectsState().eqOn)
    }

    @Ears("US-50.1")
    @Test fun `a preset sets every band, a moved band makes the curve custom (US-50_1)`() {
        effects.setEq(true)
        effects.setPreset(EqPreset.Rock)
        assertEquals(EqPreset.Rock.bandsDb, sink.applied.last().bandsDb)
        effects.setBand(4, 7f)
        val s = effects.state.value
        assertEquals(EqPreset.Custom, s.preset)
        assertEquals(7f, s.customDb[4])
        effects.setPreset(EqPreset.Flat)
        assertEquals(EqPreset.Flat.bandsDb, effects.state.value.bandsDb)
        effects.setPreset(EqPreset.Custom)
        assertEquals(s.customDb, effects.state.value.bandsDb)
    }

    @Ears("US-50.3")
    @Test fun `saved equalizer comes back as it was (US-50_3)`() {
        val custom = List(Eq.BANDS) { i -> i - 4f }
        effects.restore(EqSaved(on = true, preampDb = -3f, preset = EqPreset.Custom, customDb = custom))
        val s = effects.state.value
        assertTrue(s.eqOn)
        assertEquals(-3f, s.preampDb)
        assertEquals(custom, s.bandsDb)
        assertEquals(EqSaved(true, -3f, EqPreset.Custom, custom), s.saved)
        assertEquals(custom, sink.applied.last().bandsDb)
        effects.restore(EqSaved(on = false, preset = EqPreset.Jazz))
        assertEquals(EqPreset.Jazz.bandsDb, effects.state.value.bandsDb)
        assertFalse(sink.applied.last().processing)
    }

    @Ears("US-51.2")
    @Test fun `surround goes to stereo unless a multichannel output is attached (US-51_2)`() {
        assertEquals("stereo", OutputChannels.mpvChannels(emptyList()))
        assertEquals("stereo", OutputChannels.mpvChannels(listOf(1, 2)))
        assertEquals("auto-safe", OutputChannels.mpvChannels(listOf(2, 6, 8)))
    }
}
