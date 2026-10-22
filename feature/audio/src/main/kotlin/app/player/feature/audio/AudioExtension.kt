package app.player.feature.audio

import android.content.Context
import android.media.AudioManager
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.player.core.engine.PlayerEngine
import app.player.core.data.choice.PerFileChoices
import app.player.core.engine.PlayerSessions
import app.player.core.playerapi.MenuGroup
import app.player.core.playerapi.PanelHost
import app.player.core.playerapi.PlayerExtension
import app.player.core.playerapi.PlayerMenuEntry
import app.player.core.playerapi.PlayerPanel
import app.player.core.playerapi.VolumeBoost
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Audio tracks, external audio, delay (P1.3) and effects (P1.4) inside the player. */
object AudioExtension : PlayerExtension {
    override val id = "audio"

    const val TRACKS_PANEL = "audio-tracks"
    const val EFFECTS_PANEL = "audio-effects"

    override val panels: List<PlayerPanel> = listOf(TracksPanel, EffectsPanel)

    override val menuEntries = listOf(
        PlayerMenuEntry(id = TRACKS_PANEL, group = MenuGroup.Audio, order = 0, opensPanel = TRACKS_PANEL) {
            stringResource(R.string.audio_menu_tracks)
        },
        PlayerMenuEntry(id = EFFECTS_PANEL, group = MenuGroup.Audio, order = 10, opensPanel = EFFECTS_PANEL) {
            stringResource(R.string.audio_menu_effects)
        },
    )

    private val scope = MainScope()
    private var bound: AudioSession? = null

    private val boostEnabled = MutableStateFlow(true)
    private val boostGain = MutableStateFlow(1f)

    /** US-49.1: player-ui's volume gesture raises the gain past the system maximum through this. */
    override val volumeBoost: VolumeBoost = object : VolumeBoost {
        override val enabled: StateFlow<Boolean> = boostEnabled.asStateFlow()
        override val gain: StateFlow<Float> = boostGain.asStateFlow()
        override fun setGain(gain: Float) {
            if (boostEnabled.value) bound?.effects?.setBoost(gain)
        }
    }

    /** What this feature holds for the live engine. */
    internal class AudioSession(val engine: PlayerEngine, val effects: AudioEffects, val delay: AudioDelay)

    /** The effects of [engine], or null if it is not the live one. Main thread. */
    internal fun effects(engine: PlayerEngine): AudioEffects? = bound?.takeIf { it.engine === engine }?.effects

    internal fun delay(engine: PlayerEngine): AudioDelay? = bound?.takeIf { it.engine === engine }?.delay

    private var choices: PerFileChoices? = null

    /** US-47.4: the viewer picked [trackId] for the open file. A no-op while "remember choices" is off. */
    internal fun rememberTrack(context: Context, engine: PlayerEngine, trackId: Long) {
        val uri = engine.state.value.uri ?: return
        val store = choices ?: PerFileChoices(context).also { choices = it }
        scope.launch { store.put(uri, TRACK_KEY, trackId.toString()) }
    }

    private const val TRACK_KEY = "audio.track"
    private const val SAVE_DEBOUNCE_MS = 300L

