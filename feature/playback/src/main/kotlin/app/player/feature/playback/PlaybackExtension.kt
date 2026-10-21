package app.player.feature.playback

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import android.os.SystemClock
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import app.player.core.playerapi.MenuGroup
import app.player.core.playerapi.PanelHost
import app.player.core.playerapi.PlayerExtension
import app.player.core.playerapi.PlayerMenuEntry
import app.player.core.playerapi.PlayerPanel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

/**
 * Playback's part of the player screen: the resume question and "start over" (US-22), the failure
 * card and skip note (US-75), and the loop and shuffle panel (US-24).
 */
object PlaybackExtension : PlayerExtension {
    override val id = "playback"

    override val playback = Playback

    const val QUEUE_PANEL = "playback-queue"

    override val panels: List<PlayerPanel> = listOf(QueuePanel)

    override val menuEntries = listOf(
        PlayerMenuEntry(id = QUEUE_PANEL, group = MenuGroup.Tools, order = 5, opensPanel = QUEUE_PANEL) {
            stringResource(R.string.playback_menu_loop)
        },
    )

    /** How long "Unlock" stays after a touch while locked (US-28.1). */
    const val UNLOCK_SHOWN_MS = 3_000L

    /** How long a skip note stays (US-75.2). */
    const val NOTE_MS = 2_000L

    /** How long "start over" stays after the first frame (US-22.2). */
    const val START_OVER_MS = 5_000L

    @Composable
    override fun Overlay(host: PanelHost) {
        val locked by Playback.locked.collectAsState()
        val settings by Playback.settings.collectAsState()
        if (locked) {
            LockLayer(kids = settings.lockMode == LockMode.Kids)
            return
        }
        val ask by Playback.ask.collectAsState()
        val startOver by Playback.startOver.collectAsState()
        ask?.takeIf { it.engine === host.engine }?.let { a ->
            AlertDialog(
                onDismissRequest = {},
                properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
                title = { Text(stringResource(R.string.playback_resume_title)) },
                text = { Text(stringResource(R.string.playback_resume_text, formatTime(a.positionMs))) },
                confirmButton = {
                    TextButton(onClick = { Playback.answer(resume = true) }, Modifier.testTag("resume-yes")) {
                        Text(stringResource(R.string.playback_resume))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { Playback.answer(resume = false) }, Modifier.testTag("resume-start-over")) {
                        Text(stringResource(R.string.playback_start_over))
                    }
                },
                modifier = Modifier.testTag("resume-ask"),
            )
        }
        val state by host.engine.state.collectAsState()
        val title by Playback.title.collectAsState()
        // Audio only: no picture, so show what is playing (gate note on P1.11).
        if (state.loaded && state.video == null && state.error == null && state.firstFrameMs != null) {
            title?.let { AudioTitle(it) }
        }
        val failure by Playback.failure.collectAsState()
        failure?.takeIf { it.engine === host.engine }?.let { FailureCard(it) }
        val note by Playback.note.collectAsState()
        note?.takeIf { it.engine === host.engine }?.let { n ->
            LaunchedEffect(n) {
                delay(NOTE_MS)
                Playback.dismissNote(n)
            }
            Box(Modifier.fillMaxSize()) {
                Pill(n.text, Modifier.align(Alignment.TopCenter).padding(top = 72.dp).testTag("playback-note"))
            }
        }
        startOver?.takeIf { it.engine === host.engine }?.let { s ->
            LaunchedEffect(s) {
                host.engine.state.first { it.firstFrameMs != null }
                delay(START_OVER_MS)
                Playback.dismissStartOver()
            }
            Box(Modifier.fillMaxSize()) {
                Surface(
                    onClick = { Playback.startOver() },
                    shape = CircleShape,
                    color = Color.Black.copy(alpha = 0.6f),
                    contentColor = Color.White,
                    modifier = Modifier.align(Alignment.BottomStart).padding(start = 24.dp, bottom = 120.dp).testTag("start-over"),
                ) {
                    Text(stringResource(R.string.playback_start_over), Modifier.padding(horizontal = 20.dp, vertical = 10.dp))
                }
            }
        }
    }
}

@Composable
private fun Pill(text: String, modifier: Modifier = Modifier) {
    Surface(shape = CircleShape, color = Color.Black.copy(alpha = 0.6f), contentColor = Color.White, modifier = modifier) {
        Text(text, Modifier.padding(horizontal = 20.dp, vertical = 10.dp))
    }
}

/** The name of an audio-only file in the middle of the screen, so it is not a black page. */
@Composable
private fun AudioTitle(title: String) {
    Box(Modifier.fillMaxSize().padding(horizontal = 32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("\u266A", color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.displayMedium)
            Text(title, color = Color.White, style = MaterialTheme.typography.titleLarge, modifier = Modifier.testTag("audio-title"))
        }
    }
}

