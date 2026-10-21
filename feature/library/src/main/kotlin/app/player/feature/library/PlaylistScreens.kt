package app.player.feature.library

import android.content.Context
import android.content.res.Resources
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import app.player.core.data.library.LibraryVideo
import app.player.core.data.playlist.AddResult
import app.player.core.data.playlist.PlaylistItem
import app.player.core.data.playlist.Playlists
import app.player.core.designsystem.component.ConfirmDialog
import app.player.core.designsystem.component.EmptyState
import app.player.core.designsystem.component.VideoThumbnail
import app.player.core.designsystem.icon.LanthornIcons
import kotlinx.coroutines.launch
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

/** What a playlist plays (US-12.3, US-12.4): its present entries in order, starting at [from] or the first after it. */
internal fun playlistQueue(items: List<PlaylistItem>, from: Int = 0): Pair<List<Uri>, Int>? {
    val present = items.withIndex().filter { it.value.present }
    if (present.isEmpty()) return null
    val start = present.indexOfFirst { it.index >= from }.takeIf { it >= 0 } ?: 0
    return present.map { it.value.uri.toUri() } to start
}

/** The US-12.1 message: how many were added and how many were already there. */
internal fun addMessage(res: Resources, playlist: String, result: AddResult): String {
    val added = res.getQuantityString(R.plurals.playlist_added, result.added, result.added, playlist)
    if (result.skipped == 0) return added
    return added + " " + res.getQuantityString(R.plurals.playlist_skipped, result.skipped, result.skipped)
}

/** Uri and name pairs for the store. */
internal fun List<LibraryVideo>.forPlaylist() = map { it.uri to it.name }

