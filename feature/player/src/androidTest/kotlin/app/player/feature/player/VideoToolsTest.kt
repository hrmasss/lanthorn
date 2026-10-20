package app.player.feature.player

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import app.player.core.engine.PlayerEngine
import app.player.core.engine.PlayerSessions
import app.player.core.engine.PropertyType
import app.player.feature.player.tools.Deinterlace
import app.player.feature.player.tools.FitMode
import app.player.feature.player.tools.PictureAdjust
import app.player.feature.player.tools.VideoTools
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs

/**
 * P1.5 wiring on a real engine (emulator). Picture checks compare mpv "window" screenshots, which
 * are rendered through the video output with its shaders, of a paused frame.
 */
class VideoToolsTest {
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var scenario: ActivityScenario<PlayerActivity>
    private lateinit var engine: PlayerEngine
    private lateinit var tools: VideoTools

    private fun open(asset: String) {
        scenario = ActivityScenario.launch(PlayerActivity.intent(context, copy(asset)))
        waitUntil(10_000) { (PlayerSessions.current.value?.state?.value?.positionMs ?: 0) > 300 }
        engine = PlayerSessions.current.value!!
        tools = VideoTools(engine, context)
    }

    @After fun close() = scenario.close()

    @Test fun fitModes_US53_1() = runBlocking<Unit> {
        open("clip.mp4")
        val margins = FitMode.entries.associateWith { mode ->
            tools.setFit(mode)
            SystemClock.sleep(300)
            margins()
        }
        log("fit margins (top, bottom, left, right): $margins")
        assertTrue("fit leaves bars", margins.getValue(FitMode.Fit).sum() > 0)
        assertEquals("stretch fills", 0, margins.getValue(FitMode.Stretch).sum())
        // Crop overflows the screen: margins go negative (the picture is larger than the view).
        assertTrue("crop fills", margins.getValue(FitMode.Crop).all { it <= 0 })
        assertTrue("100% is smaller than fit", margins.getValue(FitMode.Original).sum() > margins.getValue(FitMode.Fit).sum())
    }

    @Test fun aspectOverride_US54_1() = runBlocking<Unit> {
        open("clip.mp4")
        val before = engine.get("video-out-params/aspect", PropertyType.Real)!!
        val t0 = SystemClock.elapsedRealtime()
        tools.setAspect(4.0 / 3)
        waitUntil(1_000) { runBlocking { engine.get("video-out-params/aspect", PropertyType.Real) }?.let { abs(it - 4.0 / 3) < 0.01 } == true }
        val ms = SystemClock.elapsedRealtime() - t0
        log("aspect 16:9 ($before) -> 4:3 applied in $ms ms")
        tools.setAspect(null)
        waitUntil(1_000) { runBlocking { engine.get("video-out-params/aspect", PropertyType.Real) }?.let { abs(it - before) < 0.01 } == true }
    }

    @Test fun zoomAndPan_US55() = runBlocking<Unit> {
        open("clip.mp4")
        tools.setZoom(4.0)
        SystemClock.sleep(300)
        val zoom = engine.get("video-zoom", PropertyType.Real)!!
        tools.setZoom(0.25)
        SystemClock.sleep(300)
        val small = engine.get("video-zoom", PropertyType.Real)!!
        tools.setZoom(2.0)
        tools.setPan(0.2, -0.1)
        SystemClock.sleep(300)
        val px = engine.get("video-pan-x", PropertyType.Real)!!
        val py = engine.get("video-pan-y", PropertyType.Real)!!
        log("zoom 400% -> video-zoom $zoom, 25% -> $small, pan -> $px, $py")
        assertEquals(2.0, zoom, 1e-6)
        assertEquals(-2.0, small, 1e-6)
        assertEquals(0.2, px, 1e-6)
        assertEquals(-0.1, py, 1e-6)
    }

