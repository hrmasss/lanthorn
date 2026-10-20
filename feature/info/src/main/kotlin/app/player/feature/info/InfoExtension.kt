package app.player.feature.info

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.player.core.playerapi.MenuGroup
import app.player.core.playerapi.PanelHost
import app.player.core.playerapi.PlayerExtension
import app.player.core.playerapi.PlayerMenuEntry
import app.player.core.playerapi.PlayerPanel
import kotlinx.coroutines.delay

/** The info panel (US-37): file, streams, decoder (NF-5), live drops and bit rate. */
object InfoExtension : PlayerExtension {
    override val id = "info"

    override val panels = listOf<PlayerPanel>(InfoPanel)

    override val menuEntries = listOf(
        PlayerMenuEntry(id = "info", group = MenuGroup.Tools, order = 100, opensPanel = InfoPanel.id) { "Info" },
    )

    private object InfoPanel : PlayerPanel {
        override val id = "info"

        @Composable
        override fun Content(host: PanelHost) {
            val context = LocalContext.current
            var info by remember { mutableStateOf<StreamInfo?>(null) }
            // US-37.2 and .3: refresh every second, so a decoder change shows within 1 s.
            LaunchedEffect(host.engine) {
                while (true) {
                    info = StreamInfoReader.read(context, host.engine)
                    delay(1000)
                }
            }
            val i = info ?: return
            Column(
                Modifier.padding(horizontal = 24.dp, vertical = 8.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Section("Decoder", i.decoder)
                Section("File", i.file)
                if (i.video.isNotEmpty()) Section("Video", i.video)
                i.audio.forEachIndexed { n, rows -> Section(if (i.audio.size > 1) "Audio ${n + 1}" else "Audio", rows) }
                i.subtitles.forEachIndexed { n, rows -> Section(if (i.subtitles.size > 1) "Subtitle ${n + 1}" else "Subtitle", rows) }
                Section("Playback", i.live)
            }
        }
    }
}

@Composable
private fun Section(title: String, rows: List<Pair<String, String>>) {
    Column(Modifier.testTag("info-section-$title")) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        rows.forEach { (label, value) ->
            Row(Modifier.fillMaxWidth().padding(top = 2.dp)) {
                Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(0.4f))
                Text(value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(0.6f).testTag("info-$title-$label"))
            }
        }
    }
}
