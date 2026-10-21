package app.player

import android.content.Intent
import android.net.Uri
import android.os.StrictMode
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import app.player.core.engine.PlayerSessions
import app.player.core.testing.Ears
import app.player.core.testing.evidenceShot
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * P1.7 in the real app: with background play on, leaving the player keeps the engine playing
 * (US-59.1), and coming back shows the video at the audio's position without a reload (US-59.3).
 */
@RunWith(AndroidJUnit4::class)
class BackgroundPlayUiTest {
    private val instr = InstrumentationRegistry.getInstrumentation()
    private val context = instr.targetContext
    private val device = UiDevice.getInstance(instr)

    @Before fun setUp() {
        // The player is opened with file:// uris of the app's own files.
        StrictMode.setVmPolicy(StrictMode.VmPolicy.Builder().build())
    }

    @After fun tearDown() {
        PlayerSessions.keepPlayingInBackground = false
        device.pressBack(); device.pressBack()
        waitFor { PlayerSessions.current.value == null }
    }

    private fun file(name: String): File = File(context.filesDir, name).also { f ->
        if (!f.exists()) instr.context.assets.open(name).use { i -> f.outputStream().use { i.copyTo(it) } }
    }

    private fun playerIntent(name: String, flags: Int) =
        Intent(Intent.ACTION_VIEW).setDataAndType(Uri.fromFile(file(name)), "video/mp4")
            .setClassName(context, "app.player.feature.player.PlayerActivity")
            .putExtra("app.player.background", true)
            .addFlags(flags)

    private fun state() = PlayerSessions.current.value?.state?.value

    private fun waitFor(timeoutMs: Long = 20_000, check: () -> Boolean): Boolean {
        val end = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < end) {
            if (check()) return true
            SystemClock.sleep(50)
        }
        return false
    }

    private fun log(s: String) = Log.i("P1.7", s)

    // A file with audio: with the video track off in the background, audio is what moves the clock.
    @Ears("US-59.1", "US-59.3")
    @Test fun leavingKeepsPlayingAndReturningShowsTheVideo() {
        context.startActivity(playerIntent("clip420.mp4", Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        assertTrue("never played", waitFor { state()?.let { it.video != null && it.positionMs > 1000 && !it.paused } == true })
        val engine = PlayerSessions.current.value

        device.pressHome()
        val leftAt = state()!!.positionMs
        log("left the player at $leftAt ms")
        SystemClock.sleep(5000)
        val away = state()
        log("after 5 s away: position ${away?.positionMs} ms, paused ${away?.paused}")
        evidenceShot("home-while-playing")
        assertTrue("engine released while away", PlayerSessions.current.value === engine)
        assertEquals("paused while away", false, away?.paused)
        assertTrue("position did not move while away: $leftAt -> ${away?.positionMs}", away!!.positionMs - leftAt >= 3000)

        // Same file again: the player keeps going, no reload (US-59.3).
        context.startActivity(playerIntent("clip420.mp4", Intent.FLAG_ACTIVITY_NEW_TASK))
        assertTrue("video never came back", waitFor(10_000) { state()?.videoBackMs != null })
        val back = state()!!
        log("video back after ${back.videoBackMs} ms at ${back.positionMs} ms")
        evidenceShot("back-in-player")
        assertTrue("engine replaced on return", PlayerSessions.current.value === engine)
        assertTrue("reloaded from the start: ${back.positionMs} ms", back.positionMs >= away.positionMs)
        // The hosted emulator makes no timing claim (timing=lenient): there the number is only logged.
        val lenient = InstrumentationRegistry.getArguments().getString("timing") == "lenient"
        if (!lenient) assertTrue("video back after ${back.videoBackMs} ms", back.videoBackMs!! <= 500)
    }
}
