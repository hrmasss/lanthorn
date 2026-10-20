package app.player.core.engine

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import dev.jdtech.mpv.MPVLib
import dev.jdtech.mpv.MPVLib.MpvEvent
import dev.jdtech.mpv.MPVLib.MpvFormat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The only door into libmpv. One instance per player screen.
 *
 * Threading (docs/specs/03_architecture.md#threading):
 * - Every call into mpv runs on one engine thread, in call order. Public methods return at once.
 * - mpv's own event thread delivers events; they only update [state].
 * - [attachSurface] and [detachSurface] block the caller until mpv has let go of the old surface,
 *   because Android destroys the surface as soon as `surfaceDestroyed` returns.
 */
class PlayerEngine(context: Context) {
    private val appContext = context.applicationContext
    private val thread: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, "engine") }
    private val _state = MutableStateFlow(PlaybackState())
    val state: StateFlow<PlaybackState> = _state.asStateFlow()

    private var mpv: MPVLib? = null
    @Volatile private var openedAt = 0L
    @Volatile private var starting = false
    private var released = false
    private var openFd: android.os.ParcelFileDescriptor? = null

    init {
        thread.execute { create() }
    }

    private fun create() {
        val t0 = SystemClock.elapsedRealtime()
        val lib = MPVLib.create(appContext) ?: run {
            _state.update { it.copy(error = "playback engine failed to start") }
            return
        }
        // Options that must be set before init.
        lib.setOptionString("config", "no")
        lib.setOptionString("config-dir", appContext.filesDir.path)
        lib.setOptionString("load-scripts", "no")
        lib.setOptionString("osc", "no")
        lib.setOptionString("ytdl", "no")
        lib.setOptionString("input-default-bindings", "no")
        lib.setOptionString("idle", "yes")
        lib.setOptionString("keep-open", "yes")
        lib.setOptionString("force-window", "no")
        lib.setOptionString("vo", "gpu-next,gpu")
        lib.setOptionString("gpu-context", "android")
        lib.setOptionString("opengl-es", "yes")
        lib.setOptionString("ao", "audiotrack,opensles")
        lib.setOptionString("hwdec", DecoderMode.AUTO.hwdec)
        lib.setOptionString("hwdec-codecs", "h264,hevc,mpeg4,mpeg2video,vp8,vp9,av1")
        lib.setOptionString("vd-lavc-software-fallback", "yes")
        lib.setOptionString("demuxer-max-bytes", "${64 * 1024 * 1024}")
        lib.setOptionString("demuxer-max-back-bytes", "${32 * 1024 * 1024}")
        lib.setOptionString("save-position-on-quit", "no")
        lib.setOptionString("msg-level", "all=warn")
        lib.init()
        lib.addObserver(observer)
        lib.addLogObserver(logObserver)
        OBSERVED.forEach { (name, format) -> lib.observeProperty(name, format) }
        mpv = lib
        Log.i(TAG, "libmpv ready in ${SystemClock.elapsedRealtime() - t0} ms")
    }

    /** Opens a file and starts playback at [startMs]. Accepts content://, file:// and plain paths. */
    fun open(uri: Uri, startMs: Long = 0) {
        openedAt = SystemClock.elapsedRealtime()
        _state.value = PlaybackState(uri = uri.toString(), decoderMode = _state.value.decoderMode)
        post {
            val path = resolve(uri)
            val opts = if (startMs > 0) "start=${startMs / 1000.0}" else ""
            command("loadfile", path, "replace", "-1", opts)
            setProperty("pause", "no")
        }
    }

    fun play() = post { setProperty("pause", "no") }
    fun pause() = post { setProperty("pause", "yes") }
    fun togglePause() = post { command("cycle", "pause") }

    /** Exact seek by default; pass [exact] false while scrubbing for keyframe seeks (NF-6). */
    fun seekTo(positionMs: Long, exact: Boolean = true) = post {
        command("seek", "${positionMs / 1000.0}", if (exact) "absolute+exact" else "absolute+keyframes")
    }

    /** Switches decoder live at the current position. mpv reinitialises only the video decoder. */
    fun setDecoder(mode: DecoderMode) {
        _state.update { it.copy(decoderMode = mode) }
        post { setProperty("hwdec", mode.hwdec) }
    }

    fun attachSurface(surface: Surface) = runAndWait {
        mpv?.attachSurface(surface)
        mpv?.setOptionString("force-window", "yes")
        setProperty("vo", "gpu-next,gpu")
    }

    fun setSurfaceSize(width: Int, height: Int) = post {
        setProperty("android-surface-size", "${width}x$height")
    }

    fun detachSurface() = runAndWait {
        // Setting vo=null returns once the video output has released the surface.
        setProperty("vo", "null")
        mpv?.setOptionString("force-window", "no")
        mpv?.detachSurface()
    }

    fun release() {
        if (released) return
        released = true
        thread.execute {
            mpv?.removeObserver(observer)
            mpv?.removeLogObserver(logObserver)
            mpv?.destroy()
            mpv = null
            closeFd()
        }
        thread.shutdown()
    }

    private fun resolve(uri: Uri): String = when (uri.scheme) {
        "content" -> {
            closeFd()
            val pfd = appContext.contentResolver.openFileDescriptor(uri, "r")
                ?: error("cannot open $uri")
            openFd = pfd
            "fd://${pfd.fd}"
        }
        "file" -> uri.path ?: uri.toString()
        null -> uri.toString()
        else -> uri.toString()
    }

    private fun closeFd() {
        openFd?.close()
        openFd = null
    }

    private fun post(block: () -> Unit) {
        if (released) return
        thread.execute {
            try {
                block()
            } catch (e: Exception) {
                Log.e(TAG, "engine call failed", e)
                _state.update { it.copy(error = e.message) }
            }
        }
    }

    private fun runAndWait(block: () -> Unit) {
        if (released) return
        val done = CountDownLatch(1)
        thread.execute {
            try {
                block()
            } finally {
                done.countDown()
            }
        }
        if (!done.await(SURFACE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) Log.w(TAG, "surface change timed out")
    }

    private fun command(vararg args: String) = mpv?.command(arrayOf(*args))
    private fun setProperty(name: String, value: String) = mpv?.setPropertyString(name, value)

    private fun refreshVideo() {
        val lib = mpv ?: return
        val codec = lib.getPropertyString("video-format") ?: return
        val info = VideoInfo(
            codec = codec,
            width = lib.getPropertyInt("video-params/w") ?: 0,
            height = lib.getPropertyInt("video-params/h") ?: 0,
            fps = lib.getPropertyDouble("container-fps") ?: 0.0,
            pixelFormat = lib.getPropertyString("video-params/hw-pixelformat")
                ?: lib.getPropertyString("video-params/pixelformat"),
        )
        _state.update { it.copy(video = info) }
    }

    private val observer = object : MPVLib.EventObserver {
        override fun eventProperty(property: String) {}

        override fun eventProperty(property: String, value: Long) {
            when (property) {
                "frame-drop-count" -> _state.update { it.copy(droppedFrames = value) }
            }
        }

        override fun eventProperty(property: String, value: Double) {
            when (property) {
                "time-pos" -> _state.update { it.copy(positionMs = (value * 1000).toLong()) }
                "duration" -> _state.update { it.copy(durationMs = (value * 1000).toLong()) }
            }
        }

        override fun eventProperty(property: String, value: Boolean) {
            when (property) {
                "pause" -> _state.update { it.copy(paused = value) }
                "paused-for-cache", "seeking" -> _state.update { it.copy(buffering = value) }
                "eof-reached" -> _state.update { it.copy(ended = value) }
            }
        }

        override fun eventProperty(property: String, value: String) {
            when (property) {
                "hwdec-current" -> _state.update { it.copy(activeDecoder = ActiveDecoder.fromHwdecCurrent(value)) }
            }
        }

        override fun event(eventId: Int) {
            when (eventId) {
                MpvEvent.MPV_EVENT_START_FILE -> starting = true
                MpvEvent.MPV_EVENT_FILE_LOADED -> {
                    starting = false
                    _state.update { it.copy(loaded = true, error = null) }
                }
                MpvEvent.MPV_EVENT_VIDEO_RECONFIG -> thread.execute { refreshVideo() }
                MpvEvent.MPV_EVENT_PLAYBACK_RESTART -> _state.update {
                    if (it.firstFrameMs != null) it
                    else it.copy(firstFrameMs = SystemClock.elapsedRealtime() - openedAt).also { s ->
                        Log.i(TAG, "first frame ${s.firstFrameMs} ms, decoder ${s.activeDecoder.label}")
                    }
                }
                // An end before the file loaded means mpv could not open it. The end of the
                // previous file arrives before START_FILE, so it never trips this.
                MpvEvent.MPV_EVENT_END_FILE -> if (starting) {
                    starting = false
                    _state.update { it.copy(error = "cannot play this file") }
                }
            }
        }
    }

    private val logObserver = object : MPVLib.LogObserver {
        override fun logMessage(prefix: String, level: Int, text: String) {
            Log.println(if (level <= 20) Log.ERROR else Log.WARN, "mpv", "[$prefix] ${text.trimEnd()}")
        }
    }

    companion object {
        private const val TAG = "PlayerEngine"
        private const val SURFACE_TIMEOUT_MS = 2000L
        private val OBSERVED = listOf(
            "time-pos" to MpvFormat.MPV_FORMAT_DOUBLE,
            "duration" to MpvFormat.MPV_FORMAT_DOUBLE,
            "pause" to MpvFormat.MPV_FORMAT_FLAG,
            "paused-for-cache" to MpvFormat.MPV_FORMAT_FLAG,
            "seeking" to MpvFormat.MPV_FORMAT_FLAG,
            "eof-reached" to MpvFormat.MPV_FORMAT_FLAG,
            "hwdec-current" to MpvFormat.MPV_FORMAT_STRING,
            "frame-drop-count" to MpvFormat.MPV_FORMAT_INT64,
        )
    }
}
