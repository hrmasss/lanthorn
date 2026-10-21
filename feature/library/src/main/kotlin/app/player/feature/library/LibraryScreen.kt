package app.player.feature.library

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.ReportDrawnWhen
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingToolbarDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.player.core.data.library.LibraryFolder
import app.player.core.data.library.LibraryVideo
import app.player.core.data.library.childrenOf
import app.player.core.designsystem.component.EmptyState
import app.player.core.designsystem.icon.LanthornIcons
import app.player.core.designsystem.theme.BrandPolygon
import app.player.core.engine.DecoderMode
import app.player.feature.library.network.StreamDialog
import app.player.feature.library.settings.SettingsGroupScreen
import app.player.feature.library.settings.SettingsHome
import app.player.feature.library.smb.SmbBrowseScreen
import app.player.feature.library.smb.SmbServersScreen

private const val PERF_TAG = "LibraryPerf"

/** The library: entry screen of the app. [onPlay] opens the player; the app module wires it. */
@Composable
fun LibraryRoute(
    onPlay: (Uri) -> Unit,
    /** Opens [uris] as the player's queue at [index] (US-09.2, US-24.1). */
    onPlayQueue: (uris: List<Uri>, index: Int) -> Unit = { uris, i -> onPlay(uris[i]) },
    /** Opens [uris] as the queue with one decoder for this launch (US-09.3). */
    onPlayWith: (uris: List<Uri>, decoder: DecoderMode) -> Unit = { uris, _ -> onPlayQueue(uris, 0) },
) {
    val context = LocalContext.current
    val nav = rememberNavController()
    val vm: LibraryViewModel = viewModel(factory = LibraryViewModel.Factory)
    val state by vm.state.collectAsStateWithLifecycle()
    var permanentlyDenied by rememberSaveable { mutableStateOf(false) }

    // While the library is visible: rescan on start (US-01.3), on MediaStore changes, and when storage
    // comes or goes (US-80.2).
    LifecycleStartEffect(Unit) {
        vm.onAccess(context.mediaAccess(), permanentlyDenied)
        vm.refresh()
        val observer = MediaObserver(vm::onMediaChanged)
        context.contentResolver.registerContentObserver(
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL), true, observer,
        )
        val volumes = VolumeReceiver(vm::onVolumesChanged)
        ContextCompat.registerReceiver(context, volumes, VolumeReceiver.filter(), ContextCompat.RECEIVER_NOT_EXPORTED)
        onStopOrDispose {
            Log.i(PERF_TAG, "thumbs ${LibraryViewModel.loaders(context).first.stats}")
            context.contentResolver.unregisterContentObserver(observer)
            context.unregisterReceiver(volumes)
        }
    }

    ReportDrawnWhen { state !is LibraryUiState.Loading }
    PerfLog(state, vm)

    val loaders = remember { LibraryViewModel.loaders(context) }
    val content = state as? LibraryUiState.Content
    SideEffect { content?.let { loaders.first.cacheOnDisk = it.prefs.cacheThumbnails } }

    NavHost(nav, startDestination = "home") {
        composable("home") {
            LibraryHome(
                state, vm, loaders, onPlay, onPlayQueue, onPlayWith,
                onAccessResult = { permanentlyDenied = it },
                onFolder = { nav.navigate("folder?key=" + Uri.encode(it.key)) },
                onFolderSettings = { nav.navigate("folder-settings") },
                onSettings = { nav.navigate("settings") },
                onShares = { nav.navigate("smb") },
                onPlaylists = { nav.navigate("playlists") },
                onSearch = { nav.navigate("search") },
            )
        }
        composable("search") {
            SearchScreen(
                content, vm, loaders, onPlay,
                onFolder = { nav.navigate("folder?key=" + Uri.encode(it.key)) },
                onBack = { nav.popBackStack() },
            )
        }
        composable("folder?key={key}", listOf(navArgument("key") { type = NavType.StringType })) { entry ->
            val key = entry.arguments?.getString("key").orEmpty()
            FolderScreen(
                key, state, vm, loaders, onPlay, onPlayQueue, onPlayWith,
                onFolder = { nav.navigate("folder?key=" + Uri.encode(it.key)) },
                onSearch = { nav.navigate("search") },
                onBack = { nav.popBackStack() },
            )
        }
        composable("smb") {
            SmbServersScreen(
                onOpen = { nav.navigate("smb/${it.id}") },
                onBack = { nav.popBackStack() },
            )
        }
        composable(
            "smb/{id}?path={path}",
            listOf(
                navArgument("id") { type = NavType.StringType },
                navArgument("path") { type = NavType.StringType; defaultValue = "" },
            ),
        ) { entry ->
            val id = entry.arguments?.getString("id").orEmpty()
            SmbBrowseScreen(
                id,
                entry.arguments?.getString("path").orEmpty(),
                onFolder = { nav.navigate("smb/$id?path=" + Uri.encode(it)) },
                onPlay = onPlay,
                onBack = { nav.popBackStack() },
            )
        }
        composable("settings") {
            SettingsHome(
                onGroup = { id, key -> nav.navigate("settings/$id" + (key?.let { "?highlight=$it" } ?: "")) },
                onBack = { nav.popBackStack() },
            )
        }
        composable(
            "settings/{group}?highlight={highlight}",
            listOf(
                navArgument("group") { type = NavType.StringType },
                navArgument("highlight") { type = NavType.StringType; nullable = true },
            ),
        ) { entry ->
            SettingsGroupScreen(
                entry.arguments?.getString("group").orEmpty(),
                entry.arguments?.getString("highlight"),
                onBack = { nav.popBackStack() },
            )
        }
        composable("playlists") {
            PlaylistsScreen(onOpen = { nav.navigate("playlist/$it") }, onBack = { nav.popBackStack() })
        }
        composable("playlist/{id}", listOf(navArgument("id") { type = NavType.LongType })) { entry ->
            PlaylistScreen(
                entry.arguments?.getLong("id") ?: 0L,
                content,
                loaders.first,
                onPlayQueue,
                onBack = { nav.popBackStack() },
            )
        }
        composable("folder-settings") {
            FolderSettingsScreen(content, vm, onBack = { nav.popBackStack() })
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LibraryHome(
    state: LibraryUiState,
    vm: LibraryViewModel,
    loaders: Pair<ThumbnailLoader, VideoProbe>,
    onPlay: (Uri) -> Unit,
    onPlayQueue: (List<Uri>, Int) -> Unit,
    onPlayWith: (List<Uri>, DecoderMode) -> Unit,
    onAccessResult: (permanentlyDenied: Boolean) -> Unit,
    onFolder: (LibraryFolder) -> Unit,
    onFolderSettings: () -> Unit,
    onSettings: () -> Unit,
    onShares: () -> Unit,
    onPlaylists: () -> Unit,
    onSearch: () -> Unit,
) {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val access = context.mediaAccess()
        // After a denial, no rationale means the system will not prompt again: send the viewer to settings.
        val permanentlyDenied = access == MediaAccess.None &&
            activity?.shouldShowRequestPermissionRationale(mediaPermissions().first()) == false
        onAccessResult(permanentlyDenied)
        vm.onAccess(access, permanentlyDenied)
        vm.refresh()
    }
    // US-71.4: the system file picker needs no permission, so a file can be played without granting access.
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onPlay(uri)
    }
    val scroll = TopAppBarDefaults.enterAlwaysScrollBehavior()
    val listState = rememberLazyListState()
    val gridState = rememberLazyGridState()
    var streamOpen by rememberSaveable { mutableStateOf(false) }
    if (streamOpen) StreamDialog(onPlay = { onPlay(it.toUri()) }, onDismiss = { streamOpen = false })
    val content = state as? LibraryUiState.Content
    val allMode = content?.prefs?.viewMode == ViewMode.AllVideos
    val shownFolders = if (allMode || content == null) emptyList() else content.folders
    val shownVideos = if (allMode && content != null) content.videos else emptyList()
    var selection by rememberSelection()
    BackHandler(selection.isNotEmpty()) { selection = emptySet() }
    val actions = rememberFileActions(onChanged = vm::refresh)

    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        floatingActionButton = {
            val last = (state as? LibraryUiState.Content)?.lastPlayed
            if (last != null && selection.isEmpty()) ResumeButton { onPlayQueue(listOf(last.uri.toUri()), 0) }
        },
        topBar = {
            if (selection.isNotEmpty() && content != null) {
                SelectionBar(
                    count = selection.size,
                    videos = selectedVideos(selection, shownFolders, shownVideos, content.videos),
                    actions = actions,
                    onPlay = { vs -> onPlayQueue(vs.map { it.uri.toUri() }, 0) },
                    onPlayWith = { vs, mode -> onPlayWith(vs.map { it.uri.toUri() }, mode) },
                    onSelectAll = { selection = shownFolders.map(SelectionKey::of).toSet() + shownVideos.map(SelectionKey::of) },
                    onClear = { selection = emptySet() },
                )
            } else TopAppBar(
                title = { Text(stringResource(R.string.library_title)) },
                actions = {
                    OverflowMenu(
                        onFolderSettings.takeIf { state is LibraryUiState.Content },
                        onSettings,
                        onStream = { streamOpen = true },
                        onShares = onShares,
                        onPlaylists = onPlaylists,
                    )
                },
                scrollBehavior = scroll,
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (state) {
                LibraryUiState.Loading -> Unit
                is LibraryUiState.NeedsAccess -> AccessScreen(
                    permanentlyDenied = state.permanentlyDenied,
                    onAllow = {
                        if (state.permanentlyDenied) context.openAppSettings() else request.launch(mediaPermissions())
                    },
                    onOpenFile = { pick.launch(arrayOf("video/*")) },
                )
                is LibraryUiState.Content -> {
                    Column(Modifier.fillMaxSize()) {
                        if (state.scanning) LinearProgressIndicator(Modifier.fillMaxWidth().testTag("library-scanning"))
                        if (state.partial) PartialBanner(onChooseMore = { request.launch(mediaPermissions()) })
                        when {
                            state.videos.isNotEmpty() -> PullToRefreshBox(state.scanning, vm::refresh) {
                                LibraryItems(
                                    folders = shownFolders,
                                    videos = shownVideos,
                                    layout = state.prefs.layout,
                                    ctx = rowContext(state, vm, loaders, onFolder, shownVideos, onPlayQueue, selection, actions = actions) { k ->
                                        selection = if (k in selection) selection - k else selection + k
                                    },
                                    listState = listState,
                                    gridState = gridState,
                                )
                            }
                            !state.scanned -> Scanning()
                            else -> EmptyLibrary(state.volumes, onFolderSettings)
                        }
                    }
                    if (state.videos.isNotEmpty()) {
                        ViewToolbar(state.prefs, vm, showViewMode = true, onSearch, Modifier.align(Alignment.BottomCenter))
                    }
                }
            }
        }
    }
}

