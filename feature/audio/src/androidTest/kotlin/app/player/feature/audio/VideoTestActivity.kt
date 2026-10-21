package app.player.feature.audio

import android.app.Activity
import android.os.Bundle
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import app.player.core.engine.PlayerEngine
import app.player.core.engine.PlayerSessions
import app.player.core.engine.PropertyType
import app.player.core.engine.VideoSurfaceView
import java.io.File

/** A bare surface for the engine, so video and subtitles render like in the player. */
class VideoTestActivity : Activity() {
    lateinit var surface: VideoSurfaceView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        surface = VideoSurfaceView(this)
        setContentView(surface)
    }
}

internal object OnDevice {
    val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    val context get() = instrumentation.targetContext

    /** Copies a test asset to the cache and returns its path. */
    fun asset(name: String): File {
        val out = File(context.cacheDir, name)
        InstrumentationRegistry.getInstrumentation().context.assets.open(name).use { input ->
            out.outputStream().use { input.copyTo(it) }
        }
        return out
    }

    fun <T> main(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    fun acquire(holder: Any, onCreated: (PlayerEngine) -> Unit): PlayerEngine =
        main { PlayerSessions.acquire(context, holder, onCreated) }

    fun release(holder: Any) = main { PlayerSessions.release(holder) }
}

/** Polls [name] until [ok]; returns the ms it took. Fails after [timeoutMs]. */
internal suspend fun <T : Any> PlayerEngine.await(
    name: String,
    type: PropertyType<T>,
    timeoutMs: Long = 5000,
    ok: (T?) -> Boolean,
): Long {
    val t0 = SystemClock.elapsedRealtime()
    var last: T? = null
    while (SystemClock.elapsedRealtime() - t0 < timeoutMs) {
        last = get(name, type)
        if (ok(last)) return SystemClock.elapsedRealtime() - t0
        kotlinx.coroutines.delay(5)
    }
    throw AssertionError("$name never matched in $timeoutMs ms, last $last")
}

/** Results the evidence README quotes, one line each, in logcat under "evidence". */
internal fun evidence(line: String) {
    android.util.Log.i("evidence", line)
}

/**
 * A timing limit from the spec. Asserted on the reference emulator; recorded only when the run says
 * `-e timing lenient` (the shared CI emulator is slower, its numbers make no claim either way).
 */
internal fun assertWithin(what: String, tookMs: Long, limitMs: Long) {
    val lenient = InstrumentationRegistry.getArguments().getString("timing") == "lenient"
    evidence("timing $what: $tookMs ms (limit $limitMs)" + if (lenient && tookMs >= limitMs) ", over, lenient run" else "")
    if (!lenient) org.junit.Assert.assertTrue("$what took $tookMs ms (limit $limitMs)", tookMs < limitMs)
}

/**
 * Runs of several minutes run only when asked (`-e long true`); otherwise the test passes at once
 * and says so. (An assumption failure shows as a failure in the Gradle report, so no Assume.)
 */
internal fun longRun(): Boolean =
    (InstrumentationRegistry.getArguments().getString("long") == "true").also { if (!it) evidence("long run skipped, pass -e long true") }
