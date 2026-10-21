package app.player.core.engine

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import dev.jdtech.mpv.MPVLib
import dev.jdtech.mpv.MPVLib.MpvEvent
import dev.jdtech.mpv.MPVLib.MpvFormat
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
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
class PlayerEngine private constructor(context: Context, private val preview: Boolean) {
    constructor(context: Context) : this(context, preview = false)

    private val appContext = context.applicationContext
    private val thread: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "engine") }
    private val _state = MutableStateFlow(PlaybackState())
    val state: StateFlow<PlaybackState> = _state.asStateFlow()

    private val _messages = MutableSharedFlow<EngineMessage>(extraBufferCapacity = 32, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /**
     * mpv's warning and error lines, live (no replay). For classifying failures, for example
     * "HTTP error 404" or "Failed to recognize file format" (US-61.4).
     */
    val messages: SharedFlow<EngineMessage> = _messages.asSharedFlow()

    private var mpv: MPVLib? = null
    @Volatile private var openedAt = 0L
    @Volatile private var starting = false

    // Engine thread only. A load waits for the surface: MediaCodec's zero-copy path needs the
    // video output's surface at decoder init, so loading first would cost HW for the whole file.
    private var hasSurface = false
    private var pendingLoad: (() -> Unit)? = null
    private var videoBeforeDetach: String? = null
    private var reattachedAt = 0L
    @Volatile private var released = false

    // Engine thread only. Latest-wins scrubbing: one keyframe seek in flight, the newest target waits.
    private var scrubInFlight = false
    private var scrubNext: Long? = null
    private var scrubSentAt = 0L
    private var scrubTarget: Long? = null
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
        // No video output until a surface attaches: a vo that starts without a surface fails and
        // mpv drops the video track for the whole file. attachSurface switches to the GPU output.
        lib.setOptionString("vo", "null")
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
        if (preview) {
            // Seek preview (US-30.1): video only, paused, keyframe seeks, cheapest decode.
            lib.setOptionString("aid", "no")
            lib.setOptionString("sid", "no")
            lib.setOptionString("ao", "null")
            lib.setOptionString("hr-seek", "no")
            lib.setOptionString("pause", "yes")
            lib.setOptionString("vd-lavc-skiploopfilter", "all")
            lib.setOptionString("vd-lavc-fast", "yes")
            // Copy-back decoding: the preview surface comes and goes with the finger, and the
            // zero-copy path would lose HW whenever the file loads before the surface exists.
            lib.setOptionString("hwdec", DecoderMode.HW_PLUS.hwdec)
            lib.setOptionString("demuxer-max-bytes", "${8 * 1024 * 1024}")
            lib.setOptionString("demuxer-max-back-bytes", "0")
        }
        lib.init()
        lib.addObserver(observer)
        lib.addLogObserver(logObserver)
        OBSERVED.forEach { (name, format) -> lib.observeProperty(name, format) }
        mpv = lib
        Log.i(TAG, "libmpv ready in ${SystemClock.elapsedRealtime() - t0} ms")
    }

    /**
     * Opens a file and starts playback at [startMs]. Accepts content://, file:// and plain paths.
     * [decoder] is the mode for this file, normally from [DecoderSettings.choose]; null keeps the
     * current one.
     */
    fun open(uri: Uri, startMs: Long = 0, decoder: DecoderMode? = null) {
        openedAt = SystemClock.elapsedRealtime()
        val mode = decoder ?: _state.value.decoderMode
        // open() starts playback (pause=no below). Say so now: when mpv was already unpaused it
        // sends no change event, and the default `paused = true` would stick for the whole file.
        _state.value = PlaybackState(uri = uri.toString(), decoderMode = mode, paused = false)
        post {
            setProperty("hwdec", mode.hwdec)
            val path = resolve(uri)
            val opts = if (startMs > 0) "start=${startMs / 1000.0}" else ""
            val load: () -> Unit = {
                mpvCommand("loadfile", path, "replace", "-1", opts)
                setProperty("pause", if (preview) "yes" else "no")
            }
            if (hasSurface) {
                load()
            } else {
                // No surface yet: load when it attaches, or after a short wait (audio-only, background).
                pendingLoad = load
                thread.schedule({ runPendingLoad() }, SURFACE_WAIT_MS, TimeUnit.MILLISECONDS)
            }
        }
    }

    private fun runPendingLoad() {
        val load = pendingLoad ?: return
        pendingLoad = null
        try {
            load()
        } catch (e: Exception) {
            Log.e(TAG, "load failed", e)
            _state.update { it.copy(error = e.message) }
        }
    }

    fun play() = post { setProperty("pause", "no") }
    fun pause() = post { setProperty("pause", "yes") }
    fun togglePause() = post { mpvCommand("cycle", "pause") }

    /** Exact seek by default; pass [exact] false while scrubbing for keyframe seeks (NF-6). */
    fun seekTo(positionMs: Long, exact: Boolean = true) = post {
        mpvCommand("seek", "${positionMs / 1000.0}", if (exact) "absolute+exact" else "absolute+keyframes")
    }

    /**
     * Seek while a finger drags (US-17.3, NF-6): keyframe seeks, at most one in flight, and the
     * newest target replaces any waiting one. Each finished seek shows a frame, so the picture
     * updates as fast as the decoder can deliver keyframes.
     */
    fun scrub(positionMs: Long) = post {
        if (scrubInFlight && SystemClock.elapsedRealtime() - scrubSentAt < SCRUB_STALL_MS) {
            scrubNext = positionMs
        } else {
            sendScrub(positionMs)
        }
    }

    /** The finger lifted: exact seek to where it stopped (US-17.2). */
    fun endScrub(positionMs: Long) = post {
        scrubNext = null
        scrubTarget = null
        scrubInFlight = false
        mpvCommand("seek", "${positionMs / 1000.0}", "absolute+exact")
    }

    private fun sendScrub(positionMs: Long) {
        scrubInFlight = true
        scrubTarget = positionMs
        scrubSentAt = SystemClock.elapsedRealtime()
        mpvCommand("seek", "${positionMs / 1000.0}", "absolute+keyframes")
        if (preview) Log.i(TAG, "preview seek to $positionMs ms")
    }

    private fun scrubLanded() = post {
        scrubInFlight = false
        val next = scrubNext
        if (next != null) {
            scrubNext = null
            sendScrub(next)
            return@post
        }
        // Nothing newer waiting: if the keyframe is far from the target (long GOPs), refine with
        // an exact seek, so a slowing finger sees the frame it points at (US-30.1: within 2 s).
        val target = scrubTarget ?: return@post
        val landed = mpv?.getPropertyDouble("time-pos")?.let { (it * 1000).toLong() } ?: return@post
        if (kotlin.math.abs(landed - target) > SCRUB_REFINE_MS) {
            scrubTarget = null
            scrubInFlight = true
            scrubSentAt = SystemClock.elapsedRealtime()
            mpvCommand("seek", "${target / 1000.0}", "absolute+exact")
        }
    }

    /** Switches decoder live at the current position. mpv reinitialises only the video decoder. */
    fun setDecoder(mode: DecoderMode) {
        // Judge the pick against the decoder running now. HW and Auto share one hwdec list, so a
        // pick of HW over a file already on SW changes nothing in mpv and sends no event; if mpv
        // does switch, the hwdec-current event corrects this.
        _state.update {
            val mpvSeesNoChange = mode.hwdec == it.decoderMode.hwdec
            val notice = if (mpvSeesNoChange && it.video != null) fallbackNotice(mode, it.activeDecoder) else null
            it.copy(decoderMode = mode, decoderNotice = notice)
        }
        post { setProperty("hwdec", mode.hwdec) }
    }

    // Generic access. Feature modules wire their own mpv properties, options and filters through
    // these four, so the engine does not grow a method per feature (03_architecture.md).

    /** Runs an mpv command, for example `command("af", "add", "lavfi=[loudnorm]")`. */
    fun command(vararg args: String) = post { mpvCommand(*args) }

    /** Sets an mpv property. Values are passed as mpv parses them: "yes", "1.5", "auto". */
    fun set(name: String, value: String) = post { setProperty(name, value) }
    fun set(name: String, value: Boolean) = set(name, if (value) "yes" else "no")
    fun set(name: String, value: Long) = set(name, value.toString())
    fun set(name: String, value: Double) = set(name, value.toString())

    /** Reads an mpv property once, on the engine thread. Null if unavailable or released. */
    suspend fun <T : Any> get(name: String, type: PropertyType<T>): T? = suspendCancellableCoroutine { cont ->
        if (released) {
            cont.resume(null)
            return@suspendCancellableCoroutine
        }
        post {
            val lib = mpv
            @Suppress("UNCHECKED_CAST")
            val value: T? = when (type) {
                PropertyType.Text -> lib?.getPropertyString(name)
                PropertyType.Flag -> lib?.getPropertyBoolean(name)
                PropertyType.Int64 -> lib?.getPropertyInt(name)?.toLong()
                PropertyType.Real -> lib?.getPropertyDouble(name)
            } as T?
            cont.resume(value)
        }
    }

    /**
     * Follows an mpv property. The flow holds the latest value, null while unavailable.
     * Observing the same name and type twice shares one mpv observer.
     */
    @Suppress("UNCHECKED_CAST")
    fun <T : Any> observe(name: String, type: PropertyType<T>): StateFlow<T?> {
        val key = "$name#${type.format}"
        observed[key]?.let { return it.asStateFlow() as StateFlow<T?> }
        val flow = MutableStateFlow<Any?>(null)
        val existing = observed.putIfAbsent(key, flow)
        if (existing == null) post { mpv?.observeProperty(name, type.format) }
        return (existing ?: flow).asStateFlow() as StateFlow<T?>
    }

    private val observed = ConcurrentHashMap<String, MutableStateFlow<Any?>>()

    private fun publish(name: String, format: Int, value: Any?) {
        observed["$name#$format"]?.value = value
    }

    fun attachSurface(surface: Surface) = runAndWait {
        mpv?.attachSurface(surface)
        mpv?.setOptionString("force-window", "yes")
        setProperty("vo", "gpu-next,gpu")
        hasSurface = true
        // Back from background play (US-59.3): turn the video track on again. mpv reinitialises the
        // decoder and shows the frame at the current audio position.
        videoBeforeDetach?.let {
            reattachedAt = SystemClock.elapsedRealtime()
            setProperty("vid", it)
            videoBeforeDetach = null
            Log.i(TAG, "video back on at ${_state.value.positionMs} ms")
        }
        runPendingLoad()
    }

    fun setSurfaceSize(width: Int, height: Int) = post {
        setProperty("android-surface-size", "${width}x$height")
    }

    fun detachSurface() = runAndWait {
        // No surface means nobody sees video: stop decoding it while audio plays on (background
        // play, US-59.1), and bring it back on the next attach.
        if (_state.value.loaded && _state.value.video != null) {
            videoBeforeDetach = mpv?.getPropertyString("vid")?.takeIf { it != "no" }
            if (videoBeforeDetach != null) setProperty("vid", "no")
        }
        // Setting vo=null returns once the video output has released the surface.
        setProperty("vo", "null")
        mpv?.setOptionString("force-window", "no")
        mpv?.detachSurface()
        hasSurface = false
    }

    /** Reads mpv's frame counters on the engine thread. Null once released. */
    suspend fun stats(): EngineStats? = suspendCancellableCoroutine { cont ->
        if (released) cont.resume(null)
        else post {
            val lib = mpv
            cont.resume(
                lib?.let {
                    EngineStats(
                        voDropped = it.getPropertyInt("frame-drop-count")?.toLong() ?: 0,
                        decoderDropped = it.getPropertyInt("decoder-frame-drop-count")?.toLong() ?: 0,
                        delayed = it.getPropertyInt("vo-delayed-frame-count")?.toLong() ?: 0,
                        mistimed = it.getPropertyInt("mistimed-frame-count")?.toLong() ?: 0,
                        frameNumber = it.getPropertyInt("estimated-frame-number")?.toLong() ?: 0,
                        displayFps = it.getPropertyDouble("display-fps") ?: 0.0,
                    )
                },
            )
        }
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
        execute {
            try {
                block()
            } catch (e: Exception) {
                Log.e(TAG, "engine call failed", e)
                _state.update { it.copy(error = e.message) }
            }
        }
    }

    /** mpv's event thread can deliver after [release] shut the executor down: drop, don't crash. */
    private fun execute(task: () -> Unit): Boolean = try {
        thread.execute(task)
        true
    } catch (_: RejectedExecutionException) {
        false
    }

    private fun runAndWait(block: () -> Unit) {
        if (released) return
        val done = CountDownLatch(1)
        execute {
            try {
                block()
            } finally {
                done.countDown()
            }
        }
        if (!done.await(SURFACE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) Log.w(TAG, "surface change timed out")
    }

    private fun mpvCommand(vararg args: String) = mpv?.command(arrayOf(*args))
    private fun setProperty(name: String, value: String) = mpv?.setPropertyString(name, value)

    private fun refreshVideo() {
        val lib = mpv ?: return
        if (reattachedAt > 0) {
            Log.i(TAG, "video shown again ${SystemClock.elapsedRealtime() - reattachedAt} ms after the surface came back, at ${_state.value.positionMs} ms")
            reattachedAt = 0
        }
        val codec = lib.getPropertyString("video-format") ?: return
        val info = VideoInfo(
            codec = codec,
            width = lib.getPropertyInt("video-params/w") ?: 0,
            height = lib.getPropertyInt("video-params/h") ?: 0,
            fps = lib.getPropertyDouble("container-fps") ?: 0.0,
            pixelFormat = lib.getPropertyString("video-params/hw-pixelformat")
                ?: lib.getPropertyString("video-params/pixelformat"),
        )
        // The decoder is settled once video is reconfigured: compare it with what was chosen.
        val hwdec = lib.getPropertyString("hwdec-current")
        val running = ActiveDecoder.fromHwdecCurrent(hwdec)
        val s = _state.updateAndGet {
            it.copy(video = info, activeDecoder = running, decoderNotice = fallbackNotice(it.decoderMode, running))
        }
        Log.i(TAG, "video ${info.codec} ${info.width}x${info.height}, decoder ${running.label} ($hwdec), chosen ${s.decoderMode}, notice ${s.decoderNotice}")
    }

    private val observer = object : MPVLib.EventObserver {
        override fun eventProperty(property: String) {
            // The property became unavailable: clear every typed observer of it.
            observed.forEach { (key, flow) -> if (key.substringBefore('#') == property) flow.value = null }
        }

        override fun eventProperty(property: String, value: Long) {
            publish(property, MpvFormat.MPV_FORMAT_INT64, value)
            when (property) {
                "frame-drop-count" -> _state.update { it.copy(droppedFrames = value) }
            }
        }

        override fun eventProperty(property: String, value: Double) {
            publish(property, MpvFormat.MPV_FORMAT_DOUBLE, value)
            when (property) {
                "time-pos" -> _state.update { it.copy(positionMs = (value * 1000).toLong()) }
                "duration" -> _state.update { it.copy(durationMs = (value * 1000).toLong()) }
            }
        }

        override fun eventProperty(property: String, value: Boolean) {
            publish(property, MpvFormat.MPV_FORMAT_FLAG, value)
            when (property) {
                "pause" -> _state.update { it.copy(paused = value) }
                "paused-for-cache", "seeking" -> _state.update { it.copy(buffering = value) }
                "eof-reached" -> _state.update { it.copy(ended = value) }
            }
        }

        override fun eventProperty(property: String, value: String) {
            publish(property, MpvFormat.MPV_FORMAT_STRING, value)
            when (property) {
                "hwdec-current" -> {
                    val before = _state.value.activeDecoder
                    // Only the badge follows this. While mpv tries a hardware decoder the value passes
                    // through "no" before the decoder comes up, so the fallback is judged on
                    // VIDEO_RECONFIG (refreshVideo), once the decoder has settled.
                    val s = _state.updateAndGet { it.copy(activeDecoder = ActiveDecoder.fromHwdecCurrent(value)) }
                    if (s.activeDecoder != before) Log.i(TAG, "decoder ${before.label} -> ${s.activeDecoder.label} ($value) at ${s.positionMs} ms, notice ${s.decoderNotice}")
                }
            }
        }

        override fun event(eventId: Int) {
            when (eventId) {
                MpvEvent.MPV_EVENT_START_FILE -> starting = true
                MpvEvent.MPV_EVENT_FILE_LOADED -> {
                    starting = false
                    _state.update { it.copy(loaded = true, error = null) }
                }
                MpvEvent.MPV_EVENT_VIDEO_RECONFIG -> post { refreshVideo() }
                MpvEvent.MPV_EVENT_PLAYBACK_RESTART -> {
                    if (preview) Log.i(TAG, "preview frame at ${_state.value.positionMs} ms")
                    scrubLanded()
                    _state.update {
                        if (it.firstFrameMs != null) it.copy(restarts = it.restarts + 1)
                        else it.copy(firstFrameMs = SystemClock.elapsedRealtime() - openedAt, restarts = it.restarts + 1).also { s ->
                            // The decoder line comes with VIDEO_RECONFIG; it may not be known yet here.
                            Log.i(TAG, "first frame ${s.firstFrameMs} ms")
                        }
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
            _messages.tryEmit(EngineMessage(prefix, if (level <= 20) EngineMessage.Level.Error else EngineMessage.Level.Warn, text.trimEnd()))
        }
    }

    companion object {
        private const val TAG = "PlayerEngine"
        private const val SURFACE_TIMEOUT_MS = 2000L
        private const val SURFACE_WAIT_MS = 1000L
        private const val SCRUB_STALL_MS = 250L
        private const val SCRUB_REFINE_MS = 1500L

        /**
         * A second, video-only engine for seek preview frames (US-30.1). Not shared: the caller
         * owns it, gives it a small [VideoSurfaceView] and releases it.
         */
        fun preview(context: Context): PlayerEngine = PlayerEngine(context, preview = true)
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
