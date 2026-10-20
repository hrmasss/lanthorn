package app.player.feature.subtitles

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.player.core.engine.PlayerEngine
import app.player.core.playerapi.MenuGroup
import app.player.core.playerapi.PanelHost
import app.player.core.playerapi.PlayerExtension
import app.player.core.playerapi.PlayerMenuEntry
import app.player.core.playerapi.PlayerPanel
import kotlinx.coroutines.launch
import java.nio.charset.Charset

/** Subtitle tracks, external files, sync and ASS options inside the player (P1.3). */
object SubtitlesExtension : PlayerExtension {
    override val id = "subtitles"

    const val TRACKS_PANEL = "subtitle-tracks"
    const val SYNC_PANEL = "subtitle-sync"
    const val STYLE_PANEL = "subtitle-style"

    override val panels: List<PlayerPanel> = listOf(TracksPanel, SyncPanel, StylePanel)

    override val menuEntries = listOf(
        PlayerMenuEntry(id = TRACKS_PANEL, group = MenuGroup.Subtitle, order = 0, opensPanel = TRACKS_PANEL) {
            stringResource(R.string.subs_menu_tracks)
        },
        PlayerMenuEntry(id = SYNC_PANEL, group = MenuGroup.Subtitle, order = 10, opensPanel = SYNC_PANEL) {
            stringResource(R.string.subs_menu_sync)
        },
        PlayerMenuEntry(id = STYLE_PANEL, group = MenuGroup.Subtitle, order = 20, opensPanel = STYLE_PANEL) {
            stringResource(R.string.subs_menu_style)
        },
    )

    private var files: SubtitleFiles? = null
    private var styles: SubtitleStyles? = null

    internal fun styles(context: Context): SubtitleStyles =
        styles ?: SubtitleStyles(context).also { styles = it }

    internal fun files(context: Context): SubtitleFiles =
        files ?: SubtitleFiles(context).also { files = it }

    override fun onEngineCreated(context: Context, engine: PlayerEngine) {
        val styles = styles(context)
        SubtitleFonts.install(context, listOf(styles.userFontDir.path))
        // We find and decode subtitle files ourselves (UTF-8 for mpv, which has no iconv here).
        engine.set("sub-auto", "no")
        engine.set("sub-ass-override", "scale")
        engine.set("embeddedfonts", "yes")
        styles.attach(engine)
    }
}

private object TracksPanel : PlayerPanel {
    override val id = SubtitlesExtension.TRACKS_PANEL

    @Composable
    override fun Content(host: PanelHost) {
        val context = LocalContext.current
        val controller = remember(host.engine) { SubtitleController(host.engine.asMpv()) }
        val files = remember { SubtitlesExtension.files(context) }
        val scope = rememberCoroutineScope()
        val tracks by controller.tracks.collectAsState(emptyList())
        val selection by controller.selection.collectAsState(SubSelection())
        val override by controller.overrideStyles.collectAsState(false)
        val ignoreFonts by controller.ignoreEmbeddedFonts.collectAsState(false)
        val failed = stringResource(R.string.subs_open_failed)

        fun add(uri: Uri, charset: Charset? = null) = scope.launch {
            runCatching { files.prepare(uri, charset) }
                .onSuccess { controller.addFile(it.path, it.title) }
                .onFailure { Toast.makeText(context, failed, Toast.LENGTH_SHORT).show() }
        }

        val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(::add) }

