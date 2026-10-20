package app.player.feature.player

import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.SystemClock
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.player.core.designsystem.component.BufferingIndicator
import app.player.core.designsystem.theme.PlayerColors
import app.player.core.designsystem.theme.PlayerMotion
import app.player.core.designsystem.theme.tabular
import app.player.core.engine.DecoderMode
import app.player.core.engine.PlayerEngine
import app.player.core.engine.PropertyType
import app.player.core.engine.VideoSurfaceView
import app.player.core.playerapi.MenuGroup
import app.player.core.playerapi.PanelHost
import app.player.core.playerapi.PlayerExtension
import app.player.core.playerapi.PlayerMenuEntry
import app.player.core.playerapi.PlayerPanel
import app.player.feature.player.overlay.Controls
import app.player.feature.player.overlay.ControlsActions
import app.player.feature.player.overlay.ControlsUi
import app.player.feature.player.overlay.DecoderContent
import app.player.feature.player.overlay.MenuContent
import app.player.feature.player.overlay.MenuItem
import app.player.feature.player.overlay.PanelFrame
import app.player.feature.player.overlay.StatusText
import app.player.feature.player.overlay.menuItems
import app.player.feature.player.settings.PlayerPrefs
import app.player.feature.player.settings.PlayerSettings
import app.player.feature.player.settings.PlayerSettingsContent
import app.player.feature.player.tools.FitMode
import app.player.feature.player.tools.Deinterlace
import app.player.feature.player.overlay.DeinterlaceContent
import app.player.feature.player.tools.VideoTools
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** The player's own panels. Feature panels use their own ids. */
internal object OwnPanel {
    const val MENU = "player-menu"
    const val DECODER = "player-decoder"
    const val SETTINGS = "player-settings"
    const val DEINTERLACE = "player-deinterlace"
}

private const val SEEK_STEP_MS = 10_000L
private const val NOTE_MS = 2_000L
private const val FIT_NOTE_MS = 1_000L

/**
 * The player: video, overlay controls, panels from features. Owns where things go and when the
 * overlay hides; the engine and the features own what they do.
 */
