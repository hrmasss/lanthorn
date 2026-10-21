package app.player.feature.player.tools

import androidx.compose.runtime.mutableStateOf

/**
 * The video adjustments of the open player, as Compose state, applied through [VideoTools]. One
 * place holds them so the fit button, the gestures, the video menu and the shortcuts agree.
 */
class VideoState(private val tools: VideoTools) {
    // Backing state is private: Kotlin's setter for a `var fit` would clash with setFit below.
    private val fitState = mutableStateOf(FitMode.Fit)
    private val aspectState = mutableStateOf<Double?>(null)
    private val mirrorState = mutableStateOf(false)
    private val verticalFlipState = mutableStateOf(false)
    private val nightState = mutableStateOf(false)
    private val pictureState = mutableStateOf(PictureAdjust())
    private val deinterlaceState = mutableStateOf(Deinterlace.Auto)

    val fit: FitMode get() = fitState.value
    /** Null = the file's own ratio. */
    val aspect: Double? get() = aspectState.value
    val mirror: Boolean get() = mirrorState.value
    val verticalFlip: Boolean get() = verticalFlipState.value
    val night: Boolean get() = nightState.value
    val picture: PictureAdjust get() = pictureState.value
    val deinterlace: Deinterlace get() = deinterlaceState.value

    /** Called when the viewer picks a fit, not when a stored one comes back (US-53.2). */
    var onFitChosen: (FitMode) -> Unit = {}

    fun setFit(mode: FitMode) { fitState.value = mode; tools.setFit(mode) }
    fun chooseFit(mode: FitMode) { setFit(mode); onFitChosen(mode) }
    fun setAspect(ratio: Double?) { aspectState.value = ratio; tools.setAspect(ratio) }
    fun setMirror(on: Boolean) { mirrorState.value = on; tools.setMirror(on) }
    fun setVerticalFlip(on: Boolean) { verticalFlipState.value = on; tools.setVerticalFlip(on) }
    fun setNight(on: Boolean) { nightState.value = on; tools.setNightMode(on) }
    fun setPicture(adjust: PictureAdjust) { pictureState.value = adjust; tools.setPicture(adjust) }
    fun setDeinterlace(mode: Deinterlace) { deinterlaceState.value = mode; tools.setDeinterlace(mode) }
}
