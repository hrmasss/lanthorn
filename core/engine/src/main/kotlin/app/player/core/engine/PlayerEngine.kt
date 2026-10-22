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
import kotlinx.coroutines.withTimeoutOrNull
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
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
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
    @Volatile private var engineThread: Thread? = null
    // When the task running on the engine thread started, 0 while idle: the watchdog reads it.
    @Volatile private var busySince = 0L
    // When the surface last attached or changed size: mpv rebuilds its video output then (watchdog).
    @Volatile private var surfaceChangedAt = 0L
    private val thread: ScheduledExecutorService = object : ScheduledThreadPoolExecutor(1, { r -> Thread(r, "engine").also { engineThread = it } }) {
        override fun beforeExecute(t: Thread, r: Runnable) { busySince = SystemClock.elapsedRealtime() }
        override fun afterExecute(r: Runnable, t: Throwable?) { busySince = 0L }
    }

    /**
     * Set once when a call into mpv has blocked the engine thread for [STALL_MS] while a hardware
     * decoder runs: a MediaCodec that stops answering (for example inside a seek) holds mpv, and
     * this engine cannot recover. [PlayerSessions] replaces it with a new engine on SW at the same
     * place (NF-5 mid-playback). Plain engines only report it.
     */
    val stalled: StateFlow<Stall?> get() = _stalled.asStateFlow()
    private val _stalled = MutableStateFlow<Stall?>(null)
    private var swStallLogged = false
    private val watchdog: ScheduledFuture<*>? =
        if (preview) null else WATCHDOG.scheduleWithFixedDelay({ checkStall() }, STALL_CHECK_MS, STALL_CHECK_MS, TimeUnit.MILLISECONDS)
    // A notice that outlives the decoder judgement for this file: why it plays on SW after a stall.
    @Volatile private var stickyNotice: String? = null
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
    // Engine thread only. The video chain came back after the surface returned; mpv's next
    // "first video frame" line is then its first frame on screen (US-59.3).
    private var reconfiguredSinceReattach = false
    @Volatile private var released = false

    // Engine thread only. Latest-wins scrubbing: one keyframe seek in flight, the newest target waits.
    private var scrubInFlight = false
    private var scrubNext: Long? = null
    private var scrubSentAt = 0L
    private var scrubTarget: Long? = null
    private var scrubExact = false
    // Preview only: this file's keyframes sit too far apart for a keyframe seek to land near the
    // finger, so preview seeks go exact. Learnt from the first keyframe seek of each file.
    private var longGop = false
    // Preview on a view that reports shown frames: a landed seek waits until its frame is on screen
    // before the next one goes out, or the next seek cancels the frame before it is drawn.
    @Volatile var reportsShownFrames = false
    private var awaitingShow = false
    private var nudged = false
    private var landedAt = 0L
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
            // Software decoding: an exact seek decodes every frame from the keyframe before the
            // target, and copying each one back from a hardware decoder made that the slow part.
            lib.setOptionString("hwdec", DecoderMode.SW.hwdec)
            lib.setOptionString("demuxer-max-bytes", "${8 * 1024 * 1024}")
            lib.setOptionString("demuxer-max-back-bytes", "0")
            // Paused, mpv draws each seek's frame once. With a deeper swapchain the TextureView
            // showed the frame of the seek before (gate shots: 7.8 s shown after 19.8 s landed).
            lib.setOptionString("swapchain-depth", "1")
        }
        lib.init()
        lib.addObserver(observer)
        lib.addLogObserver(logObserver)
        OBSERVED.forEach { (name, format) -> lib.observeProperty(name, format) }
        mpv = lib
        Log.i(TAG, "libmpv ready in ${SystemClock.elapsedRealtime() - t0} ms")
    }

    /**
     * A decoder for every file of the current launch (US-09.3 "play with"), set by the activity
     * from the launching intent, so any opener (the queue too) honours it. Never remembered.
     */
    @Volatile var launchDecoder: DecoderMode? = null

    /**
     * Opens a file and starts playback at [startMs]. Accepts content://, file:// and plain paths.
     * [decoder] is the mode for this file, normally from [DecoderSettings.choose]; null keeps the
     * current one. [launchDecoder], when set, wins over both.
     */
    fun open(uri: Uri, startMs: Long = 0, decoder: DecoderMode? = null) = open(uri, startMs, decoder, notice = null, startPaused = false)

    /** After [stall] on another engine: the same file and place on SW, paused if it was, saying why. */
    internal fun openAfterStall(stall: Stall) {
        val launch = launchDecoder
        launchDecoder = null
        open(Uri.parse(stall.uri), stall.positionMs, DecoderMode.SW, "${stall.decoder.label} stopped responding. Playing with SW.", stall.paused)
        launchDecoder = launch
    }

    private fun open(uri: Uri, startMs: Long, decoder: DecoderMode?, notice: String?, startPaused: Boolean) {
        openedAt = SystemClock.elapsedRealtime()
        val mode = launchDecoder ?: decoder ?: _state.value.decoderMode
        stickyNotice = notice
        // open() starts playback (pause=no below). Say so now: when mpv was already unpaused it
        // sends no change event, and the default `paused = true` would stick for the whole file.
        _state.value = PlaybackState(uri = uri.toString(), decoderMode = mode, paused = startPaused, decoderNotice = notice)
        post {
            setProperty("hwdec", mode.hwdec)
            val path = resolve(uri)
            // Track choices are global in mpv: a pick on the last file (a remembered one, applied after
            // its load) would carry into this one. Each file starts on mpv's own rule; features apply
            // their per-file picks after it loads.
            val opts = listOfNotNull(
                if (startMs > 0) "start=${startMs / 1000.0}" else null,
                if (preview) null else "aid=auto",
                if (preview) null else "sid=auto",
            ).joinToString(",")
            val load: () -> Unit = {
                mpvCommand("loadfile", path, "replace", "-1", opts)
                setProperty("pause", if (preview || startPaused) "yes" else "no")
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
        val wait = if (awaitingShow) SHOW_WAIT_MS else SCRUB_STALL_MS
        if (scrubInFlight && SystemClock.elapsedRealtime() - scrubSentAt < wait) {
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
        awaitingShow = false
        mpvCommand("seek", "${positionMs / 1000.0}", "absolute+exact")
    }

    private fun sendScrub(positionMs: Long) {
        awaitingShow = false
        scrubInFlight = true
        scrubTarget = positionMs
        scrubSentAt = SystemClock.elapsedRealtime()
        scrubExact = preview && longGop
        mpvCommand("seek", "${positionMs / 1000.0}", if (scrubExact) "absolute+exact" else "absolute+keyframes")
        if (preview) Log.i(TAG, "preview seek to $positionMs ms${if (scrubExact) " exact" else ""}")
    }

    private fun scrubLanded() = post {
        scrubInFlight = false
        val shown = mpv?.getPropertyDouble("time-pos")?.let { (it * 1000).toLong() }
        val sent = scrubTarget
        if (preview) Log.i(TAG, "preview frame at $shown ms, target $sent ms")
        if (preview && reportsShownFrames) {
            // Keep the slot taken until the view has the frame; scrub() queues meanwhile, and the
            // stall window, restarted here, sends anyway if the view never reports.
            scrubInFlight = true
            scrubSentAt = SystemClock.elapsedRealtime()
            awaitingShow = true
            nudged = false
            landedAt = scrubSentAt
            landedPos = shown
            return@post
        }
        continueScrub(shown)
    }

    /** The preview view put a new frame on screen. Called from its thread. */
    fun frameShown() = post {
        if (preview) Log.i(TAG, "preview view update, ${if (!awaitingShow) "extra" else if (nudged) "after nudge" else "first"}")
        if (!awaitingShow) return@post
        if (!nudged) {
            // Paused, mpv presents the old picture again when the seek ends and draws the new frame
            // only when something next wakes its video output (gate runs: the view always showed
            // the frame before the last landed one). An OSD change wakes it; its update is the frame.
            nudged = true
            mpvCommand("show-text", " ", "1")
            return@post
        }
        awaitingShow = false
        scrubInFlight = false
        Log.i(TAG, "preview frame shown at $landedPos ms, ${SystemClock.elapsedRealtime() - landedAt} ms after it landed")
        continueScrub(landedPos)
    }

    private var landedPos: Long? = null

    private fun continueScrub(shown: Long?) {
        val sent = scrubTarget
        if (preview && !scrubExact && sent != null && shown != null && kotlin.math.abs(shown - sent) > SCRUB_REFINE_MS) {
            if (!longGop) Log.i(TAG, "preview: keyframe landed ${shown - sent} ms from the target, seeking exact from now on")
            longGop = true
        }
        val next = scrubNext
        if (next != null) {
            scrubNext = null
            sendScrub(next)
            return
        }
        // Nothing newer waiting: if the keyframe is far from the target (long GOPs), refine with
        // an exact seek, so a slowing finger sees the frame it points at (US-30.1: within 2 s).
        val target = scrubTarget ?: return
        val landed = shown ?: return
        if (kotlin.math.abs(landed - target) > SCRUB_REFINE_MS) {
            scrubTarget = null
            scrubInFlight = true
            scrubSentAt = SystemClock.elapsedRealtime()
            mpvCommand("seek", "${target / 1000.0}", "absolute+exact")
        }
    }

    /** Switches decoder live at the current position. mpv reinitialises only the video decoder. */
    fun setDecoder(mode: DecoderMode) {
        stickyNotice = null
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
    fun set(name: String, value: String) {
        if (name in NETWORK_OPTIONS) networkOptions[name] = value
        post { setProperty(name, value) }
    }

    // What the player's features set for network requests (US-61.5): a seek preview engine for
    // the same stream sends the same user agent and timeout.
    private val networkOptions = ConcurrentHashMap<String, String>()
    fun set(name: String, value: Boolean) = set(name, if (value) "yes" else "no")
    fun set(name: String, value: Long) = set(name, value.toString())
    fun set(name: String, value: Double) = set(name, value.toString())

    /**
     * Reads an mpv property once, on the engine thread. Null if unavailable, released, or when the
     * engine thread does not answer within 5 s (then its stack is logged: what it is stuck on).
     */
    suspend fun <T : Any> get(name: String, type: PropertyType<T>): T? {
        var answered = false
        val value = withTimeoutOrNull(GET_TIMEOUT_MS) { readOnEngine(name, type).also { answered = true } }
        if (!answered && !released) {
            Log.w(TAG, "get($name) timed out; engine thread at:\n" + engineThread?.stackTrace?.take(12)?.joinToString("\n") { "  at $it" })
        }
        return value
    }

    private suspend fun <T : Any> readOnEngine(name: String, type: PropertyType<T>): T? = suspendCancellableCoroutine { cont ->
        if (released) {
            cont.resume(null)
            return@suspendCancellableCoroutine
        }
        val queued = execute {
            var value: T? = null
            try {
                val lib = mpv
                @Suppress("UNCHECKED_CAST")
                value = when (type) {
                    PropertyType.Text -> lib?.getPropertyString(name)
                    PropertyType.Flag -> lib?.getPropertyBoolean(name)
                    PropertyType.Int64 -> lib?.getPropertyInt(name)?.toLong()
                    PropertyType.Real -> lib?.getPropertyDouble(name)
                } as T?
            } catch (e: Exception) {
                Log.w(TAG, "get($name) failed", e)
            } finally {
                // Always answer: an exception must not leave the caller waiting.
                if (cont.isActive) cont.resume(value)
            }
        }
        if (!queued) {
            cont.resume(null)
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
        surfaceChangedAt = SystemClock.elapsedRealtime()
        mpv?.attachSurface(surface)
        mpv?.setOptionString("force-window", "yes")
        setProperty("vo", "gpu-next,gpu")
        hasSurface = true
        // Back from background play (US-59.3): turn the video track on again. mpv reinitialises the
        // decoder and shows the frame at the current audio position.
        videoBeforeDetach?.let {
            reattachedAt = SystemClock.elapsedRealtime()
            reconfiguredSinceReattach = false
            _state.update { s -> s.copy(videoBackMs = null) }
            setProperty("vid", it)
            videoBeforeDetach = null
            Log.i(TAG, "video back on at ${_state.value.positionMs} ms")
        }
        runPendingLoad()
    }

    fun setSurfaceSize(width: Int, height: Int) = post {
        surfaceChangedAt = SystemClock.elapsedRealtime()
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
        watchdog?.cancel(false)
        thread.execute {
            mpv?.removeObserver(observer)
            mpv?.removeLogObserver(logObserver)
            mpv?.destroy()
            mpv = null
            closeFd()
        }
        thread.shutdown()
    }

    private fun resolve(uri: Uri): String {
        // Opening a content uri (SMB, a slow provider) can take seconds and is not mpv: keep it
        // out of the watchdog's count.
        busySince = 0L
        try {
            return resolveNow(uri)
        } finally {
            busySince = SystemClock.elapsedRealtime()
        }
    }

    private fun resolveNow(uri: Uri): String = when (uri.scheme) {
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

    private fun checkStall() {
        val since = busySince
        if (released || since == 0L || _stalled.value != null) return
        val ms = SystemClock.elapsedRealtime() - since
        if (ms < STALL_MS) return
        val s = _state.value
        // Until the first frame shows, mpv is still opening the file (scripts, fonts, demuxer, decoders,
        // audio output) and holds its lock for seconds on a slow device. A decoder hanging at open is
        // caught at the longer bound.
        if (s.firstFrameMs == null && ms < OPEN_STALL_MS) return
        // Right after a surface change (rotation, picture-in-picture) mpv rebuilds its video output and
        // compiles shaders, over 3 s on a software GPU. That is not the decoder.
        if (since - surfaceChangedAt in 0..SURFACE_REBUILD_WINDOW_MS && ms < SURFACE_STALL_MS) return
        val stack = engineThread?.stackTrace?.take(12)?.joinToString("\n") { "  at $it" }
        // Hardware runs, or was just asked for (a live switch can stall while MediaCodec opens).
        val onHardware = s.activeDecoder == ActiveDecoder.HW || s.activeDecoder == ActiveDecoder.HW_PLUS
        if (s.uri == null || (!onHardware && s.decoderMode == DecoderMode.SW)) {
            // Software decoding has no other decoder to fall to: say so once.
            if (!swStallLogged) Log.w(TAG, "engine thread busy $ms ms on ${s.activeDecoder.label}, no fallback; at:\n$stack")
            swStallLogged = true
            return
        }
        Log.w(TAG, "engine stalled $ms ms on ${s.activeDecoder.label} (chosen ${s.decoderMode}) at ${s.positionMs} ms: recovering on SW; engine thread at:\n$stack")
        val decoder = if (onHardware) s.activeDecoder else s.decoderMode.intended ?: ActiveDecoder.HW
        _stalled.value = Stall(s.uri, s.positionMs, s.paused, decoder)
    }

    /**
     * Holds the engine thread for [ms], as a decoder that stops answering inside mpv would. Tests
     * only: proves the watchdog and that the UI does not wait on the engine.
     */
    fun blockEngineThreadForTest(ms: Long) = post { Thread.sleep(ms) }

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
        // A stalled engine never answers: do not hold the caller (the main thread) for it.
        if (released || _stalled.value != null) return
        val done = CountDownLatch(1)
        execute {
            try {
                block()
            } finally {
                done.countDown()
            }
        }
        // The engine thread already stuck in an mpv call will not get to this soon: do not hold the
        // caller (the main thread, for Back) for the full timeout. A stuck mpv draws nothing anyway.
        val since = busySince
        val stuck = since != 0L && SystemClock.elapsedRealtime() - since > STUCK_HINT_MS
        if (!done.await(if (stuck) STUCK_WAIT_MS else SURFACE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            Log.w(TAG, "surface change timed out" + if (stuck) " (engine thread busy)" else "")
        }
    }

    private fun mpvCommand(vararg args: String) = mpv?.command(arrayOf(*args))
    private fun setProperty(name: String, value: String) = mpv?.setPropertyString(name, value)

    /**
     * US-59.3: back from background, the time from the surface's return to the first frame drawn
     * on it. VIDEO_RECONFIG alone comes about 0.9 s before that frame on the emulator (player-ui,
     * run 39637149909), so the stamp waits for mpv's "first video frame after restart shown".
     */
    private fun videoShownAgain() {
        if (reattachedAt == 0L || !reconfiguredSinceReattach) return
        val back = SystemClock.elapsedRealtime() - reattachedAt
        Log.i(TAG, "video shown again $back ms after the surface came back, at ${_state.value.positionMs} ms")
        _state.update { it.copy(videoBackMs = back) }
        reattachedAt = 0
        reconfiguredSinceReattach = false
    }

    private fun refreshVideo(triesLeft: Int = DECODER_SETTLE_TRIES) {
        val lib = mpv ?: return
        if (reattachedAt > 0 && triesLeft == DECODER_SETTLE_TRIES) reconfiguredSinceReattach = true
        // VIDEO_RECONFIG can come before mpv knows the format, and no second one may follow: read
        // again until it does (or give up: no video track, or video turned off).
        val codec = lib.getPropertyString("video-format") ?: run {
            if (triesLeft > 0) thread.schedule({ if (!released) refreshVideo(triesLeft - 1) }, DECODER_SETTLE_STEP_MS, TimeUnit.MILLISECONDS)
            return
        }
        val info = VideoInfo(
            codec = codec,
            width = lib.getPropertyInt("video-params/w") ?: 0,
            height = lib.getPropertyInt("video-params/h") ?: 0,
            fps = lib.getPropertyDouble("container-fps") ?: 0.0,
            pixelFormat = lib.getPropertyString("video-params/hw-pixelformat")
                ?: lib.getPropertyString("video-params/pixelformat"),
        )
        _state.update { it.copy(video = info) }
        Log.i(TAG, "video ${info.codec} ${info.width}x${info.height}")
        settleDecoder()
    }

    /**
     * Compares the running decoder with the chosen one. Right after a live switch hwdec-current
     * can be unavailable while mpv opens the new decoder, and no change event follows when it
     * comes up, so read it again until mpv has a value.
     */
    private fun settleDecoder(triesLeft: Int = DECODER_SETTLE_TRIES) {
        val lib = mpv ?: return
        val hwdec = lib.getPropertyString("hwdec-current")
        if (hwdec == null && triesLeft > 0) {
            thread.schedule({ if (!released) settleDecoder(triesLeft - 1) }, DECODER_SETTLE_STEP_MS, TimeUnit.MILLISECONDS)
            return
        }
        val running = ActiveDecoder.fromHwdecCurrent(hwdec)
        // A pick reaches mpv after the state changes: until mpv has it, the decoder running is the
        // old one and says nothing about the pick.
        val requested = lib.getPropertyString("hwdec")
        val s = _state.updateAndGet {
            val judged = requested == it.decoderMode.hwdec
            it.copy(activeDecoder = running, decoderNotice = if (judged) stickyNotice ?: fallbackNotice(it.decoderMode, running) else it.decoderNotice)
        }
        Log.i(TAG, "decoder ${running.label} ($hwdec), chosen ${s.decoderMode}, notice ${s.decoderNotice}")
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
                    // Only the badge follows this. The fallback is judged in settleDecoder, once the
                    // decoder has settled after VIDEO_RECONFIG.
                    val s = _state.updateAndGet { it.copy(activeDecoder = ActiveDecoder.fromHwdecCurrent(value)) }
                    if (s.activeDecoder != before) Log.i(TAG, "decoder ${before.label} -> ${s.activeDecoder.label} ($value) at ${s.positionMs} ms, notice ${s.decoderNotice}")
                }
            }
        }

        override fun event(eventId: Int) {
            when (eventId) {
                MpvEvent.MPV_EVENT_START_FILE -> {
                    starting = true
                    post { longGop = false }
                }
                MpvEvent.MPV_EVENT_FILE_LOADED -> {
                    starting = false
                    _state.update { it.copy(loaded = true, error = null) }
                }
                MpvEvent.MPV_EVENT_VIDEO_RECONFIG -> post { refreshVideo() }
                MpvEvent.MPV_EVENT_PLAYBACK_RESTART -> {
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
            // mpv's only signal that a new video chain drew its first frame: a track switched back on
            // sends no PLAYBACK_RESTART (US-59.3). Posted, so it lands behind VIDEO_RECONFIG's post.
            if (prefix == "cplayer" && text.startsWith("first video frame after restart shown")) post { videoShownAgain() }
            Log.println(if (level <= 20) Log.ERROR else Log.WARN, "mpv", "[$prefix] ${text.trimEnd()}")
            _messages.tryEmit(EngineMessage(prefix, if (level <= 20) EngineMessage.Level.Error else EngineMessage.Level.Warn, text.trimEnd()))
        }
    }

    companion object {
        private const val TAG = "PlayerEngine"
        private const val SURFACE_TIMEOUT_MS = 2000L
        private const val STUCK_HINT_MS = 500L
        private const val STUCK_WAIT_MS = 300L
        private const val SURFACE_WAIT_MS = 1000L
        private const val DECODER_SETTLE_STEP_MS = 100L
        private const val DECODER_SETTLE_TRIES = 50
        // A newer seek cancels the one in flight, so replacing slow seeks early means no frame ever
        // lands on a slow decoder. Wait this long for a seek to finish before giving up on it.
        private const val GET_TIMEOUT_MS = 5000L
        // An mpv call that has not returned in this long on a hardware decoder is a stall.
        const val STALL_MS = 3000L
        const val OPEN_STALL_MS = 10_000L
        private const val SURFACE_STALL_MS = 5000L
        private const val SURFACE_REBUILD_WINDOW_MS = 2000L
        private const val STALL_CHECK_MS = 250L
        private val WATCHDOG: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "engine-watchdog").apply { isDaemon = true } }
        private const val SCRUB_STALL_MS = 5000L
        // Longest a landed preview frame holds the next seek back when the view never reports it.
        private const val SHOW_WAIT_MS = 500L
        private const val SCRUB_REFINE_MS = 1500L

        /**
         * A second, video-only engine for seek preview frames (US-30.1). Not shared: the caller
         * owns it, gives it a small [VideoSurfaceView] and releases it.
         */
        fun preview(context: Context, of: PlayerEngine? = PlayerSessions.current.value): PlayerEngine =
            PlayerEngine(context, preview = true).also { p -> of?.networkOptions?.forEach { (k, v) -> p.set(k, v) } }

        /** mpv options that shape a stream's requests; a preview engine copies them from the player's. */
        private val NETWORK_OPTIONS = setOf("user-agent", "network-timeout", "http-header-fields", "referrer", "cookies", "tls-verify")
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
