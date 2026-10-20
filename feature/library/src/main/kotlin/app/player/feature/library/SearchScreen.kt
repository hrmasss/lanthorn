package app.player.feature.library

import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import app.player.core.data.library.LibraryFolder

/** US-06: search names of folders and videos; recent searches when the field is empty. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SearchScreen(
    content: LibraryUiState.Content?,
    vm: LibraryViewModel,
    loaders: Pair<ThumbnailLoader, VideoProbe>,
    onPlay: (Uri) -> Unit,
    onFolder: (LibraryFolder) -> Unit,
    onBack: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val result = remember(query, content?.folders, content?.videos) {
        if (content == null) SearchResult(emptyList(), emptyList()) else search(query, content.folders, content.videos)
    }
    // A search is worth remembering once the viewer acts on it.
    val keepSearch = { if (query.isNotBlank()) vm.addSearch(query) }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { BackButton(onBack) },
                title = {
                    TextField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = { Text(stringResource(R.string.search_hint)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { keepSearch() }),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                        ),
                        trailingIcon = {
                            if (query.isNotEmpty()) {
                                IconButton(onClick = { query = "" }) {
                                    Icon(LibraryIcons.Close, contentDescription = stringResource(R.string.search_clear))
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth().focusRequester(focus).testTag("search-field"),
                    )
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                content == null -> Unit
                query.isBlank() -> RecentSearches(
                    content.prefs.recentSearches,
                    onPick = { query = it },
                    onRemove = vm::removeSearch,
                )
                result.folders.isEmpty() && result.videos.isEmpty() -> Text(
                    stringResource(R.string.search_none),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(24.dp).testTag("search-none"),
                )
                else -> {
                    val base = rowContext(content, vm, loaders, onFolder, onPlay)
                    val ctx = remember(base, query) {
                        RowContext(
                            base.loader, base.fps, base.fields, base.volumeLabel,
                            onFolder = { keepSearch(); base.onFolder(it) },
                            onVideo = { keepSearch(); base.onVideo(it) },
                            folderActions = base.folderActions,
                            videoActions = base.videoActions,
                        )
                    }
                    LibraryItems(
                        result.folders, result.videos, content.prefs.layout, ctx,
                        rememberLazyListState(), rememberLazyGridState(),
                        Modifier.testTag("search-results"),
                    )
                }
            }
        }
    }
}

@Composable
private fun RecentSearches(recent: List<String>, onPick: (String) -> Unit, onRemove: (String) -> Unit) {
    LazyColumn(Modifier.fillMaxSize().testTag("recent-searches")) {
        items(recent, key = { it }) { q ->
            ListItem(
                headlineContent = { Text(q) },
                trailingContent = {
                    IconButton(onClick = { onRemove(q) }) {
                        Icon(LibraryIcons.Close, contentDescription = stringResource(R.string.search_remove, q))
                    }
                },
                modifier = Modifier.clickable { onPick(q) },
            )
        }
    }
}
