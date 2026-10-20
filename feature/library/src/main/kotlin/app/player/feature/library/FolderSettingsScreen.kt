package app.player.feature.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.player.core.data.library.LibraryFolder
import app.player.core.data.library.toFolders
import java.util.Locale

/**
 * Folder settings: what is scanned (US-05.1, US-05.4) and what is hidden (US-15.2).
 * `.nomedia` and dot-named folders are always left out: MediaStore does not index them.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FolderSettingsScreen(content: LibraryUiState.Content?, vm: LibraryViewModel, onBack: () -> Unit) {
    var picking by rememberSaveable { mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopAppBar(title = { Text(stringResource(R.string.folders_title)) }, navigationIcon = { BackButton(onBack) })
        },
    ) { padding ->
        if (content == null) return@Scaffold
        val filter = content.prefs.filter
        val folderNames = remember(content.indexed) { content.indexed.toFolders().associate { it.key to it } }
        val hidden = remember(filter.hidden, content.indexed) {
            filter.hidden.map { id ->
                id to (folderNames[id]?.relativePath ?: content.indexed.firstOrNull { it.uri == id }?.name ?: id)
            }.sortedWith(compareBy(NaturalOrder) { it.second })
        }
        val extensions = remember(content.indexed, filter.disabledExtensions) {
            (KnownExtensions + content.indexed.mapNotNull { extensionOf(it.name)?.lowercase(Locale.ROOT) } +
                filter.disabledExtensions).distinct().sorted()
        }
        LazyColumn(Modifier.fillMaxSize().padding(padding).testTag("folder-settings")) {
            header(R.string.folders_scanned)
            items(content.volumes) { volume ->
                ListItem(
                    headlineContent = { Text(volume.label) },
                    supportingContent = { Text(stringResource(R.string.folders_all)) },
                )
            }
            item {
                Text(
                    stringResource(R.string.folders_nomedia_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }

            header(R.string.folders_excluded)
            items(filter.excludedFolders.sortedWith(NaturalOrder).toList(), key = { "x:$it" }) { key ->
                ListItem(
                    headlineContent = { Text(folderNames[key]?.relativePath ?: key.substringAfter('/').trimEnd('/')) },
                    trailingContent = {
                        IconButton(onClick = { vm.setExcluded(key, false) }) {
                            Icon(LibraryIcons.Close, contentDescription = stringResource(R.string.folders_include_again))
                        }
                    },
                    modifier = Modifier.testTag("excluded-row"),
                )
            }
            item {
                TextButton(
                    onClick = { picking = true },
                    modifier = Modifier.padding(horizontal = 8.dp).testTag("exclude-add"),
                ) { Text(stringResource(R.string.folders_exclude_add)) }
            }

            header(R.string.folders_hidden)
            if (hidden.isEmpty()) {
                item { Hint(R.string.folders_hidden_none) }
            }
            items(hidden, key = { "h:" + it.first }) { (id, label) ->
                ListItem(
                    headlineContent = { Text(label) },
                    trailingContent = {
                        TextButton(onClick = { vm.setHidden(id, false) }) { Text(stringResource(R.string.action_unhide)) }
                    },
                    modifier = Modifier.testTag("hidden-row"),
                )
            }

            header(R.string.folders_types)
            items(extensions, key = { "e:$it" }) { ext ->
                val on = ext !in filter.disabledExtensions
                ListItem(
                    headlineContent = { Text(".$ext") },
                    trailingContent = { Switch(checked = on, onCheckedChange = null) },
                    modifier = Modifier
                        .toggleable(on, role = Role.Switch) { vm.setExtension(ext, it) }
                        .testTag("ext-$ext"),
                )
            }
        }
        if (picking) {
            ExcludePicker(
                folders = folderNames.values.filter { it.key !in filter.excludedFolders }
                    .sortedWith(compareBy(NaturalOrder) { it.relativePath }),
                onPick = { vm.setExcluded(it, true); picking = false },
                onDismiss = { picking = false },
            )
        }
    }
}

@Composable
private fun ExcludePicker(
    folders: List<LibraryFolder>,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.folders_exclude_add)) },
        text = {
            LazyColumn {
                items(folders, key = { it.key }) { f ->
                    ListItem(
                        headlineContent = { Text(f.relativePath.ifEmpty { stringResource(R.string.library_storage_root) }) },
                        modifier = Modifier.clickable { onPick(f.key) }.testTag("exclude-pick"),
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } },
    )
}

private fun LazyListScope.header(label: Int) = item {
    Text(
        stringResource(label),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 8.dp),
    )
}

@Composable
private fun Hint(label: Int) {
    Text(
        stringResource(label),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}
