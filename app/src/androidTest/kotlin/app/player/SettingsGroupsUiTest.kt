package app.player

import android.content.Intent
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.rules.Timeout
import org.junit.runner.Description
import org.junit.runner.RunWith
import java.io.File

/**
 * The Subtitle and Audio groups (subs-audio, P1.28 and P1.31) inside the app's real Settings
 * screen. Lives in the app module so feature androidTests never need the app APK.
 * Screenshots go to /sdcard/Android/data/app.player/files/gate/.
 */
@RunWith(AndroidJUnit4::class)
class SettingsGroupsUiTest {
    private val instr = InstrumentationRegistry.getInstrumentation()
    private val context = instr.targetContext
    private val device = UiDevice.getInstance(instr)
    private val shots = File(context.getExternalFilesDir(null), "gate").apply { mkdirs() }

    @get:Rule val timeout: Timeout = Timeout.seconds(120)

    @get:Rule val onFailure = object : TestWatcher() {
        override fun failed(e: Throwable?, description: Description) {
            device.takeScreenshot(File(shots, "FAILED-${description.methodName}.png"))
            device.dumpWindowHierarchy(File(shots, "FAILED-${description.methodName}.xml"))
        }
    }

    private fun tap(sel: BySelector) {
        device.wait(Until.findObject(sel), 15_000)?.click() ?: throw AssertionError("not found: $sel")
    }

    private fun openSettings() {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)!!
        context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        tap(By.desc("More options"))
        tap(By.text("Settings"))
    }

    private fun shot(name: String) {
        device.takeScreenshot(File(shots, "$name.png"))
    }

    @Test fun subtitleGroupInRealSettings() {
        openSettings()
        device.wait(Until.hasObject(By.text("Subtitle")), 5_000)
        shot("settings-home-with-subtitle")
        tap(By.text("Subtitle"))
        if (!device.wait(Until.hasObject(By.text("Subtitles look like this")), 5_000)) throw AssertionError("no Subtitle group")
        Thread.sleep(1500)
        shot("settings-subtitle-group")
        device.swipe(540, 1800, 540, 600, 30)
        Thread.sleep(800)
        shot("settings-subtitle-group-scrolled")
        Log.i("evidence", "Real Settings screen: Subtitle group listed and opened; screenshots")
    }

    @Test fun audioGroupInRealSettings() {
        openSettings()
        tap(By.text("Audio"))
        if (!device.wait(Until.hasObject(By.text("Default audio delay")), 5_000)) throw AssertionError("no Audio group")
        Thread.sleep(1500)
        shot("audio-settings-real-screen")
        Log.i("evidence", "Real Settings screen: Audio group opened; screenshot")
    }
}
