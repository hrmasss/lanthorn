package app.player.feature.player.gesture

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.player.feature.player.settings.GesturePrefs
import app.player.feature.player.settings.TouchAction
import app.player.feature.player.tools.PlaybackTools
import app.player.feature.player.tools.VideoTools
import kotlin.math.abs
import kotlin.math.roundToInt

/** What the gesture layer may change. Implemented over the engine, the window and AudioManager. */
interface GestureEffects {
    val positionMs: Long
    val durationMs: Long
    val paused: Boolean
    val speed: Double
    val controlsShown: Boolean

    fun seek(ms: Long, exact: Boolean)
    fun togglePause()
    fun pause()
    fun setSpeed(speed: Double)
    fun setControls(shown: Boolean)

    /** Window brightness 0..1. */
    var brightness: Float

    /** Media volume 0..1 of the device range. */
    var volume: Float

    /** Media volume steps, so a swipe lands on whole steps. */
    val volumeSteps: Int

    /** Whether the volume swipe continues past the system maximum (US-49.1). */
    val boostOn: Boolean get() = false

    /** Player gain past the system maximum, 1.0 to 2.0 (US-49.1). */
    var boost: Float
        get() = 1f
        set(_) {}

    /** Zoom relative to fit (1.0) and pan in fractions of the scaled picture. */
    fun setZoom(scale: Double)
    fun setPan(x: Double, y: Double)
    fun toggleFitCrop()
}

/** What the centre of the screen shows while a gesture runs. */
sealed interface Readout {
    data class Seek(val targetMs: Long, val deltaMs: Long) : Readout
    data class Level(val kind: LevelKind, val percent: Int) : Readout
    data class Speed(val speed: Double, val top: Boolean) : Readout
    data class Zoom(val percent: Int) : Readout
    data class Skip(val forward: Boolean, val seconds: Int) : Readout
    data class PlayPause(val playing: Boolean) : Readout
}

enum class LevelKind { Brightness, Volume }

enum class Zone { Left, Middle, Right }

/**
 * The gesture model (US-17 to US-21, US-55, US-76): turns recognised gestures into player effects
 * and readouts. The pointer plumbing lives in [GestureLayer]; this class holds the rules, so they
 * are unit tested without touch injection.
 */
class GestureController(private val fx: GestureEffects) {
    var prefs: GesturePrefs = GesturePrefs()

    /** The readout to draw, null when none. */
    var readout by mutableStateOf<Readout?>(null)
        private set

    // Seek
    private var seekFrom = 0L
    private var seekTarget = 0L

    // Brightness and volume
    private var levelFrom = 0f

    // Fast play
    private var speedBeforeFast = 1.0

    // Two fingers
    private var zoom = 1.0
    private var zoomFrom = 1.0
    private var panX = 0.0
    private var panY = 0.0
    private var panFromX = 0.0
    private var panFromY = 0.0
    private var speedFrom = 1.0

    // Repeated skip taps (US-19.3)
    private var lastSkipAt = Long.MIN_VALUE
    private var lastSkipZone: Zone? = null
    private var skipCount = 0

    /** The picture's fit size relative to the view, per axis, for pan (US-55.3). Set by the screen. */
    var fitWidth = 1.0
    var fitHeight = 1.0

    // ---- taps ----

    /** Whether a single tap must wait to see if a second one follows. */
    val waitsForDoubleTap: Boolean
        get() = prefs.doubleTapSkip || prefs.doubleTapPause || prefs.doubleTapZoom

    /** US-16.2, US-21.3. */
    fun singleTap() {
        when (prefs.touchAction) {
            TouchAction.ToggleControls -> fx.setControls(!fx.controlsShown)
            TouchAction.Pause -> fx.togglePause()
            TouchAction.ShowThenPause -> if (fx.controlsShown) fx.togglePause() else fx.setControls(true)
            TouchAction.ShowAndPause -> {
                fx.setControls(true)
                if (!fx.paused) fx.pause()
            }
        }
    }