    @Test fun rotationMetadata_US56_4() = runBlocking<Unit> {
        open("rotated.mp4")
        waitUntil(3_000) { tools.rotation.value != null }
        // The file is 320x180 with a 90 degree display-rotation flag (ffmpeg counts it counter-
        // clockwise, mpv reports it clockwise as 270). Proof that it is honoured: the picture mpv
        // draws is taller than wide (screen rectangle from osd-dimensions).
        val m = listOf("w", "h", "ml", "mr", "mt", "mb").associateWith { engine.get("osd-dimensions/$it", PropertyType.Int64)!! }
        val shownW = m.getValue("w") - m.getValue("ml") - m.getValue("mr")
        val shownH = m.getValue("h") - m.getValue("mt") - m.getValue("mb")
        log("rotated.mp4: rotate ${tools.rotation.value}, picture drawn ${shownW}x$shownH")
        grab("rotated")
        assertTrue("rotation flag read", tools.rotation.value == 90L || tools.rotation.value == 270L)
        assertTrue("portrait after rotation: ${shownW}x$shownH", shownH > shownW)
    }

    @Test fun mirrorFlipNight_US57() = runBlocking<Unit> {
        open("clip.mp4")
        engine.pause()
        SystemClock.sleep(500)
        val plain = grab("plain")
        tools.setMirror(true)
        val mirrored = grab("mirror")
        tools.setMirror(false)
        tools.setVerticalFlip(true)
        val flipped = grab("vflip")
        tools.setVerticalFlip(false)
        tools.setNightMode(true)
        val night = grab("night")
        tools.setNightMode(false)
        val back = grab("after")

        val w = plain.width
        val h = plain.height
        val mirrorErr = sampleError(plain, mirrored) { x, y -> (w - 1 - x) to y }
        val flipErr = sampleError(plain, flipped) { x, y -> x to (h - 1 - y) }
        val (rRatio, bRatio) = nightRatios(plain, night)
        log("mirror error $mirrorErr, vflip error $flipErr, night ratios red %.2f blue %.2f".format(rRatio, bRatio))
        assertTrue("mirror error $mirrorErr", mirrorErr < 12)
        assertTrue("flip error $flipErr", flipErr < 12)
        assertTrue("red $rRatio", rRatio in 0.65..0.75)
        assertTrue("blue $bRatio", bRatio in 0.50..0.62)
        assertTrue("off restores the frame", plain.sameAs(back))
    }

    @Test fun pictureAdjustZeroIsIdentical_US57_3() = runBlocking<Unit> {
        open("clip.mp4")
        engine.pause()
        SystemClock.sleep(500)
        val plain = grab("adjust-plain")
        tools.setPicture(PictureAdjust(brightness = 40, contrast = -20, saturation = 60, gamma = 10))
        val adjusted = grab("adjust-on")
        tools.setPicture(PictureAdjust())
        val reset = grab("adjust-reset")
        val changed = maxDiff(plain, adjusted)
        val back = maxDiff(plain, reset)
        log("picture adjust: max channel difference with adjust $changed, after reset to zeros $back")
        assertTrue("adjust changed the frame", changed > 10)
        assertEquals("zeros give back the frame", 0, back)
    }

    @Test fun deinterlaceModes_US38() = runBlocking<Unit> {
        open("interlaced.mp4")
        val modes = Deinterlace.entries.associateWith { mode ->
            tools.setDeinterlace(mode)
            SystemClock.sleep(500)
            engine.get("deinterlace", PropertyType.Text)
        }
        val active = engine.get("deinterlace-active", PropertyType.Flag)
        log("deinterlace read back: $modes, deinterlace-active $active, error ${engine.state.value.error}")
        assertEquals("no", modes[Deinterlace.Off])
    }

