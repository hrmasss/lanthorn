package app.player.core.data.library

/** What a scan changes in the index. Empty when the scan found exactly what was cached. */
data class IndexDiff(val changed: List<LibraryVideo>, val removed: List<Long>) {
    val isEmpty: Boolean get() = changed.isEmpty() && removed.isEmpty()

    companion object {
        fun between(cached: List<LibraryVideo>, scanned: List<LibraryVideo>): IndexDiff {
            val old = cached.associateBy { it.id }
            val changed = scanned.filter { old[it.id] != it }
            val scannedIds = scanned.mapTo(HashSet(scanned.size)) { it.id }
            val removed = cached.filter { it.id !in scannedIds }.map { it.id }
            return IndexDiff(changed, removed)
        }
    }
}
