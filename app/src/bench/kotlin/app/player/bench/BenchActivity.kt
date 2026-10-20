package app.player.bench

import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import app.player.core.designsystem.PlayerTheme
import app.player.core.engine.DecoderMode
import app.player.core.engine.PlayerEngine
import app.player.feature.player.PlayerScreen
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.random.Random

/**
 * Debug-only bench mode, driven by perf/bench.sh over adb:
 *
 *   am start -n app.player/app.player.bench.BenchActivity \
 *     --es scenario playback|seek|scrub --es file <path> [--ei seconds 300] [--ei runs 20] [--es decoder AUTO]
 *
 * Plays through the real player screen. Writes one JSON object to
 * <external files>/bench/<scenario>-<file name>.json and logs it under tag "Bench".
 */
class BenchActivity : ComponentActivity() {
    private lateinit var engine: PlayerEngine
    private val controls = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.getInsetsController(window, window.decorView).hide(WindowInsetsCompat.Type.systemBars())
        val scenario = intent.getStringExtra("scenario") ?: "playback"
        val file = intent.getStringExtra("file") ?: return finishWith(JSONObject().put("error", "no file"))
        val decoder = DecoderMode.valueOf(intent.getStringExtra("decoder") ?: "AUTO")
        engine = PlayerEngine(this)
        engine.setDecoder(decoder)
        setContent { PlayerTheme(darkTheme = true) { PlayerScreen(engine, controlsState = controls) } }

        lifecycleScope.launch {
            engine.open(Uri.fromFile(File(file)))
            val loaded = withTimeoutOrNull(10_000) { engine.state.first { it.firstFrameMs != null || it.error != null } }
            val result = JSONObject()
                .put("scenario", scenario)
                .put("file", File(file).name)
                .put("decoderMode", decoder.name)
                .put("firstFrameMs", loaded?.firstFrameMs ?: JSONObject.NULL)
            if (loaded?.firstFrameMs == null) {
                result.put("error", loaded?.error ?: "no first frame in 10 s")
            } else {
                when (scenario) {
                    "playback" -> playback(result, intent.getIntExtra("seconds", 300))
                    "seek" -> seek(result, intent.getIntExtra("runs", 20))
                    "scrub" -> scrub(result, intent.getIntExtra("seconds", 5))
                    else -> result.put("error", "unknown scenario $scenario")
                }
            }
            val s = engine.state.value
            result.put("activeDecoder", s.activeDecoder.label)
            s.video?.let { result.put("video", "${it.codec} ${it.width}x${it.height} ${"%.2f".format(it.fps)} fps ${it.pixelFormat}") }
            finishWith(result)
        }
    }

    /** NF-3, NF-4, NF-7: play, open and close the overlay every 10 s, count dropped frames. */
    private suspend fun playback(out: JSONObject, seconds: Int) {
        engine.seekTo(0)
        val start = engine.stats()
        val t0 = SystemClock.elapsedRealtime()
        while (SystemClock.elapsedRealtime() - t0 < seconds * 1000L) {
            delay(10_000)
            controls.value = !controls.value
            if (engine.state.value.ended) break
        }
        val end = engine.stats() ?: return
        val frames = end.frameNumber - (start?.frameNumber ?: 0)
        val dropped = (end.voDropped - (start?.voDropped ?: 0)) + (end.decoderDropped - (start?.decoderDropped ?: 0))
        out.put("seconds", (SystemClock.elapsedRealtime() - t0) / 1000.0)
            .put("frames", frames)
            .put("dropped", dropped)
            .put("voDropped", end.voDropped)
            .put("decoderDropped", end.decoderDropped)
            .put("delayed", end.delayed)
            .put("mistimed", end.mistimed)
            .put("dropPercent", if (frames > 0) 100.0 * dropped / frames else JSONObject.NULL)
            .put("displayFps", end.displayFps)
    }

    /** NF-6: exact seeks to random points, time from command to the new frame being ready. */
    private suspend fun seek(out: JSONObject, runs: Int) {
        val duration = engine.state.value.durationMs
        val rnd = Random(42)
        val times = JSONArray()
        repeat(runs) {
            val before = engine.state.value.restarts
            val target = (rnd.nextDouble(0.05, 0.95) * duration).toLong()
            val t0 = SystemClock.elapsedRealtime()
            engine.seekTo(target, exact = true)
            val ok = withTimeoutOrNull(5_000) { engine.state.first { it.restarts > before } }
            times.put(if (ok != null) SystemClock.elapsedRealtime() - t0 else -1)
            delay(500)
        }
        out.put("seekMs", times).put("seekP50", percentile(times, 50)).put("seekP90", percentile(times, 90))
    }

    /** NF-6: keyframe seeks at 30 per second, like a finger dragging the bar; count frames shown per second. */
    private suspend fun scrub(out: JSONObject, seconds: Int) {
        engine.pause()
        val duration = engine.state.value.durationMs
        val before = engine.state.value.restarts
        val t0 = SystemClock.elapsedRealtime()
        var i = 0
        while (SystemClock.elapsedRealtime() - t0 < seconds * 1000L) {
            val frac = (i % 300) / 300.0
            engine.seekTo((0.1 * duration + frac * 0.8 * duration).toLong(), exact = false)
            i++
            delay(33)
        }
        delay(300)
        val elapsed = (SystemClock.elapsedRealtime() - t0) / 1000.0
        val updates = engine.state.value.restarts - before
        out.put("seeksIssued", i).put("previewUpdates", updates).put("updatesPerSecond", updates / elapsed)
    }

    private fun percentile(a: JSONArray, p: Int): Long {
        val v = (0 until a.length()).map { a.getLong(it) }.filter { it >= 0 }.sorted()
        if (v.isEmpty()) return -1
        return v[((p / 100.0) * (v.size - 1)).toInt()]
    }

    private fun finishWith(result: JSONObject) {
        Log.i("Bench", result.toString())
        val dir = File(getExternalFilesDir(null), "bench").apply { mkdirs() }
        File(dir, "${result.optString("scenario", "none")}-${result.optString("file", "none")}.json").writeText(result.toString(2))
        Log.i("Bench", "DONE")
        finish()
    }

    override fun onDestroy() {
        if (::engine.isInitialized) engine.release()
        super.onDestroy()
    }
}
