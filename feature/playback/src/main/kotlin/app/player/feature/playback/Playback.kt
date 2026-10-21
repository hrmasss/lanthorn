package app.player.feature.playback

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.provider.OpenableColumns
import android.util.Log
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import app.player.core.data.choice.DecoderChoices
import app.player.core.data.history.HistoryPrefs
import app.player.core.data.history.ResumePoints
import app.player.core.data.library.LibraryIndex
import app.player.core.engine.DecoderMode
import app.player.core.engine.EngineMessage
import app.player.core.engine.PlaybackState
import app.player.core.engine.PlayerEngine
import app.player.core.engine.PlayerSessions
import app.player.core.engine.PropertyType
import app.player.core.playerapi.PlaybackControls
import app.player.core.playerapi.PlayerIntents
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.lang.ref.WeakReference
import app.player.core.engine.PlaybackService
import app.player.core.playerapi.PlayerChrome
import kotlinx.coroutines.flow.combine

/** A resume choice waiting for the viewer (US-22.3). */
internal data class AskResume(val engine: PlayerEngine, val uri: Uri, val positionMs: Long, val decoder: DecoderMode)

/** "Start over" offered after resuming (US-22.2). */
internal data class StartOver(val engine: PlayerEngine, val uri: Uri)

/** A file that did not play (US-75.1), or the whole queue (US-75.3, [allFailed]). */
internal data class Failure(
    val engine: PlayerEngine,
    val name: String,
    /** The codec no decoder could read, when mpv named it. */
    val codec: String?,
    val reason: FailureReason,
    val details: List<String>,
    val canSkip: Boolean,
    val allFailed: Boolean,
)

/** A short note over the video, for example a silently skipped file (US-75.2). */
internal data class Note(val engine: PlayerEngine, val text: String, val id: Long = SystemClock.elapsedRealtime())

/**
 * The app's [PlaybackControls]: resume (P1.11) and the play queue (P1.12). One per process, like
 * the engine it drives, so it can keep going when the playback service holds the engine (P1.15).
 * Main thread, except saves, which run on IO.
 */
object Playback : PlaybackControls {
    private const val TAG = "Playback"

    /**
     * How long after load the video may take to show before a decoder failure counts (US-75.1).
     * A hardware decoder that refuses the file logs errors first; the software fallback can take
     * over 2 s on a slow emulator.
     */
    private const val DECODER_CHECK_MS = 4_000L
    private const val DECODER_POLL_MS = 200L
    private const val PAUSE_SETTLE_MS = 150L
    private const val PAUSE_SETTLE_TRIES = 10
    private const val LOG_SETTLE_MS = 300L

    /** Next and previous as seeks when the player screen has not given its step (US-31.4). */
    private const val DEFAULT_STEP_MS = 10_000L

    private val NETWORK_SCHEMES = setOf("http", "https", "rtsp", "rtmp", "smb")

    private val scope = MainScope() + CoroutineExceptionHandler { _, e -> Log.e(TAG, "playback coroutine failed", e) }
    private lateinit var context: Context
    private val prefs by lazy { PlaybackPrefs(context) }
    private val resumePoints by lazy { ResumePoints(context) }
    private val history by lazy { HistoryPrefs(context) }
    private val decoders by lazy { DecoderChoices(context) }

    private val _hasPrevious = MutableStateFlow(false)
    override val hasPrevious: StateFlow<Boolean> = _hasPrevious.asStateFlow()
    private val _hasNext = MutableStateFlow(false)
    override val hasNext: StateFlow<Boolean> = _hasNext.asStateFlow()
    private val _showPreviousNext = MutableStateFlow(true)
    override val showPreviousNext: StateFlow<Boolean> = _showPreviousNext.asStateFlow()
    private val _stepButtons = MutableStateFlow(false)
    override val stepButtons: StateFlow<Boolean> = _stepButtons.asStateFlow()
    private val _loop = MutableStateFlow(false)
    override val loop: StateFlow<Boolean> = _loop.asStateFlow()
    private val _shuffle = MutableStateFlow(false)
    override val shuffle: StateFlow<Boolean> = _shuffle.asStateFlow()

