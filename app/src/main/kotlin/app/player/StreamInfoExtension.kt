package app.player

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.player.core.engine.ActiveDecoder
import app.player.core.engine.PropertyType
import app.player.core.playerapi.MenuGroup
import app.player.core.playerapi.PanelHost
import app.player.core.playerapi.PlayerExtension
import app.player.core.playerapi.PlayerMenuEntry
import app.player.core.playerapi.PlayerPanel

/**
 * Stream info panel (NF-5: names the active decoder). Seed of P1.1's info panel, and the
 * reference for how a feature builds a panel on the generic property API alone.
 */
object StreamInfoExtension : PlayerExtension {
    override val id = "stream-info"

    override val panels = listOf<PlayerPanel>(InfoPanel)

    override val menuEntries = listOf(
        PlayerMenuEntry(id = "info", group = MenuGroup.Tools, order = 100, opensPanel = InfoPanel.id) { "Info" },
    )

    private object InfoPanel : PlayerPanel {
        override val id = "stream-info"

        @Composable
        override fun Content(host: PanelHost) {
            val engine = host.engine
            val rows = remember(engine) {
                listOf(
                    "Decoder" to engine.observe("hwdec-current", PropertyType.Text),
                    "Format" to engine.observe("file-format", PropertyType.Text),
                    "Video" to engine.observe("video-codec", PropertyType.Text),
                    "Pixel format" to engine.observe("video-params/pixelformat", PropertyType.Text),
                    "Audio" to engine.observe("audio-codec-name", PropertyType.Text),
                    "Audio out" to engine.observe("audio-params/samplerate", PropertyType.Text),
                )
            }
            Column(Modifier.padding(24.dp)) {
                rows.forEach { (label, flow) ->
                    val value by flow.collectAsStateWithLifecycle()
                    val shown = if (label == "Decoder") ActiveDecoder.fromHwdecCurrent(value).label + " ($value)" else value
                    Text(
                        "$label: ${shown ?: "-"}",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.testTag("info-$label"),
                    )
                }
            }
        }
    }
}