    @OptIn(FlowPreview::class)
    override fun onEngineCreated(context: Context, engine: PlayerEngine) {
        bound?.effects?.release()
        val audio = context.getSystemService(AudioManager::class.java)
        val mpv = engine.asMpv()
        // Our own audio session, so the platform effect touches only our sound.
        val session = audio.generateAudioSessionId()
        engine.set("audiotrack-session-id", session.toLong())
        val channels = OutputChannels(audio, mpv)
        channels.start()
        val effects = AudioEffects(PlatformEffects(session), StereoFilter(mpv))
        val delay = AudioDelay(mpv)
        bound = AudioSession(engine, effects, delay)

        var prefs = AudioPrefs()
        val bluetooth = BluetoothOutput(audio) { active -> delay.setBluetooth(prefs.bluetoothDelayMs, active) }
        val store = AudioPrefsStore(context)
        val jobs = listOf(
            scope.launch {
                store.prefs.collect {
                    prefs = it
                    boostEnabled.value = it.volumeBoost
                    if (!it.volumeBoost) effects.resetBoost()
                    // US-47.2: mpv picks the matching track at open, else the default-flagged one.
                    engine.set("alang", alangFor(it.preferredLanguage))
                    delay.setBluetooth(it.bluetoothDelayMs, bluetooth.active())
                }
            },
            // US-47.4: the track the viewer picked for this file, once it has loaded.
            scope.launch {
                val store = choices ?: PerFileChoices(context).also { choices = it }
                // Every finished load, also of the same file again: mpv picks tracks afresh each time.
                engine.state.map { it.loaded to it.uri }.distinctUntilChanged().filter { it.first }.collect { (_, uri) ->
                    // mpv's aid is global: without a remembered pick, give the choice back to its own rule
                    // (preferred language, else the default-flagged track), not the last file's pick.
                    val remembered = uri?.let { store.get(it, TRACK_KEY) }?.toLongOrNull()
                    // The read can outlast a quick next file: then this pick is not for the open one.
                    // open() sets the uri on the main thread before it loads, so this check holds.
                    if (engine.state.value.uri == uri) engine.set("aid", remembered?.toString() ?: "auto")
                }
            },
            // US-50.3: the equalizer as the viewer left it, saved again as it changes.
            scope.launch {
                effects.restore(store.prefs.first().eq)
                effects.state.map { it.saved }.distinctUntilChanged().drop(1).debounce(SAVE_DEBOUNCE_MS).collect { store.setEq(it) }
            },
            scope.launch { effects.state.map { it.boost }.distinctUntilChanged().collect { boostGain.value = it } },
            // US-48.3: every new file starts at the default delay, applied once it has loaded.
            scope.launch {
                engine.state.map { it.loaded to it.uri }.distinctUntilChanged().filter { it.first && it.second != null }
                    .collect { delay.setBase(prefs.defaultDelayMs) }
            },
        )
        bluetooth.start()
        // A new engine starts at 100% boost (US-49.3); the old effect goes with the old engine.
        scope.launch {
            PlayerSessions.current.first { it !== engine }
            if (bound?.engine === engine) {
                bound = null
                boostGain.value = 1f
            }
            jobs.forEach { it.cancel() }
            effects.release()
            channels.stop()
            bluetooth.stop()
        }
    }
}

private object TracksPanel : PlayerPanel {
    override val id = AudioExtension.TRACKS_PANEL

    @Composable
    override fun Content(host: PanelHost) {
        val context = LocalContext.current
        val controller = remember(host.engine) { AudioController(host.engine.asMpv()) }
        val tracks by controller.tracks.collectAsState(emptyList())
        val selected by controller.selected.collectAsState(null)
        val audioDelay = remember(host.engine) { AudioExtension.delay(host.engine) }
        val delay by (audioDelay?.baseMs ?: controller.delayMs).collectAsState(null)
        val failed = stringResource(R.string.audio_open_failed)
        val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                runCatching { openForMpv(context, uri) }
                    .onSuccess { (url, title) -> controller.addFile(url, title) }
                    .onFailure { Toast.makeText(context, failed, Toast.LENGTH_SHORT).show() }
            }
        }
        Column(Modifier.padding(bottom = 16.dp)) {
            Text(
                stringResource(R.string.audio_tracks_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
            LazyColumn {
                items(tracks, key = { it.id }) { track ->
                    ListItem(
                        headlineContent = { Text(track.title ?: track.lang ?: stringResource(R.string.audio_track_fallback, track.id)) },
                        supportingContent = { Text(track.details()) },
                        leadingContent = { RadioButton(track.id == selected, onClick = null) },
                        modifier = Modifier.clickable {
                            controller.select(track.id)
                            AudioExtension.rememberTrack(context, host.engine, track.id)
                        }.testTag("audio-track-${track.id}"),
                    )
                }
            }
            OutlinedButton(
                onClick = { picker.launch(arrayOf("audio/*")) },
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp).testTag("audio-open-file"),
            ) { Text(stringResource(R.string.audio_open_file)) }
            val d = delay ?: 0L
            Text(
                stringResource(R.string.audio_delay, d / 1000.0),
                modifier = Modifier.padding(horizontal = 24.dp).testTag("audio-delay-value"),
            )
            Row(Modifier.padding(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(-100L, -AudioController.DELAY_STEP_MS, AudioController.DELAY_STEP_MS, 100L).forEach { step ->
                    OutlinedButton(onClick = { audioDelay?.setBase(d + step) ?: controller.setDelayMs(d + step) }, modifier = Modifier.testTag("audio-delay-$step")) {
                        Text("%+.2f".format(step / 1000.0))
                    }
                }
                TextButton(onClick = { audioDelay?.setBase(0) ?: controller.setDelayMs(0) }) { Text(stringResource(R.string.audio_reset)) }
            }
        }
    }
}

