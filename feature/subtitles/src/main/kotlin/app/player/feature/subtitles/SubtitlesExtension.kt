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
import app.player.core.playerapi.SubtitleGestures
import kotlinx.coroutines.launch
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import app.player.core.data.choice.PerFileChoices
import app.player.core.engine.PlayerSessions
import java.nio.charset.Charset

/** Subtitle tracks, external files, sync and ASS options inside the player (P1.3). */
object SubtitlesExtension : PlayerExtension {
    override val id = "subtitles"

    const val TRACKS_PANEL = "subtitle-tracks"
    const val SYNC_PANEL = "subtitle-sync"
    const val STYLE_PANEL = "subtitle-style"
    const val ONLINE_PANEL = "subtitle-online"

    override val panels: List<PlayerPanel> = listOf(TracksPanel, SyncPanel, StylePanel, OnlineSearchPanel)

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

    private var autoLoad: SubtitleAutoLoad? = null

    internal fun autoLoad(context: Context): SubtitleAutoLoad =
        autoLoad ?: SubtitleAutoLoad(context, files(context)).also { autoLoad = it }

    override fun onEngineCreated(context: Context, engine: PlayerEngine) {
        val styles = styles(context)
        SubtitleFonts.install(context, listOf(styles.userFontDir.path))
        // We find subtitle files ourselves (scoped storage). Legacy encodings: mpv detects them (US-44.1).
        engine.set("sub-auto", "no")
        engine.set("sub-codepage", "auto")
        engine.set("sub-ass-override", "scale")
        engine.set("embeddedfonts", "yes")
        styles.attach(engine)
        // A new file starts at its remembered delay (US-41.4), else the default (US-41.3). Applied once
        // the file has loaded, also when the same file opens again: set earlier, a slow load could lose it.
        val controller = SubtitleController(engine.asMpv())
        val store = SubtitleStyleStore(context)
        val perFile = choices(context)
        val job = scope.launch {
            engine.state.map { it.loaded to it.uri }.distinctUntilChanged().filter { it.first }.mapNotNull { it.second }.collect { uri ->
                val remembered = perFile.get(uri, DELAY_KEY)?.toLongOrNull()
                val delayMs = remembered ?: store.defaultDelayMs.first()
                // The reads can outlast a quick next file: then this delay is not for the open one.
                if (engine.state.value.uri == uri) controller.setDelayMs(delayMs)
            }
        }
        // US-39: subtitle files beside the video, and which track turns on, once the file has loaded.
        val loader = autoLoad(context)
        val clearJob = scope.launch {
            engine.state.map { it.loaded }.distinctUntilChanged().filter { !it }.collect { loader.clear() }
        }
        val loadJob = scope.launch {
            // Every finished load, also of the same file again: its external tracks went with the old one.
            engine.state.map { it.loaded to it.uri }.distinctUntilChanged().filter { it.first }.collect { (_, uri) ->
                if (uri != null) runCatching { loader.onFileLoaded(engine, uri) }
            }
        }
        // US-45: gestures on subtitle text, with the viewer's switches.
        var switches = SubtitleGestureSwitches()
        val switchJob = scope.launch { SubtitlePrefsStore(context).prefs.collect { switches = it.gestures } }
        touch = SubtitleTouch(engine.asMpv(), styles::current, styles::update, { switches }, styles.navigationInsetPercent)
        scope.launch {
            PlayerSessions.current.first { it !== engine }
            job.cancel()
            loadJob.cancel()
            clearJob.cancel()
            switchJob.cancel()
        }
    }

    /** The live player's subtitle gestures; player-ui reaches them through [subtitleGestures]. */
    internal var touch: SubtitleTouch? = null
        private set

    override val subtitleGestures: SubtitleGestures = object : SubtitleGestures {
        override fun hit(x: Float, y: Float, width: Int, height: Int) = touch?.hit(x, y, width, height) ?: false
        override fun step(direction: Int) { touch?.step(direction) }
        override fun drag(dy: Float, height: Int, end: Boolean) { touch?.drag(dy, height, end) }
        override fun scale(factor: Float) { touch?.scale(factor) }
    }

    private val scope = MainScope()
    private var choices: PerFileChoices? = null
    private const val DELAY_KEY = "sub.delay"

    private fun choices(context: Context) = choices ?: PerFileChoices(context).also { choices = it }

