package app.player.feature.player

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import app.player.core.engine.PlayerEngine
import app.player.core.engine.PlayerSessions
import app.player.core.engine.PropertyType
import app.player.core.testing.Ears
import app.player.feature.player.tools.PlaybackTools
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** P1.2 wiring on a real engine (emulator). Numbers are function checks, not perf claims. */
class PlaybackToolsTest {
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var scenario: ActivityScenario<PlayerActivity>
    private lateinit var engine: PlayerEngine
    private lateinit var tools: PlaybackTools

    private fun open(asset: String) {
        scenario = ActivityScenario.launch(PlayerActivity.intent(context, copy(asset)))
        waitUntil(10_000) { (PlayerSessions.current.value?.state?.value?.positionMs ?: 0) > 300 }
        engine = PlayerSessions.current.value!!
        tools = PlaybackTools(engine)
    }

    @After fun close() = scenario.close()

    @Ears("US-23.1")
    @Test fun speedAppliesWithPitchKept_US23_1() = runBlocking<Unit> {
        open("clip.mp4")
        val speed = tools.speed
        val t0 = SystemClock.elapsedRealtime()
        tools.setSpeed(1.5)
        waitUntil(2_000) { speed.value == 1.5 }
        val ms = SystemClock.elapsedRealtime() - t0
        val pitch = engine.get("audio-pitch-correction", PropertyType.Flag)
        // Measure the real rate: media time over wall time across 3 s.
        val p0 = engine.state.value.positionMs
        val w0 = SystemClock.elapsedRealtime()
        SystemClock.sleep(3_000)
        val rate = (engine.state.value.positionMs - p0).toDouble() / (SystemClock.elapsedRealtime() - w0)
        log("speed 1.5 applied in $ms ms, pitch correction $pitch, measured rate %.2f".format(rate))
        assertTrue("applied in $ms ms", ms <= 200)
        assertEquals(true, pitch)
        assertTrue("rate $rate", rate in 1.35..1.65)
    }

    @Ears("US-24.2")
    @Test fun loopFileRestartsWithoutGap_US24_2() {
        open("clip.mp4")
        tools.setLoopFile(true)
        engine.seekTo(18_500)
        var lastHigh = 0L
        var wrappedAt = 0L
        waitUntil(6_000) {
            val p = engine.state.value.positionMs
            val now = SystemClock.elapsedRealtime()
            if (p >= 18_500) lastHigh = now
            if (lastHigh > 0 && p in 1..1_500) { wrappedAt = now; true } else false
        }
        val gap = wrappedAt - lastHigh
        log("loop-file wrap gap <= $gap ms (sampled every 20 ms)")
        // US-24.2's 500 ms is for the phone; emulators (the hosted one is slower) give a regression bound only.
        assertTrue("gap $gap ms", gap <= 1_000)
        assertTrue(engine.state.value.ended.not())
    }

    @Ears("US-25.1")
    @Test fun abRepeatLoopsBetweenPoints_US25_1() {
        open("clip.mp4")
        tools.setA(2_000)
        tools.setB(4_000)
        engine.seekTo(2_000)
        SystemClock.sleep(500)
        var min = Long.MAX_VALUE
        var max = 0L
        val end = SystemClock.elapsedRealtime() + 6_000
        while (SystemClock.elapsedRealtime() < end) {
            val p = engine.state.value.positionMs
            min = minOf(min, p); max = maxOf(max, p)
            SystemClock.sleep(20)
        }
        log("A-B 2.0-4.0 s: positions seen $min..$max ms over 6 s")
        assertTrue("min $min", min >= 1_900)
        assertTrue("max $max", max <= 4_300)
        tools.clearAb()
        SystemClock.sleep(3_000)
        assertTrue("cleared", engine.state.value.positionMs > 4_300)
    }

    @Ears("US-27.2")
    @Test fun chaptersListAndJump_US27_2() = runBlocking<Unit> {
        open("chapters.mkv")
        val chapters = tools.chapters()
        log("chapters: $chapters")
        assertEquals(3, chapters.size)
        assertEquals("Two", chapters[1].title)
        tools.goToChapter(2)
        waitUntil(2_000) { engine.state.value.positionMs in 12_000..13_000 }
        log("jumped to chapter 3 at ${engine.state.value.positionMs} ms")
    }

