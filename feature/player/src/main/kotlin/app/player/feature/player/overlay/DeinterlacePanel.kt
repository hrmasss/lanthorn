package app.player.feature.player.overlay

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.player.core.engine.ActiveDecoder
import app.player.core.engine.DecoderFeature
import app.player.core.engine.DecoderMode
import app.player.feature.player.R
import app.player.feature.player.settings.RadioRow
import app.player.feature.player.settings.SectionHeader
import app.player.feature.player.tools.Deinterlace

/**
 * Deinterlace from the player (US-38.2). Deinterlacing needs frames in memory, so on HW (zero-copy)
 * the choices are shown disabled with one line naming the decoder that runs it and a button that
 * switches to it at the same position (US-35.4).
 */
@Composable
internal fun DeinterlaceContent(
    mode: Deinterlace,
    active: ActiveDecoder,
    pick: (Deinterlace) -> Unit,
    switchDecoder: (DecoderMode) -> Unit,
) {
    val feature = DecoderFeature.Deinterlace
    val runs = feature.runsOn(active)
    Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 24.dp).testTag("deinterlace-panel")) {
        SectionHeader(stringResource(R.string.video_deinterlace))
        listOf(
            Deinterlace.Auto to R.string.video_deint_auto,
            Deinterlace.On to R.string.video_deint_on,
            Deinterlace.Off to R.string.video_deint_off,
        ).forEach { (m, label) ->
            RadioRow(
                stringResource(label),
                selected = mode == m,
                onClick = { pick(m) },
                modifier = Modifier.testTag("deint-${m.name}"),
                enabled = runs,
            )
        }
        if (!runs) {
            val needed = decoderLabel(feature.enabledBy)
            Text(
                stringResource(R.string.video_deint_needs_decoder, needed, active.label),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp).testTag("deint-needs"),
            )
            FilledTonalButton(
                onClick = { switchDecoder(feature.enabledBy) },
                modifier = Modifier.padding(horizontal = 24.dp).testTag("deint-switch"),
            ) { Text(stringResource(R.string.video_deint_switch, needed)) }
        }
    }
}

private fun decoderLabel(mode: DecoderMode) = when (mode) {
    DecoderMode.AUTO -> "Auto"
    DecoderMode.HW -> "HW"
    DecoderMode.HW_PLUS -> "HW+"
    DecoderMode.SW -> "SW"
}
