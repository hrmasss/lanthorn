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
import app.player.core.playerapi.PlayerChrome
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
import app.player.feature.player.tools.Chapter
import app.player.feature.player.tools.VideoState
import app.player.feature.player.overlay.DeinterlaceContent
import app.player.feature.player.overlay.FitAspectContent
import app.player.feature.player.overlay.PictureContent
import app.player.feature.player.overlay.Shortcut
import app.player.feature.player.overlay.ShortcutState
import app.player.feature.player.overlay.ShortcutsGrid
import app.player.feature.player.overlay.label
import app.player.core.engine.ActiveDecoder
import app.player.feature.player.gesture.GestureController
import app.player.feature.player.gesture.GestureEffects
import app.player.feature.player.gesture.GestureReadout
import app.player.feature.player.gesture.LevelKind
import app.player.feature.player.gesture.Readout
import app.player.feature.player.gesture.playerGestures
import android.media.AudioManager
import androidx.compose.animation.core.snap
import androidx.compose.material3.ButtonDefaults
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import kotlin.math.roundToInt
import app.player.feature.player.tools.FitMode
import app.player.feature.player.tools.PlaybackTools
import app.player.feature.player.tools.SleepTimer
import app.player.feature.player.overlay.AbContent
import app.player.feature.player.overlay.ChaptersContent
import app.player.feature.player.overlay.SleepContent
import app.player.feature.player.overlay.SpeedContent
import app.player.feature.player.overlay.rememberLeft
import androidx.compose.material3.TextButton
import androidx.compose.runtime.rememberCoroutineScope
import app.player.core.data.choice.PerFileChoices
import app.player.feature.player.tools.Deinterlace
import app.player.feature.player.tools.VideoTools
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val Main = Dispatchers.Main.immediate

/** The player's own panels. Feature panels use their own ids. */
internal object OwnPanel {
    const val MENU = "player-menu"
    const val DECODER = "player-decoder"
    const val SETTINGS = "player-settings"
    const val SPEED = "player-speed"
    const val AB = "player-ab"
    const val SLEEP = "player-sleep"
    const val CHAPTERS = "player-chapters"
    const val FIT = "player-fit"
    const val PICTURE = "player-picture"
    const val DEINTERLACE = "player-deinterlace"
}

/** A one-line note over the video, optionally with one action (US-33.2's share). */
internal data class Note(val text: String, val durationMs: Long, val action: Pair<String, () -> Unit>? = null, val at: Long = SystemClock.elapsedRealtime())

private const val SEEK_STEP_MS = 10_000L
private const val KEY_READOUT_MS = 1_000L
private const val NOTE_MS = 2_000L
private const val FIT_NOTE_MS = 1_000L

/** Per-file choice key for the fit mode (docs/specs/03_architecture.md, per-file choices). */
internal const val FIT_CHOICE = "video.fit"
private const val SHOT_NOTE_MS = 4_000L

/**
 * The player: video, overlay controls, panels from features. Owns where things go and when the
 * overlay hides; the engine and the features own what they do.
 */
