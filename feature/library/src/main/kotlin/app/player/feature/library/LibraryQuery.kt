package app.player.feature.library

import app.player.core.data.library.LibraryFolder
import app.player.core.data.library.LibraryVideo
import java.text.Normalizer
import java.util.Locale

enum class SortKey { Name, Date, Size, Length, Resolution }

data class Sort(val key: SortKey = SortKey.Name, val descending: Boolean = false)

private val marks = Regex("\\p{Mn}+")

/** Lower case without accents, for search and name order: "Été" and "ete" match. */
fun fold(s: String): String = marks.replace(Normalizer.normalize(s, Normalizer.Form.NFD), "").lowercase(Locale.ROOT)

/**
 * Natural name order (US-04.2): digit runs compare as numbers, so "Episode 2" comes before "Episode 10".
 * Text compares without case or accents.
 */
object NaturalOrder : Comparator<String> {
    override fun compare(a: String, b: String): Int = Folded.compare(fold(a), fold(b)).takeIf { it != 0 } ?: a.compareTo(b)

    /** The same order on strings already passed through [fold]. Sorting folds each name once, not per compare. */
    internal object Folded : Comparator<String> {
        override fun compare(x: String, y: String): Int {
            var i = 0
            var j = 0
            while (i < x.length && j < y.length) {
                if (x[i].isDigit() && y[j].isDigit()) {
                    val si = i
                    val sj = j
                    while (i < x.length && x[i].isDigit()) i++
                    while (j < y.length && y[j].isDigit()) j++
                    val c = compareNumbers(x.substring(si, i), y.substring(sj, j))
                    if (c != 0) return c
                } else {
                    if (x[i] != y[j]) return x[i].compareTo(y[j])
                    i++
                    j++
                }
            }
            return (x.length - i).compareTo(y.length - j)
        }

        private fun compareNumbers(p: String, q: String): Int {
            val a = p.trimStart('0')
            val b = q.trimStart('0')
            return if (a.length != b.length) a.length.compareTo(b.length) else a.compareTo(b)
        }
    }
}

/** Sorts by [primary], then natural name order, folding each name once. */
private fun <T> List<T>.sortedNaturally(name: (T) -> String, primary: Comparator<T>?, descending: Boolean): List<T> {
    val keyed = map { Triple(it, fold(name(it)), name(it)) }
    val byName = compareBy<Triple<T, String, String>, String>(NaturalOrder.Folded) { it.second }.thenBy { it.third }
    val first = primary?.let { p -> Comparator<Triple<T, String, String>> { a, b -> p.compare(a.first, b.first) } }
    val order = when {
        first == null -> if (descending) byName.reversed() else byName
        else -> (if (descending) first.reversed() else first).then(byName)
    }
    return keyed.sortedWith(order).map { it.first }
}

/** Orders videos by the chosen key; name breaks ties. */
fun List<LibraryVideo>.sortedFor(sort: Sort): List<LibraryVideo> {
    val primary: Comparator<LibraryVideo>? = when (sort.key) {
        SortKey.Name -> null
        SortKey.Date -> compareBy { it.modifiedSec }
        SortKey.Size -> compareBy { it.sizeBytes }
        SortKey.Length -> compareBy { it.durationMs }
        SortKey.Resolution -> compareBy { it.width.toLong() * it.height }
    }
    return sortedNaturally({ it.name }, primary, sort.descending)
}

/**
 * Orders folders by the same key (US-04.1): newest video for date, totals for size and length,
 * largest video for resolution. [videos] are the videos the folders were built from.
 */
fun List<LibraryFolder>.sortedFor(sort: Sort, videos: List<LibraryVideo>): List<LibraryFolder> {
    if (sort.key == SortKey.Name) return sortedNaturally({ it.name }, null, sort.descending)
    val byFolder = videos.groupBy { it.folder }
    val value = associate { f ->
        val vs = byFolder[f.key].orEmpty()
        f.key to when (sort.key) {
            SortKey.Date -> vs.maxOfOrNull { it.modifiedSec } ?: 0
            SortKey.Size -> vs.sumOf { it.sizeBytes }
            SortKey.Length -> vs.sumOf { it.durationMs }
            else -> vs.maxOfOrNull { it.width.toLong() * it.height } ?: 0
        }
    }
    return sortedNaturally({ it.name }, compareBy { value.getValue(it.key) }, sort.descending)
}

/**
 * What the viewer has taken out of the library: excluded folders with their subfolders (US-05.1),
 * hidden folders and videos (US-15.1), and switched-off extensions (US-05.4).
 */
data class LibraryFilter(
    val excludedFolders: Set<String> = emptySet(),
    val hidden: Set<String> = emptySet(),
    val disabledExtensions: Set<String> = emptySet(),
) {
    fun accepts(video: LibraryVideo): Boolean =
        video.uri !in hidden &&
            video.folder !in hidden &&
            excludedFolders.none { video.folder.startsWith(it) } &&
            (disabledExtensions.isEmpty() || extensionOf(video.name)?.lowercase(Locale.ROOT) !in disabledExtensions)
}

/** Search (US-06.1): folders and videos whose shown name contains the text, without case or accents. */
data class SearchResult(val folders: List<LibraryFolder>, val videos: List<LibraryVideo>)

fun search(query: String, folders: List<LibraryFolder>, videos: List<LibraryVideo>): SearchResult {
    val q = fold(query.trim())
    if (q.isEmpty()) return SearchResult(emptyList(), emptyList())
    return SearchResult(
        folders.filter { q in fold(it.name) },
        videos.filter { q in fold(displayName(it.name)) },
    )
}

/** Recent searches (US-06.2): newest first, no duplicates, at most 10. */
fun List<String>.withSearch(query: String): List<String> {
    val q = query.trim()
    if (q.isEmpty()) return this
    return (listOf(q) + filterNot { it.equals(q, ignoreCase = true) }).take(10)
}

/** Video extensions offered in the file types list even before a file of that type is indexed. */
val KnownExtensions = listOf(
    "3gp", "avi", "flv", "m2ts", "m4v", "mkv", "mov", "mp4", "mpeg", "mpg", "mts", "ogv", "ts", "webm", "wmv",
)
