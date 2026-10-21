package app.player.feature.subtitles

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface

/** Hosts the Subtitle settings page alone, as the settings screen would. */
class SettingsTestActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { Surface { SubtitleSettings.Content(highlight = null) } } }
    }
}