/** US-75.1 and US-75.3: the reason, with skip and info. */
@Composable
private fun FailureCard(f: Failure) {
    var info by remember(f) { mutableStateOf(false) }
    val reason = when (f.reason) {
        FailureReason.MissingFile -> stringResource(R.string.failure_missing)
        FailureReason.UnsupportedCodec -> f.codec?.let { stringResource(R.string.failure_codec_named, it) } ?: stringResource(R.string.failure_codec)
        FailureReason.Damaged -> stringResource(R.string.failure_damaged)
        FailureReason.NoPermission -> stringResource(R.string.failure_permission)
    }
    val why = stringResource(
        when (f.reason) {
            FailureReason.MissingFile -> R.string.failure_missing_why
            FailureReason.UnsupportedCodec -> R.string.failure_codec_why
            FailureReason.Damaged -> R.string.failure_damaged_why
            FailureReason.NoPermission -> R.string.failure_permission_why
        },
    )
    AlertDialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnClickOutside = false),
        title = { Text(stringResource(if (f.allFailed) R.string.failure_all_title else R.string.failure_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(f.name, style = MaterialTheme.typography.titleSmall)
                Text(reason, Modifier.testTag("failure-reason"))
                if (info) {
                    Text(why, Modifier.testTag("failure-why"))
                    if (f.details.isNotEmpty()) {
                        Text(stringResource(R.string.failure_technical), style = MaterialTheme.typography.labelLarge)
                        f.details.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { Playback.skip() }, enabled = f.canSkip, modifier = Modifier.testTag("failure-skip")) {
                Text(stringResource(R.string.failure_skip))
            }
        },
        dismissButton = {
            TextButton(onClick = { info = !info }, modifier = Modifier.testTag("failure-info")) {
                Text(stringResource(R.string.failure_info))
            }
        },
        modifier = Modifier.testTag("failure"),
    )
}

/** Loop off, one, all and shuffle (US-24). */
private object QueuePanel : PlayerPanel {
    override val id = PlaybackExtension.QUEUE_PANEL

    @Composable
    override fun Content(host: PanelHost) {
        val s by Playback.settings.collectAsState()
        Column(Modifier.padding(bottom = 16.dp)) {
            Text(
                stringResource(R.string.playback_menu_loop),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
            listOf(
                LoopMode.Off to R.string.loop_off,
                LoopMode.One to R.string.loop_one,
                LoopMode.All to R.string.loop_all,
            ).forEach { (mode, label) ->
                ListItem(
                    headlineContent = { Text(stringResource(label)) },
                    leadingContent = { RadioButton(s.loop == mode, onClick = null) },
                    modifier = Modifier.selectable(s.loop == mode, role = Role.RadioButton) { Playback.setLoop(mode) }
                        .testTag("loop-${mode.name.lowercase()}"),
                )
            }
            ListItem(
                headlineContent = { Text(stringResource(R.string.shuffle)) },
                trailingContent = { Switch(s.shuffle, onCheckedChange = null) },
                modifier = Modifier.toggleable(s.shuffle, role = Role.Switch) { Playback.setShuffle(it) }.testTag("shuffle"),
            )
        }
    }
}

/**
 * While locked (US-28): every touch lands here and nothing reaches the video or the controls. Lock
 * shows "Unlock" for 3 s after each touch (US-28.1); kids lock shows nothing and unlocks after the
 * four corners, clockwise from top left, within 5 s (US-28.2).
 */
@Composable
private fun LockLayer(kids: Boolean) {
    var touchedAt by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    var showUnlock by remember { mutableStateOf(!kids) }
    val corners = remember { KidsUnlock() }
    LaunchedEffect(touchedAt, kids) {
        showUnlock = !kids
        delay(PlaybackExtension.UNLOCK_SHOWN_MS)
        showUnlock = false
    }
    val hint = stringResource(if (kids) R.string.kids_lock_hint else R.string.unlock)
    Box(
        Modifier
            .fillMaxSize()
            .testTag("lock-layer")
            .semantics { contentDescription = hint }
            .pointerInput(kids) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    do {
                        val event = awaitPointerEvent()
                        event.changes.forEach { it.consume() }
                    } while (event.changes.any { it.pressed })
                    val now = SystemClock.elapsedRealtime()
                    if (kids) {
                        if (corners.tap(down.position.x / size.width, down.position.y / size.height, now)) Playback.unlock()
                    } else {
                        touchedAt = now
                    }
                }
            },
    ) {
        if (showUnlock) {
            Surface(
                onClick = { Playback.unlock() },
                shape = CircleShape,
                color = Color.Black.copy(alpha = 0.6f),
                contentColor = Color.White,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 96.dp).testTag("unlock"),
            ) {
                Text(stringResource(R.string.unlock), Modifier.padding(horizontal = 24.dp, vertical = 12.dp))
            }
        }
    }
}

/** 1:02:03 or 2:03. */
internal fun formatTime(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}
