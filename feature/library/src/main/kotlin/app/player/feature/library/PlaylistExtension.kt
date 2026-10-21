package app.player.feature.library

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import app.player.core.data.library.LibraryIndex
import app.player.core.playerapi.MenuGroup
import app.player.core.playerapi.PanelHost
import app.player.core.playerapi.PlayerExtension
import app.player.core.playerapi.PlayerMenuEntry
import app.player.core.playerapi.PlayerPanel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/** "Add to playlist" in the player's menu (US-12.1): the playing file goes into a playlist. */
object PlaylistExtension : PlayerExtension {
    override val id = "playlists"

    override val panels = listOf<PlayerPanel>(AddPanel)

    override val menuEntries = listOf(
        PlayerMenuEntry(id = "add-to-playlist", group = MenuGroup.Tools, order = 50, opensPanel = AddPanel.id) {
            stringResource(R.string.action_add_to_playlist)
        },
    )

    private object AddPanel : PlayerPanel {
        override val id = "add-to-playlist"

        @Composable
        override fun Content(host: PanelHost) {
            val context = LocalContext.current
            val uri = host.engine.state.value.uri
            var video by remember(uri) { mutableStateOf<Pair<String, String>?>(null) }
            LaunchedEffect(uri) { if (uri != null) video = uri to nameOf(context, uri) }
            val v = video ?: return
            AddToPlaylist(listOf(v), onDismiss = host::close, notify = host::notify) { list ->
                Column(Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) {
                    Text(
                        stringResource(R.string.playlist_add_title),
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                    list()
                }
            }
        }
    }

    /** The library's name for the file, the provider's, or the last part of its path. */
    private suspend fun nameOf(context: Context, uri: String): String = withContext(Dispatchers.IO) {
        runCatching { LibraryIndex.get(context).videos.first().firstOrNull { it.uri == uri }?.name }.getOrNull()
            ?: runCatching {
                context.contentResolver.query(uri.toUri(), arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) c.getString(0) else null
                }
            }.getOrNull()
            ?: Uri.decode(uri.toUri().lastPathSegment ?: uri)
    }
}
