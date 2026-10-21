package app.player.feature.library.network

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.player.core.data.history.HistoryPrefs
import app.player.feature.library.LibraryIcons
import app.player.feature.library.R
import kotlinx.coroutines.launch

/**
 * Asks for a stream URL (US-61.1). Opens prefilled with a URL from the clipboard (US-61.2) and
 * lists played URLs, each removable (US-61.3). Play is enabled only for a URL the player can open.
 */
@Composable
internal fun StreamDialog(onPlay: (String) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { NetworkPrefs(context) }
    val history by prefs.history.collectAsStateWithLifecycle(emptyList())
    var text by rememberSaveable { mutableStateOf(clipboardUrl(context).orEmpty()) }
    val url = parseStreamUrl(text)
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val play = {
        url?.let { u ->
            // Not the dialog's scope: the dialog closes at once and would cancel the write.
            NetworkPrefs.writes.launch { prefs.addToHistory(u, HistoryPrefs(context).isPaused()) }
            onPlay(u)
            onDismiss()
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.stream_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text(stringResource(R.string.stream_url)) },
                    supportingText = {
                        Text(stringResource(if (text.isBlank() || url != null) R.string.stream_hint else R.string.stream_invalid))
                    },
                    isError = text.isNotBlank() && url == null,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { play() }),
                    modifier = Modifier.focusRequester(focus).testTag("stream-url"),
                )
                if (history.isNotEmpty()) {
                    Text(
                        stringResource(R.string.stream_history),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    LazyColumn(Modifier.heightIn(max = 240.dp).testTag("stream-history")) {
                        items(history, key = { it }) { u ->
                            ListItem(
                                headlineContent = { Text(u, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                                trailingContent = {
                                    IconButton(onClick = { scope.launch { prefs.removeFromHistory(u) } }) {
                                        Icon(LibraryIcons.Close, contentDescription = stringResource(R.string.stream_history_remove, u))
                                    }
                                },
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                                modifier = Modifier.clickable { text = u },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { play() }, enabled = url != null, modifier = Modifier.testTag("stream-play")) {
                Text(stringResource(R.string.stream_play))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } },
    )
}

/** The clipboard's text if it is a stream URL. Reading is allowed: the dialog has focus. */
private fun clipboardUrl(context: Context): String? {
    val clip = context.getSystemService(ClipboardManager::class.java)?.primaryClip ?: return null
    if (clip.itemCount == 0) return null
    return parseStreamUrl(clip.getItemAt(0).coerceToText(context)?.toString().orEmpty())
}
