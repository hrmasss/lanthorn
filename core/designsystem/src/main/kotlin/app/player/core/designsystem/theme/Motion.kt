package app.player.core.designsystem.theme

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.tween

/** Player overlay timings (docs/design.md, Motion). App screens use the theme's motion scheme. */
object PlayerMotion {
    const val OVERLAY_IN_MS = 120
    const val OVERLAY_OUT_MS = 200
    const val READOUT_LINGER_MS = 300
    const val READOUT_OUT_MS = 150

    fun <T> overlayIn(): FiniteAnimationSpec<T> = tween(OVERLAY_IN_MS)
    fun <T> overlayOut(): FiniteAnimationSpec<T> = tween(OVERLAY_OUT_MS)
    fun <T> readoutOut(): FiniteAnimationSpec<T> = tween(READOUT_OUT_MS, delayMillis = READOUT_LINGER_MS)
}
