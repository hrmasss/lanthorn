package app.player.feature.library.network

import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import app.player.feature.library.R

/** Asks for a stream URL (US-61.1). Play is enabled only for a URL the player can open. */
@Composable
internal fun StreamDialog(onPlay: (String) -> Unit, onDismiss: () -> Unit, initial: String = "") {
    var text by rememberSaveable { mutableStateOf(initial) }
    val url = parseStreamUrl(text)
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val play = { url?.let { onPlay(it); onDismiss() } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.stream_title)) },
        text = {
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
        },
        confirmButton = {
            TextButton(onClick = { play() }, enabled = url != null, modifier = Modifier.testTag("stream-play")) {
                Text(stringResource(R.string.stream_play))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } },
    )
}
