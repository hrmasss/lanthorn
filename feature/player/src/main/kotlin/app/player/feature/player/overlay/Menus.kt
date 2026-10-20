package app.player.feature.player.overlay

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.player.core.engine.DecoderMode
import app.player.core.playerapi.MenuGroup
import app.player.core.playerapi.PlayerMenuEntry
import app.player.feature.player.R
import app.player.feature.player.settings.RadioRow
import app.player.feature.player.settings.SectionHeader

/** A row in the menu: one of a feature's entries, or one of the player's own. */
internal class MenuItem(val id: String, val group: MenuGroup, val order: Int, val label: @Composable () -> String, val onClick: () -> Unit)

/** Features' menu entries minus the two that became top-bar buttons, plus the player's own items. */
internal fun menuItems(entries: List<PlayerMenuEntry>, own: List<MenuItem>, open: (String) -> Unit): List<MenuItem> =
    (
        entries
            .filter { it.id != PlayerMenuEntry.SUBTITLE_TRACKS && it.id != PlayerMenuEntry.AUDIO_TRACKS }
            .map { e -> MenuItem(e.id, e.group, e.order, e.label) { open(e.opensPanel) } } + own
        ).sortedWith(compareBy({ it.group }, { it.order }, { it.id }))

/** The overflow menu: every item grouped Video, Audio, Subtitle, Tools. */
@Composable
internal fun MenuContent(items: List<MenuItem>) {
    Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
        items.groupBy { it.group }.forEach { (group, rows) ->
            SectionHeader(stringResource(group.title))
            rows.forEach { item ->
                Text(
                    item.label(),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .clickable(role = Role.Button, onClick = item.onClick)
                        .padding(horizontal = 24.dp, vertical = 12.dp)
                        .testTag("menu-${item.id}"),
                )
            }
        }
    }
}

private val MenuGroup.title get() = when (this) {
    MenuGroup.Video -> R.string.menu_group_video
    MenuGroup.Audio -> R.string.menu_group_audio
    MenuGroup.Subtitle -> R.string.menu_group_subtitle
    MenuGroup.Tools -> R.string.menu_group_tools
}

/** Decoder picker from the top-bar badge (US-35.2). Playback keeps running behind it. */
@Composable
internal fun DecoderContent(current: DecoderMode, active: String, pick: (DecoderMode) -> Unit) {
    Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 16.dp).testTag("decoder-picker")) {
        SectionHeader(stringResource(R.string.decoder_title) + " · " + active)
        DecoderMode.entries.forEach { mode ->
            RadioRow(
                title = stringResource(mode.title),
                summary = stringResource(mode.summary),
                selected = mode == current,
                onClick = { pick(mode) },
                modifier = Modifier.testTag("decoder-${mode.name}"),
            )
        }
    }
}

private val DecoderMode.title get() = when (this) {
    DecoderMode.AUTO -> R.string.decoder_auto
    DecoderMode.HW -> R.string.decoder_hw
    DecoderMode.HW_PLUS -> R.string.decoder_hw_plus
    DecoderMode.SW -> R.string.decoder_sw
}

private val DecoderMode.summary get() = when (this) {
    DecoderMode.AUTO -> R.string.decoder_auto_summary
    DecoderMode.HW -> R.string.decoder_hw_summary
    DecoderMode.HW_PLUS -> R.string.decoder_hw_plus_summary
    DecoderMode.SW -> R.string.decoder_sw_summary
}
