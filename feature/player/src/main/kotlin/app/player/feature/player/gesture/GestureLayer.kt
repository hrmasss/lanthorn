package app.player.feature.player.gesture

import android.os.SystemClock
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.unit.dp
import app.player.core.playerapi.SubtitleGestures
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.hypot

/** US-17.4: swipes that start this close to a side edge belong to the system back gesture. */
private val EDGE = 24.dp

/** US-20.1. */
private const val LONG_PRESS_MS = 500L

/**
 * Turns touches on the video into [GestureController] calls. One finger: tap, double tap, seek,
 * brightness, volume, long press. Two fingers: pinch, pan, speed. Nothing is drawn here.
 * A gesture whose first touch lands on shown subtitle text goes to [subtitles] instead (US-45):
 * sideways steps a line, vertical moves the text, pinch scales it. Taps and long press stay ours.
 */
@Composable
fun Modifier.playerGestures(controller: GestureController, subtitles: () -> SubtitleGestures? = { null }): Modifier {
    val config = LocalViewConfiguration.current
    val scope = rememberCoroutineScope()
    return pointerInput(controller) {
        val slop = config.touchSlop
        val edge = EDGE.toPx()
        val doubleTapMs = config.doubleTapTimeoutMillis
        var pendingTap: Job? = null
        var lastTapAt = Long.MIN_VALUE

        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val w = size.width.toFloat()
            val h = size.height.toFloat()
            val start = down.position
            val startedAt = SystemClock.uptimeMillis()
            val atEdge = start.x < edge || start.x > w - edge
            var drag: DragKind? = null
            var ignored = false
            var fast = false
            var moved = false
            var two: TwoFingerKind? = null
            var twoStartSpan = 0f
            var twoStartCentroid = Offset.Zero
            var twoSeen = false
            val sub = if (atEdge) null else subtitles()?.takeIf { it.hit(start.x, start.y, w.toInt(), h.toInt()) }
            var subDrag: Boolean? = null // true: vertical move, false: sideways step (already sent)
            var subLastY = start.y
            var subLastSpan = 0f

            while (true) {
                val waitMs = if (!moved && !fast && !twoSeen && controller.prefs.longPressFast) {
                    (LONG_PRESS_MS - (SystemClock.uptimeMillis() - startedAt)).coerceAtLeast(0)
                } else Long.MAX_VALUE
                val event: PointerEvent? = if (waitMs == Long.MAX_VALUE) awaitPointerEvent() else withTimeoutOrNull(waitMs) { awaitPointerEvent() }
                if (event == null) {
                    // Held still past the long-press time (US-20.1).
                    if (!atEdge) {
                        fast = true
                        controller.fastStart()
                    }
                    continue
                }
                val pressed = event.changes.filter { it.pressed }
                if (pressed.isEmpty()) break

                if (sub != null && pressed.size >= 2 && !fast && subDrag == null) {
                    // US-45.3: pinch on subtitle text scales it.
                    val span = span(pressed)
                    if (twoSeen && subLastSpan > 0f) sub.scale(span / subLastSpan)
                    twoSeen = true
                    subLastSpan = span
                    event.changes.forEach(PointerInputChange::consume)
                    continue
                }
                if (pressed.size >= 2 && !fast && drag == null) {
                    // Two fingers.
                    val span = span(pressed)
                    val centroid = centroid(pressed)
                    if (!twoSeen) {
                        twoSeen = true
                        twoStartSpan = span
                        twoStartCentroid = centroid
                    }
                    val d = centroid - twoStartCentroid
                    if (two == null && !ignored) {
                        two = controller.twoFingerKind(span - twoStartSpan, d.y, slop)
                        if (two != null) controller.twoFingerStart(two!!) else if (abs(span - twoStartSpan) > slop || d.getDistance() > slop) ignored = true
                    }
                    two?.let { controller.twoFinger(it, if (twoStartSpan > 0) span / twoStartSpan else 1f, d.x, d.y, w, h) }
                    event.changes.forEach(PointerInputChange::consume)
                    continue
                }
                if (twoSeen) {
                    // Back to one finger after a two-finger gesture: nothing more until all lift.
                    event.changes.forEach(PointerInputChange::consume)
                    continue
                }

                val change = pressed.first()
                val d = change.position - start
                if (!moved && d.getDistance() > slop) moved = true
                if (fast || ignored) continue
                if (sub != null && moved) {
                    if (subDrag == null) {
                        subDrag = abs(d.y) >= abs(d.x)
                        // US-45.1: one sideways swipe, one line; right is forward, as for seeking.
                        if (subDrag == false) sub.step(if (d.x > 0) 1 else -1)
                    }
                    if (subDrag == true) {
                        // US-45.2: follow the finger.
                        sub.drag(change.position.y - subLastY, h.toInt(), end = false)
                        subLastY = change.position.y
                    }
                    change.consume()
                    continue
                }
                if (moved && drag == null) {
                    if (atEdge) {
                        ignored = true
                        continue
                    }
                    drag = controller.dragKind(abs(d.x) > abs(d.y), start.x, w)
                    if (drag == null) {
                        ignored = true
                        continue
                    }
                    controller.dragStart(drag!!)
                }
                drag?.let {
                    controller.drag(it, d.x, d.y, w, h)
                    change.consume()
                }
            }

            when {
                subDrag == true -> sub?.drag(0f, h.toInt(), end = true)
                sub != null && (subDrag == false || twoSeen) -> Unit
                fast -> controller.fastEnd()
                drag != null -> controller.dragEnd(drag!!)
                two != null -> controller.twoFingerEnd()
                !moved && !twoSeen && !ignored -> {
                    // A tap.
                    val now = SystemClock.uptimeMillis()
                    val zone = GestureController.zone(start.x, w)
                    val second = pendingTap?.isActive == true && now - lastTapAt <= doubleTapMs
                    when {
                        second -> {
                            pendingTap?.cancel()
                            if (!controller.doubleTap(zone, now)) controller.singleTap()
                        }
                        controller.continuesSkip(zone, now) -> controller.continueSkip(zone, now)
                        controller.waitsForDoubleTap -> pendingTap = scope.launch {
                            delay(doubleTapMs)
                            controller.singleTap()
                        }
                        else -> controller.singleTap()
                    }
                    lastTapAt = now
                }
            }
        }
    }
}

private fun span(pressed: List<PointerInputChange>): Float {
    val a = pressed[0].position
    val b = pressed[1].position
    return hypot(a.x - b.x, a.y - b.y)
}

private fun centroid(pressed: List<PointerInputChange>): Offset =
    (pressed[0].position + pressed[1].position) / 2f
