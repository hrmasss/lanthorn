package app.player

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiScrollable
import androidx.test.uiautomator.UiSelector
import androidx.test.uiautomator.Until
import app.player.core.data.history.HistoryPrefs
import app.player.core.data.history.ResumePoints
import app.player.core.engine.PlaybackState
import app.player.core.engine.PlayerSessions
import app.player.core.testing.Ears
import app.player.core.testing.evidenceShot
import app.player.feature.library.LibraryPrefs
import app.player.feature.library.LibraryPrefsStore
import app.player.feature.library.network.NetworkPrefs
import app.player.feature.library.settings.Appearance
import app.player.feature.library.settings.AppearancePrefs
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Pause history with real playback (US-73.2) and reset settings through the real Settings screen (US-65.2). */
@RunWith(AndroidJUnit4::class)
class HistoryAndResetUiTest {
    private val instr = InstrumentationRegistry.getInstrumentation()
    private val context = instr.targetContext
    private val device = UiDevice.getInstance(instr)

    @After fun tearDown() {
        runBlocking { HistoryPrefs(context).setPaused(false) }
        instr.runOnMainSync {
            val reg = ActivityLifecycleMonitorRegistry.getInstance()
            listOf(Stage.RESUMED, Stage.PAUSED, Stage.STARTED, Stage.STOPPED)
                .flatMap { reg.getActivitiesInStage(it) }.forEach(Activity::finish)
        }
        SystemClock.sleep(1_000)
    }

    private fun asset(name: String): File = File(context.filesDir, name).also { f ->
        if (!f.exists()) instr.context.assets.open(name).use { i -> f.outputStream().use { o -> i.copyTo(o) } }
    }

    private fun state(): PlaybackState? = PlayerSessions.current.value?.state?.value

    private fun waitFor(ms: Long, check: () -> Boolean): Boolean {
        val end = SystemClock.uptimeMillis() + ms
        while (SystemClock.uptimeMillis() < end) {
            if (runCatching(check).getOrDefault(false)) return true
            SystemClock.sleep(100)
        }
        return runCatching(check).getOrDefault(false)
    }

    private fun tap(sel: BySelector) {
        device.wait(Until.findObject(sel), 15_000)?.click() ?: throw AssertionError("not found: $sel")
    }

    @Ears("US-73.2")
    @Test fun us_73_2_paused_history_records_no_resume_point_or_mark() {
        runBlocking {
            ResumePoints(context).clear()
            HistoryPrefs(context).setPaused(true)
        }
        val uri = Uri.fromFile(asset("longgop.mp4"))
        context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "video/mp4")
            .setClassName(context, "app.player.feature.player.PlayerActivity")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        assertTrue("never played", waitFor(15_000) { state()?.let { !it.paused && it.uri?.endsWith("longgop.mp4") == true } == true })
        // Past two periodic saves, then Back, which saves too when history is on. Saves run on the
        // clock (every 5 s while playing), so wait 12 s of play, not 12 s of video: a slow emulator
        // plays at half speed or less.
        SystemClock.sleep(12_000)
        assertTrue("stopped playing", state()?.paused == false)
        assertTrue("position never moved", state()!!.positionMs >= 2_000)
        device.pressBack()
        SystemClock.sleep(1_500)
        device.pressBack()
        SystemClock.sleep(2_000)
        val rows = runBlocking { ResumePoints(context).all() }
        Log.i("P2.5b", "history paused: resume rows after 12 s of play and Back: $rows")
        // Last-played marks and the resume button come from these rows (US-07.4, US-08): none to show.
        assertEquals(emptyList<Any>(), rows)
    }

    @Ears("US-65.2")
    @Test fun us_65_2_reset_restores_defaults_after_one_confirmation() {
        val library = LibraryPrefsStore(context)
        val appearance = AppearancePrefs(context)
        val network = NetworkPrefs(context)
        runBlocking {
            library.setCacheThumbnails(false)
            library.setNewDays(30)
            library.setResumeButton(false)
            appearance.setDynamicColor(false)
            network.setUserAgent("Custom/1")
            HistoryPrefs(context).setPaused(true)
        }
        try {
            context.startActivity(context.packageManager.getLaunchIntentForPackage(context.packageName)!!
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            tap(By.desc("More options"))
            tap(By.text("Settings"))
            tap(By.text("General"))
            device.wait(Until.hasObject(By.text("Theme")), 5_000)
            UiScrollable(UiSelector().scrollable(true)).scrollIntoView(UiSelector().text("Reset settings"))
            tap(By.text("Reset settings"))
            assertTrue("no confirmation", device.wait(Until.hasObject(By.textStartsWith("Put every setting back")), 5_000))
            evidenceShot("reset-confirm")
            // Nothing reset before the one confirmation.
            assertFalse(runBlocking { library.prefs.first().cacheThumbnails })
            tap(By.text("Reset"))
            assertTrue("not reset", waitFor(5_000) { runBlocking { library.prefs.first().cacheThumbnails } })
            SystemClock.sleep(500)
            runBlocking {
                val p = library.prefs.first()
                assertEquals(LibraryPrefs().copy(filter = p.filter, recentSearches = p.recentSearches), p)
                assertEquals(Appearance(), appearance.appearance.first())
                assertEquals("", network.userAgent.first())
                assertFalse(HistoryPrefs(context).isPaused())
            }
            evidenceShot("reset-done")
        } finally {
            runBlocking { library.reset(); network.setUserAgent("") }
        }
    }
}
