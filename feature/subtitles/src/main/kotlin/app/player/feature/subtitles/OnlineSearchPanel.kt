package app.player.feature.subtitles

import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.player.core.playerapi.PanelHost
import app.player.core.playerapi.PlayerPanel
import kotlinx.coroutines.launch

/**
 * Online subtitle search (US-46). Opened from the track panel only when the viewer turned it on;
 * nothing goes over the network until Search is tapped (US-46.3).
 */
internal object OnlineSearchPanel : PlayerPanel {
    override val id = SubtitlesExtension.ONLINE_PANEL

    /** Tests point this at a local server. */
    @Volatile var baseUrl: String = OpenSubtitles.BASE_URL

    @Composable
    override fun Content(host: PanelHost) {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val search = remember { OnlineSearch(context, baseUrl) }
        val state by host.engine.state.collectAsState()
        val video = state.uri?.let(Uri::parse)
        var query by remember { mutableStateOf("") }
        var hash by remember { mutableStateOf<String?>(null) }
        var language by remember { mutableStateOf("") }
        var results by remember { mutableStateOf<List<OpenSubtitles.Result>>(emptyList()) }
        var status by remember { mutableStateOf<String?>(null) }
        val searching = stringResource(R.string.subs_online_searching)
        val nothing = stringResource(R.string.subs_online_nothing)
        val downloading = stringResource(R.string.subs_online_downloading)
        val failed = stringResource(R.string.subs_online_failed)
        LaunchedEffect(video) {
            // Local only: the file name and hash. No request yet.
            video?.let { val (name, h) = search.describe(it); query = name; hash = h }
            language = SubtitlePrefsStore(context).current().preferredLanguage
        }
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp).testTag("sub-online"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.subs_online_title), style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(query, { query = it }, label = { Text(stringResource(R.string.subs_online_query)) }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("sub-online-query"))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(language, { language = it.lowercase().take(12) }, label = { Text(stringResource(R.string.subs_online_language)) }, singleLine = true, modifier = Modifier.weight(1f).testTag("sub-online-language"))
                Button(
                    onClick = {
                        status = searching
                        scope.launch {
                            runCatching { search.search(query, hash, language) }
                                .onSuccess { results = it; status = if (it.isEmpty()) nothing else null }
                                .onFailure { status = failed.format(it.message ?: "") }
                        }
                    },
                    modifier = Modifier.testTag("sub-online-search"),
                ) { Text(stringResource(R.string.subs_online_search)) }
            }
            status?.let { Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("sub-online-status")) }
            LazyColumn {
                items(results, key = { it.fileId }) { r ->
                    ListItem(
                        headlineContent = { Text(r.release) },
                        supportingContent = {
                            Text(stringResource(R.string.subs_online_result, r.language, r.rating, r.downloads) + if (r.hashMatch) " · " + stringResource(R.string.subs_online_exact) else "")
                        },
                        modifier = Modifier.clickable {
                            val v = video ?: return@clickable
                            status = downloading
                            scope.launch {
                                runCatching { search.download(v, r) }
                                    .onSuccess { saved ->
                                        val files = SubtitlesExtension.files(context)
                                        val prepared = files.prepare(Uri.fromFile(saved))
                                        SubtitleController(host.engine.asMpv()).addFile(prepared.path, saved.name)
                                        SubtitlesExtension.autoLoad(context).rememberExternal(v.toString(), Uri.fromFile(saved), saved.name)
                                        host.close()
                                    }
                                    .onFailure { status = failed.format(it.message ?: "") }
                            }
                        }.testTag("sub-online-result-${r.fileId}"),
                    )
                }
            }
        }
    }
}
