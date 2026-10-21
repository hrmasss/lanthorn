package app.player

import android.content.Intent
import android.net.Uri
import android.os.StrictMode
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.MotionEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import app.player.core.engine.PlayerSessions
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs

/**
 * P1.6 on the real seek bar: a finger drags with injected touch events (so the controls' auto-hide
 * cannot race it), the preview shows above the bar mid-drag (US-30.1), and on release the video
 * lands on the target (US-17.2). The engine logs every preview seek and frame ("preview frame at");
 * the finger's time on every move; `perf/preview-timeline.py` pairs them from logcat.
 */
@RunWith(AndroidJUnit4::class)
class SeekPreviewUiTest {
    private val instr = InstrumentationRegistry.getInstrumentation()
    private val context = instr.targetContext
    private val device = UiDevice.getInstance(instr)
    private val shots = File(context.getExternalFilesDir(null), "p16").apply { mkdirs() }

    @Before fun setUp() = StrictMode.setVmPolicy(StrictMode.VmPolicy.Builder().build())

    private fun touch(action: Int, x: Float, y: Float, down: Long) {
        val e = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
        instr.uiAutomation.injectInputEvent(e, true)
        e.recycle()
    }

    @Test fun dragShowsPreviewAndLandsOnRelease_US30_1_US17_2() {
        val f = File(context.filesDir, "longgop.mp4")
        if (!f.exists()) instr.context.assets.open("longgop.mp4").use { i -> f.outputStream().use { i.copyTo(it) } }
        context.startActivity(
            Intent(Intent.ACTION_VIEW).setDataAndType(Uri.fromFile(f), "video/mp4")
                .setClassName(context, "app.player.feature.player.PlayerActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
        )
        val engine = PlayerSessions.current
        val end = SystemClock.elapsedRealtime() + 10_000
        while (engine.value?.state?.value?.firstFrameMs == null && SystemClock.elapsedRealtime() < end) SystemClock.sleep(50)
        SystemClock.sleep(2000)
        val duration = engine.value!!.state.value.durationMs

        // Show the controls and find the seek bar.
        device.click(device.displayWidth / 2, device.displayHeight / 2)
        val bar = device.wait(Until.findObject(By.desc("Playback position")), 3000)
        assertNotNull("seek bar not shown", bar)
        val r = bar.visibleBounds
        val y = r.centerY().toFloat()
        val x0 = r.left + r.width() * 0.10f
        val x1 = r.left + r.width() * 0.70f

        val down = SystemClock.uptimeMillis()
        touch(MotionEvent.ACTION_DOWN, x0, y, down)
        Log.i("P1.6", "drag start")
        // About 3 s of dragging at 60 moves a second, a screenshot halfway, then a 1 s rest.
        val steps = 180
        for (i in 1..steps) {
            val x = x0 + (x1 - x0) * i / steps
            touch(MotionEvent.ACTION_MOVE, x, y, down)
            // The finger's time on the bar, so each preview frame can be paired with it.
            Log.i("P1.6", "finger at ${((x - r.left) / r.width() * duration).toLong()} ms")
            SystemClock.sleep(16)
            if (i == steps / 2) {
                device.takeScreenshot(File(shots, "preview-mid-drag.png"))
                Log.i("P1.6", "shot mid-drag")
            }
        }
        Log.i("P1.6", "drag end")
        SystemClock.sleep(1000)
        device.takeScreenshot(File(shots, "preview-finger-resting.png"))
        Log.i("P1.6", "shot finger resting")
        val target = ((x1 - r.left) / r.width() * duration).toLong()
        val t0 = SystemClock.elapsedRealtime()
        val restartsBefore = engine.value!!.state.value.restarts
        touch(MotionEvent.ACTION_UP, x1, y, down)
        while (engine.value!!.state.value.restarts == restartsBefore && SystemClock.elapsedRealtime() - t0 < 5000) SystemClock.sleep(10)
        val landed = SystemClock.elapsedRealtime() - t0
        SystemClock.sleep(300)
        val pos = engine.value!!.state.value.positionMs
        Log.i("P1.6", "release: target $target ms, video at $pos ms, frame ready $landed ms after release")
        device.takeScreenshot(File(shots, "after-release.png"))
        assertTrue("landed at $pos, target $target", abs(pos - target) < 1500)
        device.pressBack(); device.pressBack()
    }
}