    /** US-41.4: keep the delay the viewer set for the open file. A no-op while "remember choices" is off. */
    internal fun rememberDelay(context: Context, engine: PlayerEngine, ms: Long) {
        val uri = engine.state.value.uri ?: return
        val store = choices(context)
        scope.launch { store.put(uri, DELAY_KEY, SubtitleController.snapDelay(ms).toString()) }
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

        val loader = remember { SubtitlesExtension.autoLoad(context) }

        fun add(uri: Uri, charset: Charset? = null) = scope.launch {
            runCatching { files.prepare(uri, charset) }
                .onSuccess { prepared ->
                    controller.addFile(prepared.path, prepared.title)
                    // US-40.2: keep read access and remember it for this video.
                    runCatching { context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                    host.engine.state.value.uri?.let { video -> loader.rememberExternal(video, uri, prepared.title) }
                }
                .onFailure { Toast.makeText(context, failed, Toast.LENGTH_SHORT).show() }
        }

        // US-40.1: the picker opens in the video's folder.
        val openSubtitle = remember { OpenSubtitle() }
        val picker = rememberLauncherForActivityResult(openSubtitle) { uri -> uri?.let(::add) }

        fun rememberPick(id: Long?) {
            val uri = host.engine.state.value.uri ?: return
            val track = tracks.firstOrNull { it.id == id }
            scope.launch { loader.remember(uri, track) }
        }
        // Scoped storage: the viewer allows this folder once, then its subtitles load by themselves.
        val folderGrant = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
            if (tree != null) {
                context.contentResolver.takePersistableUriPermission(tree, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                scope.launch { host.engine.state.value.uri?.let { loader.onFileLoaded(host.engine, it) } }
            }
        }
        val online by remember { OnlineAccountStore(context).account }.collectAsState(OnlineAccount())
        val offerFolder = loader.lastResult?.takeIf { !it.folderGranted }?.place?.let(loader::folderUri)

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
                        modifier = Modifier.clickable {
                            controller.select(SubSelection())
                            rememberPick(null)
                        }.testTag("sub-off"),
                    )
                }
                items(tracks, key = { it.id }) { track ->
                    val on = track.id == selection.primary || track.id == selection.secondary
                    ListItem(
                        headlineContent = { Text(track.label()) },
                        supportingContent = { Text(track.details(track.id == selection.secondary)) },
                        leadingContent = { Checkbox(on, onCheckedChange = null) },
                        modifier = Modifier
                            .clickable {
                                val next = selection.toggle(track.id)
                                controller.select(next)
                                rememberPick(next.primary)
                            }
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
            // US-46.4: hidden unless the viewer turned online search on in settings.
            if (online.ready) {
                OutlinedButton(
                    onClick = { host.open(SubtitlesExtension.ONLINE_PANEL) },
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp).testTag("sub-search-online"),
                ) { Text(stringResource(R.string.subs_online_search_action)) }
            }
            if (offerFolder != null) {
                OutlinedButton(
                    onClick = { folderGrant.launch(offerFolder) },
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp).testTag("sub-allow-folder"),
                ) { Text(stringResource(R.string.subs_allow_folder)) }
            }
            OutlinedButton(
                onClick = {
                    openSubtitle.start = loader.lastResult?.place?.let(loader::folderUri)
                    picker.launch(arrayOf("*/*"))
                },
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
        val context = LocalContext.current
        fun setDelay(ms: Long) {
            controller.setDelayMs(ms)
            SubtitlesExtension.rememberDelay(context, host.engine, ms)
        }
        val d = delay ?: 0L
        Column(Modifier.padding(horizontal = 24.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.subs_sync_title), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.subs_delay_value, d / 1000.0), modifier = Modifier.testTag("sub-delay-value"))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(-1000L, -SubtitleController.DELAY_STEP_MS, SubtitleController.DELAY_STEP_MS, 1000L).forEach { step ->
                    OutlinedButton(onClick = { setDelay(d + step) }, modifier = Modifier.testTag("sub-delay-$step")) {
                        Text("%+.2f".format(step / 1000.0))
                    }
                }
                TextButton(onClick = { setDelay(0) }) { Text(stringResource(R.string.subs_reset)) }
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

/** The system file picker, opened at [start] (the video's folder) when the system knows it (US-40.1). */
internal class OpenSubtitle : ActivityResultContracts.OpenDocument() {
    var start: Uri? = null

    override fun createIntent(context: Context, input: Array<String>): android.content.Intent =
        super.createIntent(context, input).apply { start?.let { putExtra(android.provider.DocumentsContract.EXTRA_INITIAL_URI, it) } }
}