    private val _settings = MutableStateFlow(PlaybackSettings())
    internal val settings: StateFlow<PlaybackSettings> = _settings.asStateFlow()

    private val _ask = MutableStateFlow<AskResume?>(null)
    internal val ask: StateFlow<AskResume?> = _ask.asStateFlow()

    private val _startOver = MutableStateFlow<StartOver?>(null)
    internal val startOver: StateFlow<StartOver?> = _startOver.asStateFlow()

    private val _failure = MutableStateFlow<Failure?>(null)
    internal val failure: StateFlow<Failure?> = _failure.asStateFlow()

    private val _locked = MutableStateFlow(false)
    override val locked: StateFlow<Boolean> = _locked.asStateFlow()
    private var window: PlayerWindow? = null

    private val _note = MutableStateFlow<Note?>(null)
    internal val note: StateFlow<Note?> = _note.asStateFlow()

    private var engine: PlayerEngine? = null
    private var watcher: Job? = null
    private var media: MediaControls? = null
    private var chrome = WeakReference<PlayerChrome>(null)
    private val presses = MultiPress(scope) { act(MediaKeys.presses(it)) }

    /** Background play from the player's menu, over the "on leaving" setting until the next open (US-59.1). */
    private val backgroundOverride = MutableStateFlow<Boolean?>(null)
    private val _backgroundPlay = MutableStateFlow(false)
    override val backgroundPlay: StateFlow<Boolean> = _backgroundPlay.asStateFlow()
    private var opening: Job? = null
    private var onClose = WeakReference<() -> Unit>(null)
    private var queue = PlayQueue(emptyList<Uri>(), 0)

    /** mpv's `seekable` for the current file, and the network stream found live when it loaded. */
    @Volatile private var seekable: Boolean? = null
    @Volatile private var liveUri: String? = null

    /** The current file's name, for the audio-only screen. */
    private val _title = MutableStateFlow<String?>(null)
    internal val title: StateFlow<String?> = _title.asStateFlow()

    /** mpv log lines since the current file started, for the failure reason and "info". */
    private val lines = ArrayDeque<EngineMessage>()

    /** Saves the settled position when the player leaves the screen; set by [bind] for its engine. */
    private var saveOnLeave: (suspend () -> Unit)? = null

    /** The file whose failure is being named, so one failure gives one card. */
    private var failing: Uri? = null

    fun init(context: Context) {
        this.context = context.applicationContext
        scope.launch {
            prefs.settings.collect { s ->
                val shuffleChanged = s.shuffle != _settings.value.shuffle
                _settings.value = s
                if (shuffleChanged) queue.setShuffle(s.shuffle)
                engine?.set("loop-file", if (s.loop == LoopMode.One) "inf" else "no")
                publishQueue()
            }
        }
        scope.launch {
            combine(_settings, backgroundOverride) { s, o -> o ?: (s.onLeave == OnLeave.Background) }.collect {
                _backgroundPlay.value = it
                PlayerSessions.keepPlayingInBackground = it
            }
        }
    }

    override fun open(engine: PlayerEngine, intent: Intent, onClose: () -> Unit): Boolean {
        val uri = intent.data ?: return false
        bind(engine)
        this.onClose = WeakReference(onClose)
        backgroundOverride.value = null
        val given = PlayerIntents.queue(intent)
        setQueue(given?.first ?: listOf(uri), given?.second ?: 0)
        play(laterInQueue = false)
        if (given == null) scope.launch {
            val folder = FolderQueue.around(context, uri) ?: return@launch
            if (queue.size == 1 && queue.current == uri) setQueue(folder.first, folder.second)
        }
        return true
    }

