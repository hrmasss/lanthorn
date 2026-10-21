package app.player.feature.subtitles

import app.player.core.engine.PropertyType
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject

/** Which subtitle gestures are on (Subtitle settings, all default on). */
data class SubtitleGestureSwitches(val swipe: Boolean = true, val drag: Boolean = true, val pinch: Boolean = true) {
    val any: Boolean get() = swipe || drag || pinch
}

/**
 * Gestures that start on subtitle text (US-45). player-ui's gesture layer asks [hit] for the first
 * touch and sends the rest of that gesture here.
 */
internal class SubtitleTouch(
    private val mpv: Mpv,
    private val styles: () -> SubtitleStyle,
    private val updateStyle: ((SubtitleStyle) -> SubtitleStyle) -> Unit,
    private val switches: () -> SubtitleGestureSwitches,
    private val minBottomMarginPercent: Float = 0f,
) {
    private val text: StateFlow<String?> = mpv.observe("sub-text", PropertyType.Text)
    private val osd: StateFlow<String?> = mpv.observe("osd-dimensions", PropertyType.Text)

    fun hit(x: Float, y: Float, width: Int, height: Int): Boolean {
        if (!switches().any) return false
        val shown = text.value?.takeIf { it.isNotBlank() } ?: return false
        val dims = Osd.parse(osd.value) ?: Osd(width, height)
        return textBox(shown, styles(), dims, minBottomMarginPercent, width, height).contains(x, y)
    }

    /** US-45.1: the previous (-1) or next (+1) line's start. */
    fun step(direction: Int) {
        if (!switches().swipe || direction == 0) return
        mpv.command("sub-seek", if (direction < 0) "-1" else "1")
    }

    /** US-45.2: [dy] px since the last call, up negative; the style saves the new margin itself. */
    fun drag(dy: Float, height: Int, @Suppress("UNUSED_PARAMETER") end: Boolean) {
        if (!switches().drag || height <= 0) return
        val delta = -dy / height * 100f
        updateStyle { it.copy(bottomMarginPercent = (it.bottomMarginPercent + delta).coerceIn(0f, SubtitleStyle.MAX_MARGIN_PERCENT)) }
    }

    /** US-45.3: pinch by [factor] since the last call, within 50 to 300 %. */
    fun scale(factor: Float) {
        if (!switches().pinch || factor <= 0f) return
        updateStyle { it.copy(scale = (it.scale * factor).coerceIn(SubtitleStyle.MIN_SCALE, SubtitleStyle.MAX_SCALE)) }
    }

    /** mpv's `osd-dimensions`: the window and the black bars around the picture, in px. */
    data class Osd(val w: Int, val h: Int, val mt: Int = 0, val mb: Int = 0, val ml: Int = 0, val mr: Int = 0) {
        companion object {
            fun parse(json: String?): Osd? = json?.let {
                runCatching {
                    val o = JSONObject(it)
                    Osd(o.getInt("w"), o.getInt("h"), o.optInt("mt"), o.optInt("mb"), o.optInt("ml"), o.optInt("mr"))
                }.getOrNull()
            }?.takeIf { it.w > 0 && it.h > 0 }
        }
    }

    data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float) {
        fun contains(x: Float, y: Float) = x in left..right && y in top..bottom
    }

    companion object {
        private const val SCRIPT_HEIGHT = 720f
        private const val SCRIPT_MARGIN_X = 25f // mpv's sub-margin-x default, script px
        private const val LINE_HEIGHT = 1.25f
        private const val CHAR_WIDTH = 0.6f

        /**
         * Where [text] sits on a [viewW] x [viewH] view, with half a line of slack around it for a
         * finger. mpv does not report the drawn box, so this follows the style: text subtitles sit
         * at the bottom margin, inside the picture when "fit to video" is on, else the window.
         */
        fun textBox(text: String, style: SubtitleStyle, osd: Osd, minBottomMarginPercent: Float, viewW: Int, viewH: Int): Box {
            val areaTop = if (style.fitToVideo) osd.mt else 0
            val areaBottom = osd.h - if (style.fitToVideo) osd.mb else 0
            val areaLeft = if (style.fitToVideo) osd.ml else 0
            val areaRight = osd.w - if (style.fitToVideo) osd.mr else 0
            val px = (areaBottom - areaTop) / SCRIPT_HEIGHT
            val font = style.size * style.scale.coerceIn(SubtitleStyle.MIN_SCALE, SubtitleStyle.MAX_SCALE) * px
            val lines = text.lines().filter { it.isNotBlank() }.ifEmpty { listOf(text) }
            val margin = maxOf(style.bottomMarginPercent.coerceIn(0f, SubtitleStyle.MAX_MARGIN_PERCENT), minBottomMarginPercent) / 100f * SCRIPT_HEIGHT * px
            val bottom = areaBottom - margin
            val top = bottom - lines.size * font * LINE_HEIGHT
            val width = minOf(lines.maxOf { it.length } * font * CHAR_WIDTH, (areaRight - areaLeft).toFloat())
            val marginX = SCRIPT_MARGIN_X * px
            val left = when (style.align) {
                SubAlign.Left -> areaLeft + marginX
                SubAlign.Right -> areaRight - marginX - width
                SubAlign.Centre -> (areaLeft + areaRight) / 2f - width / 2f
            }
            val slack = font / 2f
            val sx = viewW.toFloat() / osd.w
            val sy = viewH.toFloat() / osd.h
            return Box((left - slack) * sx, (top - slack) * sy, (left + width + slack) * sx, (bottom + slack) * sy)
        }
    }
}
