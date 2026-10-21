package app.player.playback

import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
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
import app.player.core.data.library.LibraryIndex
import app.player.core.engine.PlaybackState
import app.player.core.engine.PlayerEngine
import app.player.core.engine.PlayerSessions
import app.player.core.testing.evidenceShot
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.random.Random

/**
 * Shared steps for playback's UI tests (P2.5e): test media in MediaStore so folder queues work
 * (US-13.3), opening the player, reading playback's log, and waiting without wall-clock asserts.
 * Ports work/evidence/P1.11/session.sh from adb to in-process UiAutomator.
 */
internal class PlaybackUi(private val tag: String) {
    val instr = InstrumentationRegistry.getInstrumentation()
    val context = instr.targetContext
    val device: UiDevice = UiDevice.getInstance(instr)

    /** The CI emulator makes no timing claims: spec time limits are logged there, not asserted. */
    val lenient = InstrumentationRegistry.getArguments().getString("timing") == "lenient"

    fun state(): PlaybackState? = PlayerSessions.current.value?.state?.value

    fun engine(): PlayerEngine? = PlayerSessions.current.value

    fun player(): Activity? {
        var found: Activity? = null
        instr.runOnMainSync {
            val reg = ActivityLifecycleMonitorRegistry.getInstance()
            found = listOf(Stage.RESUMED, Stage.PAUSED, Stage.STARTED, Stage.STOPPED).asSequence()
                .flatMap { reg.getActivitiesInStage(it).asSequence() }
                .firstOrNull { it.javaClass.name == PLAYER }
        }
        return found
    }

    /** Opens [uri] in the player from this app, as the library does. */
    fun open(uri: Uri, type: String = "video/mp4") {
        context.startActivity(
            Intent(Intent.ACTION_VIEW).setDataAndType(uri, type).setClassName(context, PLAYER)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
        )
    }

    /** Opens [uri] and waits until it shows a frame. */
    fun play(uri: Uri, type: String = "video/mp4") {
        open(uri, type)
        check(waitFor(30_000) { state()?.let { it.uri == uri.toString() && it.firstFrameMs != null } == true }) {
            "$uri never showed a frame, state ${state()}"
        }
    }

    fun closePlayer() {
        player()?.let { a -> instr.runOnMainSync { a.finish() } }
        waitFor(10_000) { player() == null }
    }

    /** Back until the player is gone (player-ui may ask for back twice). */
    fun backOutOfPlayer() {
        repeat(3) {
            if (player() == null) return
            device.pressBack()
            waitFor(1_500) { player() == null }
        }
    }

    fun shell(cmd: String): String {
        val pfd = instr.uiAutomation.executeShellCommand(cmd)
        return ParcelFileReader.read(pfd)
    }

    // --- playback's log

    private var markId = 0

    /** A line in the log that [since] reads from. */
    fun mark(): String {
        val m = "mark ${SystemClock.uptimeMillis()}-${markId++}"
        Log.i(LOG_TAG, m)
        return m
    }

    /** Playback's log lines after [mark], with epoch-seconds times. */
    fun since(mark: String): List<String> {
        val all = shell("logcat -d -v epoch -s Playback:V $LOG_TAG:I").lines()
        val at = all.indexOfLast { it.contains(mark) }
        return if (at < 0) emptyList() else all.drop(at + 1).filter { it.contains("Playback:") }
    }

    /** Uris playback opened after [mark], in order. */
    fun opens(mark: String): List<String> =
        since(mark).mapNotNull { l -> l.substringAfter("Playback: open ", "").takeIf { it.isNotEmpty() }?.substringBefore(" item ") }

    fun epochOf(line: String): Double = line.trim().substringBefore(' ').toDouble()

    // --- UI

    /** Shows the controls and taps [sel]; the controls hide after a few seconds, so a few tries. */
    fun tapControl(sel: BySelector) {
        repeat(5) {
            val o = device.findObject(sel) ?: run {
                device.click(device.displayWidth / 2, device.displayHeight / 2)
                device.wait(Until.findObject(sel), 2_000)
            }
            if (o != null && retrying { o.click() } != null) return
        }
        throw AssertionError("control not found: $sel")
    }

