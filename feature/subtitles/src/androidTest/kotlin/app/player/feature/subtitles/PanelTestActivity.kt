package app.player.feature.subtitles

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.player.core.engine.PlayerEngine
import app.player.core.engine.PlayerSessions
import app.player.core.engine.VideoSurfaceView
import app.player.core.playerapi.PanelHost

/**
 * Video on the left, the real style panel on the right, as the player shows a side sheet in
 * landscape (agreed with player-ui). The engine is the session's live one.
 */
@OptIn(ExperimentalComposeUiApi::class)
class PanelTestActivity : ComponentActivity() {
    companion object {
        /** Which of the subtitle panels to show; the style panel unless a test says otherwise. */
        var panelId: String = SubtitlesExtension.STYLE_PANEL

        /** False when another screen already shows the video: one engine, one surface. */
        var withVideo: Boolean = true
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val engine = PlayerSessions.current.value!!
        val host = object : PanelHost {
            override val engine: PlayerEngine = engine
            override fun close() {}
            override fun open(panelId: String) {}
        }
        setContent {
            MaterialTheme {
                // Test tags as resource ids, so UiAutomator taps the real panel.
                Row(Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }) {
                    if (withVideo) AndroidView({ VideoSurfaceView(it).apply { this.engine = engine } }, Modifier.weight(1f).fillMaxHeight())
                    else Box(Modifier.weight(1f).fillMaxHeight())
                    val panel = SubtitlesExtension.panels.first { it.id == panelId }
                    Surface(Modifier.width(380.dp).fillMaxHeight()) { Box { panel.Content(host) } }
                }
            }
        }
    }
}
