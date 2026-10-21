package app.player.feature.player.overlay

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.player.core.designsystem.theme.tabular
import app.player.feature.player.R
import app.player.feature.player.settings.SectionHeader
import app.player.feature.player.settings.SwitchRow
import app.player.feature.player.time
import app.player.feature.player.tools.Chapter
import app.player.feature.player.tools.PlaybackTools
import app.player.feature.player.tools.SleepTimer
import kotlinx.coroutines.delay
import java.util.Locale

private val SPEED_PRESETS = listOf(0.5, 0.75, 1.0, 1.25, 1.5, 2.0)

/** "1.25" for a speed, two decimals at most. */
internal fun speedText(speed: Double): String =
    String.format(Locale.ROOT, "%.2f", speed).trimEnd('0').trimEnd('.')

/** US-23.1: presets plus 0.25x to 4.0x in 0.05x steps. Applies as the slider moves. */
@Composable
internal fun SpeedContent(speed: Double, set: (Double) -> Unit) {
    Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 16.dp).testTag("speed-panel")) {
        SectionHeader(stringResource(R.string.tool_speed))
        Text(
            stringResource(R.string.tool_speed_value, speedText(speed)),
            style = MaterialTheme.typography.displaySmall.tabular(),
            modifier = Modifier.padding(horizontal = 24.dp).testTag("speed-value"),
        )
        Slider(
            value = speed.toFloat(),
            onValueChange = { set(PlaybackTools.snapSpeed(it.toDouble())) },
            valueRange = PlaybackTools.MIN_SPEED.toFloat()..PlaybackTools.MAX_SPEED.toFloat(),
            // 0.25..4.0 in 0.05 steps is 76 positions.
            steps = 74,
            modifier = Modifier.padding(horizontal = 24.dp).testTag("speed-slider"),
        )
        // Even rows of equal chips, so no preset is left alone on a row.
        FlowRow(
            Modifier.padding(horizontal = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            maxItemsInEachRow = 3,
        ) {
            SPEED_PRESETS.forEach { p ->
                FilterChip(
                    selected = kotlin.math.abs(speed - p) < 0.001,
                    onClick = { set(p) },
                    label = { Text(speedText(p) + "x", maxLines = 1) },
                    modifier = Modifier.weight(1f).testTag("speed-${speedText(p)}"),
                )
            }
        }
    }
}

/** US-25: set A, set B, clear. Points show with their times. */
@Composable
internal fun AbContent(a: Long?, b: Long?, setA: () -> Unit, setB: () -> Unit, clear: () -> Unit) {
    Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 16.dp).testTag("ab-panel")) {
        SectionHeader(stringResource(R.string.tool_ab))
        val unset = stringResource(R.string.tool_ab_unset)
        Text(
            "A ${a?.let(::time) ?: unset}   ·   B ${b?.let(::time) ?: unset}",
            style = MaterialTheme.typography.titleMedium.tabular(),
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp).testTag("ab-points"),
        )
        Row(Modifier.padding(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = setA, modifier = Modifier.testTag("ab-set-a")) { Text(stringResource(R.string.tool_ab_set_a)) }
            FilledTonalButton(onClick = setB, enabled = a != null, modifier = Modifier.testTag("ab-set-b")) {
                Text(stringResource(R.string.tool_ab_set_b))
            }
            OutlinedButton(onClick = clear, enabled = a != null || b != null, modifier = Modifier.testTag("ab-clear")) {
                Text(stringResource(R.string.tool_ab_clear))
            }
        }
    }
}

/** US-26: presets, custom minutes, finish-current-video, and the running timer with cancel. */
@Composable
internal fun SleepContent(running: SleepTimer.Running?, start: (Int, Boolean) -> Unit, cancel: () -> Unit) {
    var finish by remember { mutableStateOf(running?.finishVideo ?: false) }
    var custom by remember { mutableFloatStateOf(90f) }
    Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 16.dp).testTag("sleep-panel")) {
        SectionHeader(stringResource(R.string.tool_sleep))
        if (running != null) {
            val left = rememberLeft(running)
            Row(Modifier.padding(horizontal = 24.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.tool_sleep_running, SleepTimer.format(left)),
                    style = MaterialTheme.typography.titleMedium.tabular(),
                    modifier = Modifier.weight(1f).testTag("sleep-left"),
                )
                OutlinedButton(onClick = cancel, modifier = Modifier.testTag("sleep-cancel")) { Text(stringResource(R.string.tool_sleep_cancel)) }
            }
        }
        FlowRow(
            Modifier.padding(horizontal = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            maxItemsInEachRow = 2,
        ) {
            SleepTimer.PRESETS.forEach { m ->
                FilledTonalButton(onClick = { start(m, finish) }, modifier = Modifier.weight(1f).testTag("sleep-$m")) {
                    Text(stringResource(R.string.tool_sleep_minutes, m), maxLines = 1)
                }
            }
        }
        Row(Modifier.padding(horizontal = 24.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.tool_sleep_custom), style = MaterialTheme.typography.bodyLarge)
            Slider(
                value = custom,
                onValueChange = { custom = it },
                valueRange = 1f..180f,
                modifier = Modifier.weight(1f).padding(horizontal = 12.dp).testTag("sleep-custom"),
            )
            FilledTonalButton(onClick = { start(custom.toInt(), finish) }, modifier = Modifier.testTag("sleep-custom-start")) {
                Text(stringResource(R.string.tool_sleep_minutes, custom.toInt()))
            }
        }
        SwitchRow(
            stringResource(R.string.tool_sleep_finish),
            finish,
            { finish = it },
            summary = stringResource(R.string.tool_sleep_finish_summary),
            modifier = Modifier.testTag("sleep-finish"),
        )
    }
}

/** Ms left on [running], ticking each second while shown. */
@Composable
internal fun rememberLeft(running: SleepTimer.Running): Long {
    var left by remember(running) { mutableLongStateOf(SleepTimer.left(running)) }
    LaunchedEffect(running) {
        while (left > 0) {
            // The shown value rounds up to the second, so it changes when left crosses a whole second.
            delay((left % 1_000).takeIf { it > 0 } ?: 1_000)
            left = SleepTimer.left(running)
        }
    }
    return left
}

/** US-27.1: titles and start times; tap jumps (US-27.2). */
@Composable
internal fun ChaptersContent(chapters: List<Chapter>, current: Long, pick: (Chapter) -> Unit) {
    Column(Modifier.padding(bottom = 16.dp).testTag("chapters-panel")) {
        SectionHeader(stringResource(R.string.tool_chapters))
        LazyColumn {
            itemsIndexed(chapters, key = { _, c -> c.index }) { i, c ->
                val playing = current >= c.startMs && (i == chapters.lastIndex || current < chapters[i + 1].startMs)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .clickable(role = Role.Button) { pick(c) }
                        .padding(horizontal = 24.dp, vertical = 12.dp)
                        .testTag("chapter-${c.index}"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        c.title ?: stringResource(R.string.tool_chapter_untitled, c.index + 1),
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (playing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    Text(time(c.startMs), style = MaterialTheme.typography.labelLarge.tabular())
                }
            }
        }
    }
}
