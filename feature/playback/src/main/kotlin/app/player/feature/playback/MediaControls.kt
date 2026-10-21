package app.player.feature.playback

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.MediaMetadataRetriever
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import app.player.core.engine.PlaybackService
import app.player.core.engine.PlayerEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import app.player.core.engine.PlaybackState as EngineState

/**
 * The player as a media app (P1.15): a media session for headset, Bluetooth and lock-screen
 * controls (US-31.1, US-59.2), the media notification while the playback service holds the engine
 * (US-59.2), audio focus (US-31.3) and a pause when the headset goes (US-31.2). One per engine,
 * made by [Playback] when it binds the engine and released with it. Main thread.
 */
internal class MediaControls(
    private val context: Context,
    private val engine: PlayerEngine,
    private val scope: CoroutineScope,
    private val title: StateFlow<String?>,
    /** A media key or a session command, the same path as keys on the player screen. */
    private val onAction: (KeyAction) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private val audio = context.getSystemService(AudioManager::class.java)
    private val notifications = context.getSystemService(NotificationManager::class.java)
    private val session = MediaSession(context, "Lanthorn")
    private val focus = FocusPolicy()
    private var focusHeld = false
    /** The pause state focus handling last asked for; any other change is the viewer's. */
    private var focusSet: Boolean? = null
    private var art: Bitmap? = null
    private var artUri: String? = null
    private var released = false

    /** True while the playback service shows this notification instead of its own. */
    @Volatile var inService = false

    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(
            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build(),
        )
        .setWillPauseWhenDucked(false)
        .setOnAudioFocusChangeListener({ change -> onFocus(change) }, main)
        .build()

    private val noisy = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            if (intent.action != AudioManager.ACTION_AUDIO_BECOMING_NOISY || engine.state.value.paused) return
            val at = SystemClock.elapsedRealtime()
            engine.pause()
            Log.i(TAG, "audio output gone (headset or Bluetooth), paused ${SystemClock.elapsedRealtime() - at} ms after the broadcast")
        }
    }

    private val buttons = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            when (intent.action) {
                ACTION_TOGGLE -> onAction(KeyAction.Toggle)
                ACTION_NEXT -> onAction(KeyAction.Next)
                ACTION_PREVIOUS -> onAction(KeyAction.Previous)
            }
        }
    }

    fun start() {
        session.setCallback(object : MediaSession.Callback() {
            override fun onPlay() = onAction(KeyAction.Play)
            override fun onPause() = onAction(KeyAction.Pause)
            override fun onStop() = onAction(KeyAction.Pause)
            override fun onSkipToNext() = onAction(KeyAction.Next)
            override fun onSkipToPrevious() = onAction(KeyAction.Previous)
            override fun onFastForward() = onAction(KeyAction.Forward)
            override fun onRewind() = onAction(KeyAction.Back)
            override fun onSeekTo(pos: Long) = engine.seekTo(pos)

            // Keys from a headset or Bluetooth while the player is not in front (in front, the
            // activity sees them first through Playback.onKey).
            override fun onMediaButtonEvent(intent: Intent): Boolean {
                val key = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_KEY_EVENT, KeyEvent::class.java) ?: return false
                return Playback.mediaKey(key)
            }
        }, main)
        PlaybackService.openPlayer?.invoke(context)?.let {
            session.setSessionActivity(PendingIntent.getActivity(context, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        }
        session.isActive = true
        ContextCompat.registerReceiver(context, noisy, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), ContextCompat.RECEIVER_NOT_EXPORTED)
        ContextCompat.registerReceiver(
            context, buttons, IntentFilter().apply { addAction(ACTION_TOGGLE); addAction(ACTION_NEXT); addAction(ACTION_PREVIOUS) },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        scope.launch {
            var shown: EngineState? = null
            var shownAt = 0L
            engine.state.collect { s ->
                if (released) return@collect
                if (!s.paused && shown?.paused != false) onPlaying()
                if (shown != null && shown!!.paused != s.paused) {
                    if (focusSet != s.paused) focus.viewerActed()
                    focusSet = null
                }
                val now = SystemClock.elapsedRealtime()
                if (needsUpdate(shown, now - shownAt, s)) {
                    shown = s
                    shownAt = now
                    session.setPlaybackState(playbackState(s))
                    if (inService) notifications.notify(PlaybackService.NOTIFICATION_ID, notification())
                }
            }
        }
        scope.launch {
            combine(engine.state.map { it.uri to it.durationMs }.distinctUntilChanged(), title) { (uri, duration), t -> Triple(uri, duration, t) }
                .collect { (uri, duration, t) -> if (!released) updateMetadata(uri, duration, t) }
        }
    }

    fun release() {
        if (released) return
        released = true
        runCatching { context.unregisterReceiver(noisy) }
        runCatching { context.unregisterReceiver(buttons) }
        audio.abandonAudioFocusRequest(focusRequest)
        session.isActive = false
        session.release()
    }

    /** The viewer played or paused on purpose: a later focus gain leaves it as they set it. */
    fun viewerActed() = focus.viewerActed()

    /** The media notification (US-59.2), for [PlaybackService.notificationFactory]. */
    fun notification(): Notification {
        notifications.createNotificationChannel(
            NotificationChannel(CHANNEL, context.getString(R.string.media_channel), NotificationManager.IMPORTANCE_LOW),
        )
        val s = engine.state.value
        fun action(icon: Int, label: Int, action: String) = Notification.Action.Builder(
            Icon.createWithResource(context, icon), context.getString(label),
            PendingIntent.getBroadcast(context, action.hashCode(), Intent(action).setPackage(context.packageName), PendingIntent.FLAG_IMMUTABLE),
        ).build()
        val open = PlaybackService.openPlayer?.invoke(context)?.let {
            PendingIntent.getActivity(context, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        return Notification.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(title.value ?: s.uri?.let { Uri.parse(it).lastPathSegment })
            .setLargeIcon(art)
            .setContentIntent(open)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(!s.paused)
            .addAction(action(android.R.drawable.ic_media_previous, R.string.media_previous, ACTION_PREVIOUS))
            .addAction(
                if (s.paused) action(android.R.drawable.ic_media_play, R.string.media_play, ACTION_TOGGLE)
                else action(android.R.drawable.ic_media_pause, R.string.media_pause, ACTION_TOGGLE),
            )
            .addAction(action(android.R.drawable.ic_media_next, R.string.media_next, ACTION_NEXT))
            .setStyle(Notification.MediaStyle().setMediaSession(session.sessionToken).setShowActionsInCompactView(0, 1, 2))
            .build()
    }

    private fun onPlaying() {
        if (focusHeld) return
        focusHeld = audio.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        Log.i(TAG, "audio focus ${if (focusHeld) "granted" else "refused"}")
        // Refused, for example during a call: do not play over it.
        if (!focusHeld) engine.pause()
    }

    private fun onFocus(change: Int) {
        if (change == AudioManager.AUDIOFOCUS_LOSS) focusHeld = false
        val action = focus.onChange(change, playing = !engine.state.value.paused)
        Log.i(TAG, "audio focus change $change: ${action.name.lowercase()}")
        when (action) {
            FocusAction.Pause -> { focusSet = true; engine.pause() }
            FocusAction.Play -> { focusSet = false; engine.play() }
            FocusAction.None -> Unit
        }
    }

    /** Playing state, a file change, or a position the system's own guess has wrong (a seek). */
    private fun needsUpdate(shown: EngineState?, sinceMs: Long, s: EngineState): Boolean {
        if (shown == null || shown.paused != s.paused || shown.uri != s.uri || shown.durationMs != s.durationMs ||
            shown.loaded != s.loaded || shown.buffering != s.buffering
        ) return true
        val guess = shown.positionMs + if (shown.paused) 0 else sinceMs
        return abs(s.positionMs - guess) > POSITION_SLACK_MS
    }

    private fun playbackState(s: EngineState): PlaybackState = PlaybackState.Builder()
        .setActions(
            PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
                PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_SEEK_TO or
                PlaybackState.ACTION_FAST_FORWARD or PlaybackState.ACTION_REWIND or PlaybackState.ACTION_STOP,
        )
        .setState(
            when {
                !s.loaded -> PlaybackState.STATE_CONNECTING
                s.buffering -> PlaybackState.STATE_BUFFERING
                s.paused -> PlaybackState.STATE_PAUSED
                else -> PlaybackState.STATE_PLAYING
            },
            s.positionMs, if (s.paused) 0f else 1f, SystemClock.elapsedRealtime(),
        )
        .build()

    private suspend fun updateMetadata(uri: String?, durationMs: Long, title: String?) {
        if (uri != artUri) {
            artUri = uri
            art = uri?.let { withContext(Dispatchers.IO) { cover(it) } }
        }
        if (released) return
        session.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, title ?: uri?.let { Uri.parse(it).lastPathSegment })
                .putLong(MediaMetadata.METADATA_KEY_DURATION, durationMs)
                .apply { art?.let { putBitmap(MediaMetadata.METADATA_KEY_ART, it) } }
                .build(),
        )
        if (inService) notifications.notify(PlaybackService.NOTIFICATION_ID, notification())
    }

    /** Cover art: the file's own picture, or a video frame (US-59.2). Local files only. */
    private fun cover(uri: String): Bitmap? = runCatching {
        val u = Uri.parse(uri)
        if (u.scheme != "content" && u.scheme != "file") return null
        MediaMetadataRetriever().use { r ->
            r.setDataSource(context, u)
            r.embeddedPicture?.let { bytes ->
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = 2 })
            } ?: r.getScaledFrameAtTime(COVER_FRAME_US, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, COVER_PX, COVER_PX)
        }
    }.onFailure { Log.w(TAG, "no cover for $uri", it) }.getOrNull()

    companion object {
        private const val TAG = "PlaybackMedia"
        private const val CHANNEL = "media"
        private const val ACTION_TOGGLE = "app.player.playback.media.TOGGLE"
        private const val ACTION_NEXT = "app.player.playback.media.NEXT"
        private const val ACTION_PREVIOUS = "app.player.playback.media.PREVIOUS"
        private const val POSITION_SLACK_MS = 1_500L
        private const val COVER_FRAME_US = 10_000_000L
        private const val COVER_PX = 512
    }
}
