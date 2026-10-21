package app.player.feature.library

import app.player.core.data.PlaybackPosition
import app.player.core.data.library.LibraryVideo

/** What a list row shows about a video's watch state (US-07). */
data class VideoMark(
    /** Modified within the set period and never played (US-07.1). */
    val isNew: Boolean = false,
    /** Played share for the progress bar, null when there is none to draw (US-07.2). */
    val progress: Float? = null,
    /** Reached the last 5 % or the end (US-07.3). */
    val finished: Boolean = false,
    /** The most recently played video of its folder (US-07.4). */
    val lastPlayed: Boolean = false,
)

/** Same share as the player's resume (feature/playback): at or past it a file counts as finished. */
const val FINISHED_SHARE = 0.95

/**
 * Marks for [videos] from the player's resume points ([positions] keyed by uri, the content uri the
 * library opens). [nowSec] and [newDays] set the "new" window; [markLastPlayed] is US-07.4's switch.
 */
fun marks(
    videos: List<LibraryVideo>,
    positions: Map<String, PlaybackPosition>,
    nowSec: Long,
    newDays: Int,
    markLastPlayed: Boolean,
): Map<Long, VideoMark> {
    val newSince = nowSec - newDays * 86_400L
    val lastPerFolder: Set<Long> = if (!markLastPlayed) emptySet() else videos
        .filter { it.uri in positions }
        .groupBy { it.folder }
        .mapNotNull { (_, vs) -> vs.maxByOrNull { positions.getValue(it.uri).updatedAt }?.id }
        .toSet()
    return videos.associate { v ->
        val p = positions[v.uri]
        val finished = p != null && p.durationMs > 0 && p.positionMs >= p.durationMs * FINISHED_SHARE
        val progress = if (p == null || finished || p.durationMs <= 0 || p.positionMs < 1000) null
        else (p.positionMs.toFloat() / p.durationMs).coerceIn(0f, 1f)
        v.id to VideoMark(
            isNew = p == null && v.modifiedSec >= newSince,
            progress = progress,
            finished = finished,
            lastPlayed = v.id in lastPerFolder,
        )
    }
}

/** The video for the resume button (US-08): the latest resume point whose file is still listed and available. */
fun lastPlayed(videos: List<LibraryVideo>, positions: Map<String, PlaybackPosition>): LibraryVideo? {
    val byUri = videos.filter { it.available }.associateBy { it.uri }
    return positions.values.sortedByDescending { it.updatedAt }.firstNotNullOfOrNull { byUri[it.uri] }
}
