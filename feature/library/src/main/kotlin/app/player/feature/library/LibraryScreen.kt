package app.player.feature.library

import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.ReportDrawnWhen
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import app.player.core.data.library.LibraryFolder
import app.player.core.designsystem.component.EmptyState
import app.player.core.designsystem.icon.LanthornIcons
import app.player.core.designsystem.theme.BrandPolygon

private const val PERF_TAG = "LibraryPerf"

/** The library: entry screen of the app. [onPlay] opens the player; the app module wires it. */
@Composable
fun LibraryRoute(onPlay: (Uri) -> Unit) {
    val nav = rememberNavController()
    val vm: LibraryViewModel = viewModel(factory = LibraryViewModel.Factory)
    NavHost(nav, startDestination = "home") {
        composable("home") {
            LibraryHome(vm, onPlay, onFolderSettings = { nav.navigate("folder-settings") })
        }
        composable("folder-settings") {
            FolderSettingsScreen(vm, onBack = { nav.popBackStack() })
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LibraryHome(vm: LibraryViewModel, onPlay: (Uri) -> Unit, onFolderSettings: () -> Unit) {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val state by vm.state.collectAsStateWithLifecycle()
    var permanentlyDenied by rememberSaveable { mutableStateOf(false) }

    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val access = context.mediaAccess()
        // After a denial, no rationale means the system will not prompt again: send the viewer to settings.
        permanentlyDenied = access == MediaAccess.None &&
            activity?.shouldShowRequestPermissionRationale(mediaPermissions().first()) == false
        vm.onAccess(access, permanentlyDenied)
        vm.refresh()
    }
    // US-71.4: the system file picker needs no permission, so a file can be played without granting access.
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onPlay(uri)
    }

    LifecycleStartEffect(Unit) {
        vm.onAccess(context.mediaAccess(), permanentlyDenied)
        vm.refresh()
        val observer = MediaObserver(vm::onMediaChanged)
        context.contentResolver.registerContentObserver(
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL), true, observer,
        )
        onStopOrDispose { context.contentResolver.unregisterContentObserver(observer) }
    }

    ReportDrawnWhen { state !is LibraryUiState.Loading }
    PerfLog(state, vm)

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.library_title)) }) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (val s = state) {
                LibraryUiState.Loading -> Unit
                is LibraryUiState.NeedsAccess -> AccessScreen(
                    permanentlyDenied = s.permanentlyDenied,
                    onAllow = {
                        if (s.permanentlyDenied) context.openAppSettings() else request.launch(mediaPermissions())
                    },
                    onOpenFile = { pick.launch(arrayOf("video/*")) },
                )
                is LibraryUiState.Content -> LibraryContent(
                    s,
                    onChooseMore = { request.launch(mediaPermissions()) },
                    onFolderSettings = onFolderSettings,
                )
            }
        }
    }
}

@Composable
private fun LibraryContent(s: LibraryUiState.Content, onChooseMore: () -> Unit, onFolderSettings: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        if (s.scanning) {
            LinearProgressIndicator(Modifier.fillMaxWidth().testTag("library-scanning"))
        }
        if (s.partial) PartialBanner(onChooseMore)
        when {
            s.folders.isNotEmpty() -> FolderList(s.folders)
            !s.scanned -> Scanning()
            else -> EmptyLibrary(s.volumes, onFolderSettings)
        }
    }
}

@Composable
private fun FolderList(folders: List<LibraryFolder>) {
    LazyColumn(
        Modifier.fillMaxSize().testTag("library-folders"),
        contentPadding = PaddingValues(vertical = 8.dp),
    ) {
        items(folders, key = { it.key }) { folder ->
            ListItem(
                headlineContent = {
                    Text(folder.name.ifEmpty { stringResource(R.string.library_storage_root) }, maxLines = 2)
                },
                supportingContent = {
                    Text(pluralStringResource(R.plurals.library_video_count, folder.videoCount, folder.videoCount))
                },
                leadingContent = {
                    Icon(LibraryIcons.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                },
            )
        }
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
private fun EmptyLibrary(volumes: List<String>, onFolderSettings: () -> Unit) {
    val message = if (volumes.isEmpty()) {
        stringResource(R.string.library_empty_nowhere)
    } else {
        stringResource(R.string.library_empty_in, volumes.joinToString())
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FolderSettingsScreen(vm: LibraryViewModel, onBack: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val volumes = (state as? LibraryUiState.Content)?.volumes.orEmpty()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.folders_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(LanthornIcons.Back, contentDescription = stringResource(R.string.library_back))
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).testTag("folder-settings")) {
            item {
                Text(
                    stringResource(R.string.folders_scanned),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }
            items(volumes) { name ->
                ListItem(
                    headlineContent = { Text(name) },
                    supportingContent = { Text(stringResource(R.string.folders_all)) },
                )
            }
        }
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

private fun Context.openAppSettings() {
    startActivity(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}