    /**
     * A tap that is either the second of a double tap, or a further tap continuing a skip run
     * (US-19.3). Returns true if it was used.
     */
    fun doubleTap(zone: Zone, nowMs: Long): Boolean {
        if (prefs.doubleTapZoom) {
            fx.toggleFitCrop()
            return true
        }
        return when (zone) {
            Zone.Middle -> if (prefs.doubleTapPause) {
                fx.togglePause()
                readout = Readout.PlayPause(playing = fx.paused)
                true
            } else false
            Zone.Left, Zone.Right -> if (prefs.doubleTapSkip) {
                skipCount = 1
                lastSkipZone = zone
                lastSkipAt = nowMs
                skip(zone)
                true
            } else false
        }
    }

    /** True when a single tap at [zone] continues a skip run instead of being a single tap. */
    fun continuesSkip(zone: Zone, nowMs: Long): Boolean =
        prefs.doubleTapSkip && zone == lastSkipZone && nowMs - lastSkipAt <= SKIP_RUN_MS

    fun continueSkip(zone: Zone, nowMs: Long) {
        skipCount++
        lastSkipAt = nowMs
        skip(zone)
    }

    private fun skip(zone: Zone) {
        val forward = zone == Zone.Right
        val step = prefs.skipStepSec * 1000L
        val target = (fx.positionMs + if (forward) step else -step).coerceIn(0, fx.durationMs.coerceAtLeast(0))
        fx.seek(target, exact = true)
        readout = Readout.Skip(forward, skipCount * prefs.skipStepSec)
    }

    // ---- one finger drags ----

    /** Which drag a first move starts, or null if that gesture is off (US-21.4). */
    fun dragKind(horizontal: Boolean, startX: Float, width: Float): DragKind? = when {
        horizontal -> if (prefs.seek) DragKind.Seek else null
        startX < width / 2 -> if (prefs.brightness) DragKind.Brightness else null
        else -> if (prefs.volume) DragKind.Volume else null
    }

    fun dragStart(kind: DragKind) {
        when (kind) {
            DragKind.Seek -> {
                seekFrom = fx.positionMs
                seekTarget = seekFrom
            }
            DragKind.Brightness -> levelFrom = fx.brightness
            // One scale for both: 0 to 1 is the system volume, 1 to 2 the boost (US-49.1).
            DragKind.Volume -> levelFrom = fx.volume + if (fx.boostOn) fx.boost - 1f else 0f
        }
    }

    /** [dx] and [dy] are the total move since the drag started, in px; [w] and [h] the view size. */
    fun drag(kind: DragKind, dx: Float, dy: Float, w: Float, h: Float) {
        when (kind) {
            DragKind.Seek -> {
                seekTarget = (seekFrom + seekDelta(dx, w, prefs.seekFullWidthSec)).coerceIn(0, fx.durationMs.coerceAtLeast(0))
                // Keyframe seeks while the finger moves keep the picture following (US-17.3).
                fx.seek(seekTarget, exact = false)
                readout = Readout.Seek(seekTarget, seekTarget - seekFrom)
            }
            DragKind.Brightness -> {
                val v = level(levelFrom, dy, h)
                fx.brightness = v
                readout = Readout.Level(LevelKind.Brightness, (v * 100).roundToInt())
            }
            DragKind.Volume -> {
                val c = level(levelFrom, dy, h, max = if (fx.boostOn) 2f else 1f)
                val v = snapTo(c.coerceAtMost(1f), fx.volumeSteps)
                fx.volume = v
                // Down lowers the boost to 1.0 before the system volume moves.
                if (fx.boostOn) fx.boost = c.coerceAtLeast(1f)
                readout = Readout.Level(LevelKind.Volume, ((if (c > 1f) c else v) * 100).roundToInt())
            }
        }
    }

    fun dragEnd(kind: DragKind) {
        if (kind == DragKind.Seek) fx.seek(seekTarget, exact = true)
        readout = null
    }

    // ---- long press ----

    fun fastStart() {
        if (!prefs.longPressFast) return
        speedBeforeFast = fx.speed
        fx.setSpeed(prefs.fastSpeed)
        readout = Readout.Speed(prefs.fastSpeed, top = true)
    }

    fun fastEnd() {
        fx.setSpeed(speedBeforeFast)
        readout = null
    }

    // ---- two fingers ----

