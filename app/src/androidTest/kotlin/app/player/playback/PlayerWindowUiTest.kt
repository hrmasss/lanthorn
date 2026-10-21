package app.player.playback

import android.app.Activity
import android.content.Intent
import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.StrictMode
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import app.player.PlayerControls
import app.player.core.data.history.ResumePoints
import app.player.core.engine.PlaybackState
import app.player.core.engine.PlayerSessions
import app.player.core.engine.PropertyType
import app.player.core.testing.Ears
import app.player.core.testing.evidenceShot
import app.player.feature.playback.Playback
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

/**
 * P1.14 through the real app (UiAutomator, in-process): orientation (US-56), lock and kids lock
 * (US-28), picture-in-picture (US-58). Screenshots go to /sdcard/Android/data/app.player/files/p114/,
 * which the Instrumented workflow uploads. Settings are changed through the app's own settings screen.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class PlayerWindowUiTest {
    private val instr = InstrumentationRegistry.getInstrumentation()
    private val context = instr.targetContext
    private val device = UiDevice.getInstance(instr)
    private val controls = PlayerControls(device)
    private val shots = File(context.getExternalFilesDir(null), "p114").apply { mkdirs() }

    @get:Rule val onFailure = object : TestWatcher() {
        override fun failed(e: Throwable?, description: Description) {
            device.takeScreenshot(File(shots, "FAILED-${description.methodName}.png"))
            device.dumpWindowHierarchy(File(shots, "FAILED-${description.methodName}.xml"))
        }
    }

    @Before fun setUp() {
        StrictMode.setVmPolicy(StrictMode.VmPolicy.Builder().build())
        device.wakeUp()
        device.unfreezeRotation()
        // Each test starts its files from 0: a row left by an earlier test resumes near the end.
        // The last test's engine writes its row as it goes, so wait for it to be gone first.
        waitFor(5_000) { PlayerSessions.current.value == null }
        runBlocking { ResumePoints(context).clear() }
    }

    @After fun tearDown() {
        device.unfreezeRotation()
        player()?.let { a -> instr.runOnMainSync { a.finish() } }
        waitFor(5_000) { player() == null }
    }

    @Ears("US-56.1")
    @Test fun a_wideVideoTurnsToLandscapeTallStaysPortrait() {
        play("clip420.mp4")
        assertTrue("wide video not in landscape, rotation ${device.displayRotation}", waitFor(6_000) { landscape() })
        shot("01-wide-landscape")
        // A second view intent over a running player only brings it back, so close it first.
        closePlayer()
        play("tall.mp4")
        assertTrue("tall video not in portrait, rotation ${device.displayRotation}", waitFor(6_000) { !landscape() })
        shot("02-tall-portrait")
    }

    @Ears("US-56.4")
    @Test fun b_rotationMetadataIsHonoured() {
        // 320x180 stored, turned 90 by its display matrix: shown 180x320, so portrait.
        play("turned.mp4")
        assertTrue("turned video not in portrait, rotation ${device.displayRotation}", waitFor(6_000) { !landscape() })
        shot("03-turned-portrait")
    }

    @Ears("US-56.2")
    @Test fun c_rotateButtonHoldsTheOtherOrientation() {
        play("clip420.mp4")
        assertTrue(waitFor(6_000) { landscape() })
        tapControl(By.desc("Rotate screen"))
        assertTrue("rotate did not turn to portrait", waitFor(5_000) { !landscape() })
        shot("04-rotated")
        SystemClock.sleep(5_000)
        assertFalse("rotation not held", landscape())
    }

    @Ears("US-56.3")
    @Test fun d_orientationChoicesAndLandscapeHolds() {
        openPlaybackSettings()
        clickRow("Orientation")
        for (c in listOf("Follow the video", "Landscape", "Reverse landscape", "Landscape, turns with the phone", "Turns with the phone", "As the system is set")) {
            assertNotNull("choice missing: $c", device.wait(Until.findObject(By.text(c)), 2_000))
        }
        shot("05-orientation-choices")
        device.findObject(By.text("Landscape")).click()
        SystemClock.sleep(800)
        try {
            play("tall.mp4")
            assertTrue("Landscape choice did not hold landscape for a tall video", waitFor(6_000) { landscape() })
            shot("06-tall-in-landscape")
        } finally {
            closePlayer()
            openPlaybackSettings(); clickRow("Orientation"); device.wait(Until.findObject(By.text("Follow the video")), 2_000)?.click()
            SystemClock.sleep(800); device.pressBack(); device.pressBack()
        }
    }

    @Ears("US-28.1", "US-28.3")
    @Test fun e_lockIgnoresTouchesKeepsRotationAndMediaKeys() {
        play("clip420.mp4")
        assertTrue(waitFor(6_000) { landscape() })
        tapControl(By.desc("Lock controls"))
        assertNotNull("no unlock button after locking", device.wait(Until.findObject(By.text("Unlock")), 1_500))
        shot("10-locked-unlock-shown")
        assertTrue("unlock button still there 3 s after the last touch", device.wait(Until.gone(By.text("Unlock")), 4_500))
        // A touch on the middle shows only the unlock button: no controls, no pause.
        val wasPaused = state()!!.paused
        device.click(device.displayWidth / 2, device.displayHeight / 2)
        assertNotNull(device.wait(Until.findObject(By.text("Unlock")), 1_500))
        assertFalse("controls showed while locked", device.hasObject(By.desc("More options")))
        shot("11-touch-while-locked")
        // Near where play/pause sits when unlocked (above the system gesture bar): nothing happens.
        device.click(device.displayWidth / 2, device.displayHeight * 80 / 100)
        SystemClock.sleep(800)
        assertEquals("a touch on the play button area changed playback", wasPaused, state()!!.paused)
        // US-28.3: rotation held while locked, whatever the device does.
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_LOCKED, player()!!.requestedOrientation)
        val before = device.displayRotation
        device.setOrientationNatural()
        SystemClock.sleep(2_500)
        assertEquals("rotation changed while locked", before, device.displayRotation)
        device.unfreezeRotation()
        // US-28.3: media keys still work while locked.
        val p0 = state()!!.paused
        device.pressKeyCode(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
        assertTrue("play/pause key ignored while locked", waitFor(2_000) { state()!!.paused != p0 })
        device.pressKeyCode(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
        assertTrue(waitFor(2_000) { state()!!.paused == p0 })
        // Unlock.
        if (!device.hasObject(By.text("Unlock"))) device.click(device.displayWidth / 2, device.displayHeight / 2)
        device.wait(Until.findObject(By.text("Unlock")), 1_500)!!.click()
        SystemClock.sleep(600)
        tapControl(By.desc("More options"))
        shot("12-unlocked-controls")
    }

    @Ears("US-28.2")
    @Test fun f_kidsLockNeedsFourCornersClockwiseWithin5s() {
        openPlaybackSettings()
        clickRow("Lock button")
        device.wait(Until.findObject(By.text("Kids lock, unlock by tapping the four corners")), 2_000)!!.click()
        SystemClock.sleep(800)
        shot("13-kids-lock-setting")
        try {
            play("clip420.mp4")
            assertTrue(waitFor(6_000) { landscape() })
            tapControl(By.desc("Lock controls"))
            SystemClock.sleep(500)
            device.click(device.displayWidth / 2, device.displayHeight / 2)
            SystemClock.sleep(500)
            assertFalse("kids lock showed an unlock button", device.hasObject(By.text("Unlock")))
            shot("14-kids-locked")
            assertTrue(locked())
            corners(listOf(10 to 10, 10 to 90, 90 to 90, 90 to 10))
            assertTrue("counter-clockwise corners unlocked", locked())
            corners(listOf(10 to 10, 90 to 10, 90 to 90)); SystemClock.sleep(5_500); corners(listOf(10 to 90))
            assertTrue("corners over more than 5 s unlocked", locked())
            corners(listOf(10 to 10, 90 to 10, 90 to 90, 10 to 90))
            assertTrue("four corners clockwise within 5 s did not unlock", waitFor(1_500) { !locked() })
            shot("15-kids-unlocked")
        } finally {
            closePlayer()
            openPlaybackSettings(); clickRow("Lock button")
            device.wait(Until.findObject(By.text("Lock, unlock with a button")), 2_000)?.click()
            SystemClock.sleep(800); device.pressBack(); device.pressBack()
        }
    }

    @Ears("US-58.1", "US-58.2", "US-58.3")
    @Test fun g_pipOnLeavingWithActionsSubtitlesAndExpand() {
        play("withsubs.mp4")
        SystemClock.sleep(2_000)
        device.pressHome()
        assertTrue("no picture-in-picture after home", waitFor(5_000) { player()?.isInPictureInPictureMode == true })
        // The window settles into its corner after entering, so read its bounds once it has.
        SystemClock.sleep(1_000)
        val b = retrying { device.wait(Until.findObject(By.pkg("app.player")), 3_000)!!.visibleBounds }
        assertNotNull("no PiP window", b)
        b!!
        val ratio = b.width().toFloat() / b.height()
        log("pip window $b, ratio $ratio")
        shot("20-pip")
        assertTrue("pip ratio $ratio is not the video's 16:9", ratio in 1.6f..1.95f)
        // US-58.2: subtitles stay on (the track is still selected in PiP; the frame shows it).
        val sid = PlayerSessions.current.value!!.observe("sid", PropertyType.Text)
        assertTrue("no subtitle track selected in PiP, sid ${sid.value}", waitFor(2_000) { sid.value.let { it != null && it != "no" } })
        // Tap the window for the system's PiP menu with our actions. The menu hides by itself
        // after a few seconds and a tap during its animation can miss: tap until all three show.
        val actions = listOf("Back 10 seconds", "Pause", "Forward 10 seconds")
        var shown = false
        for (i in 1..3) {
            if (!device.hasObject(By.desc("Pause"))) device.click(b.centerX(), b.centerY())
            shown = actions.all { device.wait(Until.hasObject(By.desc(it)), 2_000) }
            if (shown) break
        }
        shot("21-pip-menu")
        assertTrue("PiP actions missing, shown: ${actions.filter { device.hasObject(By.desc(it)) }}", shown)
        val p0 = state()!!.positionMs
        pipAction("Pause", b)
        assertTrue("PiP pause did not pause", waitFor(2_000) { state()!!.paused })
        pipAction("Forward 10 seconds", b)
        assertTrue("PiP forward did not seek", waitFor(2_000) { state()!!.positionMs >= p0 + 8_000 })
        shot("22-pip-paused")
        pipAction("Play", b)
        // US-58.3: expand returns to the full player, same file, same position (no reopen).
        val uri = state()!!.uri
        val before = state()!!.positionMs
        val expand = device.wait(Until.findObject(By.descStartsWith("Expand")), 1_500)
            ?: device.wait(Until.findObject(By.descContains("ull screen")), 1_500)
        if (expand != null) expand.click() else device.click(b.centerX(), b.centerY())
        assertTrue("not back to the full player", waitFor(5_000) { player()?.isInPictureInPictureMode == false && player()?.hasWindowFocus() == true })
        val after = state()!!.positionMs
        log("expand: $before -> $after ms, uri ${state()!!.uri}")
        assertEquals(uri, state()!!.uri)
        assertTrue("position jumped on expand: $before -> $after", after in before - 500..before + 3_000)
        shot("23-expanded")
    }

    @Ears("US-58.4")
    @Test fun h_closingPipStopsAndSavesResume() {
        val uri = play("withsubs.mp4")
        SystemClock.sleep(4_000)
        device.pressHome()
        assertTrue(waitFor(5_000) { player()?.isInPictureInPictureMode == true })
        SystemClock.sleep(1_000)
        val b = retrying { device.wait(Until.findObject(By.pkg("app.player")), 3_000)!!.visibleBounds }!!
        device.click(b.centerX(), b.centerY())
        SystemClock.sleep(1_500)
        val close = device.wait(Until.findObject(By.descStartsWith("Close")), 2_000)
            ?: device.wait(Until.findObject(By.descStartsWith("Dismiss")), 1_000)
        assertNotNull("no close button on the PiP window", close)
        // PiP keeps playing until the window is really gone, so the position that counts is the
        // last one seen before the player went away.
        var at = state()!!.positionMs
        val gone = { state()?.positionMs?.let { at = it }; player() == null }
        close!!.click()
        // The system can take several seconds to take the window down on the hosted emulator, and
        // a tap during its menu animation can miss: tap Close again if the window is still up.
        if (!waitFor(4_000, gone) && player()?.isInPictureInPictureMode == true) {
            device.wait(Until.findObject(By.descStartsWith("Close")), 2_000)?.let { c -> retrying { c.click() } }
        }
        assertTrue("player still there after closing PiP", waitFor(10_000, gone))
        // The save on leaving is written off the main thread; give it a moment after the close.
        var row = runBlocking { ResumePoints(context).get(uri.toString()) }
        waitFor(3_000) {
            row = runBlocking { ResumePoints(context).get(uri.toString()) }
            row?.let { it.positionMs >= at - 1_000 } == true
        }
        log("after closing PiP at $at ms: row $row")
        val saved = row
        assertNotNull("no resume point after closing PiP", saved)
        assertTrue("resume point ${saved!!.positionMs} not near $at", saved.positionMs in at - 1_000..at + 1_000)
        shot("24-pip-closed")
    }

    // --- helpers

    /** Taps a PiP menu action. The system hides the menu after a few seconds: tap the window to bring it back first. */
    private fun pipAction(desc: String, window: android.graphics.Rect) {
        if (!device.hasObject(By.desc(desc))) {
            device.click(window.centerX(), window.centerY())
            device.wait(Until.hasObject(By.desc(desc)), 2_000)
        }
        // Found and tapped at once, so the menu cannot change between the two.
        retrying { device.findObject(By.desc(desc))!!.click() }
    }

    private fun file(name: String): File = File(context.filesDir, name).also { f ->
        if (!f.exists()) instr.context.assets.open(name).use { i -> f.outputStream().use { i.copyTo(it) } }
    }

    private fun play(name: String): Uri {
        val uri = Uri.fromFile(file(name))
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "video/mp4")
            .setClassName(context, "app.player.feature.player.PlayerActivity")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        context.startActivity(intent)
        assertTrue("$name never showed video", waitFor(10_000) { state()?.let { it.video != null && it.uri?.endsWith(name) == true } == true })
        return uri
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

    private fun locked(): Boolean = Playback.locked.value

    private fun landscape() = device.displayRotation % 2 == 1

    private fun closePlayer() {
        player()?.let { a -> instr.runOnMainSync { a.finish() } }
        waitFor(5_000) { player() == null }
    }

    /** Shows the controls and taps [sel] once it stands still ([PlayerControls]). */
    private fun tapControl(sel: BySelector) = controls.tap(sel)

    private fun openPlaybackSettings() {
        context.startActivity(
            Intent(Intent.ACTION_MAIN).setClassName(context, "app.player.MainActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
        )
        device.wait(Until.findObject(By.desc("More options")), 8_000)!!.click()
        device.wait(Until.findObject(By.text("Settings")), 3_000)!!.click()
        findText("Playback")!!.click()
        SystemClock.sleep(800)
    }

    private fun clickRow(label: String) {
        val row = findText(label)
        assertNotNull("settings row missing: $label", row)
        row!!.click()
        SystemClock.sleep(600)
    }

    private fun findText(label: String): UiObject2? =
        device.wait(Until.findObject(By.text(label)), 2_000)
            ?: device.findObject(By.scrollable(true))?.scrollUntil(Direction.DOWN, Until.findObject(By.text(label)))

    private fun corners(fractions: List<Pair<Int, Int>>) {
        for ((x, y) in fractions) {
            device.click(device.displayWidth * x / 100, device.displayHeight * y / 100)
            SystemClock.sleep(300)
        }
        SystemClock.sleep(500)
    }

    private fun <T> retrying(block: () -> T): T? {
        repeat(3) { try { return block() } catch (_: StaleObjectException) { SystemClock.sleep(200) } }
        return null
    }

    private fun waitFor(ms: Long = 8_000, check: () -> Boolean): Boolean {
        val end = SystemClock.uptimeMillis() + ms
        while (SystemClock.uptimeMillis() < end) {
            if (runCatching(check).getOrDefault(false)) return true
            SystemClock.sleep(100)
        }
        return runCatching(check).getOrDefault(false)
    }

    private fun shot(name: String) {
        evidenceShot(name)
        log("shot $name")
    }

    private fun log(s: String) = Log.i("P1.14", s)
}

