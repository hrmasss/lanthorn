package app.player

import android.content.Intent
import android.net.TrafficStats
import android.net.Uri
import android.os.Process
import android.os.StrictMode
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import app.player.core.engine.PlayerSessions
import app.player.core.testing.Ears
import app.player.core.testing.evidenceShot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * NF-8 (P2.1): local use sends and receives nothing. The app's own traffic counters, read before
 * and after starting the app, letting the library load, and playing a local file, must not move.
 * The test runs in the app's process, so the counters are the app's.
 */
@RunWith(AndroidJUnit4::class)
class NoTrafficUiTest {
    private val instr = InstrumentationRegistry.getInstrumentation()
    private val context = instr.targetContext
    private val device = UiDevice.getInstance(instr)

    private fun file(name: String): File = File(context.filesDir, name).also { f ->
        if (!f.exists()) instr.context.assets.open(name).use { i -> f.outputStream().use { i.copyTo(it) } }
    }

    private fun waitFor(timeoutMs: Long = 20_000, check: () -> Boolean): Boolean {
        val end = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < end) {
            if (check()) return true
            SystemClock.sleep(50)
        }
        return false
    }

    @Ears("NF-8")
    @Test fun localUseSendsAndReceivesNothing() {
        StrictMode.setVmPolicy(StrictMode.VmPolicy.Builder().build())
        val uid = Process.myUid()
        val tx0 = TrafficStats.getUidTxBytes(uid)
        val rx0 = TrafficStats.getUidRxBytes(uid)
        assertTrue("traffic counters unsupported here", tx0 != TrafficStats.UNSUPPORTED.toLong())

        context.startActivity(context.packageManager.getLaunchIntentForPackage(context.packageName)!!
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        SystemClock.sleep(5000)
        evidenceShot("library-after-start")

        context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(Uri.fromFile(file("withsubs.mp4")), "video/mp4")
            .setClassName(context, "app.player.feature.player.PlayerActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        assertTrue("never played", waitFor { PlayerSessions.current.value?.state?.value?.video != null })
        SystemClock.sleep(5000)
        evidenceShot("playing-local-file")
        device.pressBack(); device.pressBack(); device.pressHome()
        SystemClock.sleep(2000)

        val tx = TrafficStats.getUidTxBytes(uid) - tx0
        val rx = TrafficStats.getUidRxBytes(uid) - rx0
        Log.i("P2.1", "local use: sent $tx bytes, received $rx bytes")
        assertEquals("bytes sent during local use", 0L, tx)
        assertEquals("bytes received during local use", 0L, rx)
    }
}