    /** The mode a two-finger move starts, or null if it is off. */
    fun twoFingerKind(spanChange: Float, dy: Float, slop: Float): TwoFingerKind? = when {
        abs(spanChange) > slop -> if (prefs.pinchZoom) TwoFingerKind.Pinch else null
        abs(dy) > slop && prefs.twoFingerSpeed -> TwoFingerKind.Speed
        prefs.pan && zoom > 1.0 -> TwoFingerKind.Pan
        else -> null
    }

    fun twoFingerStart(kind: TwoFingerKind) {
        zoomFrom = zoom
        panFromX = panX
        panFromY = panY
        speedFrom = fx.speed
    }

    /** [scale] is span now over span at start; [dx], [dy] the centroid move in px. */
    fun twoFinger(kind: TwoFingerKind, scale: Float, dx: Float, dy: Float, w: Float, h: Float) {
        when (kind) {
            TwoFingerKind.Pinch -> {
                zoom = snapZoom(VideoTools.clampZoom(zoomFrom * scale), prefs.quickZoom, nativeScale)
                fx.setZoom(zoom)
                clampPan()
                readout = Readout.Zoom((zoom * 100).roundToInt())
            }
            TwoFingerKind.Pan -> {
                // Pan is in fractions of the scaled picture; a finger move of the full view moves
                // the picture by view/scaled.
                panX = panFromX + dx / w / (zoom * fitWidth)
                panY = panFromY + dy / h / (zoom * fitHeight)
                clampPan()
            }
            TwoFingerKind.Speed -> {
                // US-76.1: 0.1x per 10% of the height, up is faster.
                val s = PlaybackTools.snapSpeed(speedFrom - dy / h)
                fx.setSpeed(s)
                readout = Readout.Speed(s, top = false)
            }
        }
    }

    fun twoFingerEnd() {
        readout = null
    }

    /** US-76.3: double tap on the speed readout. */
    fun resetSpeed() {
        fx.setSpeed(1.0)
        readout = Readout.Speed(1.0, top = false)
    }

    /** Picture size at 100% (one video pixel per screen pixel) relative to fit. */
    var nativeScale = 1.0

    private fun clampPan() {
        val maxX = VideoTools.maxPan(zoom * fitWidth, 1.0)
        val maxY = VideoTools.maxPan(zoom * fitHeight, 1.0)
        panX = panX.coerceIn(-maxX, maxX)
        panY = panY.coerceIn(-maxY, maxY)
        fx.setPan(panX, panY)
    }

    /** Called when a readout should go: after a tap readout lingered, or when the file changes. */
    fun clearReadout() {
        readout = null
    }

    companion object {
        /** US-19.3. */
        const val SKIP_RUN_MS = 600L

        /** US-17.1: linear, full width = [fullWidthSec]. */
        fun seekDelta(dx: Float, width: Float, fullWidthSec: Int): Long =
            if (width <= 0f) 0 else (dx / width * fullWidthSec * 1000).toLong()

        /** US-18: full height = 0 to 100%, up raises, 1% steps. [max] 2 lets volume run into the boost (US-49.1). */
        fun level(from: Float, dy: Float, height: Float, max: Float = 1f): Float {
            if (height <= 0f) return from
            val v = (from - dy / height).coerceIn(0f, max)
            return (v * 100).roundToInt() / 100f
        }

        /** Rounds to the nearest of [steps] device steps. */
        fun snapTo(value: Float, steps: Int): Float =
            if (steps <= 0) value else (value * steps).roundToInt().toFloat() / steps

        /** US-55.2: snaps to fit (1.0) and to 100% when within 5% of either. */
        fun snapZoom(scale: Double, quick: Boolean, native: Double): Double {
            if (!quick) return scale
            return listOf(1.0, native).firstOrNull { abs(scale - it) <= it * 0.05 } ?: scale
        }

        fun zone(x: Float, width: Float): Zone = when {
            x < width / 3 -> Zone.Left
            x > width * 2 / 3 -> Zone.Right
            else -> Zone.Middle
        }
    }
}

enum class DragKind { Seek, Brightness, Volume }

enum class TwoFingerKind { Pinch, Pan, Speed }