/** The playlists, newest first by name order (US-12). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlaylistsScreen(onOpen: (Long) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val store = remember { Playlists(context) }
    val playlists by store.summaries.collectAsState(initial = null)
    val scope = rememberMainScope()
    var creating by rememberSaveable { mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.playlists_title)) },
                navigationIcon = { BackButton(onBack) },
                actions = {
                    IconButton(onClick = { creating = true }, modifier = Modifier.testTag("playlist-new")) {
                        Icon(LibraryIcons.PlaylistAdd, contentDescription = stringResource(R.string.playlist_new))
                    }
                },
            )
        },
    ) { padding ->
        val list = playlists ?: return@Scaffold
        if (list.isEmpty()) {
            EmptyState(
                stringResource(R.string.playlists_empty),
                Modifier.padding(padding),
                actionLabel = stringResource(R.string.playlist_new),
                onAction = { creating = true },
            )
            return@Scaffold
        }
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            items(list, key = { it.id }) { p ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onOpen(p.id) }
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                        .testTag("playlist-row"),
                ) {
                    Text(p.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        pluralStringResource(R.plurals.library_video_count, p.count, p.count),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
    if (creating) {
        NameDialog(
            title = stringResource(R.string.playlist_new),
            initial = "",
            confirm = stringResource(R.string.playlist_create),
            onDone = { name -> scope.launch { onOpen(store.create(name).first) } },
            onDismiss = { creating = false },
        )
    }
}

/**
 * One playlist (US-12.2): its videos in order, drag to reorder by the handle, remove from a row's
 * menu, rename and delete from the top bar. Missing files are greyed and not played (US-12.4).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlaylistScreen(
    id: Long,
    content: LibraryUiState.Content?,
    loader: ThumbnailLoader,
    onPlayQueue: (List<Uri>, Int) -> Unit,
    onBack: () -> Unit,
    notify: (String) -> Unit = rememberToast(),
) {
    val context = LocalContext.current
    val store = remember { Playlists(context) }
    val playlist by store.playlist(id).collectAsState(initial = null)
    val stored by store.items(id).collectAsState(initial = null)
    // The order shown while dragging; the store catches up when the drag ends.
    var items by remember { mutableStateOf<List<PlaylistItem>>(emptyList()) }
    var dragging by remember { mutableStateOf(false) }
    LaunchedEffect(stored) { if (!dragging) stored?.let { items = it } }
    val byUri = remember(content?.indexed) { content?.indexed?.associateBy { it.uri }.orEmpty() }
    val scope = rememberMainScope()
    var renaming by rememberSaveable { mutableStateOf(false) }
    var deleting by rememberSaveable { mutableStateOf(false) }
    val missing = stringResource(R.string.playlist_missing)
    val name = playlist?.name.orEmpty()

    fun play(from: Int) {
        val queue = playlistQueue(items, from)
        if (queue == null) notify(missing) else onPlayQueue(queue.first, queue.second)
    }

    val listState = rememberLazyListState()
    val reorder = rememberReorderableLazyListState(listState) { from, to ->
        items = items.toMutableList().apply { add(to.index, removeAt(from.index)) }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("playlist-title")) },
                navigationIcon = { BackButton(onBack) },
                actions = {
                    IconButton(onClick = { play(0) }, enabled = items.any { it.present }, modifier = Modifier.testTag("playlist-play")) {
                        Icon(LanthornIcons.Play, contentDescription = stringResource(R.string.action_play))
                    }
                    var menu by remember { mutableStateOf(false) }
                    Box {
                    IconButton(onClick = { menu = true }, modifier = Modifier.testTag("playlist-more")) {
                        Icon(LanthornIcons.More, contentDescription = stringResource(R.string.more_options))
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.action_rename)) },
                            onClick = { menu = false; renaming = true },
                            modifier = Modifier.testTag("playlist-rename"),
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.action_delete)) },
                            onClick = { menu = false; deleting = true },
                            modifier = Modifier.testTag("playlist-delete"),
                        )
                    }
                    }
                },
            )
        },
    ) { padding ->
        if (stored == null) return@Scaffold
        if (items.isEmpty()) {
            EmptyState(stringResource(R.string.playlist_empty), Modifier.padding(padding))
            return@Scaffold
        }
        LazyColumn(Modifier.fillMaxSize().padding(padding), state = listState) {
            items(items, key = { it.uri }) { item ->
                ReorderableItem(reorder, key = item.uri) { _ ->
                    val index = items.indexOf(item)
                    val moveUp = stringResource(R.string.playlist_move_up)
                    val moveDown = stringResource(R.string.playlist_move_down)
                    fun move(to: Int) {
                        items = items.toMutableList().apply { add(to, removeAt(index)) }
                        scope.launch { store.reorder(id, items.map { it.uri }) }
                    }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surface)
                            .clickable { if (item.present) play(index) else notify(missing) }
                            .padding(start = 4.dp, end = 4.dp, top = 8.dp, bottom = 8.dp)
                            .semantics {
                                // Reorder without dragging, for TalkBack and switch access.
                                customActions = listOfNotNull(
                                    CustomAccessibilityAction(moveUp) { move(index - 1); true }.takeIf { index > 0 },
                                    CustomAccessibilityAction(moveDown) { move(index + 1); true }.takeIf { index < items.lastIndex },
                                )
                            }
                            .testTag("playlist-item"),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(
                            onClick = {},
                            modifier = Modifier
                                .draggableHandle(
                                    onDragStarted = { dragging = true },
                                    onDragStopped = {
                                        dragging = false
                                        scope.launch { store.reorder(id, items.map { it.uri }) }
                                    },
                                )
                                .testTag("playlist-drag"),
                        ) {
                            Icon(LibraryIcons.DragHandle, contentDescription = stringResource(R.string.playlist_reorder))
                        }
                        val video = byUri[item.uri]
                        Row(
                            Modifier.weight(1f).alpha(if (item.present) 1f else 0.38f),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            val length = item.durationMs
                            if (length != null) {
                                VideoThumbnail(duration = formatDuration(length), modifier = Modifier.width(112.dp)) {
                                    if (video != null && item.present) ThumbImage(video, loader)
                                }
                            } else {
                                // Not in the library (a stream, a share, a deleted file): a plain frame, no length.
                                Box(
                                    Modifier
                                        .width(112.dp)
                                        .aspectRatio(16f / 9f)
                                        .clip(MaterialTheme.shapes.medium)
                                        .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                                )
                            }
                            Column(Modifier.weight(1f).heightIn(min = 40.dp), verticalArrangement = Arrangement.Center) {
                                Text(
                                    displayName(item.name),
                                    style = MaterialTheme.typography.titleMedium,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (!item.present) {
                                    Text(
                                        missing,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.testTag("playlist-missing"),
                                    )
                                }
                            }
                        }
                        ItemMenu(onRemove = { scope.launch { store.remove(id, item.uri) } }, name = item.name)
                    }
                }
            }
        }
    }
    if (renaming) {
        NameDialog(
            title = stringResource(R.string.action_rename),
            initial = name,
            confirm = stringResource(R.string.action_rename),
            onDone = { scope.launch { store.rename(id, it) } },
            onDismiss = { renaming = false },
        )
    }
    if (deleting) {
        ConfirmDialog(
            stringResource(R.string.playlist_delete_title),
            stringResource(R.string.playlist_delete_text, name),
            stringResource(R.string.action_delete),
            onConfirm = { scope.launch { store.delete(id); onBack() } },
            onDismiss = { deleting = false },
        )
    }
}

@Composable
private fun ItemMenu(onRemove: () -> Unit, name: String) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, modifier = Modifier.testTag("playlist-item-more")) {
            Icon(LanthornIcons.More, contentDescription = stringResource(R.string.row_menu, name))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.playlist_remove)) },
                onClick = { open = false; onRemove() },
                modifier = Modifier.testTag("playlist-remove"),
            )
        }
    }
}

/**
 * Picks a playlist for [videos] or makes a new one, then adds them and says how many went in
 * (US-12.1). [frame] draws the list: a dialog in the library, a panel in the player.
 */