    /**
     * The video area of the screen as composited (the libmpv build cannot write image files, so
     * mpv's own screenshots are not available). The picture rectangle comes from mpv's
     * `osd-dimensions` margins, offset by where the video surface sits on screen.
     */
    private suspend fun grab(name: String): Bitmap {
        SystemClock.sleep(500)
        val ml = engine.get("osd-dimensions/ml", PropertyType.Int64)!!.toInt()
        val mt = engine.get("osd-dimensions/mt", PropertyType.Int64)!!.toInt()
        val mr = engine.get("osd-dimensions/mr", PropertyType.Int64)!!.toInt()
        val mb = engine.get("osd-dimensions/mb", PropertyType.Int64)!!.toInt()
        val w = engine.get("osd-dimensions/w", PropertyType.Int64)!!.toInt()
        val h = engine.get("osd-dimensions/h", PropertyType.Int64)!!.toInt()
        val at = IntArray(2)
        scenario.onActivity { a -> findSurface(a.window.decorView)?.getLocationOnScreen(at) }
        val screen = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            .copy(Bitmap.Config.ARGB_8888, false)
        // Keep 2 px off each edge so scaling seams do not count.
        val crop = Bitmap.createBitmap(screen, at[0] + ml + 2, at[1] + mt + 2, w - ml - mr - 4, h - mt - mb - 4)
        val file = File(File(context.getExternalFilesDir(null), "evidence").apply { mkdirs() }, "p15-$name.png")
        file.outputStream().use { crop.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return crop
    }

    private fun findSurface(v: android.view.View): android.view.View? = when {
        v is android.view.SurfaceView -> v
        v is android.view.ViewGroup -> (0 until v.childCount).firstNotNullOfOrNull { findSurface(v.getChildAt(it)) }
        else -> null
    }

    /** Largest per-channel difference over every pixel. */
    private fun maxDiff(a: Bitmap, b: Bitmap): Int {
        var worst = 0
        for (y in 0 until minOf(a.height, b.height)) for (x in 0 until minOf(a.width, b.width)) {
            val p = a.getPixel(x, y)
            val q = b.getPixel(x, y)
            worst = maxOf(worst, abs(Color.red(p) - Color.red(q)), abs(Color.green(p) - Color.green(q)), abs(Color.blue(p) - Color.blue(q)))
        }
        return worst
    }

    private fun sampleError(a: Bitmap, b: Bitmap, map: (Int, Int) -> Pair<Int, Int>): Int {
        var worst = 0
        for (i in 1..9) for (j in 1..9) {
            val x = a.width * i / 10
            val y = a.height * j / 10
            val (bx, by) = map(x, y)
            val p = a.getPixel(x, y)
            val q = b.getPixel(bx, by)
            worst = maxOf(worst, abs(Color.red(p) - Color.red(q)), abs(Color.green(p) - Color.green(q)), abs(Color.blue(p) - Color.blue(q)))
        }
        return worst
    }

    /** Mean of night/plain for red and blue over bright pixels. */
    private fun nightRatios(plain: Bitmap, night: Bitmap): Pair<Double, Double> {
        var r = 0.0; var rn = 0; var b = 0.0; var bn = 0
        for (y in 0 until plain.height step 7) for (x in 0 until plain.width step 7) {
            val p = plain.getPixel(x, y)
            val q = night.getPixel(x, y)
            if (Color.red(p) > 120) { r += Color.red(q).toDouble() / Color.red(p); rn++ }
            if (Color.blue(p) > 120) { b += Color.blue(q).toDouble() / Color.blue(p); bn++ }
        }
        return r / rn to b / bn
    }

    private suspend fun margins(): List<Int> = listOf("mt", "mb", "ml", "mr").map {
        (engine.get("osd-dimensions/$it", PropertyType.Int64) ?: 0L).toInt()
    }

    private fun waitUntil(ms: Long, check: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + ms
        while (!check()) {
            if (SystemClock.elapsedRealtime() > end) throw AssertionError("timed out after $ms ms")
            SystemClock.sleep(20)
        }
    }

    private fun copy(asset: String): Uri {
        // Always fresh: a run that crashed mid-copy once left a truncated file here.
        val file = File(context.cacheDir, asset)
        InstrumentationRegistry.getInstrumentation().context.assets.open(asset).use { input ->
            file.outputStream().use { input.copyTo(it) }
        }
        return Uri.fromFile(file)
    }

    private fun log(line: String) {
        android.util.Log.i("VideoToolsTest", line)
        val dir = File(context.getExternalFilesDir(null), "evidence").apply { mkdirs() }
        File(dir, "numbers.txt").appendText(line + "\n")
    }
}
