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
 */
@Composable
fun Modifier.playerGestures(controller: GestureController): Modifier {
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
