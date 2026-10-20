package app.player.catalog

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.player.core.designsystem.component.BufferingIndicator
import app.player.core.designsystem.component.EmptyState
import app.player.core.designsystem.component.OverlayChip
import app.player.core.designsystem.component.OverlayIconButton
import app.player.core.designsystem.component.OverlayTitle
import app.player.core.designsystem.component.PlayPauseButton
import app.player.core.designsystem.component.ReadoutPill
import app.player.core.designsystem.component.ScrimEdge
import app.player.core.designsystem.component.SeekBar
import app.player.core.designsystem.component.VideoThumbnail
import app.player.core.designsystem.component.playerScrim
import app.player.core.designsystem.icon.LanthornIcons
import app.player.core.designsystem.theme.LanthornPlayerTheme
import app.player.core.designsystem.theme.LanthornTheme
import app.player.core.designsystem.theme.PlayerColors
import app.player.core.designsystem.theme.ThemeMode
import app.player.core.designsystem.theme.tabular

/**
 * Screens for design evidence. Extras: screen = overlay | library, mode = light | dark,
 * dynamic = true | false, frame = white | black | gradient (the fake video behind the overlay).
 */
class CatalogActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val screen = intent.getStringExtra("screen") ?: "overlay"
        val mode = if (intent.getStringExtra("mode") == "dark") ThemeMode.Dark else ThemeMode.Light
        val dynamic = intent.getStringExtra("dynamic") != "false"
        val frame = intent.getStringExtra("frame") ?: "white"
        setContent {
            when (screen) {
                "library" -> LanthornTheme(mode = mode, dynamicColor = dynamic) { LibraryCatalog() }
                else -> LanthornPlayerTheme(dynamicColor = dynamic) { OverlayCatalog(frame) }
            }
        }
    }
}

@Composable
private fun OverlayCatalog(frame: String) {
    val video = when (frame) {
        "black" -> Brush.verticalGradient(listOf(Color.Black, Color.Black))
        "gradient" -> Brush.linearGradient(listOf(Color(0xFFE8D5B0), Color(0xFF3A6EA5), Color(0xFF101820)))
        else -> Brush.verticalGradient(listOf(Color.White, Color.White))
    }
    var playing by remember { mutableStateOf(true) }
    var pos by remember { mutableFloatStateOf(0.32f) }
    Box(Modifier.fillMaxSize().background(video)) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().playerScrim(ScrimEdge.Top).safeDrawingPadding().padding(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OverlayIconButton(LanthornIcons.Back, "Back", {})
                OverlayTitle("Holiday in the mountains.mkv", Modifier.weight(1f))
                OverlayIconButton(LanthornIcons.Subtitles, "Subtitles", {})
                OverlayIconButton(LanthornIcons.Audio, "Audio track", {})
                OverlayIconButton(LanthornIcons.More, "More", {})
            }
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(24.dp)) {
                    ReadoutPill("+00:15", detail = "12:19")
                    Row(horizontalArrangement = Arrangement.spacedBy(32.dp), verticalAlignment = Alignment.CenterVertically) {
                        PlayPauseButton(playing, { playing = !playing }, "Pause")
                        PlayPauseButton(false, {}, "Play")
                        BufferingIndicator(Modifier.size(56.dp))
                    }
                }
            }
            Column(Modifier.fillMaxWidth().playerScrim(ScrimEdge.Bottom).safeDrawingPadding().padding(horizontal = 16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("12:04", style = MaterialTheme.typography.labelLarge.tabular(), color = PlayerColors.Content)
                    SeekBar(pos, {}, { pos = it }, "Seek", Modifier.weight(1f).padding(horizontal = 12.dp), buffered = 0.55f)
                    Text("1:42:10", style = MaterialTheme.typography.labelLarge.tabular(), color = PlayerColors.Content)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OverlayChip("HW", true, {})
                    OverlayChip("1.0x", false, {})
                    Spacer(Modifier.weight(1f))
                    OverlayIconButton(LanthornIcons.Lock, "Lock", {})
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun LibraryCatalog() {
    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Videos", style = MaterialTheme.typography.headlineSmall)
            val items = listOf(
                Triple("Holiday in the mountains.mkv", "1:42:10", 0.32f),
                Triple("Concert 2026-09-14.mp4", "0:58:03", null),
                Triple("Lecture 07 - Signals.webm", "1:15:44", 0.9f),
            )
            for ((i, item) in items.withIndex()) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    VideoThumbnail(item.second, Modifier.weight(0.45f), resume = item.third, unplayed = i == 1) {
                        Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(0xFF4A6FA5), Color(0xFF1B2A41)))))
                    }
                    Column(Modifier.weight(0.55f)) {
                        Text(item.first, style = MaterialTheme.typography.titleMedium, maxLines = 2)
                        Text("1080p · 2.1 GB", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Box(Modifier.fillMaxWidth().height(320.dp)) {
                EmptyState("No videos yet", actionLabel = "Pick a folder")
            }
        }
    }
}