    /** Shows the controls if they are hidden. */
    fun showControls() {
        repeat(4) {
            if (device.hasObject(By.desc("More options"))) return
            device.click(device.displayWidth / 2, device.displayHeight / 2)
            if (device.wait(Until.hasObject(By.desc("More options")), 2_000)) return
        }
    }

    fun openPlaybackSettings() {
        context.startActivity(
            Intent(Intent.ACTION_MAIN).setClassName(context, "app.player.MainActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
        )
        device.wait(Until.findObject(By.desc("More options")), 15_000)!!.click()
        device.wait(Until.findObject(By.text("Settings")), 5_000)!!.click()
        findText("Playback")!!.click()
        SystemClock.sleep(800)
    }

    fun findText(label: String): UiObject2? =
        device.wait(Until.findObject(By.text(label)), 3_000)
            ?: device.findObject(By.scrollable(true))?.scrollUntil(Direction.DOWN, Until.findObject(By.text(label)))

    fun <T> retrying(block: () -> T): T? {
        repeat(3) { try { return block() } catch (_: StaleObjectException) { SystemClock.sleep(200) } }
        return null
    }

    fun waitFor(ms: Long = 10_000, check: () -> Boolean): Boolean {
        val end = SystemClock.uptimeMillis() + ms
        while (SystemClock.uptimeMillis() < end) {
            if (runCatching(check).getOrDefault(false)) return true
            SystemClock.sleep(100)
        }
        return runCatching(check).getOrDefault(false)
    }

    /** A spec time limit: asserted on a real device, logged on the CI emulator. */
    fun within(what: String, ms: Long, limitMs: Long, lowMs: Long = 0) {
        val ok = ms in lowMs..limitMs
        log("time: $what $ms ms (limit $lowMs..$limitMs, ${if (ok) "met" else "missed"}${if (lenient) ", lenient" else ""})")
        if (!lenient && !ok) throw AssertionError("$what took $ms ms, limit $lowMs..$limitMs ms")
    }

    fun shot(name: String) {
        evidenceShot(name)
        log("shot $name")
    }

    fun log(s: String) = Log.i(LOG_TAG, "$tag: $s")

    companion object {
        const val PLAYER = "app.player.feature.player.PlayerActivity"
        const val LOG_TAG = "PlaybackUiTest"
    }
}

/** Test media in MediaStore under Movies/PBTest, made once per process. */
internal object TestMedia {
    private const val ROOT = "PBTest"
    private val made = mutableMapOf<String, Uri>()

    /** `<folder>/<name>` -> its MediaStore uri; the library index is refreshed after a batch. */
    @Synchronized
    fun videos(vararg files: Pair<String, String>): List<Uri> {
        val instr = InstrumentationRegistry.getInstrumentation()
        val context = instr.targetContext
        var added = false
        val out = files.map { (folder, name) ->
            made.getOrPut("$folder/$name") {
                added = true
                insert(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), "Movies/$ROOT/$folder/", name, "video/mp4", bytesFor(name))
            }
        }
        if (added) runBlocking { LibraryIndex.get(context).refresh() }
        return out
    }