/** mpv reads the file through a descriptor it closes itself, so `content://` needs no copy. */
private fun openForMpv(context: Context, uri: Uri): Pair<String, String?> {
    if (uri.scheme != "content") return (uri.path ?: uri.toString()) to uri.lastPathSegment
    val title = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0) else null
    }
    val pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: error("cannot open $uri")
    return "fdclose://${pfd.detachFd()}" to title
}

@Composable
private fun Track.details(): String = listOfNotNull(
    lang,
    codec,
    channels?.let { stringResource(R.string.audio_channels, it) },
    stringResource(R.string.audio_external).takeIf { external },
).joinToString(" · ")

private object EffectsPanel : PlayerPanel {
    override val id = AudioExtension.EFFECTS_PANEL

    @Composable
    override fun Content(host: PanelHost) {
        val effects = remember(host.engine) { AudioExtension.effects(host.engine) } ?: return
        val state by effects.state.collectAsState()
        Column(Modifier.padding(horizontal = 24.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.audio_effects_title), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.audio_stereo_mode), style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StereoMode.entries.forEach { mode ->
                    FilterChip(
                        selected = state.stereo == mode,
                        onClick = { effects.setStereo(mode) },
                        label = { Text(stringResource(mode.label)) },
                        modifier = Modifier.testTag("audio-stereo-${mode.name}"),
                    )
                }
            }
            Text(stringResource(R.string.audio_boost, (state.boost * 100).roundToInt()))
            Slider(
                value = state.boost,
                onValueChange = effects::setBoost,
                valueRange = 1f..AudioEffects.MAX_BOOST,
                modifier = Modifier.testTag("audio-boost"),
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.audio_eq)) },
                trailingContent = { Switch(state.eqOn, onCheckedChange = null) },
                modifier = Modifier.clickable { effects.setEq(!state.eqOn) }.testTag("audio-eq"),
            )
            if (state.eqOn) {
                LazyColumn {
                    item {
                        Text(stringResource(R.string.audio_eq_presets), style = MaterialTheme.typography.labelLarge)
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(end = 24.dp)) {
                            items(EqPreset.entries) { preset ->
                                FilterChip(
                                    selected = state.preset == preset,
                                    onClick = { effects.setPreset(preset) },
                                    label = { Text(stringResource(preset.label)) },
                                    modifier = Modifier.testTag("audio-preset-${preset.name}"),
                                )
                            }
                        }
                    }
                    item {
                        Text(stringResource(R.string.audio_preamp, state.preampDb))
                        Slider(state.preampDb, effects::setPreamp, valueRange = Eq.MIN_DB..Eq.MAX_DB)
                    }
                    items(Eq.BANDS) { i ->
                        Text(stringResource(R.string.audio_band, hz(Eq.CENTRES_HZ[i]), state.bandsDb[i]))
                        Slider(
                            value = state.bandsDb[i],
                            onValueChange = { effects.setBand(i, it) },
                            valueRange = Eq.MIN_DB..Eq.MAX_DB,
                            modifier = Modifier.testTag("audio-band-$i"),
                        )
                    }
                }
            }
        }
    }
}

private val StereoMode.label: Int get() = when (this) {
    StereoMode.Stereo -> R.string.audio_stereo
    StereoMode.Mono -> R.string.audio_mono
    StereoMode.Reverse -> R.string.audio_reverse
}

private val EqPreset.label: Int get() = when (this) {
    EqPreset.Flat -> R.string.audio_eq_flat
    EqPreset.Classical -> R.string.audio_eq_classical
    EqPreset.Dance -> R.string.audio_eq_dance
    EqPreset.Folk -> R.string.audio_eq_folk
    EqPreset.HeavyMetal -> R.string.audio_eq_heavy_metal
    EqPreset.HipHop -> R.string.audio_eq_hip_hop
    EqPreset.Jazz -> R.string.audio_eq_jazz
    EqPreset.Pop -> R.string.audio_eq_pop
    EqPreset.Rock -> R.string.audio_eq_rock
    EqPreset.Custom -> R.string.audio_eq_custom
}

private fun hz(f: Float): String = if (f >= 1000) "${(f / 1000).let { if (it % 1f == 0f) it.toInt().toString() else it.toString() }} kHz" else "${f.toInt()} Hz"