@Composable
internal fun AddToPlaylist(
    videos: List<Pair<String, String>>,
    onDismiss: () -> Unit,
    notify: (String) -> Unit,
    frame: @Composable (list: @Composable () -> Unit) -> Unit,
) {
    val context = LocalContext.current
    val store = remember { Playlists(context) }
    val playlists by store.summaries.collectAsState(initial = emptyList())
    val scope = rememberMainScope()
    var creating by rememberSaveable { mutableStateOf(false) }
    // Drawn as nothing while the add runs, but kept composed so [scope] lives until it is done.
    var adding by remember { mutableStateOf(false) }
    if (adding) return
    if (creating) {
        NameDialog(
            title = stringResource(R.string.playlist_new),
            initial = "",
            confirm = stringResource(R.string.playlist_create),
            onDone = { name ->
                adding = true
                scope.launch {
                    val (_, result) = store.create(name, videos)
                    notify(addMessage(context.resources, name.trim(), result))
                    onDismiss()
                }
            },
            onDismiss = { if (!adding) onDismiss() },
        )
        return
    }
    frame {
        LazyColumn(Modifier.testTag("playlist-pick")) {
            item {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { creating = true }
                        .padding(vertical = 12.dp)
                        .testTag("playlist-pick-new"),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(LibraryIcons.PlaylistAdd, contentDescription = null)
                    Text(stringResource(R.string.playlist_new), style = MaterialTheme.typography.bodyLarge)
                }
                if (playlists.isNotEmpty()) HorizontalDivider()
            }
            items(playlists, key = { it.id }) { p ->
                Text(
                    p.name,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            adding = true
                            scope.launch {
                                notify(addMessage(context.resources, p.name, store.add(p.id, videos)))
                                onDismiss()
                            }
                        }
                        .padding(vertical = 12.dp)
                        .testTag("playlist-pick-row"),
                )
            }
        }
    }
}

/** [AddToPlaylist] as a dialog, for selection mode. */
@Composable
internal fun AddToPlaylistDialog(
    videos: List<Pair<String, String>>,
    onDismiss: () -> Unit,
    notify: (String) -> Unit = rememberToast(),
) {
    AddToPlaylist(videos, onDismiss, notify) { list ->
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.playlist_add_title)) },
            text = list,
            confirmButton = {},
            dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } },
        )
    }
}

@Composable
private fun NameDialog(title: String, initial: String, confirm: String, onDone: (String) -> Unit, onDismiss: () -> Unit) {
    var text by rememberSaveable { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                label = { Text(stringResource(R.string.playlist_name)) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                modifier = Modifier.testTag("playlist-name-field"),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onDone(text); onDismiss() },
                enabled = text.isNotBlank(),
                modifier = Modifier.testTag("playlist-name-save"),
            ) { Text(confirm) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } },
    )
}


@Composable
internal fun rememberToast(): (String) -> Unit {
    val context = LocalContext.current
    return remember(context) { { text: String -> context.toast(text) } }
}

private fun Context.toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()