@Composable
fun PlayerScreen(
    engine: PlayerEngine,
    modifier: Modifier = Modifier,
    /** Hoisted so the bench can open and close the overlay like a viewer would (NF-3). */
    /** Hidden on open, as MX users expect (recon/ux-map.md, defaults). */
    controlsState: MutableState<Boolean> = remember { mutableStateOf(false) },
    extensions: List<PlayerExtension> = emptyList(),
    settings: PlayerSettings? = null,
    /** Seek preview above the seek bar (US-30.1, default on). Off: the video itself follows the finger. */
    seekPreview: Boolean = true,
    /** The viewer picked a decoder by hand (US-36.3: remembered for the file). */
    onDecoderPicked: (DecoderMode) -> Unit = {},
) {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val engineState by engine.state.collectAsStateWithLifecycle()
    // mpv's own pause flag: PlaybackState.paused resets to true on open and mpv sends no event when
    // pause was already off, so it can read paused while playing (reported to engine).
    val pauseFlag by remember(engine) { engine.observe("pause", PropertyType.Flag) }.collectAsStateWithLifecycle()
    // Until the first pause event arrives, read the value once per load.
    var pauseRead by remember(engine) { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(engine, engineState.uri, engineState.loaded) { pauseRead = engine.get("pause", PropertyType.Flag) }
    val state = engineState.copy(paused = pauseFlag ?: pauseRead ?: engineState.paused)
    val speed by remember(engine) { engine.observe("speed", PropertyType.Real) }.collectAsStateWithLifecycle()
    val prefs by (settings?.prefs ?: remember { kotlinx.coroutines.flow.MutableStateFlow(PlayerPrefs()) })
        .collectAsStateWithLifecycle()
    val talkBack = rememberTalkBackOn()
    var controls by controlsState
    var openPanel by remember { mutableStateOf<String?>(null) }
    var scrubbing by remember { mutableStateOf(false) }
    var scrubTargetMs by remember { mutableLongStateOf(0L) }
    // US-30.1: the preview engine loads with the file, so the first drag shows frames at once.
    val preview = if (seekPreview) rememberSeekPreview(state.uri) else null
    var touches by remember { mutableIntStateOf(0) }
    var note by remember { mutableStateOf<Pair<String, Long>?>(null) }
    val brightness = remember(activity) { activity?.let(::WindowBrightness) }
    val video = remember(engine) { VideoTools(engine, context) }
    var fitMode by remember { mutableStateOf(FitMode.Fit) }
    val fitNames = FitMode.entries.associateWith { stringResource(it.label) }

    // Playback's controls (feature/playback through core/player-api): queue, lock, rotate.
    val playback = remember(extensions) { extensions.firstNotNullOfOrNull { it.playback } }
    val off = remember { kotlinx.coroutines.flow.MutableStateFlow(false) }
    val on = remember { kotlinx.coroutines.flow.MutableStateFlow(true) }
    val hasPrevious by (playback?.hasPrevious ?: off).collectAsStateWithLifecycle()
    val hasNext by (playback?.hasNext ?: off).collectAsStateWithLifecycle()
    val showPreviousNext by (playback?.showPreviousNext ?: on).collectAsStateWithLifecycle()
    val stepButtons by (playback?.stepButtons ?: off).collectAsStateWithLifecycle()
    // While locked, playback's Overlay owns every touch and the controls stay hidden (US-28).
    val locked by (playback?.locked ?: off).collectAsStateWithLifecycle()
    LaunchedEffect(locked) { if (locked) { controls = false; openPanel = null } }
    val panels = remember(extensions) { extensions.flatMap { it.panels }.associateBy { it.id } }
    val entries = remember(extensions) { extensions.flatMap { it.menuEntries } }
    val subtitleEntry = entries.firstOrNull { it.id == PlayerMenuEntry.SUBTITLE_TRACKS }
    val audioEntry = entries.firstOrNull { it.id == PlayerMenuEntry.AUDIO_TRACKS }
    val settingsLabel = stringResource(R.string.menu_player_settings)
    val deinterlaceLabel = stringResource(R.string.video_deinterlace)
    val deinterlaceMode by remember(engine) { engine.observe("deinterlace", PropertyType.Text) }.collectAsStateWithLifecycle()
    val items = remember(entries, settingsLabel) {
        val own = listOf(
            MenuItem("deinterlace", MenuGroup.Video, 120, { deinterlaceLabel }) { openPanel = OwnPanel.DEINTERLACE },
            MenuItem("player-settings", MenuGroup.Tools, 900, { settingsLabel }) { openPanel = OwnPanel.SETTINGS },
        )
        menuItems(entries, own) { openPanel = it }
    }
    val title by produceState(titleOf(state.uri), state.uri) { value = displayName(context, state.uri) ?: titleOf(state.uri) }

    // Auto-hide (US-16.3, US-79): restarts on every touch; the delay is read when it starts, so a
    // changed delay applies from the next time the controls show (US-79.2).
    val hideAfter = autoHideDelay(prefs.autoHide, prefs.autoHideDelayMs, state.paused, talkBack, openPanel != null || scrubbing)
    AutoHideEffect(controls, touches, hideAfter) { controls = false }
    SystemBarsEffect(activity, controls)
    KeepScreenOnEffect(activity, on = !state.paused || prefs.keepScreenOnPaused)
    LaunchedEffect(note) {
        note?.let { (text, _) -> delay(if (text in fitNames.values) FIT_NOTE_MS else NOTE_MS); note = null }
    }
    fun show(message: String) { note = message to SystemClock.elapsedRealtime() }

    // Back twice (US-78). With the callback enabled the system shows no predictive back animation,
    // so the first press keeps the player on screen (US-78.2).
    val backTwice = remember { BackTwice() }
    val backHint = stringResource(R.string.player_back_again)
    BackHandler(enabled = prefs.backTwice && openPanel == null) {
        if (backTwice.press(SystemClock.elapsedRealtime())) activity?.finish() else show(backHint)
    }

    val host = remember(engine) {
        object : PanelHost {
            override val engine = engine
            override fun close() { openPanel = null }
            override fun open(panelId: String) { openPanel = panelId }
            override fun notify(message: String) = show(message)
        }
    }
    // US-35.3: one line when the chosen decoder could not play the stream.
    LaunchedEffect(state.decoderNotice) { state.decoderNotice?.let(::show) }
    val currentState by rememberUpdatedState(state)
    val actions = remember(engine, activity, fitNames, preview) {
        ControlsActions(
            back = { activity?.finish() },
            togglePause = engine::togglePause,
            scrub = { f ->
                scrubbing = true
                scrubTargetMs = (f * currentState.durationMs).toLong()
                // With a preview the video holds still until release; without, it follows the finger (US-17.3).
                if (preview == null) engine.scrub(scrubTargetMs)
            },
            seek = { f ->
                scrubbing = false
                engine.endScrub((f * currentState.durationMs).toLong())
            },
            toggleRemaining = { settings?.update { it.copy(showRemaining = !it.showRemaining) } },
            openDecoder = { openPanel = OwnPanel.DECODER },
            openSubtitles = { subtitleEntry?.let { openPanel = it.opensPanel } },
            openAudio = { audioEntry?.let { openPanel = it.opensPanel } },
            openMenu = { openPanel = OwnPanel.MENU },
            lock = { playback?.lock() },
            previous = { playback?.previous() },
            next = { playback?.next() },
            rotate = { playback?.rotate() ?: activity?.let(::toggleOrientation) },
            stepBack = { engine.seekTo((currentState.positionMs - SEEK_STEP_MS).coerceAtLeast(0)) },
            stepForward = { engine.seekTo(currentState.positionMs + SEEK_STEP_MS) },
            fit = {
                // US-53.1: cycle and name the mode for 1 s.
                fitMode = fitMode.next()
                video.setFit(fitMode)
                note = fitNames.getValue(fitMode) to SystemClock.elapsedRealtime()
            },
        )
    }

    // TalkBack gets seek, volume and brightness as actions on the video instead of gestures (US-69.2).
    val a11yActions = listOf(
        stringResource(R.string.a11y_seek_forward) to { engine.seekTo(currentState.positionMs + SEEK_STEP_MS) },
        stringResource(R.string.a11y_seek_back) to { engine.seekTo((currentState.positionMs - SEEK_STEP_MS).coerceAtLeast(0)) },
        stringResource(R.string.a11y_volume_up) to { stepVolume(context, up = true) },
        stringResource(R.string.a11y_volume_down) to { stepVolume(context, up = false) },
        stringResource(R.string.a11y_brightness_up) to { brightness?.let { it.set(it.level + 0.1f) }; Unit },
        stringResource(R.string.a11y_brightness_down) to { brightness?.let { it.set(it.level - 0.1f) }; Unit },
    ).map { (label, run) -> CustomAccessibilityAction(label) { run(); true } }
    val videoDesc = stringResource(R.string.a11y_video)
    val toggleLabel = stringResource(if (controls) R.string.a11y_hide_controls else R.string.a11y_show_controls)

    Box(
        modifier
            .fillMaxSize()
            // Test tags double as resource ids, so uiautomator scripts (perf/, bench) find controls.
            .semantics { testTagsAsResourceId = true }
            .background(Color.Black)
            // Every touch restarts the auto-hide timer; nothing is consumed here.
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val type = awaitPointerEvent(PointerEventPass.Initial).type
                        if (type == PointerEventType.Press || type == PointerEventType.Release) touches++
                    }
                }
            },
    ) {
        AndroidView(
            factory = { VideoSurfaceView(it).apply { this.engine = engine } },
            update = { it.engine = engine },
            modifier = Modifier.fillMaxSize(),
        )
        // Touch layer over the video. Single tap toggles the controls (US-16.2); the gesture model
        // replaces this with P1.10.
        Box(
            Modifier
                .fillMaxSize()
                .testTag("video")
                .clickable(remember { MutableInteractionSource() }, indication = null, enabled = !locked) { controls = !controls }
                .semantics {
                    contentDescription = videoDesc
                    customActions = a11yActions
                    onClick(toggleLabel) { controls = !controls; true }
                },
        )
        // Extension overlays (network state and the like): over the video, under the controls.
        extensions.forEach { it.Overlay(host) }
        if (state.buffering && !state.paused) BufferingIndicator(Modifier.align(Alignment.Center))
        state.error?.let {
            Text(
                it.replaceFirstChar(Char::uppercaseChar),
                color = PlayerColors.Content,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(PlayerColors.Scrim, CircleShape)
                    .padding(horizontal = 20.dp, vertical = 10.dp)
                    .testTag("player-error"),
            )
        }

        val status = rememberStatus(active = (controls && prefs.statusInTopBar) || (!controls && prefs.keepStatusVisible))
        AnimatedVisibility(
            controls,
            enter = fadeIn(PlayerMotion.overlayIn()),
            exit = fadeOut(PlayerMotion.overlayOut()),
        ) {
            Controls(
                ControlsUi(
                    title = title,
                    playing = !state.paused && state.error == null,
                    positionMs = state.positionMs,
                    durationMs = state.durationMs,
                    decoder = state.activeDecoder.label,
                    speed = speedLabel(speed),
                    endTime = endTime(state, prefs.showRemaining),
                    showRemaining = prefs.showRemaining,
                    status = if (prefs.statusInTopBar) status else null,
                    subtitleButton = subtitleEntry?.label?.invoke(),
                    audioButton = audioEntry?.label?.invoke(),
                    hasPrevious = hasPrevious,
                    hasNext = hasNext,
                    canLock = playback != null,
                    showPreviousNext = showPreviousNext,
                    stepButtons = stepButtons,
                ),
                actions,
            )
        }
        // Status kept in the corners while the controls are hidden (US-60.4).
        if (!controls && prefs.keepStatusVisible) {
            KeptStatus(time(state.positionMs), status, Modifier.align(Alignment.TopCenter))
        }

        note?.let { (text, _) ->
            Text(
                text,
                color = PlayerColors.Content,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(bottom = 160.dp)
                    .background(PlayerColors.Scrim, CircleShape)
                    .padding(horizontal = 20.dp, vertical = 10.dp)
                    .testTag("note"),
            )
        }

        if (scrubbing && preview != null) {
            SeekPreview(preview, scrubTargetMs, Modifier.align(Alignment.BottomCenter).padding(bottom = 160.dp))
        }

        // Keep the last panel's content during the exit animation.
        var lastPanel by remember { mutableStateOf<String?>(null) }
        LaunchedEffect(openPanel) { if (openPanel != null) lastPanel = openPanel }
        val shownPanel = openPanel ?: lastPanel
        PanelFrame(visible = openPanel != null, panelId = shownPanel, onClose = { openPanel = null }) {
            when (val id = shownPanel) {
                OwnPanel.MENU -> MenuContent(items)
                OwnPanel.DECODER -> DecoderContent(state.decoderMode, state.activeDecoder.label) {
                    engine.setDecoder(it)
                    onDecoderPicked(it)
                    openPanel = null
                }
                OwnPanel.SETTINGS -> PlayerSettingsContent(prefs, { change -> settings?.update(change) })
                OwnPanel.DEINTERLACE -> DeinterlaceContent(
                    Deinterlace.fromMpv(deinterlaceMode),
                    state.activeDecoder,
                    pick = video::setDeinterlace,
                    switchDecoder = { mode ->
                        engine.setDecoder(mode)
                        onDecoderPicked(mode)
                    },
                )
                null -> Unit
                else -> panels[id]?.Content(host)
            }
        }
    }
}

