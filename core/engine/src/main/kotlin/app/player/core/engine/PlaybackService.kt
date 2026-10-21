package app.player.core.engine

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.IBinder
import android.util.Log

/**
 * Keeps playback alive while no player activity is visible: background play (US-59.1) and the
 * hand-over to PiP (docs/decisions/2026-10-20-engine-lifecycle.md). A `mediaPlayback` foreground
 * service that holds the engine through [PlayerSessions]. It never creates an engine: if none is
 * live when it starts, it stops.
 *
 * The notification here is the minimum Android requires. The media notification proper (session,
 * artwork, headset keys) is P1.15's and replaces it through [notificationFactory].
 */
class PlaybackService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val engine = PlayerSessions.current.value
        if (engine == null || intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_TOGGLE) engine.togglePause()
        if (!holding) {
            PlayerSessions.acquire(this, this)
            holding = true
            Log.i(TAG, "holding the engine at ${engine.state.value.positionMs} ms")
        }
        val notification = notificationFactory?.invoke(this, engine) ?: basicNotification(engine)
        startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        if (holding) {
            PlayerSessions.release(this)
            holding = false
            Log.i(TAG, "released the engine")
        }
        super.onDestroy()
    }

    private var holding = false

    private fun basicNotification(engine: PlayerEngine): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Playback", NotificationManager.IMPORTANCE_LOW))
        val title = engine.state.value.uri?.let { Uri.parse(it).lastPathSegment } ?: "Playing"
        val open = openPlayer?.invoke(this)?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        fun action(name: String, label: String) = Notification.Action.Builder(
            null, label,
            PendingIntent.getService(this, name.hashCode(), Intent(this, PlaybackService::class.java).setAction(name), PendingIntent.FLAG_IMMUTABLE),
        ).build()
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(title)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(action(ACTION_TOGGLE, "Play/pause"))
            .addAction(action(ACTION_STOP, "Stop"))
            .build()
    }

    companion object {
        private const val TAG = "PlaybackService"
        private const val CHANNEL = "playback"
        /** The notification's id, so P1.15 can update the one [notificationFactory] made. */
        const val NOTIFICATION_ID = 1
        const val ACTION_TOGGLE = "app.player.playback.TOGGLE"
        const val ACTION_STOP = "app.player.playback.STOP"

        /** The intent that brings the player back, set by the player feature. */
        var openPlayer: ((Context) -> Intent)? = null

        /** P1.15's media notification. Null: the basic one above. */
        var notificationFactory: ((Context, PlayerEngine) -> Notification)? = null

        /** Starts holding the live engine in the foreground. Call when the player leaves the screen. */
        fun start(context: Context) {
            if (PlayerSessions.current.value == null) return
            context.startForegroundService(Intent(context, PlaybackService::class.java))
        }

        /** Lets go of the engine. Call when the player is back on screen. */
        fun stop(context: Context) {
            context.stopService(Intent(context, PlaybackService::class.java))
        }
    }
}
