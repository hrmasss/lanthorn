package app.player.feature.subtitles

import kotlin.math.roundToInt

enum class SubAlign(val mpv: String) { Left("left"), Centre("center"), Right("right") }

/**
 * How text subtitles look and sit (US-42, US-43). ASS tracks keep their own look unless
 * "override styles" is on (US-42.4). Defaults are US-42.2: white bold text with a shadow, no
 * outline, no box, centred, inside the picture.
 */
data class SubtitleStyle(
    val font: String = DEFAULT_FONT,
    /** mpv's `sub-font-size`: pixels at a 720-line script height. */
    val size: Int = 46,
    val color: Long = 0xFFFFFFFF,
    val bold: Boolean = true,
    val outline: Boolean = false,
    val outlineColor: Long = 0xFF000000,
    /** Outline thickness in script pixels. */
    val outlineSize: Float = 3f,
    val shadow: Boolean = true,
    val box: Boolean = false,
    val boxColor: Long = 0xFF000000,
    /** 0 to 1. */
    val boxOpacity: Float = 0.5f,
    val align: SubAlign = SubAlign.Centre,
    /** 0 to 50 percent of the picture height (US-43.1). */
    val bottomMarginPercent: Float = 4f,
    val fitToVideo: Boolean = true,
    /** 0.5 to 3.0; set by the pinch gesture (US-45.3). */
    val scale: Float = 1f,
) {
    /** The mpv properties for this style, in the order they are set. */
    fun toMpv(): List<Pair<String, String>> = buildList {
        add("sub-font" to font)
        add("sub-font-size" to size.toString())
        add("sub-scale" to scale.coerceIn(MIN_SCALE, MAX_SCALE).toString())
        add("sub-color" to hex(color))
        add("sub-bold" to yesNo(bold))
        when {
            box -> {
                add("sub-border-style" to "background-box")
                add("sub-back-color" to hex(withAlpha(boxColor, boxOpacity)))
                // In box mode the border size is the box padding.
                add("sub-border-size" to "6")
            }
            else -> {
                add("sub-border-style" to "outline-and-shadow")
                add("sub-border-size" to if (outline) outlineSize.toString() else "0")
            }
        }
        add("sub-border-color" to hex(outlineColor))
        add("sub-shadow-offset" to if (shadow) "2" else "0")
        add("sub-shadow-color" to "#80000000")
        add("sub-align-x" to align.mpv)
        add("sub-justify" to align.mpv)
        add("sub-margin-y" to (bottomMarginPercent.coerceIn(0f, MAX_MARGIN_PERCENT) / 100f * SCRIPT_HEIGHT).roundToInt().toString())
        // "Fit to video": keep text subtitles inside the picture, not in the black bars (US-43.2).
        add("sub-use-margins" to yesNo(!fitToVideo))
    }

    companion object {
        const val DEFAULT_FONT = "sans-serif"
        const val MIN_SIZE = 16
        const val MAX_SIZE = 110
        const val MIN_SCALE = 0.5f
        const val MAX_SCALE = 3f
        const val MAX_MARGIN_PERCENT = 50f
        private const val SCRIPT_HEIGHT = 720

        /** mpv colours are #AARRGGBB. */
        fun hex(argb: Long): String = "#%08X".format(argb and 0xFFFFFFFFL)

        fun withAlpha(argb: Long, opacity: Float): Long =
            ((opacity.coerceIn(0f, 1f) * 255).roundToInt().toLong() shl 24) or (argb and 0xFFFFFF)

        private fun yesNo(b: Boolean) = if (b) "yes" else "no"
    }
}
