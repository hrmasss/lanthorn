package app.player.feature.library

import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.MediaStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.player.core.data.history.HistoryPrefs
import app.player.core.data.library.LibraryFolder
import app.player.core.data.library.LibraryIndex
import app.player.core.data.library.LibraryVideo
import app.player.core.data.library.toFolders
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** A mounted storage volume: MediaStore's name for it and the system's label ("SD card"). */
data class Volume(val name: String, val label: String)

sealed interface LibraryUiState {
    /** Before the screen has read the grant state, or before the cached index has loaded. */
    data object Loading : LibraryUiState

    /** No media access. [permanentlyDenied]: the system will not show its prompt again. */
    data class NeedsAccess(val permanentlyDenied: Boolean) : LibraryUiState

    data class Content(
        /** Every folder holding shown videos, in the viewer's sort order. */
        val folders: List<LibraryFolder>,
        /** Every indexed video not filtered out (excluded, hidden, extension off), in sort order. */
        val videos: List<LibraryVideo>,
        /** Every indexed video, filtered or not. Folder settings name hidden items from it. */
        val indexed: List<LibraryVideo>,
        val prefs: LibraryPrefs,
        val partial: Boolean,
        val scanning: Boolean,
        /** True once a scan has finished in this process. Until then an empty list is not yet "no videos". */
        val scanned: Boolean,
        /** Storage the scan covers (US-01.4, US-80.1). */
        val volumes: List<Volume>,
    ) : LibraryUiState {
        val videoCount get() = videos.size

        /** Label for a volume, null for internal storage (the common case needs no label). */
        fun volumeLabel(name: String): String? =
            if (name == MediaStore.VOLUME_EXTERNAL_PRIMARY) null else volumes.firstOrNull { it.name == name }?.label
    }
}


class LibraryViewModel(
    private val index: LibraryIndex,
    private val prefsStore: LibraryPrefsStore?,
    volumes: suspend () -> List<Volume>,
    io: CoroutineDispatcher = Dispatchers.IO,
    prefs: Flow<LibraryPrefs> = prefsStore?.prefs ?: flowOf(LibraryPrefs()),
    private val historyPaused: suspend () -> Boolean = { false },
) : ViewModel() {
    private data class Access(val access: MediaAccess, val permanentlyDenied: Boolean)

    private val access = MutableStateFlow<Access?>(null)
    private val scanned = MutableStateFlow(false)
    private val volumeTick = MutableStateFlow(0)

    @OptIn(ExperimentalCoroutinesApi::class)
    private val mounted = volumeTick.mapLatest { volumes() }.flowOn(io)

    @OptIn(ExperimentalCoroutinesApi::class)
    val state: StateFlow<LibraryUiState> = access.flatMapLatest { a ->
        when {
            a == null -> flowOf(LibraryUiState.Loading)
            a.access == MediaAccess.None -> flowOf(LibraryUiState.NeedsAccess(a.permanentlyDenied))
            else -> combine(index.videos, prefs, index.scanning, scanned, mounted) { indexed, p, scanning, done, vols ->
                val shown = indexed.filter(p.filter::accepts)
                LibraryUiState.Content(
                    folders = shown.toFolders().sortedFor(p.sort, shown),
                    videos = shown.sortedFor(p.sort),
                    indexed = indexed,
                    prefs = p,
                    partial = a.access == MediaAccess.Partial,
                    scanning = scanning,
                    scanned = done,
                    volumes = vols,
                )
            }.flowOn(io)
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

    /** A volume was mounted or removed (US-80.2): relabel storage and rescan. */
    fun onVolumesChanged() {
        volumeTick.value++
        onMediaChanged()
    }

    fun setViewMode(mode: ViewMode) = edit { setViewMode(mode) }
    fun setLayout(layout: Layout) = edit { setLayout(layout) }
    fun toggleField(field: Field) = edit { toggleField(field) }
    fun setSort(sort: Sort) = edit { setSort(sort) }
    fun setExcluded(folderKey: String, excluded: Boolean) = edit { setExcluded(folderKey, excluded) }
    fun setHidden(id: String, hidden: Boolean) = edit { setHidden(id, hidden) }
    fun setExtension(extension: String, on: Boolean) = edit { setExtension(extension, on) }
    /** Records a search unless history is paused (US-73.2). */
    fun addSearch(query: String) = edit { if (!historyPaused()) addSearch(query) }
    fun removeSearch(query: String) = edit { removeSearch(query) }

    private fun edit(block: suspend LibraryPrefsStore.() -> Unit) {
        val store = prefsStore ?: return
        viewModelScope.launch { store.block() }
    }

    /** The last scan's numbers, logged by the screen for NF-9. */
    var lastScan: LibraryIndex.ScanResult? = null
        private set

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY]!!
                LibraryViewModel(
                    LibraryIndex.get(app),
                    LibraryPrefsStore(app),
                    { mountedVolumes(app) },
                    historyPaused = HistoryPrefs(app)::isPaused,
                )
            }
        }

        /** Shared by every library screen in the process. */
        @Volatile private var loaders: Pair<ThumbnailLoader, VideoProbe>? = null

        fun loaders(context: Context): Pair<ThumbnailLoader, VideoProbe> = loaders ?: synchronized(this) {
            loaders ?: Pair(
                ThumbnailLoader(context),
                VideoProbe(context, LibraryIndex.get(context)),
            ).also { loaders = it }
        }
    }
}

/** Mounted storage volumes as MediaStore names them, with the system's labels. Binder call: not on main. */
internal fun mountedVolumes(context: Context): List<Volume> =
    context.getSystemService(StorageManager::class.java).storageVolumes
        .filter { it.state == Environment.MEDIA_MOUNTED || it.state == Environment.MEDIA_MOUNTED_READ_ONLY }
        .map { v ->
            val name = when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> v.mediaStoreVolumeName
                v.isPrimary -> MediaStore.VOLUME_EXTERNAL_PRIMARY
                else -> v.uuid?.lowercase()
            }
            Volume(name ?: "", v.getDescription(context))
        }