    override fun attach(activity: ComponentActivity) {
        val e = engine ?: PlayerSessions.current.value ?: return
        _locked.value = false
        val w = PlayerWindow(activity, e, settings, _locked)
        window = w
        w.start()
        activity.lifecycle.addObserver(object : DefaultLifecycleObserver {
            // In front again: the playback service has let go, and its notification with it.
            override fun onStart(owner: LifecycleOwner) {
                media?.inService = false
            }

            // Leaving without background play (US-59.4, closing PiP, US-58.4) pauses: save where it stopped.
            override fun onStop(owner: LifecycleOwner) {
                if (activity.isChangingConfigurations) return
                if (PlayerSessions.keepPlayingInBackground && !activity.isFinishing && !e.state.value.paused) return
                scope.launch { saveOnLeave?.invoke() }
            }

            override fun onDestroy(owner: LifecycleOwner) {
                if (window === w) window = null
                _locked.value = false
            }
        })
    }

    override fun attachChrome(chrome: PlayerChrome) {
        this.chrome = WeakReference(chrome)
    }

    override fun toggleBackgroundPlay() {
        backgroundOverride.value = !_backgroundPlay.value
        Log.i(TAG, "background play ${if (backgroundOverride.value == true) "on" else "off"} from the menu")
    }

    /**
     * Keys while the player is in front, locked or not. Volume keys change brightness when set and
     * the full player shows (US-77); in PiP they stay the system's (US-77.2). Media keys: [mediaKey].
     */
    override fun onKey(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_VOLUME_UP || event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            val c = chrome.get() ?: return false
            if (_settings.value.volumeKeys != VolumeKeys.Brightness || window?.inPip != false) return false
            if (event.action == KeyEvent.ACTION_DOWN) c.stepBrightness(event.keyCode == KeyEvent.KEYCODE_VOLUME_UP)
            return true
        }
        return mediaKey(event)
    }

    /**
     * Media keys from the player screen (US-28.3, also while locked) and from the media session in
     * background (US-31.1, US-31.4). Acts on the first key down and swallows the rest of the press.
     */
    internal fun mediaKey(event: KeyEvent): Boolean {
        if (engine == null) return false
        val action = MediaKeys.action(event.keyCode, _settings.value.nextPreviousSeek)
        if (action == KeyAction.None) return false
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            Log.i(TAG, "media key ${KeyEvent.keyCodeToString(event.keyCode)}, locked ${_locked.value}")
            if (action == KeyAction.Press) presses.press() else act(action)
        }
        return true
    }

    /** One path for keys, headset presses, the session and the notification's buttons. */
    private fun act(action: KeyAction) {
        val e = engine ?: return
        Log.i(TAG, "media action ${action.name.lowercase()}")
        val step = chrome.get()?.seekStepMs ?: DEFAULT_STEP_MS
        when (action) {
            KeyAction.Toggle -> e.togglePause()
            KeyAction.Play -> e.play()
            KeyAction.Pause -> e.pause()
            KeyAction.Next -> next()
            KeyAction.Previous -> previous()
            KeyAction.Forward -> e.seekTo(e.state.value.positionMs + step)
            KeyAction.Back -> e.seekTo((e.state.value.positionMs - step).coerceAtLeast(0))
            KeyAction.Press, KeyAction.None -> return
        }
        media?.viewerActed()
    }

    override fun lock() {
        window?.lock()
    }

    internal fun unlock() {
        window?.unlock()
    }

    override fun rotate() {
        window?.rotate()
    }

    override fun pip(): Boolean = window?.enterPip() ?: false

    override fun previous() {
        val e = engine ?: return
        if (previousRestarts(_settings.value.smartPrevious, e.state.value.positionMs)) {
            e.seekTo(0)
            return
        }
        if (queue.previous(_settings.value.loop)) play(laterInQueue = true)
    }

    override fun next() {
        if (queue.next(_settings.value.loop)) play(laterInQueue = true)
    }

    internal fun setLoop(mode: LoopMode) = scope.launch { prefs.setLoop(mode) }

    internal fun setShuffle(on: Boolean) = scope.launch { prefs.setShuffle(on) }

    override fun toggleLoop() {
        setLoop(if (_settings.value.loop == LoopMode.One) LoopMode.Off else LoopMode.One)
    }

    override fun toggleShuffle() {
        setShuffle(!_settings.value.shuffle)
    }

    /** The viewer's answer to [ask]. */
    internal fun answer(resume: Boolean) {
        val a = _ask.value ?: return
        _ask.value = null
        a.engine.open(a.uri, if (resume) a.positionMs else 0, a.decoder)
    }

    internal fun startOver() {
        val s = _startOver.value ?: return
        _startOver.value = null
        s.engine.seekTo(0)
    }

    internal fun dismissStartOver() {
        _startOver.value = null
    }

    internal fun dismissNote(n: Note) {
        if (_note.value == n) {
            _note.value = null
            Log.i(TAG, "note hidden")
        }
    }

    internal fun skip() {
        _failure.value = null
        next()
    }

    private fun setQueue(uris: List<Uri>, index: Int) {
        queue = PlayQueue(uris, index).also { it.setShuffle(_settings.value.shuffle) }
        publishQueue()
    }

    private fun publishQueue() {
        val s = _settings.value
        _hasPrevious.value = queue.hasPrevious(s.loop) || s.smartPrevious
        _hasNext.value = queue.hasNext(s.loop)
        _showPreviousNext.value = s.showPreviousNext
        _stepButtons.value = s.stepButtons
        _shuffle.value = s.shuffle
        _loop.value = s.loop == LoopMode.One
    }

    private fun play(laterInQueue: Boolean) {
        val e = engine ?: return
        if (queue.size == 0) return
        val uri = queue.current
        publishQueue()
        _ask.value = null
        _startOver.value = null
        _failure.value = null
        opening?.cancel()
        opening = scope.launch {
            val settings = prefs.settings.first()
            val saved = resumePoints.get(uri.toString())
            // US-36.1: the decoder remembered for the file, its extension's, or Auto.
            val decoder = decoders.choose(uri.toString())
            val start = Resume.startAt(saved, settings.resume, laterInQueue, settings.resumeFirstOnly)
            Log.i(TAG, "open $uri item ${queue.index + 1}/${queue.size} start $start")
            launch { _title.value = displayName(uri) }
            lines.clear()
            failing = null
            when (start) {
                StartAt.Beginning -> e.open(uri, decoder = decoder)
                is StartAt.Resume -> {
                    e.open(uri, start.positionMs, decoder)
                    _startOver.value = StartOver(e, uri)
                }
                is StartAt.Ask -> _ask.value = AskResume(e, uri, start.positionMs, decoder)
            }
        }
    }

    /** Follows [engine] until it goes: resume points (US-22.1, US-22.5), the end of a file (US-24), failures (US-75). */
    private fun bind(engine: PlayerEngine) {
        if (this.engine === engine) return
        this.engine = engine
        engine.set("loop-file", if (_settings.value.loop == LoopMode.One) "inf" else "no")
        media?.release()
        watcher?.cancel()
        watcher = scope.launch {
            val self = coroutineContext.job
            val m = MediaControls(context, engine, this, title, ::act)
            media = m
            m.start()
            PlaybackService.notificationFactory = { _, _ -> m.inService = true; m.notification() }
            launch {
                engine.messages.collect { m ->
                    lines.addLast(m)
                    while (lines.size > 40) lines.removeFirst()
                }
            }
            var last: PlaybackState? = null
            var lastSaved: PlaybackState? = null
            var savedAt = 0L
            fun save(s: PlaybackState, why: String, fresh: Boolean = false) {
                savedAt = SystemClock.elapsedRealtime()
                lastSaved = s
                scope.launch(NonCancellable) {
                    val at = if (fresh) settledPosition(engine) else null
                    write(if (at != null) s.copy(positionMs = at) else s, why)
                }
            }
            // Leaving the player pauses it and may release the engine at once, before the pause
            // reaches the state: hold the engine until the settled position is saved, so the
            // release below finds that save and does not write an older position over it.
            saveOnLeave = saveOnLeave@{
                val s = last ?: return@saveOnLeave
                if (PlayerSessions.current.value !== engine || !s.loaded) return@saveOnLeave
                val hold = Any()
                PlayerSessions.acquire(context, hold)
                try {
                    val left = s.copy(paused = true, positionMs = settledPosition(engine) ?: s.positionMs)
                    savedAt = SystemClock.elapsedRealtime()
                    lastSaved = left
                    withContext(NonCancellable) { write(left, "left the player") }
                } finally {
                    PlayerSessions.release(hold)
                }
            }
            launch {
                PlayerSessions.current.first { it !== engine }
                // Leaving pauses first, and that save is the newest; `last` can be one update behind it.
                val pausedSaved = lastSaved?.let { it.paused && it.uri == last?.uri } == true
                if (!pausedSaved) last?.let { withContext(NonCancellable) { write(it, "engine released") } }
                if (this@Playback.engine === engine) this@Playback.engine = null
                m.release()
                if (media === m) {
                    media = null
                    PlaybackService.notificationFactory = null
                }
                // This watcher only: a player opened right after the close may have bound a new one already.
                self.cancel()
            }
            // mpv's own pause flag: PlaybackState.paused starts true after open() and only changes
            // when mpv reports a change, so a file that plays from the start can read as paused.
            val pause = engine.observe("pause", PropertyType.Flag)
            val seekableFlow = engine.observe("seekable", PropertyType.Flag)
            combine(engine.state, pause, seekableFlow) { st, p, sk -> (if (p == null) st else st.copy(paused = p)) to sk }.collect { (s, sk) ->
                // Released: mpv reports end of file while it shuts down. Not a real end, not a save.
                if (PlayerSessions.current.value !== engine) return@collect
                seekable = sk
                // A network stream that shows its first frame without a duration is live, whatever it reports later.
                if (s.firstFrameMs != null && s.uri != null && s.uri != liveUri && s.durationMs <= 0 && s.uri!!.substringBefore("://") in NETWORK_SCHEMES) {
                    liveUri = s.uri
                    Log.i(TAG, "live stream, no resume point: ${s.uri}")
                }
                val prev = last
                val now = SystemClock.elapsedRealtime()
                if (prev != null && (prev.paused != s.paused || prev.loaded != s.loaded || prev.error != s.error)) {
                    Log.d(TAG, "state: paused ${s.paused}, loaded ${s.loaded}, error ${s.error}, ended ${s.ended}, at ${s.positionMs}")
                }
                when {
                    prev != null && prev.uri != s.uri -> save(prev, "next file")
                    s.loaded && s.paused && prev?.paused == false -> save(s, "paused", fresh = true)
                    s.ended && prev?.ended == false -> save(s, "ended")
                    s.loaded && !s.paused && now - savedAt >= Resume.SAVE_EVERY_MS -> save(s, "every 5 s")
                }
                if (s.uri == queue.currentOrNull()?.toString()) {
                    // State updates are conflated: two files in a row can arrive without the reset
                    // between them, so a new file counts as a change on every field.
                    val newFile = prev == null || prev.uri != s.uri
                    if (s.error != null && (newFile || prev?.error == null)) failed(engine, s)
                    if (s.loaded && (newFile || prev?.loaded != true)) launch { checkDecoders(engine, s.uri) }
                    if (s.firstFrameMs != null && (newFile || prev?.firstFrameMs == null)) queue.markPlayed()
                    if (s.ended && (newFile || prev?.ended == false)) {
                        // No decoder for any stream: mpv "plays" nothing and ends at once (US-75.1).
                        val nothingPlayed = s.video == null && (lines.any(Failures::isDecoderFailure) || s.positionMs < 1000)
                        if (nothingPlayed || Failures.endedEarly(s.positionMs, s.durationMs, lines.toList())) failed(engine, s) else ended()
                    }
                }
                last = s
            }
        }
        watcher?.invokeOnCompletion { Log.i(TAG, "stopped following the engine", it) }
    }

    /**
     * Where mpv stopped after a pause: frames already decoded can still be shown just after it, so
     * the position is read until two reads agree. The state's own position can trail it.
     */
    private suspend fun settledPosition(engine: PlayerEngine): Long? {
        var at = engine.get("time-pos", PropertyType.Real) ?: return null
        repeat(PAUSE_SETTLE_TRIES) {
            delay(PAUSE_SETTLE_MS)
            val now = engine.get("time-pos", PropertyType.Real) ?: return (at * 1000).toLong()
            if (now == at) return (now * 1000).toLong()
            at = now
        }
        return (at * 1000).toLong()
    }

    /** A file that loads but has no decoder for its streams fails too (US-75.1, unsupported codec). */
    private suspend fun checkDecoders(engine: PlayerEngine, uri: String?) {
        // Video showing at any point means a decoder took it, whatever errors came before.
        var s = engine.state.value
        var waited = 0L
        while (waited < DECODER_CHECK_MS) {
            if (s.uri != uri || s.error != null || s.video != null || s.ended) return
            delay(DECODER_POLL_MS)
            waited += DECODER_POLL_MS
            s = engine.state.value
        }
        if (s.uri != uri || s.error != null || s.video != null || s.ended) return
        // Loaded, yet no video and not even an audio frame: nothing here can decode it.
        if (lines.any(Failures::isDecoderFailure) || s.firstFrameMs == null) failed(engine, s)
    }

    /** End of a file: next, back to the first with loop all, or stop (US-24.1, US-24.3, US-24.5). */
    private fun ended() {
        val s = _settings.value
        if (s.loop == LoopMode.One) return
        if (queue.next(s.loop)) {
            play(laterInQueue = true)
        } else if (s.backToList) {
            Log.i(TAG, "queue ended, closing the player")
            onClose.get()?.invoke()
        }
    }

    private fun failed(engine: PlayerEngine, s: PlaybackState) {
        if (_failure.value != null) return
        val uri = queue.current
        // Network streams fail through library's overlay (P1.8), with retry; one card, not two.
        if (uri.scheme in NETWORK_SCHEMES) return
        if (failing == uri) return
        failing = uri
        queue.markFailed()
        scope.launch {
            // The end of the file can arrive before mpv's lines saying why: let them land first.
            delay(LOG_SETTLE_MS)
            val reason = Failures.classify(s.error, lines.toList())
            val details = Failures.technical(s.error, lines.toList())
            Log.w(TAG, "failed $uri: $reason")
            val name = displayName(uri)
            val settings = _settings.value
            val allFailed = queue.size > 1 && queue.allFailed
            val canSkip = !allFailed && queue.hasNext(settings.loop)
            if (settings.skipFailed && canSkip) {
                _note.value = Note(engine, context.getString(R.string.playback_skipped, name))
                Log.i(TAG, "note shown: skipped $name")
                next()
            } else {
                _failure.value = Failure(engine, name, Failures.codecOf(lines.toList()), reason, details, canSkip, allFailed)
                Log.i(TAG, "failure card: $reason${if (allFailed) ", whole queue" else ""}")
            }
        }
    }

    /**
     * What the viewer calls the file: the provider's display name, the library's name for it (a
     * deleted file keeps its row until the next scan), or the last part of its path. Never a bare id.
     */
    private suspend fun displayName(uri: Uri): String = withContext(Dispatchers.IO) {
        val fromProvider = if (uri.scheme == "content") {
            runCatching {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) c.getString(0) else null
                }
            }.getOrNull()
        } else {
            null
        }
        fromProvider
            ?: runCatching { LibraryIndex.get(context).videos.first().firstOrNull { it.uri == uri.toString() }?.name }.getOrNull()
            ?: Failures.nameFromPath(Uri.decode(uri.lastPathSegment ?: ""))
            ?: context.getString(R.string.failure_unknown_name)
    }

    private fun <T> PlayQueue<T>.currentOrNull(): T? = if (size == 0) null else current

    /** Writes in the order they were asked for, so an older position never lands last. */
    private val writes = Mutex()

    private suspend fun write(s: PlaybackState, why: String) {
        writes.withLock {
            val row = Resume.toSave(s, System.currentTimeMillis(), seekable, live = s.uri == liveUri) ?: run {
                Log.d(TAG, "not saved ($why): at ${s.positionMs}, loaded ${s.loaded}, error ${s.error}, duration ${s.durationMs}, seekable $seekable, uri ${s.uri}")
                return
            }
            if (history.isPaused()) return
            resumePoints.put(row)
            Log.i(TAG, "saved ${row.uri} at ${row.positionMs} of ${row.durationMs} ($why)")
        }
    }
}