/** One folder: its nearest video-holding subfolders, then its own videos (US-01.2). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FolderScreen(
    key: String,
    state: LibraryUiState,
    vm: LibraryViewModel,
    loaders: Pair<ThumbnailLoader, VideoProbe>,
    onPlay: (Uri) -> Unit,
    onPlayQueue: (List<Uri>, Int) -> Unit,
    onPlayWith: (List<Uri>, DecoderMode) -> Unit,
    onFolder: (LibraryFolder) -> Unit,
    onSearch: () -> Unit,
    onBack: () -> Unit,
) {
    val content = state as? LibraryUiState.Content
    val name = key.substringAfter('/').trimEnd('/').substringAfterLast('/')
    val subfolders = remember(content?.folders, key) { content?.folders?.childrenOf(key).orEmpty() }
    val videos = remember(content?.videos, key) { content?.videos?.filter { it.folder == key }.orEmpty() }
    val scroll = TopAppBarDefaults.enterAlwaysScrollBehavior()
    val listState = rememberLazyListState()
    val gridState = rememberLazyGridState()
    var selection by rememberSelection()
    BackHandler(selection.isNotEmpty()) { selection = emptySet() }
    val actions = rememberFileActions(onChanged = vm::refresh)

    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        floatingActionButton = {
            val last = (state as? LibraryUiState.Content)?.lastPlayed
            if (last != null && selection.isEmpty()) ResumeButton { onPlayQueue(listOf(last.uri.toUri()), 0) }
        },
        topBar = {
            if (selection.isNotEmpty() && content != null) {
                SelectionBar(
                    count = selection.size,
                    videos = selectedVideos(selection, subfolders, videos, content.videos),
                    actions = actions,
                    onPlay = { vs -> onPlayQueue(vs.map { it.uri.toUri() }, 0) },
                    onPlayWith = { vs, mode -> onPlayWith(vs.map { it.uri.toUri() }, mode) },
                    onSelectAll = { selection = subfolders.map(SelectionKey::of).toSet() + videos.map(SelectionKey::of) },
                    onClear = { selection = emptySet() },
                )
            } else TopAppBar(
                title = { Text(name.ifEmpty { stringResource(R.string.library_storage_root) }, maxLines = 1) },
                navigationIcon = { BackButton(onBack) },
                scrollBehavior = scroll,
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            if (content != null) {
                PullToRefreshBox(content.scanning, vm::refresh) {
                    LibraryItems(
                        folders = subfolders,
                        videos = videos,
                        layout = content.prefs.layout,
                        ctx = rowContext(content, vm, loaders, onFolder, videos, onPlayQueue, selection, actions = actions) { k ->
                            selection = if (k in selection) selection - k else selection + k
                        },
                        listState = listState,
                        gridState = gridState,
                    )
                }
                ViewToolbar(content.prefs, vm, showViewMode = false, onSearch, Modifier.align(Alignment.BottomCenter))
            }
        }
    }
}

@Composable
internal fun rowContext(
    state: LibraryUiState.Content,
    vm: LibraryViewModel,
    loaders: Pair<ThumbnailLoader, VideoProbe>,
    onFolder: (LibraryFolder) -> Unit,
    /** The videos as listed: tapping one plays this list from there, so next and previous follow it. */
    queue: List<LibraryVideo>,
    onPlayQueue: (List<Uri>, Int) -> Unit,
    selection: Set<String> = emptySet(),
    /** Share and properties in each video's row menu (US-74.1, US-10.4). */
    actions: FileActions? = null,
    onSelect: ((String) -> Unit)? = null,
) = remember(state.prefs.fields, state.volumes, loaders, queue, selection, actions, state.marks) {
    RowContext(
        loader = loaders.first,
        fps = loaders.second::fps,
        fields = state.prefs.fields,
        volumeLabel = state::volumeLabel,
        onFolder = onFolder,
        onVideo = { v: LibraryVideo ->
            val playable = queue.filter { it.available }
            onPlayQueue(playable.map { it.uri.toUri() }, playable.indexOfFirst { it.id == v.id }.coerceAtLeast(0))
        },
        folderActions = listOf(
            RowAction(R.string.action_hide) { f -> vm.setHidden(f.key, true) },
            RowAction(R.string.action_exclude) { f -> vm.setExcluded(f.key, true) },
        ),
        videoActions = listOfNotNull(
            actions?.let { a -> RowAction(R.string.action_share) { v: LibraryVideo -> a.share(listOf(v)) } },
            actions?.let { a -> RowAction(R.string.action_properties) { v: LibraryVideo -> a.properties(v) } },
            RowAction(R.string.action_hide) { v -> vm.setHidden(v.uri, true) },
        ),
        selection = selection,
        onSelect = onSelect,
        marks = state.marks,
    )
}

