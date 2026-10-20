package app.player.feature.playback

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.core.net.toUri
import app.player.core.data.library.LibraryIndex
import app.player.core.data.library.LibraryVideo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * The videos of the opened file's folder, for a file opened without a queue (US-13.3). Read from
 * the library index, so it costs one Room query and no MediaStore scan.
 */
internal object FolderQueue {
    suspend fun around(context: Context, uri: Uri): Pair<List<Uri>, Int>? = withContext(Dispatchers.IO) {
        val videos = runCatching { LibraryIndex.get(context).videos.first() }.getOrNull() ?: return@withContext null
        val key = mediaUri(context, uri)
        val opened = videos.firstOrNull { it.uri == key || (uri.scheme == "file" && it.path == uri.path) }
            ?: return@withContext null
        val folder = videos.filter { it.folder == opened.folder && it.available }.sortedWith(byName)
        val index = folder.indexOfFirst { it.id == opened.id }
        // The opened uri stays as given, so its resume row keeps the key it was saved under.
        folder.map { if (it.id == opened.id) uri else it.uri.toUri() } to index
    }

    /** A file manager's document uri points at the same MediaStore row the library indexed. */
    private fun mediaUri(context: Context, uri: Uri): String {
        if (uri.authority == MediaStore.AUTHORITY) return uri.toString()
        if (uri.scheme != "content") return uri.toString()
        return runCatching { MediaStore.getMediaUri(context, uri)?.toString() }.getOrNull() ?: uri.toString()
    }

    private val byName: Comparator<LibraryVideo> = Comparator { a, b -> NaturalOrder.compare(a.name, b.name) }
}

/** Name order that reads numbers as numbers: "Episode 2" before "Episode 10". Case ignored. */
internal object NaturalOrder : Comparator<String> {
    override fun compare(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val ca = a[i]
            val cb = b[j]
            if (ca.isDigit() && cb.isDigit()) {
                val ei = a.indexOfFirstFrom(i) { !it.isDigit() }
                val ej = b.indexOfFirstFrom(j) { !it.isDigit() }
                val na = a.substring(i, ei).trimStart('0')
                val nb = b.substring(j, ej).trimStart('0')
                if (na.length != nb.length) return na.length - nb.length
                val c = na.compareTo(nb)
                if (c != 0) return c
                i = ei
                j = ej
            } else {
                val c = ca.lowercaseChar().compareTo(cb.lowercaseChar())
                if (c != 0) return c
                i++
                j++
            }
        }
        return (a.length - i) - (b.length - j)
    }

    private inline fun String.indexOfFirstFrom(start: Int, predicate: (Char) -> Boolean): Int {
        for (k in start until length) if (predicate(this[k])) return k
        return length
    }
}
