package app.player.feature.audio

import android.media.audiofx.DynamicsProcessing
import android.util.Log

/**
 * EQ, preamp, boost and limiter on Android's DynamicsProcessing, attached to the audio session
 * mpv's AudioTrack plays on. Our libmpv has no FFmpeg audio filters (questions.md), and this
 * runs in the platform audio path at no cost to mpv. Created on first use, disabled when every
 * setting is neutral. Main thread.
 */
internal class PlatformEffects(private val sessionId: Int) : EffectsSink {
    private var dp: DynamicsProcessing? = null
    private var failed = false

    override fun apply(state: EffectsState) {
        if (!state.processing) {
            dp?.enabled = false
            return
        }
        val effect = effect() ?: return
        val channels = effect.channelCount
        for (ch in 0 until channels) effect.setInputGainbyChannel(ch, state.inputGainDb)
        for (i in 0 until Eq.BANDS) effect.setPreEqBandAllChannelsTo(i, band(i, state.bandGainDb(i)))
        effect.setLimiterAllChannelsTo(limiter())
        effect.enabled = true
    }

    override fun setBand(index: Int, gainDb: Float) {
        dp?.setPreEqBandAllChannelsTo(index, band(index, gainDb))
    }

    override fun release() {
        dp?.release()
        dp = null
    }

    private fun effect(): DynamicsProcessing? {
        dp?.let { return it }
        if (failed) return null
        return try {
            val config = DynamicsProcessing.Config.Builder(
                DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
                CHANNELS,
                true, Eq.BANDS, // pre-EQ: the graphic EQ
                false, 0, // no multiband compressor
                false, 0, // no post-EQ
                true, // limiter
            ).setPreferredFrameDuration(FRAME_MS).build()
            DynamicsProcessing(0, sessionId, config).also { dp = it }
        } catch (e: RuntimeException) {
            // No effect engine on this device: the player still plays, without EQ and boost.
            Log.w(TAG, "DynamicsProcessing unavailable", e)
            failed = true
            null
        }
    }

    companion object {
        private const val TAG = "PlatformEffects"
        private const val CHANNELS = 2
        private const val FRAME_MS = 10f

        fun band(i: Int, gainDb: Float) = DynamicsProcessing.EqBand(true, Eq.CUTOFFS_HZ[i], gainDb)

        /** Fast attack, brick-wall ratio, ceiling just under 0 dBFS (US-49.2). */
        fun limiter() = DynamicsProcessing.Limiter(true, true, 0, 1f, 60f, 10f, Eq.LIMITER_THRESHOLD_DB, 0f)
    }
}
