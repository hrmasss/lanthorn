package app.player

import android.content.Intent
import android.net.Uri
import android.os.StrictMode
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import app.player.core.engine.ActiveDecoder
import app.player.core.engine.DecoderMode
import app.player.core.playerapi.PlayerIntents
import app.player.core.engine.PlayerSessions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import java.io.File

/**
 * P1.1 through the real player UI (UiAutomator, in-process, so the 3 s auto-hide of the controls
 * cannot race the steps). Screenshots go to /sdcard/Android/data/app.player/files/gate/.
 * Timings come from the engine's own state.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class DecoderUiTest {
    private val instr = InstrumentationRegistry.getInstrumentation()
    private val context = instr.targetContext
    private val device = UiDevice.getInstance(instr)
    private val shots = File(context.getExternalFilesDir(null), "gate").apply { mkdirs() }

    @Before fun setUp() {
        // The player is opened with file:// uris of the app's own files.
        StrictMode.setVmPolicy(StrictMode.VmPolicy.Builder().build())
    }

    private fun file(name: String): File = File(context.filesDir, name).also { f ->
        if (!f.exists()) instr.context.assets.open(name).use { i -> f.outputStream().use { i.copyTo(it) } }
    }

    private fun play(name: String, oneTime: DecoderMode? = null) {
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(Uri.fromFile(file(name)), "video/mp4")
            .setClassName(context, "app.player.feature.player.PlayerActivity")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        if (oneTime != null) PlayerIntents.withDecoder(intent, oneTime)
        context.startActivity(intent)
        val ok = waitFor { PlayerSessions.current.value?.state?.value?.let { it.video != null && it.uri?.endsWith(name) == true } == true }
        assertTrue("$name never showed video", ok)
        SystemClock.sleep(1500)
        log("playing $name, decoder ${engineState()?.activeDecoder?.label}")
    }

    private fun close() {
        device.pressBack(); device.pressBack()
        waitFor { PlayerSessions.current.value == null }
    }

    private fun engineState() = PlayerSessions.current.value?.state?.value

    private fun waitFor(timeoutMs: Long = 10_000, check: () -> Boolean): Boolean {
        val end = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < end) {
            if (check()) return true
            SystemClock.sleep(50)
        }
        return false
    }

    private fun showControls() {
        if (device.hasObject(By.desc("More options"))) return
        device.click(device.displayWidth / 2, device.displayHeight / 2)
        device.wait(Until.hasObject(By.desc("More options")), 2000)
    }

    private fun openFromMenu(label: String) {
        repeat(3) {
            showControls()
            device.findObject(By.desc("More options"))?.click()
            if (device.wait(Until.hasObject(By.text(label)), 2000)) {
                device.findObject(By.text(label)).click()
                SystemClock.sleep(1200)
                return
            }
            device.pressBack()
        }
        fail("menu entry '$label' not reachable")
    }

    private fun pickDecoder(summary: String) {
        repeat(3) {
            showControls()
            device.findObject(By.descStartsWith("Decoder "))?.click()
            if (device.wait(Until.hasObject(By.text(summary)), 2000)) {
                device.findObject(By.text(summary)).click()
                return
            }
            device.pressBack()
        }
        fail("decoder option '$summary' not reachable")
    }

    private fun badge(): String? {
        showControls()
        return device.findObject(By.descStartsWith("Decoder "))?.contentDescription
    }

    private fun shot(name: String) {
        device.takeScreenshot(File(shots, "$name.png"))
        log("shot $name")
    }

    private fun log(s: String) = Log.i("P1.1", s)

    /** US-37.2 after the 4:4:4 fallback, then US-35.3 by hand: pick HW on a file only SW plays. */
    @Test fun a_fallbackThenHandPickHw() {
        play("clip444.mp4")
        assertEquals(ActiveDecoder.SW, engineState()?.activeDecoder)
        openFromMenu("Info")
        assertTrue(device.wait(Until.hasObject(By.text("SW")), 2000))
        shot("info-after-fallback")
        device.pressBack()

        pickDecoder("Hardware, fastest")
        val t0 = SystemClock.elapsedRealtime()
        val ok = waitFor(3000) { engineState()?.decoderNotice != null }
        log("notice after ${SystemClock.elapsedRealtime() - t0} ms: '${engineState()?.decoderNotice}', running ${engineState()?.activeDecoder?.label}")
        assertTrue(ok)
        SystemClock.sleep(300)
        shot("notice-after-hand-pick-hw")
        close()
    }

    /** US-36.3 (badge pick remembered on reopen) and US-37.2 (info names the new decoder within 1 s). */
    @Test fun b_badgePickRememberedAndShownInInfo() {
        play("clip420.mp4")
        log("badge before: ${badge()}")
        pickDecoder("Software, plays anything, more battery")
        val t0 = SystemClock.elapsedRealtime()
        val switched = waitFor(3000) { engineState()?.activeDecoder == ActiveDecoder.SW }
        log("decoder SW ${SystemClock.elapsedRealtime() - t0} ms after the pick")
        assertTrue(switched)
        openFromMenu("Info")
        shot("info-after-badge-switch-to-sw")
        device.pressBack()
        close()

        play("clip420.mp4")
        val after = badge()
        log("badge after reopen: $after")
        assertEquals("Decoder SW. Tap to change", after)
        shot("badge-after-reopen")
        close()
    }

    /** US-37.3: two captures a second apart while playing, with the engine's counters logged. */
    @Test fun c_liveValues() {
        play("clip420.mp4")
        openFromMenu("Info")
        assertTrue(device.wait(Until.hasObject(By.text("Dropped frames")), 3000))
        val s1 = engineState(); shot("info-live-1")
        SystemClock.sleep(1000)
        val s2 = engineState(); shot("info-live-2")
        log("live: position ${s1?.positionMs} -> ${s2?.positionMs} ms, dropped ${s1?.droppedFrames} -> ${s2?.droppedFrames}")
        assertNotNull(s2)
        assertTrue(s2!!.positionMs > s1!!.positionMs)
        close()
    }

    /** US-37.1: the embedded subtitle section. */
    @Test fun d_embeddedAndExternalSubtitlesInInfo() {
        play("withsubs.mp4")
        // subs-audio loads sidecar files itself; here the external file is added directly.
        val srt = File(context.filesDir, "withsubs.fr.srt").apply { writeText("1\n00:00:00,000 --> 00:00:30,000\nSous-titre externe\n\n") }
        PlayerSessions.current.value!!.command("sub-add", srt.path, "auto", "external", "fr")
        SystemClock.sleep(800)
        openFromMenu("Info")
        repeat(2) { device.findObject(By.scrollable(true))?.scroll(androidx.test.uiautomator.Direction.DOWN, 1f) }
        SystemClock.sleep(800)
        assertTrue(device.wait(Until.hasObject(By.text("MP4 text")), 3000))
        assertTrue(device.wait(Until.hasObject(By.text("External")), 3000))
        shot("info-subtitles")
        close()
    }

    /** US-09.3 (library's "play with"): a one-time decoder for that launch, not remembered. */
    @Test fun e_oneTimeDecoderNotRemembered() {
        play("withsubs.mp4", oneTime = DecoderMode.SW)
        assertEquals(ActiveDecoder.SW, engineState()?.activeDecoder)
        assertEquals(DecoderMode.SW, engineState()?.decoderMode)
        close()
        play("withsubs.mp4")
        log("one-time SW, then a plain open: ${engineState()?.decoderMode} on ${engineState()?.activeDecoder?.label}")
        assertEquals(ActiveDecoder.HW, engineState()?.activeDecoder)
        close()
    }

    /** US-10.4: file properties from anywhere, by action, with nothing playing. */
    @Test fun f_infoScreenForAFile() {
        context.startActivity(PlayerIntents.showInfo(Uri.fromFile(file("withsubs.mp4"))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        assertTrue(device.wait(Until.hasObject(By.text("H.264")), 8000))
        assertTrue(device.hasObject(By.text("MP4")))
        assertTrue(device.hasObject(By.text("AAC")))
        assertEquals(null, PlayerSessions.current.value)
        shot("info-screen-for-a-file")
        device.pressBack()
    }
}
