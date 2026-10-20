package app.player.feature.playback

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import app.player.core.data.history.HistoryPrefs
import app.player.core.data.history.ResumePoints
import app.player.core.engine.PlaybackState
import app.player.core.engine.PlayerEngine
import app.player.core.engine.PlayerSessions
import app.player.core.playerapi.PlaybackController
import app.player.core.playerapi.PlayerIntents
import app.player.core.playerapi.QueueState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.lang.ref.WeakReference

/** A resume choice waiting for the viewer (US-22.3). */
internal data class AskResume(val engine: PlayerEngine, val uri: Uri, val positionMs: Long)

/** "Start over" offered after resuming (US-22.2). */
internal data class StartOver(val engine: PlayerEngine, val uri: Uri)

/**
 * The app's [PlaybackController]: resume (P1.11) and the play queue (P1.12). One per process, like
 * the engine it drives, so it can keep going when the playback service holds the engine (P1.15).
 * Main thread, except saves, which run on IO.
 */
object Playback : PlaybackController {
    private val scope = MainScope()
    private lateinit var context: Context
    private val prefs by lazy { PlaybackPrefs(context) }
    private val resumePoints by lazy { ResumePoints(context) }
    private val history by lazy { HistoryPrefs(context) }

    private val _state = MutableStateFlow(QueueState())
    override val state: StateFlow<QueueState> = _state.asStateFlow()

    private val _ask = MutableStateFlow<AskResume?>(null)
    internal val ask: StateFlow<AskResume?> = _ask.asStateFlow()

    private val _startOver = MutableStateFlow<StartOver?>(null)
    internal val startOver: StateFlow<StartOver?> = _startOver.asStateFlow()

    private var engine: PlayerEngine? = null
    private var recorder: Job? = null
    private var opening: Job? = null
    private var onClose = WeakReference<() -> Unit>(null)

    private var queue: List<Uri> = emptyList()
    private var index = 0

    fun init(context: Context) {
        this.context = context.applicationContext
    }

    override fun open(engine: PlayerEngine, intent: Intent, onClose: () -> Unit): Boolean {
        val uri = intent.data ?: return false
        bind(engine)
        this.onClose = WeakReference(onClose)
        val given = PlayerIntents.queue(intent)
        queue = given?.first ?: listOf(uri)
        index = given?.second ?: 0
        publishQueue()
        play(laterInQueue = false)
        if (given == null) scope.launch {
            val folder = FolderQueue.around(context, uri) ?: return@launch
            if (queue.size == 1 && queue[0] == uri) {
                queue = folder.first
                index = folder.second
                publishQueue()
            }
        }
        return true
    }

    override fun previous() {
        if (index <= 0) return
        index--
        publishQueue()
        play(laterInQueue = true)
    }

    override fun next() {
        if (index >= queue.lastIndex) return
        index++
        publishQueue()
        play(laterInQueue = true)
    }

    override fun step(forward: Boolean) {
        val e = engine ?: return
        val s = e.state.value
        val to = s.positionMs + if (forward) _state.value.stepMs else -_state.value.stepMs
        e.seekTo(to.coerceIn(0, maxOf(0, s.durationMs)))
    }

    /** The viewer's answer to [ask]. */
    internal fun answer(resume: Boolean) {
        val a = _ask.value ?: return
        _ask.value = null
        a.engine.open(a.uri, if (resume) a.positionMs else 0)
    }

    internal fun startOver() {
        val s = _startOver.value ?: return
        _startOver.value = null
        s.engine.seekTo(0)
    }

    internal fun dismissStartOver() {
        _startOver.value = null
    }

    private fun publishQueue() {
        _state.value = _state.value.copy(hasPrevious = index > 0, hasNext = index < queue.lastIndex)
    }

    private fun play(laterInQueue: Boolean) {
        val e = engine ?: return
        val uri = queue.getOrNull(index) ?: return
        _ask.value = null
        _startOver.value = null
        opening?.cancel()
        opening = scope.launch {
            val settings = prefs.settings.first()
            val saved = resumePoints.get(uri.toString())
            when (val start = Resume.startAt(saved, settings.resume, laterInQueue, settings.resumeFirstOnly)) {
                StartAt.Beginning -> e.open(uri)
                is StartAt.Resume -> {
                    e.open(uri, start.positionMs)
                    _startOver.value = StartOver(e, uri)
                }
                is StartAt.Ask -> _ask.value = AskResume(e, uri, start.positionMs)
            }
        }
    }

    /** Follows [engine]'s state and writes resume points (US-22.1, US-22.5) until the engine goes. */
    private fun bind(engine: PlayerEngine) {
        if (this.engine === engine) return
        this.engine = engine
        recorder?.cancel()
        recorder = scope.launch(Dispatchers.Default) {
            var last: PlaybackState? = null
            var savedAt = 0L
            suspend fun save(s: PlaybackState) {
                savedAt = SystemClock.elapsedRealtime()
                write(s)
            }
            launch {
                PlayerSessions.current.first { it !== engine }
                last?.let { withContext(NonCancellable) { write(it) } }
                if (this@Playback.engine === engine) this@Playback.engine = null
                recorder?.cancel()
            }
            engine.state.collect { s ->
                val prev = last
                val now = SystemClock.elapsedRealtime()
                when {
                    prev != null && prev.uri != s.uri -> save(prev)
                    s.loaded && s.paused && prev?.paused == false -> save(s)
                    s.ended && prev?.ended == false -> save(s)
                    s.loaded && !s.paused && now - savedAt >= Resume.SAVE_EVERY_MS -> save(s)
                }
                last = s
            }
        }
    }

    private suspend fun write(s: PlaybackState) {
        val row = Resume.toSave(s, System.currentTimeMillis()) ?: return
        if (history.isPaused()) return
        resumePoints.put(row)
    }
}
