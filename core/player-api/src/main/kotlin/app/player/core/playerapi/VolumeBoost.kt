package app.player.core.playerapi

import kotlinx.coroutines.flow.StateFlow

/**
 * Volume past the system maximum (US-49.1). subs-audio implements it; player-ui's volume gesture
 * calls it: at system maximum a further swipe up raises [gain], a swipe down lowers it to 1.0
 * before the system volume.
 */
interface VolumeBoost {
    /** The "volume boost" setting (default on). */
    val enabled: StateFlow<Boolean>

    /** 1.0 to 2.0. The level bar shows 100 to 200 % in its second colour. */
    val gain: StateFlow<Float>

    /** Clamped to 1.0 to 2.0; 1.0 turns the boost off. */
    fun setGain(gain: Float)
}
