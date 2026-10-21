package app.player.feature.audio

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlin.math.log10
import kotlin.math.sqrt

enum class StereoMode {
    Stereo,
    Mono,

    /** Left and right swapped, through FFmpeg's `pan` (in libmpv since P1.33). */
    Reverse,
}

/** EQ presets (US-50.1): gains in dB for the ten bands, 31 Hz to 16 kHz. Custom holds the viewer's own. */
enum class EqPreset(internal val bandsDb: List<Float>?) {
    Flat(listOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)),
    Classical(listOf(0f, 0f, 0f, 0f, 0f, 0f, -3f, -4f, -4f, -6f)),
    Dance(listOf(6f, 5f, 2f, 0f, 0f, -2f, -2f, -2f, 0f, 1f)),
    Folk(listOf(-1f, 0f, 1f, 2f, 3f, 2f, 1f, 0f, 0f, -1f)),
    HeavyMetal(listOf(4f, 3f, 0f, -2f, 0f, 2f, 4f, 5f, 4f, 2f)),
    HipHop(listOf(5f, 4f, 2f, 3f, -1f, -1f, 1f, -1f, 2f, 3f)),
    Jazz(listOf(3f, 2f, 1f, 2f, -2f, -2f, 0f, 1f, 3f, 4f)),
    Pop(listOf(-1f, 1f, 3f, 4f, 3f, 0f, -1f, -1f, -1f, -1f)),
    Rock(listOf(5f, 3f, -2f, -4f, -1f, 2f, 4f, 5f, 5f, 5f)),
    Custom(null),
}

/** What the equalizer keeps across files and restarts (US-50.3). */
internal data class EqSaved(
    val on: Boolean = false,
    val preampDb: Float = 0f,
    val preset: EqPreset = EqPreset.Flat,
    val customDb: List<Float> = List(Eq.BANDS) { 0f },
)

/** Everything the viewer can change about the sound past track and delay (P1.4). */
internal data class EffectsState(
    val eqOn: Boolean = false,
    val bandsDb: List<Float> = List(Eq.BANDS) { 0f },
    val preampDb: Float = 0f,
    /** Player gain, 1.0 to 2.0 (US-49). Never saved: it resets with the player (US-49.3). */
    val boost: Float = 1f,
    val stereo: StereoMode = StereoMode.Stereo,
    val preset: EqPreset = EqPreset.Flat,
    /** The Custom preset's bands: the last hand-moved curve. */
    val customDb: List<Float> = List(Eq.BANDS) { 0f },
) {
    val saved: EqSaved get() = EqSaved(eqOn, preampDb, preset, customDb)

    /** Whether the platform effect needs to run at all. Off costs nothing. */
    val processing: Boolean get() = eqOn || boost > 1f

    /** Gain in front of the EQ and limiter: preamp plus boost. */
    val inputGainDb: Float get() = (if (eqOn) preampDb else 0f) + 20f * log10(boost)

    fun bandGainDb(i: Int): Float = if (eqOn) bandsDb[i] else 0f
}

/** The 10-band graphic EQ layout (ISO octave centres). */
internal object Eq {
    const val BANDS = 10
    const val MIN_DB = -12f
    const val MAX_DB = 12f
    val CENTRES_HZ = floatArrayOf(31f, 62f, 125f, 250f, 500f, 1000f, 2000f, 4000f, 8000f, 16000f)

    /**
     * Platform EQ bands are given by their upper edge. Each edge sits halfway (geometric mean)
     * between two centres; the top band runs to 20 kHz.
     */
    val CUTOFFS_HZ = FloatArray(BANDS) { i -> if (i == BANDS - 1) 20000f else sqrt(CENTRES_HZ[i] * CENTRES_HZ[i + 1]) }

    /** Limiter ceiling. Just under 0 dBFS so boosted audio never clips (US-49.2). */
    const val LIMITER_THRESHOLD_DB = -1f

    /** Steep enough that the worst case (all gains at maximum) stays under 0 dBFS. */
    const val LIMITER_RATIO = 50f