    @Synchronized
    fun song(): Uri = made.getOrPut("Music/song.m4a") {
        val bytes = InstrumentationRegistry.getInstrumentation().context.assets.open("song.m4a").use { it.readBytes() }
        insert(MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), "Music/$ROOT/", "Song.m4a", "audio/mp4", bytes)
    }

    /** The file on disk behind a MediaStore uri. */
    fun path(uri: Uri): String? {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        return context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }

    /**
     * What each name holds: "Long"/"Step" 60 s, "Part 2" 30 s, "Broken"/"Bad"/"A 2" random bytes,
     * "Codec unknown" a real mp4 whose codec tag no decoder knows, anything else a 4 s clip.
     */
    private fun bytesFor(name: String): ByteArray {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        fun asset(a: String) = assets.open(a).use { it.readBytes() }
        return when {
            name.startsWith("Long") || name.startsWith("Step") -> asset("longgop.mp4")
            name.startsWith("Part 2") -> asset("clip420.mp4")
            name.startsWith("Broken") || name.startsWith("Bad") || name == "A 2.mp4" -> Random(name.hashCode()).nextBytes(200_000)
            name.startsWith("Codec unknown") -> asset("short.mp4").also { replace(it, "avc1", "zq9x"); replace(it, "avcC", "zq9C") }
            else -> asset("short.mp4")
        }
    }

    private fun replace(b: ByteArray, from: String, to: String) {
        val f = from.toByteArray()
        val t = to.toByteArray()
        var i = 0
        while (i <= b.size - f.size) {
            if ((f.indices).all { b[i + it] == f[it] }) { t.copyInto(b, i); i += f.size } else i++
        }
    }

    private fun insert(collection: Uri, relative: String, name: String, mime: String, bytes: ByteArray): Uri {
        val resolver = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
        // A row left by an earlier run on the same device: start clean.
        resolver.query(
            collection, arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.RELATIVE_PATH} = ? AND ${MediaStore.MediaColumns.DISPLAY_NAME} = ?",
            arrayOf(relative, name), null,
        )?.use { c -> while (c.moveToNext()) resolver.delete(Uri.withAppendedPath(collection, c.getLong(0).toString()), null, null) }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relative)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values) ?: error("MediaStore refused $relative$name")
        resolver.openOutputStream(uri)!!.use { it.write(bytes) }
        resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        Log.i(PlaybackUi.LOG_TAG, "media $relative$name -> $uri")
        return uri
    }
}

private object ParcelFileReader {
    fun read(pfd: android.os.ParcelFileDescriptor): String =
        android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes().toString(Charsets.UTF_8) }
}

/** A minimal HTTP server on 127.0.0.1 with byte ranges, for a media link that plays (US-13.2). */
internal class LocalHttpServer(private val file: File) : AutoCloseable {
    private val socket = java.net.ServerSocket(0, 8, java.net.InetAddress.getByName("127.0.0.1"))
    val port: Int get() = socket.localPort
    @Volatile var requests = 0

    private val thread = Thread {
        while (!socket.isClosed) {
            val s = runCatching { socket.accept() }.getOrNull() ?: break
            Thread { runCatching { serve(s) }; runCatching { s.close() } }.start()
        }
    }.apply { isDaemon = true; start() }

    private fun serve(s: java.net.Socket) {
        val input = s.getInputStream().bufferedReader(Charsets.ISO_8859_1)
        val request = input.readLine() ?: return
        var range: String? = null
        while (true) {
            val h = input.readLine() ?: break
            if (h.isEmpty()) break
            if (h.startsWith("Range:", ignoreCase = true)) range = h.substringAfter(':').trim()
        }
        requests++
        val size = file.length()
        var from = 0L
        var to = size - 1
        val partial = range?.startsWith("bytes=") == true
        if (partial) {
            val (a, b) = range!!.removePrefix("bytes=").split('-', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
            from = a.toLongOrNull() ?: 0L
            to = b.toLongOrNull()?.coerceAtMost(size - 1) ?: (size - 1)
        }
        val out = s.getOutputStream().buffered()
        val head = buildString {
            append(if (partial) "HTTP/1.1 206 Partial Content\r\n" else "HTTP/1.1 200 OK\r\n")
            append("Content-Type: video/mp4\r\nAccept-Ranges: bytes\r\nConnection: close\r\n")
            append("Content-Length: ${to - from + 1}\r\n")
            if (partial) append("Content-Range: bytes $from-$to/$size\r\n")
            append("\r\n")
        }
        out.write(head.toByteArray(Charsets.ISO_8859_1))
        if (!request.startsWith("HEAD")) {
            java.io.RandomAccessFile(file, "r").use { raf ->
                raf.seek(from)
                val buf = ByteArray(64 * 1024)
                var left = to - from + 1
                while (left > 0) {
                    val n = raf.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                    if (n <= 0) break
                    out.write(buf, 0, n)
                    left -= n
                }
            }
        }
        out.flush()
    }

    override fun close() {
        runCatching { socket.close() }
        thread.join(1_000)
    }
}
