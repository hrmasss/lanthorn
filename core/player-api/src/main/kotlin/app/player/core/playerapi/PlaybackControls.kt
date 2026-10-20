package app.player.core.playerapi

import android.content.Intent
import android.net.Uri
import android.os.Build
import app.player.core.engine.PlayerEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * What plays, in what order and from where (resume, queue, loop, lock, PiP), one per app, from
 * feature/playback through [PlayerExtension.playback]. The player activity opens every intent
 * through [open]; the controls read the flows and call the actions. Members playback has not built
 * yet keep their defaults, and the button they drive stays disabled.
 */
interface PlaybackControls {
    /**
     * Opens what [intent] asks for on [engine]: one file, a link, or a queue built with
     * [PlayerIntents.withQueue]. [onClose] closes the player when the queue ends with "back to
     * list" on (US-24.5). Returns false when the intent has nothing to play.
     */
    fun open(engine: PlayerEngine, intent: Intent, onClose: () -> Unit): Boolean

    val hasPrevious: StateFlow<Boolean> get() = OFF
    val hasNext: StateFlow<Boolean> get() = OFF
    fun previous() {}
    fun next() {}

    /** False hides previous and next (US-29.3). */
    val showPreviousNext: StateFlow<Boolean> get() = ON

    /** True shows back and forward step buttons beside play/pause (US-29.2). */
    val stepButtons: StateFlow<Boolean> get() = OFF

    /** While true the player hides its controls and gestures and passes touches to playback's Overlay (US-28). */
    val locked: StateFlow<Boolean> get() = OFF
    fun lock() {}
    fun rotate() {}

    /** Enters picture-in-picture. False when not available. */
    fun pip(): Boolean = false

    val shuffle: StateFlow<Boolean> get() = OFF
    fun toggleShuffle() {}
    val backgroundPlay: StateFlow<Boolean> get() = OFF
    fun toggleBackgroundPlay() {}

    private companion object {
        val OFF: StateFlow<Boolean> = MutableStateFlow(false)
        val ON: StateFlow<Boolean> = MutableStateFlow(true)
    }
}

/** How an opener passes the list it shows, so next and previous follow it (US-24.1). */
object PlayerIntents {
    private const val EXTRA_QUEUE = "app.player.extra.QUEUE"
    private const val EXTRA_INDEX = "app.player.extra.QUEUE_INDEX"

    /** At most this many uris travel in an intent, centred on the opened one, to stay far below the binder limit. */
    const val MAX_QUEUE = 2000

    /** Adds [uris] as the queue; [index] is the opened file. Sets the intent's data to that file. */
    fun withQueue(intent: Intent, uris: List<Uri>, index: Int): Intent {
        require(index in uris.indices)
        val from = (index - MAX_QUEUE / 2).coerceIn(0, maxOf(0, uris.size - MAX_QUEUE))
        val window = uris.subList(from, minOf(uris.size, from + MAX_QUEUE))
        return intent.setData(uris[index])
            .putParcelableArrayListExtra(EXTRA_QUEUE, ArrayList(window))
            .putExtra(EXTRA_INDEX, index - from)
    }

    /** The queue and the opened index, or null when the intent carries none. */
    fun queue(intent: Intent): Pair<List<Uri>, Int>? {
        val uris = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableArrayListExtra(EXTRA_QUEUE, Uri::class.java)
        } else {
            @Suppress("DEPRECATION") intent.getParcelableArrayListExtra(EXTRA_QUEUE)
        } ?: return null
        val index = intent.getIntExtra(EXTRA_INDEX, -1)
        return if (index in uris.indices) uris to index else null
    }
}
