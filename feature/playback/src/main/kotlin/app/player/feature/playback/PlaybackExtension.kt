package app.player.feature.playback

import androidx.compose.foundation.layout.Box
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
import app.player.core.playerapi.PanelHost
import app.player.core.playerapi.PlayerExtension
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

/** Playback's part of the player screen: the resume question and "start over" (US-22). */
object PlaybackExtension : PlayerExtension {
    override val id = "playback"

    /** How long "start over" stays after the first frame (US-22.2). */
    const val START_OVER_MS = 5_000L

    @Composable
    override fun Overlay(host: PanelHost) {
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

/** 1:02:03 or 2:03. */
internal fun formatTime(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}
