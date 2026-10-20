package app.player.core.data.library

/** What a scan changes in the index. Empty when the scan found exactly what was cached. */
data class IndexDiff(val changed: List<LibraryVideo>, val removed: List<Long>) {
    val isEmpty: Boolean get() = changed.isEmpty() && removed.isEmpty()

    companion object {
        /**
         * A cached video missing from the scan is deleted when its volume is mounted, and kept as
         * unavailable when its volume is not (an SD card taken out, US-80.3).
         */
        fun between(cached: List<LibraryVideo>, scanned: List<LibraryVideo>, mounted: Set<String>): IndexDiff {
            val old = cached.associateBy { it.id }
            val changed = scanned.filterTo(ArrayList()) { old[it.id] != it }
            val scannedIds = scanned.mapTo(HashSet(scanned.size)) { it.id }
            val removed = ArrayList<Long>()
            for (video in cached) {
                if (video.id in scannedIds) continue
                when {
                    video.volume in mounted -> removed += video.id
                    video.available -> changed += video.copy(available = false)
                }
            }
            return IndexDiff(changed, removed)
        }
    }
}
