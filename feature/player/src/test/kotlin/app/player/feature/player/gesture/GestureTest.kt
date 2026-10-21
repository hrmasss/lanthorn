package app.player.feature.player.gesture

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.click
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.pinch
import app.player.feature.player.settings.GesturePrefs
import app.player.feature.player.settings.TouchAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Gesture rules and touch recognition. The view is 800 x 400 px. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w800dp-h400dp-mdpi")
class GestureTest {
    @get:Rule val rule = createComposeRule()

    private class Fake : GestureEffects {
        override var positionMs = 60_000L
        override var durationMs = 600_000L
        override var paused = false
        var spd = 1.0
        override val speed get() = spd
        override var controlsShown = false
        val seeks = mutableListOf<Pair<Long, Boolean>>()
        var zoomed = 1.0
        var pan = 0.0 to 0.0
        var fitCrop = 0
        override fun seek(ms: Long, exact: Boolean) { seeks += ms to exact; if (exact) positionMs = ms }
        override fun togglePause() { paused = !paused }
        override fun pause() { paused = true }
        override fun setSpeed(speed: Double) { spd = speed }
        override fun setControls(shown: Boolean) { controlsShown = shown }
        override var brightness = 0.5f
        override var volume = 0.5f
        override val volumeSteps = 15
        override var boostOn = false
        override var boost = 1f
        override fun setZoom(scale: Double) { zoomed = scale }
        override fun setPan(x: Double, y: Double) { pan = x to y }
        override fun toggleFitCrop() { fitCrop++ }
    }

    private val fx = Fake()
    private val c = GestureController(fx)

    private fun show(prefs: GesturePrefs = GesturePrefs()) {
        c.prefs = prefs
        rule.setContent { Box(Modifier.fillMaxSize().testTag("v").playerGestures(c)) }
    }

    // ---- rules ----

    @Test fun `US-17_1 full width swipe seeks 90 s, linear`() {
        assertEquals(90_000L, GestureController.seekDelta(800f, 800f, 90))
        assertEquals(45_000L, GestureController.seekDelta(400f, 800f, 90))
        assertEquals(-9_000L, GestureController.seekDelta(-80f, 800f, 90))
        assertEquals(600_000L, GestureController.seekDelta(800f, 800f, 600))
    }

    @Test fun `US-18 full height is 0 to 100 percent in 1 percent steps`() {
        assertEquals(1f, GestureController.level(0f, -400f, 400f))
        assertEquals(0f, GestureController.level(1f, 400f, 400f))
        assertEquals(0.63f, GestureController.level(0.5f, -52.3f, 400f))
        assertEquals(1f, GestureController.level(0.9f, -400f, 400f))
    }

    @Test fun `US-19 zones are thirds`() {
        assertEquals(Zone.Left, GestureController.zone(100f, 900f))
        assertEquals(Zone.Middle, GestureController.zone(450f, 900f))
        assertEquals(Zone.Right, GestureController.zone(800f, 900f))
    }

    @Test fun `US-19_1 and US-19_3 skip runs add a step per tap`() {
        c.prefs = GesturePrefs(skipStepSec = 10)
        assertTrue(c.doubleTap(Zone.Left, 1_000))
        assertEquals(50_000L, fx.positionMs)
        assertTrue(c.continuesSkip(Zone.Left, 1_500))
        c.continueSkip(Zone.Left, 1_500)
        assertEquals(40_000L, fx.positionMs)
        assertEquals(Readout.Skip(forward = false, seconds = 20), c.readout)
        assertFalse("too late", c.continuesSkip(Zone.Left, 2_200))
        assertFalse("other side", c.continuesSkip(Zone.Right, 1_600))
    }

    @Test fun `US-19_4 double tap middle toggles pause`() {
        c.prefs = GesturePrefs()
        c.doubleTap(Zone.Middle, 0)
        assertTrue(fx.paused)
    }