@Composable
fun PlayerScreen(
    engine: PlayerEngine,
    modifier: Modifier = Modifier,
    /** Hoisted so the bench can open and close the overlay like a viewer would (NF-3). */
    /** Hidden on open, the familiar default for players of this kind (US-16.3). */
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
    // Engine flows change on mpv's thread. Collecting on Main keeps the resumed collector, and any
    // frame it triggers, off that thread (under the test clock a frame ran there and broke the
    // slot table).
    // Engine's paused flag is right after open since 9004c23; read it as it is.
    val state by engine.state.collectAsStateWithLifecycle(context = Main)
    val speed by remember(engine) { engine.observe("speed", PropertyType.Real) }.collectAsStateWithLifecycle(context = Main)
    val prefs by (settings?.prefs ?: remember { kotlinx.coroutines.flow.MutableStateFlow(PlayerPrefs()) })
        .collectAsStateWithLifecycle(context = Main)
    val talkBack = rememberTalkBackOn()
    var controls by controlsState
    var openPanel by remember { mutableStateOf<String?>(null) }
    var scrubbing by remember { mutableStateOf(false) }
    var scrubTargetMs by remember { mutableLongStateOf(0L) }
    // US-30.1: the preview engine loads with the file, so the first drag shows frames at once.
    val preview = if (seekPreview) rememberSeekPreview(state.uri) else null
    var touches by remember { mutableIntStateOf(0) }
    var screenshotRequests by remember { mutableIntStateOf(0) }
    var note by remember { mutableStateOf<Note?>(null) }
    val tools = remember(engine) { PlaybackTools(engine) }
    val sleep by SleepTimer.running.collectAsStateWithLifecycle(context = Main)
    val abA by remember(engine) { tools.abA }.collectAsStateWithLifecycle(context = Main)
    val abB by remember(engine) { tools.abB }.collectAsStateWithLifecycle(context = Main)
    val chapterCount by remember(engine) { tools.chapterCount }.collectAsStateWithLifecycle(context = Main)
    val chapters by produceState(emptyList<Chapter>(), state.uri, chapterCount, state.loaded) { value = tools.chapters() }
    val brightness = remember(activity) { activity?.let(::WindowBrightness) }
    val video = remember(engine) { VideoState(VideoTools(engine, context)) }
    val videoTools = remember(engine) { VideoTools(engine, context) }
    var rotationLocked by remember { mutableStateOf(false) }
    val muted by remember(engine) { engine.observe("mute", PropertyType.Flag) }.collectAsStateWithLifecycle(context = Main)
    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    val audio = remember(context) { context.getSystemService(AudioManager::class.java) }
    val currentControls by rememberUpdatedState(controls)
    val windowBrightness = brightness
    val currentPreview by rememberUpdatedState(preview)
    val volumeBoost by rememberUpdatedState(extensions.firstNotNullOfOrNull { it.volumeBoost })
    val gestures = remember(engine) {
        GestureController(object : GestureEffects {
            override val positionMs get() = engine.state.value.positionMs
            override val durationMs get() = engine.state.value.durationMs
            override val paused get() = engine.state.value.paused
            override val speed get() = engine.observe("speed", PropertyType.Real).value ?: 1.0
            override val controlsShown get() = currentControls
            // A swipe uses the seek bar's path: scrub while the finger moves (the preview frame
            // shows when it is on, US-17.3, US-30.1), one exact seek on release (US-17.2).
            override fun seek(ms: Long, exact: Boolean) {
                if (exact) {
                    if (scrubbing) {
                        scrubbing = false
                        engine.endScrub(ms)
                    } else {
                        engine.seekTo(ms, exact = true)
                    }
                } else {
                    scrubbing = true
                    scrubTargetMs = ms
                    if (currentPreview == null) engine.scrub(ms)
                }
            }
            override fun togglePause() = engine.togglePause()
            override fun pause() = engine.pause()
            override fun setSpeed(speed: Double) = tools.setSpeed(speed)
            override fun setControls(shown: Boolean) { controls = shown }
            override var brightness: Float
                get() = windowBrightness?.level ?: 0.5f
                set(v) { windowBrightness?.set(v) }
            override var volume: Float
                get() = audio?.let { it.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / it.getStreamMaxVolume(AudioManager.STREAM_MUSIC) } ?: 0f
                set(v) {
                    audio?.let { it.setStreamVolume(AudioManager.STREAM_MUSIC, (v * it.getStreamMaxVolume(AudioManager.STREAM_MUSIC)).roundToInt(), 0) }
                }
            override val volumeSteps get() = audio?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: 15
            override val boostOn get() = volumeBoost?.enabled?.value == true
            override var boost: Float
                get() = volumeBoost?.gain?.value ?: 1f
                set(v) { volumeBoost?.setGain(v) }
            override fun setZoom(scale: Double) = videoTools.setZoom(scale)
            override fun setPan(x: Double, y: Double) = videoTools.setPan(x, y)
            override fun toggleFitCrop() {
                video.chooseFit(if (video.fit == FitMode.Crop) FitMode.Fit else FitMode.Crop)
            }
        })
    }
    gestures.prefs = prefs.gestures
    // Picture geometry for pan limits and the 100% snap (US-55.2, US-55.3).
    LaunchedEffect(state.video, viewSize) {
        val v = state.video ?: return@LaunchedEffect
        if (viewSize.width == 0 || v.width == 0 || v.height == 0) return@LaunchedEffect
        val aspect = v.width.toDouble() / v.height
        val fitW = minOf(1.0, viewSize.height * aspect / viewSize.width)
        val fitH = minOf(1.0, viewSize.width / aspect / viewSize.height)
        gestures.fitWidth = fitW
        gestures.fitHeight = fitH
        gestures.nativeScale = v.width / (fitW * viewSize.width)
    }
    // Readouts from taps go after the skip window (US-19.3).
    LaunchedEffect(gestures.readout) {
        val r = gestures.readout
        if (r is Readout.Skip || r is Readout.PlayPause || (r is Readout.Speed && !r.top)) {
            delay(GestureController.SKIP_RUN_MS + 300)
            if (gestures.readout === r) gestures.clearReadout()
        }
    }
    // US-18.3: the window starts at the system brightness unless the viewer asked to remember it;
    // leaving the player drops the window override, so the system level is back.
    LaunchedEffect(prefs.gestures.rememberBrightness) {
        val last = prefs.gestures.lastBrightness
        if (prefs.gestures.rememberBrightness && last >= 0f) brightness?.set(last)
    }
    DisposableEffect(brightness) {
        onDispose {
            val b = brightness ?: return@onDispose
            val level = b.level
            settings?.update { p -> if (p.gestures.rememberBrightness) p.copy(gestures = p.gestures.copy(lastBrightness = level)) else p }
        }
    }
    val fitNames = FitMode.entries.associateWith { stringResource(it.label) }

    // Playback's controls (feature/playback through core/player-api): queue, lock, rotate.
    val playback = remember(extensions) { extensions.firstNotNullOfOrNull { it.playback } }
    val off = remember { kotlinx.coroutines.flow.MutableStateFlow(false) }
    // Volume keys as brightness and next/previous keys as seeks (US-77.1, US-31.4): playback reads
    // the keys and calls back here. Each key press shows the level bar for a moment.
    var brightnessKeys by remember { mutableIntStateOf(0) }
    // Playback holds it weakly, so the screen keeps it for as long as it shows.
    val chrome = remember(gestures) {
        object : PlayerChrome {
            override fun stepBrightness(up: Boolean) {
                gestures.stepBrightness(up)
                brightnessKeys++
            }
            override val seekStepMs get() = SEEK_STEP_MS
        }
    }
    LaunchedEffect(playback, chrome) { playback?.attachChrome(chrome) }
    LaunchedEffect(brightnessKeys) {
        if (brightnessKeys == 0) return@LaunchedEffect
        delay(KEY_READOUT_MS)
        if ((gestures.readout as? Readout.Level)?.kind == LevelKind.Brightness) gestures.clearReadout()
    }
    // Loop, shuffle and background play belong to playback; the shortcuts call its hooks.
    val loopOn by (playback?.loop ?: off).collectAsStateWithLifecycle(context = Main)
    val shuffleOn by (playback?.shuffle ?: off).collectAsStateWithLifecycle(context = Main)
    val backgroundOn by (playback?.backgroundPlay ?: off).collectAsStateWithLifecycle(context = Main)
    val on = remember { kotlinx.coroutines.flow.MutableStateFlow(true) }
    val hasPrevious by (playback?.hasPrevious ?: off).collectAsStateWithLifecycle(context = Main)
    val hasNext by (playback?.hasNext ?: off).collectAsStateWithLifecycle(context = Main)
    val showPreviousNext by (playback?.showPreviousNext ?: on).collectAsStateWithLifecycle(context = Main)
    val stepButtons by (playback?.stepButtons ?: off).collectAsStateWithLifecycle(context = Main)
    // While locked, playback's Overlay owns every touch and the controls stay hidden (US-28).
    val locked by (playback?.locked ?: off).collectAsStateWithLifecycle(context = Main)
    LaunchedEffect(locked) { if (locked) { controls = false; openPanel = null } }
    val panels = remember(extensions) { extensions.flatMap { it.panels }.associateBy { it.id } }
    val entries = remember(extensions) { extensions.flatMap { it.menuEntries } }
    val subtitleEntry = entries.firstOrNull { it.id == PlayerMenuEntry.SUBTITLE_TRACKS }
    val audioEntry = entries.firstOrNull { it.id == PlayerMenuEntry.AUDIO_TRACKS }
    val deinterlaceMode by remember(engine) { engine.observe("deinterlace", PropertyType.Text) }.collectAsStateWithLifecycle(context = Main)
    val hasChapters = chapters.isNotEmpty()
    val items = remember(entries, hasChapters, playback) {
        val own = listOfNotNull(
            MenuItem("fit-aspect", MenuGroup.Video, 100, { stringResource(R.string.video_fit_aspect) }) { openPanel = OwnPanel.FIT },
            MenuItem("picture", MenuGroup.Video, 110, { stringResource(R.string.video_picture) }) { openPanel = OwnPanel.PICTURE },
            MenuItem("deinterlace", MenuGroup.Video, 120, { stringResource(R.string.video_deinterlace) }) { openPanel = OwnPanel.DEINTERLACE },
            MenuItem("speed", MenuGroup.Tools, 100, { stringResource(R.string.tool_speed) }) { openPanel = OwnPanel.SPEED },
            MenuItem("ab-repeat", MenuGroup.Tools, 110, { stringResource(R.string.tool_ab) }) { openPanel = OwnPanel.AB },
            MenuItem("sleep-timer", MenuGroup.Tools, 120, { sleepLabel() }) { openPanel = OwnPanel.SLEEP },
            if (hasChapters) MenuItem("chapters", MenuGroup.Tools, 130, { stringResource(R.string.tool_chapters) }) { openPanel = OwnPanel.CHAPTERS } else null,
            MenuItem("screenshot", MenuGroup.Tools, 140, { stringResource(R.string.tool_screenshot) }) {
                openPanel = null
                screenshotRequests++
            },
            // US-59.1: playback's background play, switched from the menu.
            playback?.let { p ->
                MenuItem("background-play", MenuGroup.Tools, 150, { stringResource(R.string.sc_background) }, { p.backgroundPlay.collectAsStateWithLifecycle(context = Main).value }) {
                    p.toggleBackgroundPlay()
                }
            },
            MenuItem("player-settings", MenuGroup.Tools, 900, { stringResource(R.string.menu_player_settings) }) { openPanel = OwnPanel.SETTINGS },
        )
        menuItems(entries, own) { openPanel = it }
    }
    val title by produceState(titleOf(state.uri), state.uri) { value = displayName(context, state.uri) ?: titleOf(state.uri) }

    // Auto-hide (US-16.3, US-79): restarts on every touch; the delay is read when it starts, so a
    // changed delay applies from the next time the controls show (US-79.2).
    val hideAfter = autoHideDelay(prefs.autoHide, prefs.autoHideDelayMs, state.paused, talkBack, openPanel != null || scrubbing)
    AutoHideEffect(controls, touches, hideAfter) { controls = false }
    LaunchedEffect(controls, hideAfter) {
        android.util.Log.i("PlayerChrome", "auto-hide: controls $controls, after $hideAfter (paused ${state.paused}, panel ${openPanel != null}, scrubbing $scrubbing, talkback $talkBack)")
    }
    SystemBarsEffect(activity, controls)
    KeepScreenOnEffect(activity, on = !state.paused || prefs.keepScreenOnPaused)
    LaunchedEffect(note) {
        note?.let { delay(it.durationMs); note = null }
    }
    fun show(message: String) { note = Note(message, NOTE_MS) }

    // US-23.2: every new video starts at the default speed.
    // Keyed on each load, not the uri: reopening the same file is a new video too.
    LaunchedEffect(state.uri, state.loaded, prefs.defaultSpeed) {
        if (state.uri != null && state.loaded) {
            android.util.Log.i("PlayerChrome", "default speed ${prefs.defaultSpeed} for a new video")
            tools.setSpeed(prefs.defaultSpeed)
        }
    }

    // US-53.2: a fit the viewer picks is kept for the file and comes back on its next open,
    // while "remember choices" is on (the store ignores reads and writes when it is off).
    val choices = remember(context) { PerFileChoices(context) }
    val currentUri by rememberUpdatedState(state.uri)
    val scope = rememberCoroutineScope()
    video.onFitChosen = { mode -> currentUri?.let { uri -> scope.launch { choices.put(uri, FIT_CHOICE, mode.name) } } }
    LaunchedEffect(state.uri, state.loaded) {
        val uri = state.uri
        if (uri != null && state.loaded) {
            val saved = choices.get(uri, FIT_CHOICE)
            FitMode.entries.firstOrNull { it.name == saved }?.let(video::setFit)
        }
    }

    // US-33: grab the frame, then offer to share it.
    val savedText = stringResource(R.string.tool_screenshot_saved)
    val failedText = stringResource(R.string.tool_screenshot_failed)
    val shareText = stringResource(R.string.tool_share)
    LaunchedEffect(screenshotRequests) {
        if (screenshotRequests == 0) return@LaunchedEffect
        val uri = tools.screenshot(context)
        note = if (uri == null) Note(failedText, NOTE_MS) else Note(savedText, SHOT_NOTE_MS, shareText to { share(context, uri) })
    }

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
                video.chooseFit(video.fit.next())
                note = Note(fitNames.getValue(video.fit), FIT_NOTE_MS)
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
    // US-32.1: each shortcut's state and action. Null action = its item has not landed yet.
    fun shortcutState(sc: Shortcut): ShortcutState = when (sc) {
        Shortcut.RotationLock -> ShortcutState(rotationLocked) {
            rotationLocked = !rotationLocked
            activity?.requestedOrientation =
                if (rotationLocked) ActivityInfo.SCREEN_ORIENTATION_LOCKED else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
        Shortcut.Speed -> ShortcutState(speedLabel(speed) != null) { openPanel = OwnPanel.SPEED }
        Shortcut.Background -> ShortcutState(backgroundOn, playback?.let { p -> { p.toggleBackgroundPlay() } })
        Shortcut.Loop -> ShortcutState(loopOn, playback?.let { p -> { p.toggleLoop() } })
        Shortcut.Mute -> ShortcutState(muted == true) { engine.set("mute", muted != true) }
        Shortcut.Shuffle -> ShortcutState(shuffleOn, playback?.let { p -> { p.toggleShuffle() } })
        Shortcut.Equalizer -> ShortcutState(false, null)
        Shortcut.SleepTimer -> ShortcutState(sleep != null) { openPanel = OwnPanel.SLEEP }
        Shortcut.AbRepeat -> ShortcutState(abMs(abA) != null) { openPanel = OwnPanel.AB }
        Shortcut.NightMode -> ShortcutState(video.night) { video.setNight(!video.night) }
        Shortcut.Screenshot -> ShortcutState(false) { openPanel = null; screenshotRequests++ }
        Shortcut.Mirror -> ShortcutState(video.mirror) { video.setMirror(!video.mirror) }
        Shortcut.VerticalFlip -> ShortcutState(video.verticalFlip) { video.setVerticalFlip(!video.verticalFlip) }
    }
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
                .onSizeChanged { viewSize = it }
                // While locked, playback's Overlay owns every touch (US-28): no gestures here.
                .then(if (locked) Modifier else Modifier.playerGestures(gestures))
                .semantics {
                    contentDescription = videoDesc
                    customActions = a11yActions
                    onClick(toggleLabel) { controls = !controls; true }
                },
        )
        // Extension overlays (network state and the like): over the video, under the controls.
        extensions.forEach { it.Overlay(host) }
        // Gesture readouts linger 300 ms after the finger lifts, then fade (docs/design.md).
        // The last readout is kept for the fade-out; it is updated in an effect, never written
        // during composition (that corrupted the slot table on the hosted emulator).
        val readoutNow = gestures.readout
        var lastReadout by remember { mutableStateOf<Readout?>(null) }
        LaunchedEffect(readoutNow) { if (readoutNow != null) lastReadout = readoutNow }
        AnimatedVisibility(
            readoutNow != null,
            enter = fadeIn(snap()),
            exit = fadeOut(PlayerMotion.readoutOut()),
        ) {
            GestureReadout(readoutNow ?: lastReadout, gestures::resetSpeed)
        }
        if (state.buffering && !state.paused) BufferingIndicator(Modifier.align(Alignment.Center))
        // Playback draws the failure card with the reason and actions (US-75.1); the raw line is
        // only for a player without it.
        if (playback == null) state.error?.let {
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
                    // While scrubbing (bar or swipe) the elapsed time and the bar follow the finger.
                    positionMs = if (scrubbing) scrubTargetMs else state.positionMs,
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
                    marks = chapters.drop(1).map { it.startMs.toFloat() / state.durationMs.coerceAtLeast(1) },
                    abA = abMs(abA)?.let { it.toFloat() / state.durationMs.coerceAtLeast(1) },
                    abB = abMs(abB)?.let { it.toFloat() / state.durationMs.coerceAtLeast(1) },
                ),
                actions,
            )
        }
        // Status kept in the corners while the controls are hidden (US-60.4).
        if (!controls && prefs.keepStatusVisible) {
            KeptStatus(time(state.positionMs), status, Modifier.align(Alignment.TopCenter))
        }

        // Snackbar look: opaque, above the bottom controls, clear of the video's centre.
        note?.let { n ->
            Row(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 160.dp)
                    .background(MaterialTheme.colorScheme.inverseSurface, MaterialTheme.shapes.small)
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .testTag("note"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(n.text, color = MaterialTheme.colorScheme.inverseOnSurface, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 10.dp))
                n.action?.let { (label, run) ->
                    TextButton(
                        onClick = { run(); note = null },
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.inversePrimary),
                        modifier = Modifier.testTag("note-action"),
                    ) { Text(label) }
                }
            }
        }

        if (preview != null) {
            SeekPreview(preview, scrubTargetMs, Modifier.align(Alignment.BottomCenter).padding(bottom = 160.dp), visible = scrubbing)
        }

        // Keep the last panel's content during the exit animation.
        var lastPanel by remember { mutableStateOf<String?>(null) }
        LaunchedEffect(openPanel) { if (openPanel != null) lastPanel = openPanel }
        val shownPanel = openPanel ?: lastPanel
        PanelFrame(visible = openPanel != null, panelId = shownPanel, onClose = { openPanel = null }) {
            when (val id = shownPanel) {
                OwnPanel.MENU -> MenuContent(items) {
                    ShortcutsGrid(Shortcut.parse(prefs.shortcuts)) { sc -> shortcutState(sc) }
                }
                OwnPanel.FIT -> FitAspectContent(video)
                OwnPanel.PICTURE -> PictureContent(video)
                OwnPanel.DECODER -> DecoderContent(state.decoderMode, state.activeDecoder.label) {
                    engine.setDecoder(it)
                    onDecoderPicked(it)
                    openPanel = null
                }
                OwnPanel.SETTINGS -> PlayerSettingsContent(prefs, { change -> settings?.update(change) })
                OwnPanel.SPEED -> SpeedContent(speed ?: 1.0, tools::setSpeed)
                OwnPanel.AB -> AbContent(
                    abMs(abA),
                    abMs(abB),
                    setA = { tools.setA(state.positionMs) },
                    setB = { tools.setB(state.positionMs) },
                    clear = tools::clearAb,
                )
                OwnPanel.SLEEP -> SleepContent(sleep, { m, finish -> SleepTimer.start(m, finish) }, SleepTimer::cancel)
                OwnPanel.CHAPTERS -> ChaptersContent(chapters, state.positionMs) { tools.goToChapter(it.index) }
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


/** "Sleep timer", or "Sleep timer · 12:30 left" while one runs (US-26.3). */
@Composable
private fun sleepLabel(): String {
    val running by SleepTimer.running.collectAsStateWithLifecycle(context = Main)
    return running?.let { stringResource(R.string.tool_sleep_left, SleepTimer.format(rememberLeft(it))) }
        ?: stringResource(R.string.tool_sleep)
}

/** mpv's A-B point text ("12.500" or "no") as ms. */
internal fun abMs(value: String?): Long? = value?.toDoubleOrNull()?.let { (it * 1000).toLong() }

/** US-33.2: hand the saved PNG to the share sheet. */
private fun share(context: android.content.Context, uri: Uri) {
    val send = android.content.Intent(android.content.Intent.ACTION_SEND)
        .setType("image/png")
        .putExtra(android.content.Intent.EXTRA_STREAM, uri)
        .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(android.content.Intent.createChooser(send, null).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
}
