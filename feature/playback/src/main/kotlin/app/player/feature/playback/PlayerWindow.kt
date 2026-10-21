package app.player.feature.playback

import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.drawable.Icon
import android.os.Build
import android.util.Log
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import app.player.core.engine.PlayerEngine
import app.player.core.engine.PlayerSessions
import app.player.core.engine.PropertyType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * What playback does with the player's window (P1.14): orientation (US-56), lock (US-28) and
 * picture-in-picture (US-58). One per player activity, made by [Playback.attach].
 */
internal class PlayerWindow(
    private val activity: ComponentActivity,
    private val engine: PlayerEngine,
    private val settings: StateFlow<PlaybackSettings>,
    private val _locked: MutableStateFlow<Boolean>,
) {
    /** Set by the rotate button and held until the player closes (US-56.2). */
    private var rotated: Int? = null
    private var inPip = false

    fun start() {
        activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.CREATED) {
                val rotation = engine.observe("video-params/rotate", PropertyType.Int64)
                launch {
                    combine(engine.state, rotation, settings) { s, r, set -> Triple(s.video, r ?: 0L, set.orientation) }
                        .distinctUntilChanged()
                        .collect { (video, r, mode) -> applyOrientation(mode, video?.width ?: 0, video?.height ?: 0, r.toInt()) }
                }
                launch {
                    combine(engine.state, settings) { s, set -> PipKey(s.paused, s.video?.width ?: 0, s.video?.height ?: 0, set.onLeave) }
                        .distinctUntilChanged()
                        .collect { updatePip(it) }
                }
                launch {
                    settings.collect { PlayerSessions.keepPlayingInBackground = it.onLeave == OnLeave.Background }
                }
            }
        }
        activity.addOnPictureInPictureModeChangedListener { info ->
            inPip = info.isInPictureInPictureMode
            Log.i(TAG, "picture-in-picture ${if (inPip) "on" else "off"}")
        }
        // Before Android 12 there is no auto-enter: enter on the leave hint (US-58.1).
        activity.addOnUserLeaveHintListener {
            if (Build.VERSION.SDK_INT < 31 && wantsPip()) enterPip()
        }
        activity.lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) {
                // The PiP window was closed: stop (the activity pauses and saves the position) and
                // close the player, so it does not linger in recents (US-58.4).
                if (inPip && !activity.isChangingConfigurations) {
                    Log.i(TAG, "picture-in-picture closed, stopping")
                    activity.finish()
                }
            }

            override fun onDestroy(owner: LifecycleOwner) {
                runCatching { activity.unregisterReceiver(receiver) }
            }
        })
        ContextCompat.registerReceiver(activity, receiver, IntentFilter(ACTION_PIP), ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    fun lock() {
        if (_locked.value) return
        _locked.value = true
        // US-28.3: rotation stays where it is while locked.
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LOCKED
        Log.i(TAG, "locked (${settings.value.lockMode})")
    }

    fun unlock() {
        if (!_locked.value) return
        _locked.value = false
        val s = engine.state.value
        applyOrientation(settings.value.orientation, s.video?.width ?: 0, s.video?.height ?: 0, 0)
        Log.i(TAG, "unlocked")
    }

    fun rotate() {
        if (_locked.value) return
        val landscape = activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        rotated = Orientation.other(landscape)
        activity.requestedOrientation = rotated!!
        Log.i(TAG, "rotated to ${if (landscape) "portrait" else "landscape"}")
    }

    private fun applyOrientation(mode: OrientationMode, width: Int, height: Int, rotateDeg: Int) {
        if (_locked.value) return
        val wanted = rotated ?: Orientation.requested(mode, width, height, rotateDeg)
        if (activity.requestedOrientation != wanted) {
            activity.requestedOrientation = wanted
            Log.i(TAG, "orientation $wanted for $mode, video ${width}x$height turned $rotateDeg")
        }
    }

    private data class PipKey(val paused: Boolean, val width: Int, val height: Int, val onLeave: OnLeave)

    private fun wantsPip() = settings.value.onLeave == OnLeave.Pip && !engine.state.value.paused && engine.state.value.video != null

    private fun updatePip(key: PipKey) {
        if (!activity.packageManager.hasSystemFeature("android.software.picture_in_picture")) return
        activity.setPictureInPictureParams(params(key))
    }

    /** Enters PiP now, for the leave hint and the player's PiP button. False when refused. */
    fun enterPip(): Boolean {
        val s = engine.state.value
        return runCatching { activity.enterPictureInPictureMode(params(PipKey(s.paused, s.video?.width ?: 0, s.video?.height ?: 0, settings.value.onLeave))) }
            .onFailure { Log.w(TAG, "picture-in-picture refused", it) }
            .getOrDefault(false)
    }

    private fun params(key: PipKey): PictureInPictureParams {
        val b = PictureInPictureParams.Builder().setActions(actions(key.paused))
        aspect(key.width, key.height)?.let(b::setAspectRatio)
        if (Build.VERSION.SDK_INT >= 31) {
            b.setAutoEnterEnabled(key.onLeave == OnLeave.Pip && !key.paused && key.width > 0)
            b.setSeamlessResizeEnabled(true)
        }
        return b.build()
    }

    /** The video's shape, inside the range Android accepts for a PiP window. */
    private fun aspect(width: Int, height: Int): Rational? {
        if (width <= 0 || height <= 0) return null
        val r = (width.toFloat() / height).coerceIn(MIN_ASPECT, MAX_ASPECT)
        return Rational((r * 1000).toInt(), 1000)
    }

    /** US-58.2: back 10 s, play/pause, forward 10 s. */
    private fun actions(paused: Boolean): List<RemoteAction> = listOf(
        action(BACK, R.drawable.ic_pip_back, R.string.pip_back),
        if (paused) action(PLAY, R.drawable.ic_pip_play, R.string.pip_play) else action(PAUSE, R.drawable.ic_pip_pause, R.string.pip_pause),
        action(FORWARD, R.drawable.ic_pip_forward, R.string.pip_forward),
    )

    private fun action(code: Int, icon: Int, label: Int): RemoteAction {
        val intent = Intent(ACTION_PIP).setPackage(activity.packageName).putExtra(EXTRA_CODE, code)
        val pending = PendingIntent.getBroadcast(activity, code, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val text = activity.getString(label)
        return RemoteAction(Icon.createWithResource(activity, icon), text, text, pending)
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val s = engine.state.value
            when (intent.getIntExtra(EXTRA_CODE, -1)) {
                PLAY -> engine.play()
                PAUSE -> engine.pause()
                BACK -> engine.seekTo((s.positionMs - STEP_MS).coerceAtLeast(0))
                FORWARD -> engine.seekTo((s.positionMs + STEP_MS).coerceAtMost(maxOf(0, s.durationMs)))
            }
            Log.i(TAG, "picture-in-picture action ${intent.getIntExtra(EXTRA_CODE, -1)}")
        }
    }

    private companion object {
        const val TAG = "Playback"
        const val ACTION_PIP = "app.player.playback.PIP"
        const val EXTRA_CODE = "code"
        const val PLAY = 1
        const val PAUSE = 2
        const val BACK = 3
        const val FORWARD = 4
        const val STEP_MS = 10_000L
        const val MIN_ASPECT = 1 / 2.39f
        const val MAX_ASPECT = 2.39f
    }
}