    @Test fun `US-20 fast play and back within the release`() {
        c.prefs = GesturePrefs(fastSpeed = 2.0)
        fx.spd = 1.25
        c.fastStart()
        assertEquals(2.0, fx.speed, 0.0)
        c.fastEnd()
        assertEquals(1.25, fx.speed, 0.0)
    }

    @Test fun `US-21_2 defaults`() {
        val g = GesturePrefs()
        assertTrue(g.seek && g.brightness && g.volume && g.doubleTapSkip && g.doubleTapPause && g.longPressFast && g.pinchZoom)
        assertTrue(g.subtitleSwipe && g.subtitleDrag && g.subtitlePinch)
        assertFalse(g.doubleTapZoom || g.twoFingerSpeed || g.pan)
        assertEquals(TouchAction.ToggleControls, g.touchAction)
    }

    @Test fun `US-21_3 touch actions`() {
        c.prefs = GesturePrefs(touchAction = TouchAction.Pause)
        c.singleTap()
        assertTrue(fx.paused)
        c.prefs = GesturePrefs(touchAction = TouchAction.ShowThenPause)
        fx.paused = false
        c.singleTap()
        assertTrue(fx.controlsShown)
        assertFalse(fx.paused)
        c.singleTap()
        assertTrue(fx.paused)
        c.prefs = GesturePrefs(touchAction = TouchAction.ShowAndPause)
        fx.paused = false
        fx.controlsShown = false
        c.singleTap()
        assertTrue(fx.controlsShown && fx.paused)
    }

    @Test fun `US-21_4 a gesture that is off is not started`() {
        c.prefs = GesturePrefs(seek = false, brightness = false)
        assertNull(c.dragKind(horizontal = true, startX = 400f, width = 800f))
        assertNull(c.dragKind(horizontal = false, startX = 100f, width = 800f))
        assertEquals(DragKind.Volume, c.dragKind(horizontal = false, startX = 700f, width = 800f))
    }

    @Test fun `US-55 zoom clamps to 25 to 400 percent and snaps near fit and 100 percent`() {
        assertEquals(1.0, GestureController.snapZoom(1.04, quick = true, native = 2.0), 0.0)
        assertEquals(2.0, GestureController.snapZoom(1.95, quick = true, native = 2.0), 0.0)
        assertEquals(1.04, GestureController.snapZoom(1.04, quick = false, native = 2.0), 0.0)
        c.prefs = GesturePrefs()
        c.twoFingerStart(TwoFingerKind.Pinch)
        c.twoFinger(TwoFingerKind.Pinch, 10f, 0f, 0f, 800f, 400f)
        assertEquals(4.0, fx.zoomed, 0.0)
        c.twoFingerStart(TwoFingerKind.Pinch)
        c.twoFinger(TwoFingerKind.Pinch, 0.01f, 0f, 0f, 800f, 400f)
        assertEquals(0.25, fx.zoomed, 0.0)
    }

    @Test fun `US-55_3 pan stops at the picture edges`() {
        c.prefs = GesturePrefs(pan = true, quickZoom = false)
        c.twoFingerStart(TwoFingerKind.Pinch)
        c.twoFinger(TwoFingerKind.Pinch, 2f, 0f, 0f, 800f, 400f)
        c.twoFingerStart(TwoFingerKind.Pan)
        c.twoFinger(TwoFingerKind.Pan, 1f, 5_000f, 0f, 800f, 400f)
        // 2x zoom on a picture that fits: 0.5 overhang each side = 0.25 of the scaled picture.
        assertEquals(0.25, fx.pan.first, 1e-9)
    }

