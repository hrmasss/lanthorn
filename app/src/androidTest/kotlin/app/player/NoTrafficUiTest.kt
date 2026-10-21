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
import java.net.ServerSocket
import kotlin.concurrent.thread

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

    private fun shell(command: String): String = runCatching {
        android.os.ParcelFileDescriptor.AutoCloseInputStream(instr.uiAutomation.executeShellCommand(command)).bufferedReader().readText()
    }.getOrDefault("")

    /** Open sockets in this process (app and test share it), with their /proc/net rows where readable. */
    private fun sockets(): List<String> {
        val inodes = File("/proc/self/fd").listFiles().orEmpty().mapNotNull { f ->
            runCatching { android.system.Os.readlink(f.path) }.getOrNull()?.takeIf { it.startsWith("socket:[") }
                ?.removePrefix("socket:[")?.removeSuffix("]")
        }.toSet()
        val rows = listOf("tcp", "tcp6", "udp", "udp6", "raw", "raw6", "unix").flatMap { t ->
            runCatching { File("/proc/self/net/$t").readLines().drop(1).map { "$t $it" } }.getOrDefault(emptyList())
        }
        return inodes.map { inode -> rows.firstOrNull { r -> r.split(Regex("\\s+")).contains(inode) }?.trim() ?: "socket $inode (no /proc/net row)" }
    }

    private fun settle(uid: Int) {
        var last = TrafficStats.getUidRxBytes(uid) + TrafficStats.getUidTxBytes(uid)
        var stillSince = SystemClock.elapsedRealtime()
        val end = stillSince + 20_000
        while (SystemClock.elapsedRealtime() < end && SystemClock.elapsedRealtime() - stillSince < 3_000) {
            SystemClock.sleep(200)
            val now = TrafficStats.getUidRxBytes(uid) + TrafficStats.getUidTxBytes(uid)
            if (now != last) {
                Log.i("P2.1", "counters moved by ${now - last} bytes before the baseline")
                last = now
                stillSince = SystemClock.elapsedRealtime()
            }
        }
    }

    @Ears("NF-8", "US-72.3")
    @Test fun localUseSendsAndReceivesNothing() {
        StrictMode.setVmPolicy(StrictMode.VmPolicy.Builder().build())
        val uid = Process.myUid()
        assertTrue("traffic counters unsupported here", TrafficStats.getUidTxBytes(uid) != TrafficStats.UNSUPPORTED.toLong())
        // The baseline waits until the counters hold still for 3 s, so a late packet from an earlier
        // test in this process is not counted as local use.
        settle(uid)
        val tx0 = TrafficStats.getUidTxBytes(uid)
        val rx0 = TrafficStats.getUidRxBytes(uid)
        sockets().filterNot { it.startsWith("unix") }.forEach { Log.i("P2.1", "baseline socket: $it") }
        // Logs the moment any counter moves, so a failure lines up with the logcat around it.
        val sampling = java.util.concurrent.atomic.AtomicBoolean(true)
        val sampler = kotlin.concurrent.thread(isDaemon = true, name = "nf8-sampler") {
            var last = longArrayOf(tx0, rx0, TrafficStats.getUidTxPackets(uid), TrafficStats.getUidRxPackets(uid))
            while (sampling.get()) {
                SystemClock.sleep(200)
                val now = longArrayOf(TrafficStats.getUidTxBytes(uid), TrafficStats.getUidRxBytes(uid), TrafficStats.getUidTxPackets(uid), TrafficStats.getUidRxPackets(uid))
                if (!now.contentEquals(last)) {
                    Log.i("P2.1", "moved: tx +${now[0] - last[0]} B/${now[2] - last[2]} pk, rx +${now[1] - last[1]} B/${now[3] - last[3]} pk")
                    sockets().filterNot { it.startsWith("unix") }.forEach { Log.i("P2.1", "socket now: $it") }
                    last = now
                }
            }
        }

        context.startActivity(context.packageManager.getLaunchIntentForPackage(context.packageName)!!
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        Log.i("P2.1", "step: library started")
        SystemClock.sleep(5000)
        evidenceShot("library-after-start")
        Log.i("P2.1", "step: opening the local file")

        context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(Uri.fromFile(file("withsubs.mp4")), "video/mp4")
            .setClassName(context, "app.player.feature.player.PlayerActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        assertTrue("never played", waitFor { PlayerSessions.current.value?.state?.value?.video != null })
        SystemClock.sleep(5000)
        evidenceShot("playing-local-file")
        Log.i("P2.1", "step: leaving")
        device.pressBack(); device.pressBack(); device.pressHome()
        SystemClock.sleep(2000)

        sampling.set(false); sampler.join(1_000)
        val tx = TrafficStats.getUidTxBytes(uid) - tx0
        val rx = TrafficStats.getUidRxBytes(uid) - rx0
        Log.i("P2.1", "local use: sent $tx bytes, received $rx bytes")
        // If anything moved, say where: the uid's sockets and its network stats by tag.
        if (tx != 0L || rx != 0L) {
            for (cmd in listOf("cat /proc/net/tcp /proc/net/tcp6 /proc/net/udp /proc/net/udp6", "dumpsys netstats detail")) {
                shell(cmd).lineSequence().filter { " $uid " in it || "uid=$uid " in it }.take(60).forEach { Log.i("P2.1", "$cmd: $it") }
            }
        }
        assertEquals("bytes sent during local use", 0L, tx)
        assertEquals("bytes received during local use", 0L, rx)
    }

    /**
     * Positive control for the test above: a stream the viewer starts does move the same counters,
     * so a zero there means no traffic, not counters that never move. The stream comes from a small
     * HTTP server in this test, on 127.0.0.1.
     */
    @Ears("NF-8")
    @Test fun userStartedStreamMovesTheCounters() {
        StrictMode.setVmPolicy(StrictMode.VmPolicy.Builder().build())
        val bytes = file("clip420.mp4").readBytes()
        val server = ServerSocket(0)
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                thread(isDaemon = true) {
                    socket.use { s ->
                        val input = s.getInputStream().bufferedReader()
                        val range = generateSequence { input.readLine()?.takeIf { it.isNotEmpty() } }
                            .firstOrNull { it.startsWith("Range:", ignoreCase = true) }
                        val from = range?.substringAfter("bytes=")?.substringBefore("-")?.toIntOrNull() ?: 0
                        val head = if (from > 0) "HTTP/1.1 206 Partial Content\r\nContent-Range: bytes $from-${bytes.size - 1}/${bytes.size}\r\n"
                            else "HTTP/1.1 200 OK\r\n"
                        val out = s.getOutputStream()
                        out.write((head + "Content-Type: video/mp4\r\nAccept-Ranges: bytes\r\nContent-Length: ${bytes.size - from}\r\nConnection: close\r\n\r\n").toByteArray())
                        runCatching { out.write(bytes, from, bytes.size - from); out.flush() }
                    }
                }
            }
        }
        val uid = Process.myUid()
        val tx0 = TrafficStats.getUidTxBytes(uid)
        val rx0 = TrafficStats.getUidRxBytes(uid)
        context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse("http://127.0.0.1:${server.localPort}/clip420.mp4"), "video/mp4")
            .setClassName(context, "app.player.feature.player.PlayerActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        assertTrue("stream never played", waitFor { PlayerSessions.current.value?.state?.value?.video != null })
        SystemClock.sleep(3000)
        evidenceShot("playing-user-started-stream")
        device.pressBack(); device.pressBack()
        SystemClock.sleep(2000)
        server.close()
        val tx = TrafficStats.getUidTxBytes(uid) - tx0
        val rx = TrafficStats.getUidRxBytes(uid) - rx0
        Log.i("P2.1", "user-started stream: sent $tx bytes, received $rx bytes, file ${bytes.size} bytes")
        assertTrue("counters did not move for a stream: sent $tx, received $rx", rx > 0 && tx > 0)
    }
}
