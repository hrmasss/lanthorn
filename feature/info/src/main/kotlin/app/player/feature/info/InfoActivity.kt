package app.player.feature.info

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import app.player.core.designsystem.icon.LanthornIcons
import app.player.core.designsystem.theme.LanthornTheme
import app.player.core.engine.PlayerEngine

/**
 * File properties (US-10.4, US-37.1 and .2) for any uri, opened by action
 * `PlayerIntents.ACTION_SHOW_INFO`. A headless engine reads the file: paused, no audio output, no
 * video surface. Nothing plays.
 */
class InfoActivity : ComponentActivity() {
    private var engine: PlayerEngine? = null

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val uri: Uri = intent.data ?: return finish()
        val e = PlayerEngine.preview(this).also { engine = it }
        e.open(uri)
        val name = uri.lastPathSegment?.substringAfterLast('/') ?: uri.toString()
        setContent {
            LanthornTheme {
                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = { Text(name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                            navigationIcon = {
                                IconButton(onClick = ::finish) { Icon(LanthornIcons.Back, contentDescription = "Back") }
                            },
                        )
                    },
                ) { padding ->
                    InfoContent(e, live = false, modifier = Modifier.padding(padding).testTag("file-info"))
                }
            }
        }
    }

    override fun onDestroy() {
        engine?.release()
        super.onDestroy()
    }
}
