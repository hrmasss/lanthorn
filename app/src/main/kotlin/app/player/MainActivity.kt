package app.player

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import app.player.core.designsystem.theme.LanthornTheme
import app.player.feature.library.LibraryRoute
import app.player.feature.player.PlayerActivity

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            LanthornTheme {
                LibraryRoute(onPlay = { uri -> startActivity(PlayerActivity.intent(this, uri)) })
            }
        }
    }
}
