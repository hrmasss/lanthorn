package app.player.playback

import android.net.Uri
import android.os.StrictMode
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import app.player.core.data.history.ResumePoints
import app.player.core.testing.Ears
import app.player.feature.playback.LoopMode
import app.player.feature.playback.OrientationMode
import app.player.feature.playback.PlaybackPrefs
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.FixMethodOrder
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import java.io.File
import java.util.regex.Pattern
import kotlin.math.abs

/**
 * P1.12 through the real player: folder queues, loop and shuffle (US-13.3, US-24), previous, next
 * and step buttons (US-29), and files that will not play (US-75). The files sit in MediaStore
 * folders under Movies/PBTest, indexed by the library, as a viewer's would.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class QueueUiTest {
    private val ui = PlaybackUi("P1.12")
    private val context = ui.context
    private val device = ui.device
    private val prefs = PlaybackPrefs(context)

    @get:Rule val onFailure = object : TestWatcher() {
        override fun failed(e: Throwable?, description: Description) = failedShot(ui, description)
    }

    @Before fun setUp() {
        StrictMode.setVmPolicy(StrictMode.VmPolicy.Builder().build())
        device.wakeUp()
        device.setOrientationNatural()
        runBlocking { ResumePoints(context).clear(); prefs.reset(); prefs.setOrientation(OrientationMode.System) }
    }

    @After fun tearDown() {
        ui.closePlayer()
        runBlocking { prefs.reset() }
        device.unfreezeRotation()
    }

    /** Clip 10 first, so neither MediaStore id order nor plain text order gives 1, 2, 10. */
    private fun clips(): List<Uri> {
        val (c10, c2, c1) = TestMedia.videos("Clips" to "Clip 10.mp4", "Clips" to "Clip 2.mp4", "Clips" to "Clip 1.mp4")
        return listOf(c1, c2, c10)
    }

    /** Waits until playback opened [count] files after [m] and the last one ended, then 5 s more. */
    private fun queueSettles(m: String, count: Int, last: () -> String?): List<String> {
        ui.waitFor(120_000) {
            ui.opens(m).size >= count && ui.state()?.let { it.ended && it.uri == last() } == true
        }
        SystemClock.sleep(5_000)
        return ui.opens(m).also { ui.log("opened: $it") }
    }

    @Ears("US-13.3", "US-24.1")
    @Test fun a_folderQueueInNameOrderThenStop() {
        val (c1, c2, c10) = clips()
        val m = ui.mark()
        ui.play(c1)
        val got = queueSettles(m, 3) { c10.toString() }
        ui.shot("01-after-queue")
        assertEquals("not Clip 1, Clip 2, Clip 10 then stop", listOf(c1, c2, c10).map { it.toString() }, got)
        assertTrue("player closed at the end with back to list off", ui.player() != null)
    }

    @Ears("US-24.3")
    @Test fun b_loopAllGoesFromTheLastBackToTheFirst() {
        val (c1, _, c10) = clips()
        runBlocking { prefs.setLoop(LoopMode.All) }
        val m = ui.mark()
        ui.play(c10)
        assertTrue("first file never followed the last, opened ${ui.opens(m)}", ui.waitFor(60_000) {
            ui.opens(m).take(2) == listOf(c10.toString(), c1.toString()) &&
                ui.state()?.let { it.uri == c1.toString() && it.firstFrameMs != null } == true
        })
        ui.shot("02-loop-all-back-to-first")
    }

    @Ears("US-24.2")
    @Test fun c_loopOneRestartsTheSameVideo() {
        // The 500 ms gap is the phone's to measure (perf/phone-only.md); here: it restarts, no next, no end.
        val (c1) = clips()
        runBlocking { prefs.setLoop(LoopMode.One) }
        val m = ui.mark()
        ui.play(c1)
        val start = SystemClock.uptimeMillis()
        var last = ui.state()!!.positionMs
        var restarts = 0
        var ended = false
        while (SystemClock.uptimeMillis() - start < 15_000) {
            SystemClock.sleep(100)
            val s = ui.state() ?: continue
            if (s.ended) ended = true
            if (s.positionMs < last - 1_500) restarts++
            last = s.positionMs
        }
        ui.log("loop one over 15 s of a 4 s clip: $restarts restarts, ended $ended, opened ${ui.opens(m)}")
        ui.shot("03-loop-one")
        assertEquals("another file opened with loop one", listOf(c1.toString()), ui.opens(m))
        assertFalse("the file ended with loop one", ended)
        assertTrue("the 4 s clip never restarted in 15 s", restarts >= 1)
    }

    @Ears("US-24.4")
    @Test fun d_shufflePlaysEveryFileOnceThenStops() {
        val (c1, c2, c10) = clips()
        runBlocking { prefs.setShuffle(true) }
        val m = ui.mark()
        ui.play(c1)
        // The last of a shuffled queue is not known ahead: settle on whichever ended last.
        val got = queueSettles(m, 3) { ui.opens(m).lastOrNull() }
        ui.shot("04-shuffle-done")
        assertEquals("shuffle did not play 3 files", 3, got.size)
        assertEquals("shuffle repeated or missed a file", setOf(c1, c2, c10).map { it.toString() }.toSet(), got.toSet())
    }

    @Ears("US-24.5")
    @Test fun e_backToListClosesThePlayerAtTheEnd() {
        val (_, _, c10) = clips()
        runBlocking { prefs.setBackToList(true) }
        ui.play(c10)
        assertTrue("player still open after the queue ended", ui.waitFor(60_000) { ui.player() == null })
        ui.shot("05-closed-at-end")
    }

    @Ears("US-29.1", "US-29.3")
    @Test fun f_smartPreviousRestartsThenOpensThePreviousFile() {
        // Two long files of their own: a short first file ends by itself and moves on while the test steps.
        val (p1, p2) = TestMedia.videos("Smart" to "Step 1.mp4", "Smart" to "Step 2.mp4")
        ui.play(p2)
        assertTrue("never reached 6 s", ui.waitFor(60_000) { ui.state()!!.positionMs >= 6_000 })
        ui.engine()!!.pause()
        SystemClock.sleep(1_000)
        ui.showControls()
        // US-29.3, default: previous and next are shown.
        assertNotNull("no previous button by default", device.wait(Until.findObject(By.desc("Previous video")), 3_000))
        assertNotNull("no next button by default", device.findObject(By.desc("Next video")))
        ui.shot("06-previous-next-shown")
        val at = ui.state()!!.positionMs
        val m = ui.mark()
        ui.tapControl(By.desc("Previous video"))
        assertTrue("previous at $at ms did not restart the video, state ${ui.state()}", ui.waitFor(10_000) {
            ui.state()?.let { it.uri == p2.toString() && it.positionMs < 2_000 } == true
        })
        SystemClock.sleep(1_500)
        assertEquals("previous more than 5 s in opened another file", emptyList<String>(), ui.opens(m))
        ui.shot("07-restarted")
        ui.tapControl(By.desc("Previous video"))
        assertTrue("previous within 5 s did not open the previous file, opened ${ui.opens(m)}", ui.waitFor(30_000) {
            ui.opens(m).lastOrNull() == p1.toString() && ui.state()?.uri == p1.toString()
        })
        ui.shot("08-previous-file")
        ui.engine()!!.pause()
        val m2 = ui.mark()
        ui.tapControl(By.desc("Next video"))
        assertTrue("next did not open the next file, opened ${ui.opens(m2)}", ui.waitFor(30_000) { ui.opens(m2).firstOrNull() == p2.toString() })
    }

    @Ears("US-29.2", "US-29.3")
    @Test fun g_stepButtonsSeek10sAndPreviousNextHide() {
        val (step) = TestMedia.videos("Step" to "Step.mp4")
        runBlocking { prefs.setStepButtons(true); prefs.setShowPreviousNext(false) }
        ui.play(step)
        assertTrue("never reached 3 s", ui.waitFor(60_000) { ui.state()!!.positionMs >= 3_000 })
        ui.engine()!!.pause()
        SystemClock.sleep(1_500)
        ui.showControls()
        assertNotNull("no forward step button", device.wait(Until.findObject(By.desc("Forward 10 seconds")), 3_000))
        assertNotNull("no back step button", device.findObject(By.desc("Back 10 seconds")))
        assertFalse("previous shown with previous/next off", device.hasObject(By.desc("Previous video")))
        assertFalse("next shown with previous/next off", device.hasObject(By.desc("Next video")))
        ui.shot("09-step-buttons-no-previous-next")
        val p0 = ui.state()!!.positionMs
        fun stepTo(desc: String, want: Long) {
            ui.tapControl(By.desc(desc))
            val ok = ui.waitFor(10_000) { abs(ui.state()!!.positionMs - want) <= 1_500 }
            ui.log("$desc: want $want ms, at ${ui.state()!!.positionMs} ms")
            assertTrue("$desc landed at ${ui.state()!!.positionMs} ms, not $want", ok)
        }
        stepTo("Forward 10 seconds", p0 + 10_000)
        ui.shot("10-after-forward")
        stepTo("Back 10 seconds", p0)
        stepTo("Back 10 seconds", maxOf(p0 - 10_000, 0))
        assertTrue("back went below 0", ui.state()!!.positionMs >= 0)
        ui.shot("11-after-back")
    }

    // --- US-75: files that will not play

    /** Opens [uri] and waits for the failure card; returns its texts. Times from the open to the card. */
    private fun failureCard(uri: Uri, what: String): String {
        val t0 = SystemClock.uptimeMillis()
        ui.open(uri)
        val card = device.wait(Until.findObject(By.text("Can't play this file")), 30_000)
        val ms = SystemClock.uptimeMillis() - t0
        assertNotNull("no failure card for the $what file", card)
        ui.within("$what failure card after the open", ms, 3_000)
        SystemClock.sleep(500)
        val texts = device.findObjects(By.text(Pattern.compile(".+", Pattern.DOTALL))).mapNotNull { o: UiObject2 -> ui.retrying { o.text } }.joinToString(" | ")
        ui.log("$what card: $texts")
        assertTrue("no Skip on the $what card", device.hasObject(By.text("Skip")))
        assertTrue("no Info on the $what card", device.hasObject(By.text("Info")))
        return texts
    }

    @Ears("US-75.1")
    @Test fun h_failuresNameTheReasonWithSkipAndInfo() {
        val (broken, gone, long) = TestMedia.videos("Odd" to "Broken.mp4", "Odd4" to "Gone.mp4", "Single" to "Long.mp4")
        val codec = TestMedia.privateFile("Codec unknown.mp4")
        // A file opened in a stale library: the row is there, the file is not.
        val path = TestMedia.path(gone)
        assertTrue("could not delete $path", path != null && File(path).delete())

        var t = failureCard(broken, "damaged")
        assertTrue("damaged not named: $t", t.contains("damaged"))
        ui.shot("12-damaged")
        device.findObject(By.text("Info")).click()
        assertNotNull("info gives no plain reason", device.wait(Until.findObject(By.textStartsWith("The file could not be read as a video or song")), 5_000))
        ui.shot("13-damaged-info")
        ui.closePlayer()

        t = failureCard(gone, "missing")
        assertTrue("missing not named: $t", t.contains("missing"))
        assertTrue("the missing file's name is not on the card: $t", t.contains("Gone.mp4"))
        ui.shot("14-missing")
        ui.closePlayer()

        // A document uri this app was never granted.
        val longPath = TestMedia.path(long)!!.substringAfter("/emulated/0/")
        val doc = Uri.parse("content://com.android.externalstorage.documents/document/primary%3A" + Uri.encode(longPath))
        t = failureCard(doc, "permission")
        assertTrue("no permission not named: $t", t.contains("no permission"))
        assertTrue("the file's name is not on the permission card: $t", t.contains("Long.mp4"))
        ui.shot("15-permission")
        ui.closePlayer()

        t = failureCard(codec, "codec")
        assertTrue("unsupported codec not named: $t", t.contains("format no decoder"))
        ui.shot("16-codec")
    }

    @Ears("US-75.2")
    @Test fun i_skipFailedFilesSilently() {
        val (a1, a2, a3) = TestMedia.videos("Mixed" to "A 1.mp4", "Mixed" to "A 2.mp4", "Mixed" to "A 3.mp4")
        runBlocking { prefs.setSkipFailed(true) }
        val m = ui.mark()
        ui.play(a1)
        assertNotNull("no note naming the skipped file", device.wait(Until.findObject(By.text("Skipped A 2.mp4")), 60_000))
        ui.shot("17-skipped-note")
        assertTrue("A 3 never played after A 2 failed, opened ${ui.opens(m)}", ui.waitFor(30_000) {
            ui.state()?.let { it.uri == a3.toString() && it.firstFrameMs != null } == true
        })
        assertTrue("the note never went away", device.wait(Until.gone(By.text("Skipped A 2.mp4")), 15_000))
        assertFalse("a failure card showed with skip silently on", device.hasObject(By.text("Can't play this file")))
        val lines = ui.since(m)
        lines.filter { it.contains("open ") || it.contains("failed") || it.contains("note") }.forEach { ui.log("log: $it") }
        assertEquals(listOf(a1, a2, a3).map { it.toString() }, ui.opens(m))
        val failed = lines.first { it.contains("failed $a2") }
        val next = lines.first { it.contains("open $a3") }
        ui.within("next file after the failure", ((ui.epochOf(next) - ui.epochOf(failed)) * 1000).toLong(), 1_000)
        val shown = lines.first { it.contains("note shown") }
        val hidden = lines.first { it.contains("note hidden") }
        ui.within("skip note on screen", ((ui.epochOf(hidden) - ui.epochOf(shown)) * 1000).toLong(), 2_300, lowMs = 1_900)
    }

    @Ears("US-75.3")
    @Test fun j_everyFileFailsOneMessageNoLoop() {
        val (b1, b2) = TestMedia.videos("Bad" to "Bad 1.mp4", "Bad" to "Bad 2.mp4")
        // Skip on, so the queue moves past the first failure by itself; loop all on: still one pass.
        runBlocking { prefs.setSkipFailed(true); prefs.setLoop(LoopMode.All) }
        val m = ui.mark()
        ui.open(b1)
        assertNotNull("no message for the whole queue", device.wait(Until.findObject(By.text("None of these files would play")), 60_000))
        ui.shot("18-all-failed")
        SystemClock.sleep(6_000)
        assertEquals("not stopped after both files failed once", listOf(b1, b2).map { it.toString() }, ui.opens(m))
        assertTrue("the whole-queue message went away", device.hasObject(By.text("None of these files would play")))
    }
}
