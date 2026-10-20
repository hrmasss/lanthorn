package app.player.core.data.library

import android.content.Context
import android.os.SystemClock
import app.player.core.data.PlayerDatabase
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The library index: a Room copy of what MediaStore holds. Screens draw from [videos] at once and
 * call [refresh] in the background; a scan only writes what changed (US-01.3, NF-1).
 */
class LibraryIndex(
    private val dao: LibraryDao,
    private val source: VideoSource,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
) {
    data class ScanResult(val count: Int, val changed: Int, val removed: Int, val ms: Long)

    private val mutex = Mutex()
    private val _scanning = MutableStateFlow(false)

    /** Every indexed video, unsorted. The first emission comes from disk, not from a scan. */
    val videos: Flow<List<LibraryVideo>> = dao.observeAll()

    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    /** Scans the source and applies the difference. Calls made while a scan runs wait for it, then scan again. */
    suspend fun refresh(): ScanResult = mutex.withLock {
        withContext(io) {
            _scanning.value = true
            try {
                val start = clock()
                val scanned = source.scan()
                val diff = IndexDiff.between(dao.all(), scanned)
                if (!diff.isEmpty) dao.apply(diff)
                ScanResult(scanned.size, diff.changed.size, diff.removed.size, clock() - start)
            } finally {
                _scanning.value = false
            }
        }
    }

    companion object {
        @Volatile private var instance: LibraryIndex? = null

        /** Cheap: no disk access until the first query or scan. */
        fun get(context: Context): LibraryIndex = instance ?: synchronized(this) {
            instance ?: LibraryIndex(
                PlayerDatabase.get(context).library(),
                MediaStoreSource(context),
            ).also { instance = it }
        }
    }
}
