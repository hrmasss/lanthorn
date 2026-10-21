package app.player.playback

import android.Manifest
import android.app.Activity
import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.PlaybackState as SessionState
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
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import app.player.core.data.history.ResumePoints
import app.player.core.engine.PlaybackService
import app.player.core.engine.PlaybackState
import app.player.core.engine.PlayerSessions
import app.player.core.playerapi.PlayerIntents
import app.player.core.testing.Ears
import app.player.core.testing.evidenceShot
import app.player.feature.playback.OnLeave
import app.player.feature.playback.Playback
import app.player.feature.playback.PlaybackPrefs
import app.player.feature.playback.VolumeKeys
import kotlinx.coroutines.runBlocking
import org.junit.After
import kotlin.math.roundToInt
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
 * P1.15 through the real app (in-process): background play and its media notification (US-59),
 * headset and media keys (US-31.1, US-31.4), audio focus (US-31.3), pause when covered (US-60.2)
 * and volume keys (US-77). Settings are written through playback's own prefs.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class BackgroundMediaUiTest {
    private val instr = InstrumentationRegistry.getInstrumentation()
    private val context = instr.targetContext
    private val device = UiDevice.getInstance(instr)
    private val prefs = PlaybackPrefs(context)
    private val audio = context.getSystemService(AudioManager::class.java)
    private val shots = File(context.getExternalFilesDir(null), "p115").apply { mkdirs() }

    @get:Rule val onFailure = object : TestWatcher() {
        override fun failed(e: Throwable?, description: Description) {
            device.takeScreenshot(File(shots, "FAILED-${description.methodName}.png"))
            device.dumpWindowHierarchy(File(shots, "FAILED-${description.methodName}.xml"))
        }
    }

    @Before fun setUp() {
        StrictMode.setVmPolicy(StrictMode.VmPolicy.Builder().build())
        instr.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        device.wakeUp()
        device.setOrientationNatural()
        runBlocking {
            prefs.reset()
            ResumePoints(context).clear()
        }
    }

    @After fun tearDown() {
        device.pressBack()
        // A background test leaves the service holding the engine: let it go too.
        instr.runOnMainSync { PlaybackService.stop(context) }
        closePlayer()
        runBlocking { prefs.reset() }
        device.unfreezeRotation()
    }

    @Ears("US-59.1", "US-59.2")
    @Test fun a_backgroundFromTheMenuKeepsPlayingWithAMediaNotification() {
        play(listOf("clip420.mp4"))
        // On leaving is PiP by default; the menu's switch turns background play on (US-59.1).
        instr.runOnMainSync { Playback.toggleBackgroundPlay() }
        assertTrue("menu switch not on", waitFor(3_000) { Playback.backgroundPlay.value })
        device.pressHome()
        assertTrue("player not stopped after Home", waitFor(5_000) { stage(Stage.STOPPED) })
        val before = state()!!.positionMs
        SystemClock.sleep(3_000)
        val after = state()!!.positionMs
        log("background: $before -> $after ms, paused ${state()?.paused}")
        assertFalse("paused in background", state()!!.paused)
        // The hosted emulator plays at under half speed at times: moving at all is the point.
        assertTrue("position did not move in background: $before -> $after", after - before >= 500)

        // US-59.2: the notification is a media one, tied to the session, with title, picture and buttons.
        val n = waitForValue(5_000) { mediaNotification() }
        assertNotNull("no media notification", n)
        val token = n!!.extras.getParcelable(Notification.EXTRA_MEDIA_SESSION, MediaSession.Token::class.java)
        assertNotNull("notification has no media session", token)
        assertEquals("previous, play/pause, next", 3, n.actions.size)
        val controller = MediaController(context, token!!)
        val meta = controller.metadata!!
        log("metadata: ${meta.getString(MediaMetadata.METADATA_KEY_TITLE)}, ${meta.getLong(MediaMetadata.METADATA_KEY_DURATION)} ms, art ${meta.getBitmap(MediaMetadata.METADATA_KEY_ART)?.width}")
        assertEquals("clip420.mp4", meta.getString(MediaMetadata.METADATA_KEY_TITLE))
        assertTrue("no duration for the seek bar", meta.getLong(MediaMetadata.METADATA_KEY_DURATION) > 0)
        assertNotNull("no cover art or video frame", meta.getBitmap(MediaMetadata.METADATA_KEY_ART))
        val actions = controller.playbackState!!.actions
        for ((name, a) in listOf("seek" to SessionState.ACTION_SEEK_TO, "next" to SessionState.ACTION_SKIP_TO_NEXT, "previous" to SessionState.ACTION_SKIP_TO_PREVIOUS, "pause" to SessionState.ACTION_PAUSE)) {
            assertTrue("session lacks $name", actions and a != 0L)
        }
        assertEquals(SessionState.STATE_PLAYING, controller.playbackState!!.state)
        device.openNotification()
        device.wait(Until.hasObject(By.text("clip420.mp4")), 3_000)
        shot("01-media-notification")

        // The lock-screen and notification controls go through the session: pause from there.
        controller.transportControls.pause()
        assertTrue("session pause did not pause", waitFor(3_000) { state()?.paused == true })
        controller.transportControls.seekTo(2_000)
        assertTrue("session seek did not seek", waitFor(3_000) { state()!!.positionMs in 1_500..3_000 })
        shot("02-notification-paused")
        device.pressBack()
    }

    @Ears("US-59.4")
    @Test fun b_onLeavingStopPausesAndSaves() {
        runBlocking { prefs.setOnLeave(OnLeave.Stop) }
        val uri = play(listOf("clip420.mp4"))
        SystemClock.sleep(3_000)
        device.pressHome()
        assertTrue("not paused after leaving", waitFor(5_000) { state()?.paused == true })
        // Frames decoded before the pause can still show just after it: where it stopped is where
        // the position stands still.
        var at = state()!!.positionMs
        waitFor(3_000) { SystemClock.sleep(300); val now = state()!!.positionMs; (now == at).also { at = now } }
        // A row from the regular saves while playing is already there: wait for the one from leaving.
        var row = runBlocking { ResumePoints(context).get(uri.toString()) }
        waitFor(3_000) { row = runBlocking { ResumePoints(context).get(uri.toString()) }; row?.positionMs == at / 1000 * 1000 }
        log("stop on leaving: paused at $at, row $row")
        assertNotNull("no resume point after leaving", row)
        // Rows are whole seconds: the pause's own second.
        assertTrue("row ${row!!.positionMs} not at the pause $at", row!!.positionMs == at / 1000 * 1000)
        SystemClock.sleep(1_500)
        assertEquals("moved while left paused", at, state()!!.positionMs)
    }

    @Ears("US-31.1")
    @Test fun c_headsetOnePressTogglesTwoNextThreePrevious() {
        play(listOf("clip420.mp4", "withsubs.mp4", "longgop.mp4"), index = 1)
        val headset = { n: Int -> repeat(n) { key(KeyEvent.KEYCODE_HEADSETHOOK); SystemClock.sleep(120) } }

        headset(1)
        assertTrue("one press did not pause", waitFor(3_000) { state()?.paused == true })
        headset(1)
        assertTrue("one press did not play", waitFor(3_000) { state()?.paused == false })
        headset(2)
        assertTrue("two presses did not open the next video", waitFor(8_000) { state()?.uri?.endsWith("longgop.mp4") == true })
        // Presses while the next file opens arrive late and split the count: wait for it to show.
        assertTrue("next video never showed", waitFor(8_000) { state()?.firstFrameMs != null })
        SystemClock.sleep(1_000)
        headset(3)
        assertTrue("three presses did not open the previous video", waitFor(8_000) { state()?.uri?.endsWith("withsubs.mp4") == true })
        shot("03-headset-previous")

        // In background the session gets the keys (the activity is stopped).
        runBlocking { prefs.setOnLeave(OnLeave.Background) }
        device.pressHome()
        assertTrue(waitFor(5_000) { stage(Stage.STOPPED) })
        val controller = MediaController(context, waitForValue(5_000) { mediaNotification() }!!.extras.getParcelable(Notification.EXTRA_MEDIA_SESSION, MediaSession.Token::class.java)!!)
        controller.dispatchMediaButtonEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_HEADSETHOOK))
        controller.dispatchMediaButtonEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_HEADSETHOOK))
        assertTrue("a headset press in background did not pause", waitFor(3_000) { state()?.paused == true })
    }

    @Ears("US-31.4")
    @Test fun d_nextAndPreviousSeekWhenSet() {
        runBlocking { prefs.setNextPreviousSeek(true) }
        play(listOf("clip420.mp4", "withsubs.mp4"))
        instr.runOnMainSync { PlayerSessions.current.value!!.pause() }
        waitFor(2_000) { state()?.paused == true }
        val at = state()!!.positionMs
        key(KeyEvent.KEYCODE_MEDIA_NEXT)
        assertTrue("next did not seek forward by the step", waitFor(3_000) { state()!!.positionMs in at + 9_000..at + 11_000 })
        assertTrue("next changed the file", state()!!.uri!!.endsWith("clip420.mp4"))
        val ahead = state()!!.positionMs
        key(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
        assertTrue("previous did not seek back by the step", waitFor(3_000) { state()!!.positionMs in ahead - 11_000..ahead - 9_000 })
        log("next/previous as seek: $at -> $ahead -> ${state()!!.positionMs}")

        runBlocking { prefs.setNextPreviousSeek(false) }
        SystemClock.sleep(300)
        key(KeyEvent.KEYCODE_MEDIA_NEXT)
        assertTrue("with the setting off next did not open the next video", waitFor(8_000) { state()?.uri?.endsWith("withsubs.mp4") == true })
    }

    @Ears("US-31.3")
    @Test fun e_audioFocusLossPausesShortLossComesBack() {
        play(listOf("clip420.mp4"))
        val other = { gain: Int ->
            AudioFocusRequest.Builder(gain)
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build())
                .setOnAudioFocusChangeListener {}
                .build()
        }
        // A call or a navigation prompt: a short loss pauses, and playing comes back after it.
        val call = other(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        assertEquals(AudioManager.AUDIOFOCUS_REQUEST_GRANTED, audio.requestAudioFocus(call))
        assertTrue("a short focus loss did not pause", waitFor(3_000) { state()?.paused == true })
        audio.abandonAudioFocusRequest(call)
        assertTrue("did not play again after the short loss", waitFor(3_000) { state()?.paused == false })

        // Another player takes focus for good: pause, and stay paused.
        val music = other(AudioManager.AUDIOFOCUS_GAIN)
        assertEquals(AudioManager.AUDIOFOCUS_REQUEST_GRANTED, audio.requestAudioFocus(music))
        assertTrue("a focus loss did not pause", waitFor(3_000) { state()?.paused == true })
        audio.abandonAudioFocusRequest(music)
        SystemClock.sleep(2_000)
        assertTrue("played again by itself after a permanent loss", state()!!.paused)
    }

    @Ears("US-60.2")
    @Test fun f_pauseWhenCoveredOnlyWhenSet() {
        play(listOf("clip420.mp4"))
        cover()
        assertFalse("paused when covered with the setting off", state()!!.paused)
        device.pressBack()
        assertTrue(waitFor(5_000) { stage(Stage.RESUMED) })

        runBlocking { prefs.setPauseWhenCovered(true) }
        SystemClock.sleep(300)
        cover()
        assertTrue("not paused when covered", waitFor(3_000) { state()?.paused == true })
        shot("04-covered-paused")
        device.pressBack()
    }

    @Ears("US-77.1", "US-77.2")
    @Test fun g_volumeKeysChangeBrightnessWhenSetSystemInPip() {
        runBlocking { prefs.setVolumeKeys(VolumeKeys.Brightness) }
        play(listOf("clip420.mp4"))
        // The player screen's own chrome moves the window brightness 5% a key (the level bar: GestureTest, shot here).
        val activity = player()!!
        fun brightness(): Int {
            var b = -1f
            instr.runOnMainSync { b = activity.window.attributes.screenBrightness }
            return (b * 100).roundToInt()
        }
        instr.runOnMainSync { activity.window.attributes = activity.window.attributes.apply { screenBrightness = 0.5f } }
        val volume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        key(KeyEvent.KEYCODE_VOLUME_UP)
        assertTrue("volume up did not step brightness to 55%, at ${brightness()}", waitFor(2_000) { brightness() == 55 })
        shot("07-volume-key-brightness")
        key(KeyEvent.KEYCODE_VOLUME_DOWN)
        key(KeyEvent.KEYCODE_VOLUME_DOWN)
        assertTrue("volume down did not step brightness to 45%, at ${brightness()}", waitFor(2_000) { brightness() == 45 })
        assertEquals("media volume changed", volume, audio.getStreamVolume(AudioManager.STREAM_MUSIC))

        // In PiP the keys are the system's (US-77.2).
        instr.runOnMainSync { Playback.pip() }
        assertTrue("no PiP", waitFor(5_000) { player()?.isInPictureInPictureMode == true })
        val before = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        val down = before > 0
        // A press can be swallowed while the system's volume panel comes up: up to three.
        var changed = false
        repeat(3) {
            if (changed) return@repeat
            device.pressKeyCode(if (down) KeyEvent.KEYCODE_VOLUME_DOWN else KeyEvent.KEYCODE_VOLUME_UP)
            changed = waitFor(2_000) { audio.getStreamVolume(AudioManager.STREAM_MUSIC) != before }
        }
        assertTrue("volume key in PiP did not change the volume", changed)
        assertEquals("brightness stepped in PiP", 45, brightness())
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, before, 0)
    }

    @Ears("US-59.1")
    @Test fun h_backWithBackgroundOnClosesThePlayer() {
        play(listOf("clip420.mp4"))
        instr.runOnMainSync { Playback.toggleBackgroundPlay() }
        assertTrue("menu switch not on", waitFor(3_000) { Playback.backgroundPlay.value })
        // Back closes the player: playback stops, no service is left holding it, nothing crashes.
        device.pressBack()
        device.pressBack()
        assertTrue("player still there after Back", waitFor(5_000) { player() == null })
        assertTrue("engine still live after Back", waitFor(3_000) { PlayerSessions.current.value == null })
        assertTrue("media notification left after Back", waitFor(3_000) { mediaNotification() == null })
    }

    // --- helpers

    private fun file(name: String): File = File(context.filesDir, name).also { f ->
        if (!f.exists()) instr.context.assets.open(name).use { i -> f.outputStream().use { i.copyTo(it) } }
    }

    private fun play(names: List<String>, index: Int = 0): Uri {
        val uris = names.map { Uri.fromFile(file(it)) }
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uris[index], "video/mp4")
            .setClassName(context, "app.player.feature.player.PlayerActivity")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        context.startActivity(PlayerIntents.withQueue(intent, uris, index))
        // Until the first frame the engine thread is busy opening, and a pause or key waits behind it.
        assertTrue("${names[index]} never played", waitFor(15_000) { state()?.let { it.video != null && !it.paused && it.firstFrameMs != null && it.uri?.endsWith(names[index]) == true } == true })
        return uris[index]
    }

    /** A key as the activity gets it from a headset or keyboard. */
    private fun key(code: Int) {
        instr.sendKeySync(KeyEvent(KeyEvent.ACTION_DOWN, code))
        instr.sendKeySync(KeyEvent(KeyEvent.ACTION_UP, code))
    }

    /** The share sheet over the player: a window on top that does not stop it. */
    private fun cover() {
        val a = player()!!
        instr.runOnMainSync {
            a.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "x"), null))
        }
        assertTrue("player not paused under the sheet", waitFor(5_000) { stage(Stage.PAUSED) })
        SystemClock.sleep(1_000)
    }

    private fun mediaNotification(): Notification? =
        context.getSystemService(NotificationManager::class.java).activeNotifications
            .map { it.notification }
            .firstOrNull { it.extras.containsKey(Notification.EXTRA_MEDIA_SESSION) }

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

    private fun stage(s: Stage): Boolean {
        var at = false
        instr.runOnMainSync {
            at = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(s)
                .any { it.javaClass.name == "app.player.feature.player.PlayerActivity" }
        }
        return at
    }

    private fun closePlayer() {
        player()?.let { a -> instr.runOnMainSync { a.finish() } }
        waitFor(5_000) { player() == null }
        waitFor(3_000) { PlayerSessions.current.value == null }
    }

    private fun waitFor(ms: Long = 8_000, check: () -> Boolean): Boolean {
        val end = SystemClock.uptimeMillis() + ms
        while (SystemClock.uptimeMillis() < end) {
            if (runCatching(check).getOrDefault(false)) return true
            SystemClock.sleep(100)
        }
        return runCatching(check).getOrDefault(false)
    }

    private fun <T> waitForValue(ms: Long, get: () -> T?): T? {
        var v: T? = null
        waitFor(ms) { v = get(); v != null }
        return v
    }

    private fun shot(name: String) {
        evidenceShot(name)
        log("shot $name")
    }

    private fun log(s: String) = Log.i("P1.15", s)
}