@Composable
private fun KeptStatus(elapsed: String, status: app.player.feature.player.overlay.StatusUi?, modifier: Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.displayCutout)
            .padding(12.dp)
            .testTag("kept-status"),
    ) {
        Text(
            elapsed,
            color = PlayerColors.Content,
            style = MaterialTheme.typography.labelLarge.tabular(),
            modifier = Modifier.background(PlayerColors.Scrim, CircleShape).padding(horizontal = 10.dp, vertical = 4.dp),
        )
        Box(Modifier.weight(1f))
        status?.let {
            StatusText(it, Modifier.background(PlayerColors.Scrim, CircleShape).padding(horizontal = 10.dp, vertical = 4.dp))
        }
    }
}

/** Switches to the other orientation and holds it until the player closes (US-56.2 seed). */
private fun toggleOrientation(activity: android.app.Activity) {
    val landscape = activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    activity.requestedOrientation =
        if (landscape) ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
}

/** The file name a content uri stands for, read off the main thread. */
private suspend fun displayName(context: android.content.Context, uri: String?): String? {
    val parsed = uri?.let(Uri::parse)?.takeIf { it.scheme == "content" } ?: return null
    return withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.query(parsed, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull()
    }?.let { name -> name.substringBeforeLast('.', name) }
}

private val FitMode.label get() = when (this) {
    FitMode.Fit -> R.string.fit_fit
    FitMode.Stretch -> R.string.fit_stretch
    FitMode.Crop -> R.string.fit_crop
    FitMode.Original -> R.string.fit_original
}