    /** Most a full-scale input can be pushed over the limiter threshold: preamp, boost, one band. */
    val WORST_CASE_OVER_DB: Float get() = MAX_DB + 20f * kotlin.math.log10(AudioEffects.MAX_BOOST) + MAX_DB - LIMITER_THRESHOLD_DB
}

/** Where effect settings go. The real one is [PlatformEffects]; tests use a fake. */
internal interface EffectsSink {
    fun apply(state: EffectsState)
    fun setBand(index: Int, gainDb: Float)
    fun release()
}

/** Where the stereo mode goes: an mpv labelled audio filter, so no other feature's `af` is touched. */
internal class StereoFilter(private val mpv: Mpv) {
    fun apply(mode: StereoMode) {
        mpv.command("af", "remove", "@$LABEL")
        when (mode) {
            StereoMode.Stereo -> Unit
            // mpv's own format filter downmixes with the FFmpeg resampler: centre and surround kept.
            StereoMode.Mono -> mpv.command("af", "add", "@$LABEL:format=channels=mono")
            StereoMode.Reverse -> mpv.command("af", "add", "@$LABEL:$REVERSE")
        }
    }

    companion object {
        const val LABEL = "subsaudio-stereo"
        const val REVERSE = "lavfi=[pan=stereo|c0=c1|c1=c0]"
    }
}

/** Holds the effects state and pushes each change to the sink and mpv (P1.4). */
internal class AudioEffects(internal val sink: EffectsSink, private val stereo: StereoFilter) {
    private val _state = MutableStateFlow(EffectsState())
    val state: StateFlow<EffectsState> = _state.asStateFlow()

    fun setEq(on: Boolean) = change { it.copy(eqOn = on) }
    fun setPreamp(db: Float) = change { it.copy(preampDb = db.coerceIn(Eq.MIN_DB, Eq.MAX_DB)) }
    fun setBoost(gain: Float) = change { it.copy(boost = gain.coerceIn(1f, MAX_BOOST)) }

    fun setBands(bandsDb: List<Float>) {
        require(bandsDb.size == Eq.BANDS)
        change { it.copy(bandsDb = bandsDb.map { db -> db.coerceIn(Eq.MIN_DB, Eq.MAX_DB) }) }
    }

    /** US-50.1: a preset sets all ten bands; Custom brings back the viewer's own curve. */
    fun setPreset(preset: EqPreset) = change { it.copy(preset = preset, bandsDb = preset.bandsDb ?: it.customDb) }

    /** US-50.2: one band moves alone, without rebuilding the effect. The curve becomes Custom. */
    fun setBand(index: Int, db: Float) {
        val clamped = db.coerceIn(Eq.MIN_DB, Eq.MAX_DB)
        val next = _state.updateAndGet {
            val bands = it.bandsDb.toMutableList().also { b -> b[index] = clamped }
            it.copy(bandsDb = bands, preset = EqPreset.Custom, customDb = bands)
        }
        if (next.eqOn) sink.setBand(index, clamped)
    }

    /** US-50.3: the equalizer as it was saved. */
    fun restore(saved: EqSaved) = change {
        val custom = saved.customDb.takeIf { c -> c.size == Eq.BANDS } ?: List(Eq.BANDS) { 0f }
        it.copy(
            eqOn = saved.on,
            preampDb = saved.preampDb.coerceIn(Eq.MIN_DB, Eq.MAX_DB),
            preset = saved.preset,
            customDb = custom,
            bandsDb = (saved.preset.bandsDb ?: custom).map { db -> db.coerceIn(Eq.MIN_DB, Eq.MAX_DB) },
        )
    }

    fun setStereo(mode: StereoMode) {
        _state.update { it.copy(stereo = mode) }
        stereo.apply(mode)
    }

    /** US-49.3: back to 100% when the player closes. */
    fun resetBoost() = setBoost(1f)

    fun release() = sink.release()

    private fun change(block: (EffectsState) -> EffectsState) {
        sink.apply(_state.updateAndGet(block))
    }

    companion object {
        const val MAX_BOOST = 2f
    }
}
