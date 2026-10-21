package app.player.feature.playback

/**
 * Kids lock (US-28.2): unlock after one tap in each corner zone, clockwise from top left, all
 * within 5 s. A zone is 20% of the screen's width by 20% of its height. Without Android.
 */
class KidsUnlock(private val windowMs: Long = WINDOW_MS) {
    private var next = 0
    private var startedAt = 0L

    /** Feeds one tap at fractions [x], [y] of the screen. True when this tap unlocks. */
    fun tap(x: Float, y: Float, now: Long): Boolean {
        val corner = cornerOf(x, y)
        if (next > 0 && now - startedAt > windowMs) next = 0
        when {
            corner == ORDER[next] -> {
                if (next == 0) startedAt = now
                next++
            }
            corner == ORDER[0] -> {
                next = 1
                startedAt = now
            }
            else -> next = 0
        }
        if (next == ORDER.size) {
            next = 0
            return true
        }
        return false
    }

    enum class Corner { TopLeft, TopRight, BottomRight, BottomLeft }

    companion object {
        const val WINDOW_MS = 5_000L
        const val ZONE = 0.2f
        private val ORDER = listOf(Corner.TopLeft, Corner.TopRight, Corner.BottomRight, Corner.BottomLeft)

        fun cornerOf(x: Float, y: Float): Corner? {
            val left = x <= ZONE
            val right = x >= 1 - ZONE
            val top = y <= ZONE
            val bottom = y >= 1 - ZONE
            return when {
                top && left -> Corner.TopLeft
                top && right -> Corner.TopRight
                bottom && right -> Corner.BottomRight
                bottom && left -> Corner.BottomLeft
                else -> null
            }
        }
    }
}
