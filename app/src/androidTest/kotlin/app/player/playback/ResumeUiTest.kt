package app.player.playback

import android.os.StrictMode
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import app.player.core.data.PlaybackPosition
import app.player.core.data.history.ResumePoints
import app.player.core.testing.Ears
import app.player.feature.playback.OrientationMode
import app.player.feature.playback.PlaybackPrefs
import app.player.feature.playback.ResumeMode
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

/**
 * P1.11, resume where I stopped (US-22) through the real player: the save on leaving, resume with
 * start over, ask and no, the first file only, and how old the saved position can get while playing.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class ResumeUiTest {
    private val ui = PlaybackUi("P1.11")
    private val context = ui.context
    private val device = ui.device
    private val points = ResumePoints(context)
    private val prefs = PlaybackPrefs(context)

    @get:Rule val onFailure = object : TestWatcher() {
        override fun failed(e: Throwable?, description: Description) = failedShot(ui, description)
    }

    @Before fun setUp() {
        StrictMode.setVmPolicy(StrictMode.VmPolicy.Builder().build())
        device.wakeUp()
        device.setOrientationNatural()
        runBlocking { points.clear(); prefs.reset(); prefs.setOrientation(OrientationMode.System) }
    }

    @After fun tearDown() {
        ui.closePlayer()
        runBlocking { prefs.reset() }
        device.unfreezeRotation()
    }

    private fun long() = TestMedia.videos("Single" to "Long.mp4")[0]

    private fun row(uri: String) = runBlocking { points.get(uri) }

    private fun seed(uri: String, at: Long, duration: Long) =
        runBlocking { points.put(PlaybackPosition(uri, at, duration, System.currentTimeMillis())) }

    @Ears("US-22.1")
    @Test fun a_leavingSavesThePositionInWholeSeconds() {
        val uri = long()
        ui.play(uri)
        assertTrue("never reached 9 s, state ${ui.state()}", ui.waitFor(60_000) { ui.state()!!.positionMs >= 9_000 })
        val at = ui.state()!!.positionMs
        ui.backOutOfPlayer()
        assertTrue("player still open", ui.waitFor(5_000) { ui.player() == null })
        var r = row(uri.toString())
        ui.waitFor(5_000) { r = row(uri.toString()); r?.let { it.positionMs >= at - 1_000 } == true }
        ui.log("left at $at ms, row $r")
        val saved = r
        assertNotNull("no position saved on leaving", saved)
        assertEquals("not rounded down to the second: ${saved!!.positionMs}", 0L, saved.positionMs % 1_000)
        assertTrue("saved ${saved.positionMs} not the position left at, $at", saved.positionMs in at - 1_000..at + 3_000)
        ui.shot("01-left")
    }

    @Ears("US-22.2")
    @Test fun b_reopenResumesWithStartOverFor5s() {
        val uri = long()
        seed(uri.toString(), 20_000, 60_000)
        val m = ui.mark()
        ui.open(uri)
        val shown = device.wait(Until.findObject(By.text("Start over")), 30_000)
        val seenAt = SystemClock.uptimeMillis()
        assertNotNull("no start over on resume", shown)
        assertTrue("did not start at the saved 20 s, state ${ui.state()}", ui.waitFor(30_000) {
            ui.state()?.let { it.uri == uri.toString() && it.firstFrameMs != null && it.positionMs >= 19_500 } == true
        })
        ui.shot("02-resumed-start-over")
        assertTrue("no resume start in the log", ui.since(m).any { it.contains("open $uri") && it.contains("start Resume(positionMs=20000)") })
        assertTrue("start over still there after 20 s", device.wait(Until.gone(By.text("Start over")), 20_000))
        ui.within("start over shown", SystemClock.uptimeMillis() - seenAt, 7_000, lowMs = 4_000)
        ui.shot("03-start-over-gone")
        // Tapping it goes back to the beginning.
        ui.closePlayer()
        seed(uri.toString(), 20_000, 60_000)
        ui.open(uri)
        device.wait(Until.findObject(By.text("Start over")), 30_000)!!.click()
        assertTrue("start over did not go back to 0, state ${ui.state()}", ui.waitFor(10_000) {
            ui.state()?.let { it.firstFrameMs != null && it.positionMs < 5_000 } == true
        })
        ui.shot("04-after-start-over")
    }

    @Ears("US-22.3")
    @Test fun c_askChoosesBeforeTheFirstFrameNoStartsAt0() {
        val uri = long()
        seed(uri.toString(), 20_000, 60_000)
        runBlocking { prefs.setResume(ResumeMode.Ask) }
        ui.open(uri)
        assertNotNull("no resume question", device.wait(Until.findObject(By.text("Resume?")), 30_000))
        SystemClock.sleep(1_000)
        val s = ui.state()
        assertTrue("a frame showed before the answer: $s", s == null || s.uri != uri.toString() || s.firstFrameMs == null)
        ui.shot("05-ask-before-first-frame")
        device.findObject(By.text("Resume")).click()
        assertTrue("resume did not start at 20 s, state ${ui.state()}", ui.waitFor(30_000) {
            ui.state()?.let { it.uri == uri.toString() && it.firstFrameMs != null && it.positionMs >= 19_500 } == true
        })
        ui.shot("06-ask-resumed")
        ui.closePlayer()

        seed(uri.toString(), 20_000, 60_000)
        runBlocking { prefs.setResume(ResumeMode.No) }
        val m = ui.mark()
        ui.play(uri)
        assertTrue("resume no did not start at 0, state ${ui.state()}", ui.state()!!.positionMs < 5_000)
        assertTrue("no start-at-0 in the log", ui.since(m).any { it.contains("open $uri") && it.contains("start Beginning") })
        assertFalse("start over shown with resume no", device.hasObject(By.text("Start over")))
        assertFalse("question shown with resume no", device.hasObject(By.text("Resume?")))
        ui.shot("07-resume-no")
    }

    @Ears("US-22.4")
    @Test fun d_resumeOnlyTheFirstFileStartsLaterFilesAt0() {
        val (p1, p2) = TestMedia.videos("Parts" to "Part 1.mp4", "Parts" to "Part 2.mp4")
        seed(p2.toString(), 8_000, 30_000)
        runBlocking { prefs.setResumeFirstOnly(true) }
        val m = ui.mark()
        ui.play(p1)
        assertTrue("Part 2 never played after Part 1, state ${ui.state()}", ui.waitFor(60_000) {
            ui.state()?.let { it.uri == p2.toString() && it.firstFrameMs != null } == true
        })
        val pos = ui.state()!!.positionMs
        ui.shot("08-second-file-from-0")
        val line = ui.since(m).lastOrNull { it.contains("open $p2") }
        ui.log("Part 2 at $pos ms, $line")
        assertTrue("Part 2 did not start at 0: $line", line?.contains("start Beginning") == true)
        assertTrue("Part 2 is at $pos ms, not near 0", pos < 7_000)
    }

    @Ears("US-22.5")
    @Test fun e_savedPositionNeverOlderThan10sWhilePlaying() {
        // A kill cannot run inside the test's own process. What a kill would find instead: the
        // age of the saved row, sampled through 25 s of playback, never over 10 s.
        val uri = long()
        ui.play(uri)
        val start = SystemClock.uptimeMillis()
        var maxAge = 0L
        var samples = 0
        while (SystemClock.uptimeMillis() - start < 25_000) {
            SystemClock.sleep(500)
            val s = ui.state() ?: continue
            val r = row(uri.toString())
            val now = System.currentTimeMillis()
            if (r == null) {
                // Before the first periodic save, the age counts from the first frame.
                maxAge = maxOf(maxAge, SystemClock.uptimeMillis() - start)
            } else {
                maxAge = maxOf(maxAge, now - r.updatedAt)
                assertTrue("saved ${r.positionMs} ms trails the player at ${s.positionMs} ms by over 10 s", s.positionMs - r.positionMs <= 11_000)
            }
            samples++
        }
        ui.log("saved row age over 25 s of playback: max $maxAge ms, $samples samples, row ${row(uri.toString())}")
        ui.shot("09-playing-saved")
        assertTrue("too few samples while playing: $samples", samples >= 20)
        assertTrue("saved position got $maxAge ms old while playing (limit 10 s)", maxAge <= 10_000)
    }
}
