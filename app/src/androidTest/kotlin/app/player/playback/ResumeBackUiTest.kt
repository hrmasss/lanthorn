package app.player.playback

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.uiautomator.UiDevice
import app.player.core.data.history.ResumePoints
import app.player.core.engine.PlaybackState
import app.player.core.engine.PlayerSessions
import app.player.core.playerapi.PlayerIntents
import app.player.core.testing.Ears
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Leaving by Back part way through keeps the real position (US-22.1, library's US-07.3 marks).
 * mpv reports end of file while the engine shuts down; that must not save the file as finished
 * or open the next file of the queue.
 */
@RunWith(AndroidJUnit4::class)
class ResumeBackUiTest {
    private val instr = InstrumentationRegistry.getInstrumentation()
    private val context = instr.targetContext
    private val device = UiDevice.getInstance(instr)

    @Before fun setUp() {
        device.wakeUp()
        runBlocking { ResumePoints(context).clear() }
    }

    @After fun tearDown() {
        player()?.let { a -> instr.runOnMainSync { a.finish() } }
        waitFor(5_000) { player() == null }
    }

    @Ears("US-22.1")
    @Test fun backAt14sKeepsThePositionNotFinished() {
        val uris = listOf("longgop.mp4", "clip420.mp4").map { Uri.fromFile(file(it)) }
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uris[0], "video/mp4")
            .setClassName(context, "app.player.feature.player.PlayerActivity")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        context.startActivity(PlayerIntents.withQueue(intent, uris, 0))
        assertTrue("never played", waitFor(15_000) { state()?.let { !it.paused && it.uri?.endsWith("longgop.mp4") == true } == true })
        assertTrue("never reached 14 s", waitFor(30_000) { state()!!.positionMs >= 14_000 })
        val at = state()!!.positionMs

        // Back leaves the player (twice when the player asks for a second Back).
        device.pressBack()
        if (!waitFor(1_500) { player() == null }) device.pressBack()
        assertTrue("player still open after Back", waitFor(5_000) { player() == null })
        SystemClock.sleep(2_000)

        val row = runBlocking { ResumePoints(context).get(uris[0].toString()) }
        Log.i("P1.11", "Back at $at ms of 60000: row $row; next file row ${runBlocking { ResumePoints(context).get(uris[1].toString()) }}")
        assertNotNull("no resume point after Back", row)
        assertTrue("saved as finished or moved: ${row!!.positionMs} after Back at $at", row.positionMs in (at / 1000 * 1000 - 1_000)..at)
        assertEquals(60_000L, row.durationMs / 1000 * 1000)
        assertEquals("the queue opened the next file after Back", null, runBlocking { ResumePoints(context).get(uris[1].toString()) })
        assertEquals("player reopened by the queue", null, player())
    }

    private fun file(name: String): File = File(context.filesDir, name).also { f ->
        if (!f.exists()) instr.context.assets.open(name).use { i -> f.outputStream().use { i.copyTo(it) } }
    }

    private fun state(): PlaybackState? = PlayerSessions.current.value?.state?.value

    private fun player(): Activity? {
        var found: Activity? = null
        instr.runOnMainSync {
            val reg = ActivityLifecycleMonitorRegistry.getInstance()
            found = listOf(Stage.RESUMED, Stage.PAUSED, Stage.STARTED, Stage.STOPPED).asSequence()
                .flatMap { reg.getActivitiesInStage(it).asSequence() }
                .firstOrNull { it.javaClass.name == "app.player.feature.player.PlayerActivity" }
        }
        return found
    }

    private fun waitFor(ms: Long, check: () -> Boolean): Boolean {
        val end = SystemClock.uptimeMillis() + ms
        while (SystemClock.uptimeMillis() < end) {
            if (runCatching(check).getOrDefault(false)) return true
            SystemClock.sleep(100)
        }
        return runCatching(check).getOrDefault(false)
    }
}
