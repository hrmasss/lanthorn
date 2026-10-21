package app.player.feature.player.settings

/** What a single tap on the video does (US-21.3). */
enum class TouchAction { ToggleControls, Pause, ShowThenPause, ShowAndPause }

/** Gesture switches and amounts. Defaults are US-21.2's and the stories' stated values. */
data class GesturePrefs(
    val seek: Boolean = true,
    val brightness: Boolean = true,
    val volume: Boolean = true,
    val doubleTapSkip: Boolean = true,
    val doubleTapPause: Boolean = true,
    val doubleTapZoom: Boolean = false,
    val longPressFast: Boolean = true,
    val twoFingerSpeed: Boolean = false,
    val pinchZoom: Boolean = true,
    val pan: Boolean = false,
    val subtitleSwipe: Boolean = true,
    val subtitleDrag: Boolean = true,
    val subtitlePinch: Boolean = true,
    val touchAction: TouchAction = TouchAction.ToggleControls,
    /** US-17.1: seconds a swipe across the full width moves, 10 to 600. */
    val seekFullWidthSec: Int = 90,
    /** US-19.1: double-tap step, 5 to 60 s. Also the step buttons' and keys' step. */
    val skipStepSec: Int = 10,
    /** US-20.1: 1.5x to 4.0x. */
    val fastSpeed: Double = 2.0,
    /** US-55.2. */
    val quickZoom: Boolean = true,
    /** US-18.3. */
    val rememberBrightness: Boolean = false,
    /** Last window brightness, used only with [rememberBrightness]. -1 = system. */
    val lastBrightness: Float = -1f,
)
