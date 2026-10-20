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

    /** Needs FFmpeg's `pan`, which our libmpv build lacks (questions.md). Shown, not selectable. */
    Reverse,
    ;

    val available: Boolean get() = this != Reverse
}

/** Everything the viewer can change about the sound past track and delay (P1.4). */
internal data class EffectsState(
    val eqOn: Boolean = false,
    val bandsDb: List<Float> = List(Eq.BANDS) { 0f },
    val preampDb: Float = 0f,
    /** Player gain, 1.0 to 2.0 (US-49). Never saved: it resets with the player (US-49.3). */
    val boost: Float = 1f,
    val stereo: StereoMode = StereoMode.Stereo,
) {
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
            StereoMode.Stereo, StereoMode.Reverse -> Unit
            // mpv's own format filter downmixes with the FFmpeg resampler: centre and surround kept.
            StereoMode.Mono -> mpv.command("af", "add", "@$LABEL:format=channels=mono")
        }
    }

    companion object {
        const val LABEL = "subsaudio-stereo"
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

    /** US-50.2: one band moves alone, without rebuilding the effect. */
    fun setBand(index: Int, db: Float) {
        val clamped = db.coerceIn(Eq.MIN_DB, Eq.MAX_DB)
        val next = _state.updateAndGet { it.copy(bandsDb = it.bandsDb.toMutableList().also { b -> b[index] = clamped }) }
        if (next.eqOn) sink.setBand(index, clamped)
    }

    fun setStereo(mode: StereoMode) {
        if (!mode.available) return
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
