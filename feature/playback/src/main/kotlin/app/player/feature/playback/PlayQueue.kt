package app.player.feature.playback

import kotlin.random.Random

/** Loop setting (US-24). */
enum class LoopMode { Off, One, All }

/**
 * Play order over a list (US-24), without Android. Positions are into [items]. With shuffle on, the
 * order is the current item, then the rest at random, so each item plays once before any repeats
 * (US-24.4). Loop all at the end starts a fresh order.
 */
class PlayQueue<T>(val items: List<T>, start: Int, private val random: Random = Random.Default) {
    private var order: List<Int> = items.indices.toList()
    private var at = start.coerceIn(0, maxOf(0, items.lastIndex))
    private val failed = mutableSetOf<Int>()

    var shuffle = false
        private set

    val index: Int get() = order[at]
    val current: T get() = items[index]
    val size: Int get() = items.size

    fun setShuffle(on: Boolean) {
        if (on == shuffle) return
        val now = index
        shuffle = on
        if (on) {
            order = listOf(now) + (items.indices - now).shuffled(random)
            at = 0
        } else {
            order = items.indices.toList()
            at = now
        }
    }

    fun hasNext(loop: LoopMode) = at < order.lastIndex || (loop == LoopMode.All && items.size > 1)

    fun hasPrevious(loop: LoopMode) = at > 0 || (loop == LoopMode.All && items.size > 1)

    /** Moves to the next item. Returns false at the end without loop all (US-24.1, US-24.3). */
    fun next(loop: LoopMode): Boolean {
        if (at < order.lastIndex) {
            at++
            return true
        }
        if (loop != LoopMode.All || items.size < 2) return false
        if (shuffle) {
            // A new round: every item once again, not starting with the one just played.
            val last = index
            order = (items.indices - last).shuffled(random) + last
        }
        at = 0
        return true
    }

    fun previous(loop: LoopMode): Boolean {
        if (at > 0) {
            at--
            return true
        }
        if (loop != LoopMode.All || items.size < 2) return false
        at = order.lastIndex
        return true
    }

    fun markFailed() {
        failed += index
    }

    fun markPlayed() {
        failed -= index
    }

    /** True when every item failed (US-75.3). */
    val allFailed: Boolean get() = items.isNotEmpty() && failed.size == items.size
}