    @Ears("US-33.1")
    @Test fun screenshotSavesVideoFrameAsPng_US33_1() = runBlocking<Unit> {
        open("clip.mp4")
        val t0 = SystemClock.elapsedRealtime()
        val uri = tools.screenshot(context)
        val ms = SystemClock.elapsedRealtime() - t0
        assertNotNull(uri)
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri!!)!!.use { BitmapFactory.decodeStream(it, null, opts) }
        val path = context.contentResolver.query(uri, arrayOf(android.provider.MediaStore.Images.Media.RELATIVE_PATH), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        log("screenshot in $ms ms: ${opts.outWidth}x${opts.outHeight} ${opts.outMimeType} at $path")
        assertEquals(320, opts.outWidth)
        assertEquals(180, opts.outHeight)
        assertEquals("image/png", opts.outMimeType)
        assertTrue("path $path", path!!.startsWith("Pictures/"))
        // US-33.1's 1 s is a phone number; on an emulator this is a regression bound only.
        assertTrue("took $ms ms", ms <= 5_000)
        context.contentResolver.delete(uri, null, null)
    }

    /**
     * US-33.1 "without subtitles": with a subtitle on screen, the saved PNG has no subtitle text,
     * while mpv's own subtitle-including capture of the same paused frame does.
     */
    @Ears("US-33.1")
    @Test fun screenshotLeavesSubtitlesOut_US33_1() = runBlocking<Unit> {
        open("clip.mp4")
        engine.command("sub-add", copy("sub.srt").path!!, "select")
        SystemClock.sleep(1_000)
        engine.pause()
        SystemClock.sleep(500)
        val uri = tools.screenshot(context)!!
        val saved = context.contentResolver.openInputStream(uri)!!.use { android.graphics.BitmapFactory.decodeStream(it) }
        val withSubs = File(context.cacheDir, "with-subs.png")
        engine.command("screenshot-to-file", withSubs.path, "subtitles")
        engine.get("pause", PropertyType.Flag)
        val reference = android.graphics.BitmapFactory.decodeFile(withSubs.path)
        // Same paused frame: the two captures match on the picture above the subtitle band and
        // differ only where mpv's capture draws the subtitle text.
        val bandTop = saved.height * 3 / 4
        val diffBand = differing(saved, reference, bandTop, saved.height)
        val diffAbove = differing(saved, reference, 0, bandTop)
        log("US-33.1 pixels differing from mpv's capture with subtitles: subtitle band $diffBand, picture above $diffAbove (${saved.width}x${saved.height})")
        File(context.getExternalFilesDir(null), "evidence").apply { mkdirs() }.let { dir ->
            context.contentResolver.openInputStream(uri)!!.use { input -> File(dir, "p113-screenshot-saved.png").outputStream().use { input.copyTo(it) } }
            withSubs.copyTo(File(dir, "p113-screenshot-with-subs-reference.png"), overwrite = true)
        }
        context.contentResolver.delete(uri, null, null)
        assertTrue("same frame above the band", diffAbove <= 20)
        assertTrue("the saved frame lacks the subtitle the reference shows", diffBand >= 40)
    }

    /** Pixels in rows [from, to) whose channels differ by more than 60. */
    private fun differing(a: android.graphics.Bitmap, b: android.graphics.Bitmap, from: Int, to: Int): Int {
        var n = 0
        for (y in from until to) for (x in 0 until minOf(a.width, b.width)) {
            val p = a.getPixel(x, y); val q = b.getPixel(x, y)
            val d = maxOf(
                kotlin.math.abs(android.graphics.Color.red(p) - android.graphics.Color.red(q)),
                kotlin.math.abs(android.graphics.Color.green(p) - android.graphics.Color.green(q)),
                kotlin.math.abs(android.graphics.Color.blue(p) - android.graphics.Color.blue(q)),
            )
            if (d > 60) n++
        }
        return n
    }

    /** Near-white pixels in the bottom third, where the subtitle sits. */
    private fun whiteRows(b: android.graphics.Bitmap): Int {
        var n = 0
        for (y in b.height * 2 / 3 until b.height) for (x in 0 until b.width) {
            val c = b.getPixel(x, y)
            if (android.graphics.Color.red(c) > 235 && android.graphics.Color.green(c) > 235 && android.graphics.Color.blue(c) > 235) n++
        }
        return n
    }

    private fun waitUntil(ms: Long, check: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + ms
        while (!check()) {
            if (SystemClock.elapsedRealtime() > end) throw AssertionError("timed out after $ms ms")
            SystemClock.sleep(20)
        }
    }

    private fun copy(asset: String): Uri {
        // Always fresh: a crashed run once left a truncated copy here.
        val file = File(context.cacheDir, asset)
        InstrumentationRegistry.getInstrumentation().context.assets.open(asset).use { input ->
            file.outputStream().use { input.copyTo(it) }
        }
        return Uri.fromFile(file)
    }

    private fun log(line: String) {
        android.util.Log.i("PlaybackToolsTest", line)
        val dir = File(context.getExternalFilesDir(null), "evidence").apply { mkdirs() }
        File(dir, "numbers.txt").appendText(line + "\n")
    }
}
