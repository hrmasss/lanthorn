package app.player

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import app.player.core.designsystem.theme.LanthornTheme
import app.player.core.playerapi.PlayerIntents
import app.player.feature.library.LibraryRoute
import app.player.feature.library.settings.rememberAppearance
import app.player.feature.player.PlayerActivity

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            // Null for the first frames while the theme choice is read, so a dark choice never flashes light.
            val appearance = rememberAppearance() ?: return@setContent
            LanthornTheme(appearance.mode, appearance.dynamicColor, appearance.pureBlack) {
                LibraryRoute(
                    onPlay = { uri -> startActivity(PlayerActivity.intent(this, uri)) },
                    onPlayQueue = { uris, index ->
                        startActivity(PlayerIntents.withQueue(PlayerActivity.intent(this, uris[index]), uris, index))
                    },
                    onPlayWith = { uris, decoder ->
                        val queue = PlayerIntents.withQueue(PlayerActivity.intent(this, uris[0]), uris, 0)
                        startActivity(PlayerIntents.withDecoder(queue, decoder))
                    },
                )
            }
        }
    }
}
