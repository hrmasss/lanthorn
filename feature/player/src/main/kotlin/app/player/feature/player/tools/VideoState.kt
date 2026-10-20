package app.player.feature.player.tools

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * The video adjustments of the open player, as Compose state, applied through [VideoTools]. One
 * place holds them so the fit button, the gestures, the video menu and the shortcuts agree.
 */
class VideoState(private val tools: VideoTools) {
    var fit by mutableStateOf(FitMode.Fit)
        private set
    /** Null = the file's own ratio. */
    var aspect by mutableStateOf<Double?>(null)
        private set
    var mirror by mutableStateOf(false)
        private set
    var verticalFlip by mutableStateOf(false)
        private set
    var night by mutableStateOf(false)
        private set
    var picture by mutableStateOf(PictureAdjust())
        private set
    var deinterlace by mutableStateOf(Deinterlace.Auto)
        private set

    fun setFit(mode: FitMode) { fit = mode; tools.setFit(mode) }
    fun setAspect(ratio: Double?) { aspect = ratio; tools.setAspect(ratio) }
    fun setMirror(on: Boolean) { mirror = on; tools.setMirror(on) }
    fun setVerticalFlip(on: Boolean) { verticalFlip = on; tools.setVerticalFlip(on) }
    fun setNight(on: Boolean) { night = on; tools.setNightMode(on) }
    fun setPicture(adjust: PictureAdjust) { picture = adjust; tools.setPicture(adjust) }
    fun setDeinterlace(mode: Deinterlace) { deinterlace = mode; tools.setDeinterlace(mode) }
}