/** Floating toolbar for view choices (design.md: sort, view and search live here on phones). */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ViewToolbar(
    prefs: LibraryPrefs,
    vm: LibraryViewModel,
    showViewMode: Boolean,
    onSearch: () -> Unit,
    modifier: Modifier,
) {
    var fieldsOpen by remember { mutableStateOf(false) }
    var sortOpen by remember { mutableStateOf(false) }
    HorizontalFloatingToolbar(
        expanded = true,
        modifier = modifier
            .navigationBarsPadding()
            .offset(y = -FloatingToolbarDefaults.ScreenOffset)
            .testTag("library-toolbar"),
    ) {
        IconButton(onClick = onSearch, modifier = Modifier.testTag("open-search")) {
            Icon(LibraryIcons.Search, contentDescription = stringResource(R.string.search))
        }
        Box {
            IconButton(onClick = { sortOpen = true }, modifier = Modifier.testTag("open-sort")) {
                Icon(LibraryIcons.Sort, contentDescription = stringResource(R.string.sort))
            }
            DropdownMenu(expanded = sortOpen, onDismissRequest = { sortOpen = false }) {
                for (key in SortKey.entries) {
                    DropdownMenuItem(
                        text = { Text(stringResource(key.label)) },
                        leadingIcon = { RadioButton(selected = prefs.sort.key == key, onClick = null) },
                        onClick = { vm.setSort(prefs.sort.copy(key = key)) },
                        modifier = Modifier.testTag("sort-${key.name}"),
                    )
                }
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.sort_descending)) },
                    leadingIcon = { Checkbox(checked = prefs.sort.descending, onCheckedChange = null) },
                    onClick = { vm.setSort(prefs.sort.copy(descending = !prefs.sort.descending)) },
                    modifier = Modifier.testTag("sort-descending"),
                )
            }
        }
        if (showViewMode) {
            val all = prefs.viewMode == ViewMode.AllVideos
            IconButton(
                onClick = { vm.setViewMode(if (all) ViewMode.Folders else ViewMode.AllVideos) },
                modifier = Modifier.testTag("toggle-view-mode"),
            ) {
                Icon(
                    if (all) LibraryIcons.Folder else LibraryIcons.AllVideos,
                    contentDescription = stringResource(if (all) R.string.view_folders else R.string.view_all_videos),
                )
            }
        }
        val grid = prefs.layout == Layout.Grid
        IconButton(
            onClick = { vm.setLayout(if (grid) Layout.List else Layout.Grid) },
            modifier = Modifier.testTag("toggle-layout"),
        ) {
            Icon(
                if (grid) LibraryIcons.List else LibraryIcons.Grid,
                contentDescription = stringResource(if (grid) R.string.view_as_list else R.string.view_as_grid),
            )
        }
        Box {
            IconButton(onClick = { fieldsOpen = true }, modifier = Modifier.testTag("open-fields")) {
                Icon(LibraryIcons.Fields, contentDescription = stringResource(R.string.view_fields))
            }
            DropdownMenu(expanded = fieldsOpen, onDismissRequest = { fieldsOpen = false }) {
                for (field in Field.entries) {
                    DropdownMenuItem(
                        text = { Text(stringResource(field.label)) },
                        leadingIcon = { Checkbox(checked = field in prefs.fields, onCheckedChange = null) },
                        onClick = { vm.toggleField(field) },
                        modifier = Modifier.testTag("field-${field.name}"),
                    )
                }
            }
        }
    }
}