    @Test fun `US-76_1 two fingers change speed 0_1x per 10 percent of height`() {
        c.prefs = GesturePrefs(twoFingerSpeed = true)
        fx.spd = 1.0
        c.twoFingerStart(TwoFingerKind.Speed)
        c.twoFinger(TwoFingerKind.Speed, 1f, 0f, -80f, 800f, 400f)
        assertEquals(1.2, fx.speed, 1e-9)
        c.twoFinger(TwoFingerKind.Speed, 1f, 0f, 4_000f, 800f, 400f)
        assertEquals(0.25, fx.speed, 1e-9)
        c.resetSpeed()
        assertEquals(1.0, fx.speed, 0.0)
    }

    @Test fun `US-76_2 speed gesture is chosen over pan when span holds`() {
        c.prefs = GesturePrefs(twoFingerSpeed = true, pan = true)
        assertEquals(TwoFingerKind.Speed, c.twoFingerKind(spanChange = 2f, dy = 40f, slop = 8f))
        assertEquals(TwoFingerKind.Pinch, c.twoFingerKind(spanChange = 30f, dy = 40f, slop = 8f))
    }

    // ---- touch recognition ----

    @Test fun `US-17 horizontal swipe seeks, keyframe while moving, exact on release`() {
        show()
        rule.onNodeWithTag("v").performTouchInput { swipe(Offset(200f, 200f), Offset(600f, 200f), 300) }
        rule.waitForIdle()
        // 400 of 800 px = 45 s from 60 s.
        assertEquals(105_000L to true, fx.seeks.last())
        assertTrue(fx.seeks.dropLast(1).all { !it.second })
        assertNull(c.readout)
    }

    @Test fun `US-17_4 swipe from the edge is left to the system`() {
        show()
        rule.onNodeWithTag("v").performTouchInput { swipe(Offset(5f, 200f), Offset(500f, 200f), 300) }
        rule.waitForIdle()
        assertTrue(fx.seeks.isEmpty())
    }

    @Test fun `US-18_1 left half vertical swipe sets brightness`() {
        show()
        fx.brightness = 0.2f
        rule.onNodeWithTag("v").performTouchInput { swipe(Offset(200f, 300f), Offset(200f, 100f), 300) }
        rule.waitForIdle()
        assertEquals(0.7f, fx.brightness, 0.011f)
    }

    @Test fun `US-18_2 right half vertical swipe sets volume`() {
        show()
        fx.volume = 0.8f
        rule.onNodeWithTag("v").performTouchInput { swipe(Offset(600f, 100f), Offset(600f, 300f), 300) }
        rule.waitForIdle()
        assertEquals(0.333f, fx.volume, 0.04f)
    }

    @Test fun `US-49_1 swipe up past maximum volume raises the boost to 200 percent`() {
        fx.boostOn = true
        fx.volume = 0.8f
        c.dragStart(DragKind.Volume)
        c.drag(DragKind.Volume, 0f, -500f, 1000f, 1000f) // up half the height: 80 -> 130
        assertEquals(1f, fx.volume)
        assertEquals(1.3f, fx.boost, 0.001f)
        assertEquals(Readout.Level(LevelKind.Volume, 130), c.readout)
        c.drag(DragKind.Volume, 0f, -2000f, 1000f, 1000f) // far past: stops at 200
        assertEquals(2f, fx.boost)
        assertEquals(Readout.Level(LevelKind.Volume, 200), c.readout)
    }

    @Test fun `US-49_1 swipe down lowers the boost to 100 percent before the system volume`() {
        fx.boostOn = true
        fx.volume = 1f
        fx.boost = 1.2f
        c.dragStart(DragKind.Volume)
        c.drag(DragKind.Volume, 0f, 100f, 1000f, 1000f) // down 10%: 120 -> 110
        assertEquals(1f, fx.volume)
        assertEquals(1.1f, fx.boost, 0.001f)
        c.drag(DragKind.Volume, 0f, 400f, 1000f, 1000f) // down 40%: 120 -> 80
        assertEquals(1f, fx.boost)
        assertEquals(0.8f, fx.volume, 0.04f)
    }

