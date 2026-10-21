package app.player.feature.library

import app.player.core.data.library.LibraryFolder
import app.player.core.data.library.LibraryVideo

/** Keys of selectable rows. Folders and videos share one selection. */
object SelectionKey {
    fun of(folder: LibraryFolder) = "f:" + folder.key
    fun of(video: LibraryVideo) = "v:" + video.id
}

/**
 * The videos a selection stands for, in the order the list shows them (US-09.2): selected folders
 * first as all their own videos (US-09.4), then selected videos. Each video once; unavailable ones
 * (removed storage) are left out.
 */
fun selectedVideos(
    selected: Set<String>,
    shownFolders: List<LibraryFolder>,
    shownVideos: List<LibraryVideo>,
    allVideos: List<LibraryVideo>,
): List<LibraryVideo> {
    val out = LinkedHashMap<Long, LibraryVideo>()
    for (folder in shownFolders) {
        if (SelectionKey.of(folder) !in selected) continue
        allVideos.filter { it.folder == folder.key }.forEach { out.putIfAbsent(it.id, it) }
    }
    for (video in shownVideos) {
        if (SelectionKey.of(video) in selected) out.putIfAbsent(video.id, video)
    }
    return out.values.filter { it.available }
}

/** The new display name for a rename: the typed base name plus the old extension, or null if unusable. */
fun renamed(oldName: String, typed: String): String? {
    val base = typed.trim()
    if (base.isEmpty() || base.any { it in "/\\\u0000" } || base == "." || base == "..") return null
    val ext = oldName.substringAfterLast('.', "")
    val withExt = if (ext.isEmpty() || base.endsWith(".$ext", ignoreCase = true)) base else "$base.$ext"
    return withExt.takeIf { it != oldName }
}