private val SortKey.label
    get() = when (this) {
        SortKey.Name -> R.string.sort_name
        SortKey.Date -> R.string.sort_date
        SortKey.Size -> R.string.sort_size
        SortKey.Length -> R.string.sort_length
        SortKey.Resolution -> R.string.sort_resolution
    }

private val Field.label
    get() = when (this) {
        Field.Size -> R.string.field_size
        Field.Resolution -> R.string.field_resolution
        Field.FrameRate -> R.string.field_frame_rate
        Field.Date -> R.string.field_date
        Field.Extension -> R.string.field_extension
    }

/** Top bar overflow menu. */
@Composable
private fun OverflowMenu(
    onFolderSettings: (() -> Unit)?,
    onSettings: () -> Unit,
    onStream: () -> Unit,
    onShares: () -> Unit,
    onPlaylists: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, modifier = Modifier.testTag("library-overflow")) {
            Icon(LanthornIcons.More, contentDescription = stringResource(R.string.more_options))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.stream_title)) },
                onClick = {
                    open = false
                    onStream()
                },
                modifier = Modifier.testTag("open-stream"),
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.smb_title)) },
                onClick = {
                    open = false
                    onShares()
                },
                modifier = Modifier.testTag("open-shares"),
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.playlists_title)) },
                onClick = {
                    open = false
                    onPlaylists()
                },
                modifier = Modifier.testTag("open-playlists"),
            )
            if (onFolderSettings != null) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.library_folder_settings)) },
                    onClick = {
                        open = false
                        onFolderSettings()
                    },
                )
            }
            DropdownMenuItem(
                text = { Text(stringResource(R.string.settings_title)) },
                onClick = {
                    open = false
                    onSettings()
                },
                modifier = Modifier.testTag("open-settings"),
            )
        }
    }
}