    @Test fun `US-49_1 boost off stops the swipe at the system maximum`() {
        fx.volume = 0.8f
        c.dragStart(DragKind.Volume)
        c.drag(DragKind.Volume, 0f, -500f, 1000f, 1000f)
        assertEquals(1f, fx.volume)
        assertEquals(1f, fx.boost)
        assertEquals(Readout.Level(LevelKind.Volume, 100), c.readout)
    }

    @Test fun `US-21_4 switched-off swipe does nothing and shows nothing`() {
        show(GesturePrefs(volume = false))
        fx.volume = 0.8f
        rule.onNodeWithTag("v").performTouchInput { swipe(Offset(600f, 100f), Offset(600f, 300f), 300) }
        rule.waitForIdle()
        assertEquals(0.8f, fx.volume)
        assertNull(c.readout)
    }

    @Test fun `US-16_2 single tap toggles controls`() {
        show()
        rule.onNodeWithTag("v").performTouchInput { click(Offset(400f, 200f)) }
        rule.mainClock.advanceTimeBy(500)
        rule.waitForIdle()
        assertTrue(fx.controlsShown)
    }

    @Test fun `US-19_2 double tap on the right skips forward`() {
        show()
        rule.onNodeWithTag("v").performTouchInput { doubleClick(Offset(700f, 200f)) }
        rule.mainClock.advanceTimeBy(500)
        rule.waitForIdle()
        assertEquals(70_000L, fx.positionMs)
        assertFalse("a double tap is not also a single tap", fx.controlsShown)
    }

    @Test fun `US-20_1 hold plays fast until release`() {
        show()
        rule.onNodeWithTag("v").performTouchInput {
            down(Offset(400f, 200f))
            advanceEventTime(700)
            moveBy(Offset(1f, 0f))
        }
        rule.waitForIdle()
        assertEquals(2.0, fx.speed, 0.0)
        rule.onNodeWithTag("v").performTouchInput { up() }
        rule.waitForIdle()
        assertEquals(1.0, fx.speed, 0.0)
    }

    @Test fun `US-55_1 pinch zooms`() {
        show()
        rule.onNodeWithTag("v").performTouchInput {
            pinch(Offset(350f, 200f), Offset(150f, 200f), Offset(450f, 200f), Offset(650f, 200f), 300)
        }
        rule.waitForIdle()
        assertTrue("zoom ${fx.zoomed}", fx.zoomed > 4.0 - 1e-9 || fx.zoomed > 2.5)
    }

    @Test fun `US-55_1 pinch shows the zoom percentage while it runs`() {
        c.twoFingerStart(TwoFingerKind.Pinch)
        c.twoFinger(TwoFingerKind.Pinch, 2f, 0f, 0f, 800f, 400f)
        assertEquals(Readout.Zoom(200), c.readout)
        c.twoFinger(TwoFingerKind.Pinch, 10f, 0f, 0f, 800f, 400f)
        assertEquals(Readout.Zoom(400), c.readout)
        c.twoFinger(TwoFingerKind.Pinch, 0.1f, 0f, 0f, 800f, 400f)
        assertEquals(Readout.Zoom(25), c.readout)
    }

    @Test fun `US-55_4 double tap with double-tap zoom on toggles fit and crop`() {
        show(GesturePrefs(doubleTapZoom = true))
        rule.onNodeWithTag("v").performTouchInput { doubleClick(Offset(700f, 200f)) }
        rule.mainClock.advanceTimeBy(500)
        rule.waitForIdle()
        assertEquals(1, fx.fitCrop)
        assertEquals("no skip while double-tap zoom is on", 60_000L, fx.positionMs)
        rule.onNodeWithTag("v").performTouchInput { doubleClick(Offset(400f, 200f)) }
        rule.mainClock.advanceTimeBy(500)
        rule.waitForIdle()
        assertEquals(2, fx.fitCrop)
    }
}