        Column(Modifier.padding(bottom = 16.dp)) {
            Text(
                stringResource(R.string.subs_tracks_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
            LazyColumn {
                item {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.subs_off)) },
                        leadingContent = { Checkbox(selection.primary == null, onCheckedChange = null) },
                        modifier = Modifier.clickable { controller.select(SubSelection()) }.testTag("sub-off"),
                    )
                }
                items(tracks, key = { it.id }) { track ->
                    val on = track.id == selection.primary || track.id == selection.secondary
                    ListItem(
                        headlineContent = { Text(track.label()) },
                        supportingContent = { Text(track.details(track.id == selection.secondary)) },
                        leadingContent = { Checkbox(on, onCheckedChange = null) },
                        modifier = Modifier
                            .clickable { controller.select(selection.toggle(track.id)) }
                            .testTag("sub-track-${track.id}"),
                    )
                    if (on && files.isOurs(track.externalFilename)) {
                        EncodingRow { charset ->
                            scope.launch {
                                if (files.reencode(track.externalFilename!!, charset)) controller.reload(track.id)
                            }
                        }
                    }
                }
            }
            OutlinedButton(
                onClick = { picker.launch(arrayOf("*/*")) },
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp).testTag("sub-open-file"),
            ) { Text(stringResource(R.string.subs_open_file)) }
            SwitchRow(stringResource(R.string.subs_override_styles), override, "sub-override", onChange = controller::setOverrideStyles)
            SwitchRow(
                stringResource(R.string.subs_ignore_fonts),
                ignoreFonts,
                "sub-ignore-fonts",
                stringResource(R.string.subs_ignore_fonts_note),
                onChange = controller::setIgnoreEmbeddedFonts,
            )
        }
    }
}

@Composable
private fun EncodingRow(onPick: (Charset?) -> Unit) {
    var picked by rememberSaveable { mutableStateOf<String?>(null) }
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(R.string.subs_encoding), style = MaterialTheme.typography.labelLarge)
        FilterChip(picked == null, onClick = { picked = null; onPick(null) }, label = { Text(stringResource(R.string.subs_encoding_auto)) })
        SubtitleText.CHOICES.forEach { name ->
            FilterChip(
                picked == name,
                onClick = { picked = name; onPick(Charset.forName(name)) },
                label = { Text(name) },
                modifier = Modifier.testTag("sub-encoding-$name"),
            )
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, tag: String, note: String? = null, onChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(label) },
        supportingContent = note?.let { { Text(it) } },
        trailingContent = { Switch(checked, onCheckedChange = null) },
        modifier = Modifier.clickable { onChange(!checked) }.testTag(tag),
    )
}

private object SyncPanel : PlayerPanel {
    override val id = SubtitlesExtension.SYNC_PANEL

    @Composable
    override fun Content(host: PanelHost) {
        val controller = remember(host.engine) { SubtitleController(host.engine.asMpv()) }
        val delay by controller.delayMs.collectAsState(null)
        val speed by controller.speed.collectAsState(null)
        val d = delay ?: 0L
        Column(Modifier.padding(horizontal = 24.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.subs_sync_title), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.subs_delay_value, d / 1000.0), modifier = Modifier.testTag("sub-delay-value"))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(-1000L, -SubtitleController.DELAY_STEP_MS, SubtitleController.DELAY_STEP_MS, 1000L).forEach { step ->
                    OutlinedButton(onClick = { controller.setDelayMs(d + step) }, modifier = Modifier.testTag("sub-delay-$step")) {
                        Text("%+.2f".format(step / 1000.0))
                    }
                }
                TextButton(onClick = { controller.setDelayMs(0) }) { Text(stringResource(R.string.subs_reset)) }
            }
            val s = speed ?: 1.0
            Text(stringResource(R.string.subs_speed) + "  " + stringResource(R.string.subs_speed_value, s))
            Slider(
                value = s.toFloat(),
                onValueChange = { controller.setSpeed(Math.round(it * 20) / 20.0) },
                valueRange = SubtitleController.MIN_SPEED.toFloat()..SubtitleController.MAX_SPEED.toFloat(),
                modifier = Modifier.testTag("sub-speed"),
            )
        }
    }
}

@Composable
private fun Track.label(): String = title ?: lang ?: stringResource(R.string.subs_track_fallback, id)

@Composable
private fun Track.details(secondary: Boolean): String = listOfNotNull(
    lang,
    codec,
    stringResource(R.string.subs_external).takeIf { external },
    stringResource(R.string.subs_second).takeIf { secondary },
).joinToString(" · ")
