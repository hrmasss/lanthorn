package app.player.core.playerapi

/**
 * Gestures that start on subtitle text (US-45). subs-audio implements it; player-ui's gesture layer
 * asks [hit] for a gesture's first touch and, if it hits, sends that gesture here instead of to
 * seek, volume, brightness or zoom.
 */
interface SubtitleGestures {
    /** Whether a touch at ([x], [y]), in px of a player view of [width] x [height], starts on shown subtitle text. */
    fun hit(x: Float, y: Float, width: Int, height: Int): Boolean

    /** Sideways swipe: -1 previous line's start, +1 next (US-45.1). */
    fun step(direction: Int)

    /** Vertical drag by [dy] px since the last call, of a [height] px view, up negative; [end] on release (US-45.2). */
    fun drag(dy: Float, height: Int, end: Boolean)

    /** Pinch by [factor] since the last call; the size stays within 50 to 300 % (US-45.3). */
    fun scale(factor: Float)
}