@Composable
internal fun BackButton(onBack: () -> Unit) {
    IconButton(onClick = onBack) {
        Icon(LanthornIcons.Back, contentDescription = stringResource(R.string.library_back))
    }
}

@Composable
private fun PartialBanner(onChooseMore: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .background(MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.shapes.medium)
            .padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp)
            .testTag("library-partial"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(R.string.partial_banner),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onChooseMore) { Text(stringResource(R.string.partial_more)) }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun Scanning() {
    Column(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        LoadingIndicator()
        Text(stringResource(R.string.library_scanning), style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun EmptyLibrary(volumes: List<Volume>, onFolderSettings: () -> Unit) {
    val message = if (volumes.isEmpty()) {
        stringResource(R.string.library_empty_nowhere)
    } else {
        stringResource(R.string.library_empty_in, volumes.joinToString { it.label })
    }
    EmptyState(
        message = message,
        actionLabel = stringResource(R.string.library_folder_settings),
        onAction = onFolderSettings,
        modifier = Modifier.testTag("library-empty"),
    )
}

/** US-71.1: one screen that says why, then the system prompt. */
@Composable
private fun AccessScreen(permanentlyDenied: Boolean, onAllow: () -> Unit, onOpenFile: () -> Unit) {
    Centered(Modifier.testTag("library-access")) {
        Text(stringResource(R.string.access_title), style = MaterialTheme.typography.headlineSmall)
        Text(
            stringResource(if (permanentlyDenied) R.string.access_denied_body else R.string.access_body),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = onAllow, modifier = Modifier.testTag("access-allow")) {
            Text(stringResource(if (permanentlyDenied) R.string.access_settings else R.string.access_allow))
        }
        TextButton(onClick = onOpenFile, modifier = Modifier.testTag("access-open-file")) {
            Text(stringResource(R.string.access_open_file))
        }
    }
}

/** Like the design system's EmptyState, with a title and a second action. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun Centered(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(
        modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(96.dp).background(MaterialTheme.colorScheme.primaryContainer, BrandPolygon.toShape()))
        content()
    }
}

/** Logs when the library first shows content and when a scan lands, for NF-1 and NF-9 runs. */
@Composable
private fun PerfLog(state: LibraryUiState, vm: LibraryViewModel) {
    val content = state as? LibraryUiState.Content
    LaunchedEffect(state is LibraryUiState.Loading) {
        if (state !is LibraryUiState.Loading) {
            Log.i(PERF_TAG, "first-content state=${state::class.simpleName} sinceStart=${sinceProcessStart()}ms")
        }
    }
    val scans by vm.scanCount.collectAsStateWithLifecycle()
    val unavailable = content?.indexed?.count { !it.available }
    LaunchedEffect(scans, unavailable) {
        // Wall-clock time, so a test can compare it with the moment storage was mounted or removed.
        if (scans > 0) {
            Log.i(PERF_TAG, "scan-done #$scans at=${System.currentTimeMillis()} unavailable=$unavailable scan=${vm.lastScan}")
        }
    }
    LaunchedEffect(content?.videoCount, content?.scanned) {
        if (content != null && content.scanned) {
            Log.i(PERF_TAG, "listed n=${content.videoCount} scan=${vm.lastScan} sinceStart=${sinceProcessStart()}ms")
        }
    }
}

private fun sinceProcessStart() = SystemClock.uptimeMillis() - Process.getStartUptimeMillis()

private class MediaObserver(private val onChange: () -> Unit) : ContentObserver(Handler(Looper.getMainLooper())) {
    override fun onChange(selfChange: Boolean) = onChange()
}

/** Storage mounted, unmounted or pulled out. */
private class VolumeReceiver(private val onChange: () -> Unit) : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = onChange()

    companion object {
        fun filter() = IntentFilter().apply {
            addAction(Intent.ACTION_MEDIA_MOUNTED)
            addAction(Intent.ACTION_MEDIA_UNMOUNTED)
            addAction(Intent.ACTION_MEDIA_REMOVED)
            addAction(Intent.ACTION_MEDIA_BAD_REMOVAL)
            addAction(Intent.ACTION_MEDIA_EJECT)
            addDataScheme("file")
        }
    }
}

private fun Context.openAppSettings() {
    startActivity(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}

/** US-08: one tap back into the last played video; the player resumes it at its resume point. */
@Composable
internal fun ResumeButton(onClick: () -> Unit) {
    val label = stringResource(R.string.resume_last)
    ExtendedFloatingActionButton(
        onClick = onClick,
        icon = { Icon(LanthornIcons.Play, contentDescription = null) },
        text = { Text(label) },
        // Above the floating view toolbar. The label is also set on the button itself: on API 36 the
        // accessibility tree showed the button with an empty text node (P1.21 evidence).
        modifier = Modifier.padding(bottom = 72.dp).testTag("resume-last").semantics { contentDescription = label },
    )
}
