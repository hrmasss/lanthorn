package app.player.feature.library

import android.content.Context
import android.os.Environment
import android.os.storage.StorageManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import app.player.core.data.library.LibraryFolder
import app.player.core.data.library.LibraryIndex
import app.player.core.data.library.toFolders
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface LibraryUiState {
    /** Before the screen has read the grant state, or before the cached index has loaded. */
    data object Loading : LibraryUiState

    /** No media access. [permanentlyDenied]: the system will not show its prompt again. */
    data class NeedsAccess(val permanentlyDenied: Boolean) : LibraryUiState

    data class Content(
        val folders: List<LibraryFolder>,
        val videoCount: Int,
        val partial: Boolean,
        val scanning: Boolean,
        /** True once a scan has finished in this process. Until then an empty list is not yet "no videos". */
        val scanned: Boolean,
        /** Storage the scan covers, named for the viewer (US-01.4). */
        val volumes: List<String>,
    ) : LibraryUiState
}

class LibraryViewModel(
    private val index: LibraryIndex,
    volumes: suspend () -> List<String>,
    io: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private data class Access(val access: MediaAccess, val permanentlyDenied: Boolean)

    private val access = MutableStateFlow<Access?>(null)
    private val scanned = MutableStateFlow(false)
    private val volumeNames = flow { emit(volumes()) }.flowOn(io)

    @OptIn(ExperimentalCoroutinesApi::class)
    val state: StateFlow<LibraryUiState> = access.flatMapLatest { a ->
        when {
            a == null -> flowOf(LibraryUiState.Loading)
            a.access == MediaAccess.None -> flowOf(LibraryUiState.NeedsAccess(a.permanentlyDenied))
            else -> combine(index.videos, index.scanning, scanned, volumeNames) { videos, scanning, done, vols ->
                LibraryUiState.Content(
                    folders = videos.toFolders().sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name }),
                    videoCount = videos.size,
                    partial = a.access == MediaAccess.Partial,
                    scanning = scanning,
                    scanned = done,
                    volumes = vols,
                )
            }
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, LibraryUiState.Loading)

    /** Called by the screen on start and after every permission result. */
    fun onAccess(access: MediaAccess, permanentlyDenied: Boolean) {
        this.access.value = Access(access, permanentlyDenied)
    }

    /** Rescans in the background. No-op without access. */
    fun refresh() {
        if (access.value?.access?.let { it != MediaAccess.None } != true) return
        viewModelScope.launch {
            lastScan = index.refresh()
            scanned.value = true
        }
    }

    private var pendingChange: Job? = null

    /** MediaStore changed while the library is visible. Changes come in bursts, so wait for a quiet second. */
    fun onMediaChanged() {
        pendingChange?.cancel()
        pendingChange = viewModelScope.launch {
            delay(1_000)
            refresh()
        }
    }

    /** The last scan's numbers, logged by the screen for NF-9. */
    var lastScan: LibraryIndex.ScanResult? = null
        private set

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY]!!
                LibraryViewModel(LibraryIndex.get(app), { storageNames(app) })
            }
        }
    }
}

/** Mounted storage volumes as the system names them ("Internal shared storage", "SD card"). Binder call: not on main. */
internal fun storageNames(context: Context): List<String> =
    context.getSystemService(StorageManager::class.java).storageVolumes
        .filter { it.state == Environment.MEDIA_MOUNTED || it.state == Environment.MEDIA_MOUNTED_READ_ONLY }
        .map { it.getDescription(context) }
