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
        grabScreen("p15-rotated-full")
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
        SystemClock.sleep(1_000)
        val plain = grab("adjust-plain")
        // Baseline: two captures of the same paused frame, so capture noise is known.
        val baseline = maxDiff(plain, grab("adjust-plain-2"))
        tools.setPicture(PictureAdjust(brightness = 40, contrast = -20, saturation = 60, gamma = 10))
        val adjusted = grab("adjust-on")
        tools.setPicture(PictureAdjust())
        val reset = grab("adjust-reset")
        val changed = maxDiff(plain, adjusted)
        val back = maxDiff(plain, reset)
        log("picture adjust: capture baseline $baseline, max channel difference with adjust $changed, after reset to zeros $back")
        assertTrue("adjust changed the frame", changed > 10)
        assertEquals("zeros give back the frame", 0, back)
    }

    @Test fun deinterlaceModes_US38() = runBlocking<Unit> {
        open("interlaced.mp4")
        // Deinterlacing needs frames in memory: zero-copy HW cannot run it (US-35.4).
        engine.setDecoder(app.player.core.engine.DecoderMode.SW)
        waitUntil(5_000) { engine.state.value.activeDecoder == app.player.core.engine.ActiveDecoder.SW }
        tools.setDeinterlace(Deinterlace.Off)
        SystemClock.sleep(800)
        engine.pause()
        val off = grab("deint-off")
        val offActive = engine.get("deinterlace-active", PropertyType.Flag)
        engine.play()
        tools.setDeinterlace(Deinterlace.On)
        waitUntil(3_000) { runBlocking { engine.get("deinterlace-active", PropertyType.Flag) } == true }
        SystemClock.sleep(800)
        engine.pause()
        val on = grab("deint-on")
        engine.play()
        tools.setDeinterlace(Deinterlace.Auto)
        SystemClock.sleep(1_000)
        val autoInterlaced = engine.get("deinterlace-active", PropertyType.Flag)
        log("US-38 interlaced.mp4 on SW: off active=$offActive, on active=true, auto active=$autoInterlaced; " +
            "row-to-row difference (comb energy) off ${comb(off)} on ${comb(on)}")
        scenario.close()

        open("clip.mp4")
        engine.setDecoder(app.player.core.engine.DecoderMode.SW)
        waitUntil(5_000) { engine.state.value.activeDecoder == app.player.core.engine.ActiveDecoder.SW }
        tools.setDeinterlace(Deinterlace.Auto)
        SystemClock.sleep(1_000)
        val autoProgressive = engine.get("deinterlace-active", PropertyType.Flag)
        log("US-38.1 clip.mp4 (progressive) on SW, auto: deinterlace-active=$autoProgressive")
        assertEquals(false, offActive)
        assertEquals(true, autoInterlaced)
        assertEquals(false, autoProgressive)
        assertTrue("comb lines reduced: off ${comb(off)} on ${comb(on)}", comb(on) < comb(off))
    }

    @Test fun aspectRatiosDrawn_US54_1() = runBlocking<Unit> {
        open("clip.mp4")
        for ((name, ratio) in listOf("1:1" to 1.0, "21:9" to 21.0 / 9, "custom 1.85" to 1.85)) {
            tools.setAspect(ratio)
            SystemClock.sleep(400)
            val drawn = drawn()
            val got = drawn.first.toDouble() / drawn.second
            log("US-54.1 $name: picture drawn ${drawn.first}x${drawn.second}, ratio %.3f (asked %.3f)".format(got, ratio))
            assertEquals(ratio, got, ratio * 0.02)
            grabScreen("p15-aspect-${name.replace(' ', '-').replace(':', 'x')}")
        }
    }

    @Test fun zoomDrawn_US55_1() = runBlocking<Unit> {
        open("clip.mp4")
        val fit = drawn()
        tools.setZoom(4.0)
        SystemClock.sleep(400)
        val big = drawn()
        grabScreen("p15-zoom-400")
        tools.setZoom(0.25)
        SystemClock.sleep(400)
        val small = drawn()
        grabScreen("p15-zoom-25")
        log("US-55.1 picture width: fit ${fit.first}, 400 percent ${big.first} (x%.2f), 25 percent ${small.first} (x%.2f)"
            .format(big.first.toDouble() / fit.first, small.first.toDouble() / fit.first))
        assertEquals(4.0, big.first.toDouble() / fit.first, 0.05)
        assertEquals(0.25, small.first.toDouble() / fit.first, 0.02)
    }

    /** US-57.1, US-57.2 on the whole screen: a subtitle and the controls stay as they are. */
    @Test fun mirrorAndNightFullScreen_US57() = runBlocking<Unit> {
        open("clip.mp4")
        engine.command("sub-add", copyFile("sub.srt").path, "select")
        SystemClock.sleep(1_000)
        engine.pause()
        shell("input tap 540 600")
        SystemClock.sleep(800)
        val plain = grabScreen("p15-full-plain")
        tools.setMirror(true)
        val mirror = grabScreen("p15-full-mirror")
        tools.setMirror(false)
        tools.setNightMode(true)
        val night = grabScreen("p15-full-night")
        tools.setNightMode(false)
        // Subtitle: mpv draws text subtitles in the black band under the picture, near the bottom of
        // the screen. Its white pixels sit in the same place with mirror on (not mirrored).
        val sy = plain.height - 520
        val textSame = overlap(white(plain, 0, sy, plain.width, 420), white(mirror, 0, sy, plain.width, 420))
        // Controls: the bottom bar is pixel-identical under night mode (video only).
        val bar = maxDiffRegion(plain, night, 0, plain.height - 260, plain.width, 200)
        log("US-57 full screen: subtitle white-pixel overlap plain vs mirror %.2f; bottom bar max difference plain vs night $bar".format(textSame))
        assertTrue("subtitle not mirrored", textSame > 0.8)
        assertEquals(0, bar)
    }

    /** US-57.3 as an emulator regression number; the 100 ms claim is for the phone. */
    @Test fun pictureAdjustTiming_US57_3() = runBlocking<Unit> {
        open("clip.mp4")
        engine.pause()
        SystemClock.sleep(500)
        val before = grab("timing-before")
        val t0 = SystemClock.elapsedRealtime()
        tools.setPicture(PictureAdjust(brightness = 60))
        var seen = -1L
        while (SystemClock.elapsedRealtime() - t0 < 3_000) {
            val now = grabQuick()
            if (maxDiff(before, now) > 20) { seen = SystemClock.elapsedRealtime() - t0; break }
        }
        log("US-57.3 brightness +60 visible on screen after $seen ms (emulator, screen capture polling)")
        tools.setPicture(PictureAdjust())
        assertTrue(seen in 0..3_000)
    }

    /** Picture rectangle mpv draws, from osd-dimensions. */
    private suspend fun drawn(): Pair<Int, Int> {
        val m = listOf("w", "h", "ml", "mr", "mt", "mb").associateWith { engine.get("osd-dimensions/$it", PropertyType.Int64)!!.toInt() }
        return (m.getValue("w") - m.getValue("ml") - m.getValue("mr")) to (m.getValue("h") - m.getValue("mt") - m.getValue("mb"))
    }

    private fun white(b: Bitmap, x: Int, y: Int, w: Int, h: Int): Set<Int> {
        val out = HashSet<Int>()
        for (yy in y until (y + h).coerceAtMost(b.height)) for (xx in x until (x + w).coerceAtMost(b.width)) {
            val p = b.getPixel(xx, yy)
            if (Color.red(p) > 235 && Color.green(p) > 235 && Color.blue(p) > 235) out += yy * 10_000 + xx
        }
        return out
    }

    private fun overlap(a: Set<Int>, b: Set<Int>): Double =
        if (a.isEmpty() && b.isEmpty()) 0.0 else a.intersect(b).size.toDouble() / a.union(b).size

    private fun maxDiffRegion(a: Bitmap, b: Bitmap, x: Int, y: Int, w: Int, h: Int): Int =
        maxDiff(Bitmap.createBitmap(a, x, y, w, h), Bitmap.createBitmap(b, x, y, w, h))

    /** Mean absolute difference between neighbouring rows: comb lines raise it. */
    private fun comb(b: Bitmap): Long {
        var sum = 0L
        var n = 0L
        for (y in 0 until b.height - 1 step 2) for (x in 0 until b.width step 3) {
            sum += abs(Color.green(b.getPixel(x, y)) - Color.green(b.getPixel(x, y + 1)))
            n++
        }
        return if (n == 0L) 0 else sum * 100 / n
    }

    private fun grabScreen(name: String): Bitmap {
        SystemClock.sleep(500)
        val screen = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            .copy(Bitmap.Config.ARGB_8888, false)
        val file = File(File(context.getExternalFilesDir(null), "evidence").apply { mkdirs() }, "$name.png")
        file.outputStream().use { screen.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return screen
    }

    private suspend fun grabQuick(): Bitmap {
        val ml = engine.get("osd-dimensions/ml", PropertyType.Int64)!!.toInt()
        val mt = engine.get("osd-dimensions/mt", PropertyType.Int64)!!.toInt()
        val (w, h) = drawn()
        val at = IntArray(2)
        scenario.onActivity { a -> findSurface(a.window.decorView)?.getLocationOnScreen(at) }
        val screen = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            .copy(Bitmap.Config.ARGB_8888, false)
        return Bitmap.createBitmap(screen, at[0] + ml + 2, at[1] + mt + 2, w - 4, h - 4)
    }

    private fun shell(cmd: String) {
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(cmd).close()
    }

    /**
     * The video area of the screen as composited, so what the viewer sees. The picture rectangle
     * comes from mpv's `osd-dimensions` margins, offset by where the video surface sits on screen.
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

    private fun copyFile(asset: String): File {
        val file = File(context.cacheDir, asset)
        InstrumentationRegistry.getInstrumentation().context.assets.open(asset).use { input ->
            file.outputStream().use { input.copyTo(it) }
        }
        return file
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
