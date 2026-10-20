package app.player.feature.library

import android.os.Build
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import app.player.core.data.library.LibraryVideo
import app.player.core.designsystem.component.ConfirmDialog
import app.player.core.designsystem.icon.LanthornIcons

/** Selection kept across rotation. */
@Composable
fun rememberSelection(): MutableState<Set<String>> = rememberSaveable(
    saver = Saver(save = { ArrayList(it.value) }, restore = { mutableStateOf(it.toSet()) }),
) { mutableStateOf(emptySet()) }

/**
 * The top bar while something is selected (US-09, US-10, US-74). [videos] is what the selection
 * stands for, in list order (folders expanded). Rename and properties need exactly one video.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SelectionBar(
    count: Int,
    videos: List<LibraryVideo>,
    actions: FileActions,
    onPlay: (List<LibraryVideo>) -> Unit,
    onSelectAll: () -> Unit,
    onClear: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<LibraryVideo?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    TopAppBar(
        title = { Text(pluralStringResource(R.plurals.selected_count, count, count), modifier = Modifier.testTag("selection-count")) },
        navigationIcon = {
            IconButton(onClick = onClear) { Icon(LibraryIcons.Close, contentDescription = stringResource(R.string.selection_clear)) }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        actions = {
            IconButton(onClick = { onPlay(videos); onClear() }, enabled = videos.isNotEmpty(), modifier = Modifier.testTag("selection-play")) {
                Icon(LanthornIcons.Play, contentDescription = stringResource(R.string.action_play))
            }
            IconButton(onClick = { actions.share(videos) }, enabled = videos.isNotEmpty(), modifier = Modifier.testTag("selection-share")) {
                Icon(LibraryIcons.Share, contentDescription = stringResource(R.string.action_share))
            }
            IconButton(
                onClick = { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) actions.delete(videos) else confirmDelete = true },
                enabled = videos.isNotEmpty(),
                modifier = Modifier.testTag("selection-delete"),
            ) { Icon(LibraryIcons.Delete, contentDescription = stringResource(R.string.action_delete)) }
            Box {
                IconButton(onClick = { menu = true }, modifier = Modifier.testTag("selection-more")) {
                    Icon(LanthornIcons.More, contentDescription = stringResource(R.string.more_options))
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    if (videos.size == 1) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.action_rename)) },
                            onClick = { menu = false; renaming = videos.first() },
                            modifier = Modifier.testTag("selection-rename"),
                        )
                    }
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.selection_all)) },
                        onClick = { menu = false; onSelectAll() },
                    )
                }
            }
        },
    )
    renaming?.let { video ->
        RenameDialog(video, onRename = { actions.rename(video, it); onClear() }, onDismiss = { renaming = null })
    }
    if (confirmDelete) {
        ConfirmDialog(
            stringResource(R.string.action_delete),
            pluralStringResource(R.plurals.confirm_delete, videos.size, videos.size),
            stringResource(R.string.action_delete),
            onConfirm = { actions.delete(videos, confirmedOnQ = true); onClear() },
            onDismiss = { confirmDelete = false },
        )
    }
}

@Composable
private fun RenameDialog(video: LibraryVideo, onRename: (String) -> Unit, onDismiss: () -> Unit) {
    var text by rememberSaveable { mutableStateOf(displayName(video.name)) }
    val newName = renamed(video.name, text)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.action_rename)) },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    supportingText = { Text(stringResource(R.string.rename_hint)) },
                    modifier = Modifier.testTag("rename-field"),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { newName?.let(onRename); onDismiss() }, enabled = newName != null, modifier = Modifier.testTag("rename-save")) {
                Text(stringResource(R.string.action_rename))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } },
    )
}
