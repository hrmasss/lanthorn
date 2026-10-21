package app.player.feature.player.overlay

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.FilledIconToggleButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.player.core.designsystem.icon.LanthornIcons
import app.player.feature.player.R
import app.player.feature.player.icon.PlayerIcons

/** US-32.1's shortcuts, in its order. */
enum class Shortcut(@StringRes val label: Int) {
    RotationLock(R.string.sc_rotation_lock),
    Speed(R.string.tool_speed),
    Background(R.string.sc_background),
    Loop(R.string.sc_loop),
    Mute(R.string.sc_mute),
    Shuffle(R.string.sc_shuffle),
    Equalizer(R.string.sc_equalizer),
    SleepTimer(R.string.tool_sleep),
    AbRepeat(R.string.tool_ab),
    NightMode(R.string.sc_night),
    Screenshot(R.string.tool_screenshot),
    Mirror(R.string.sc_mirror),
    VerticalFlip(R.string.sc_vflip),
    ;

    val icon: ImageVector get() = when (this) {
        RotationLock -> PlayerIcons.RotationLock
        Speed -> PlayerIcons.Speed
        Background -> PlayerIcons.Background
        Loop -> PlayerIcons.RepeatOne
        Mute -> PlayerIcons.Mute
        Shuffle -> PlayerIcons.Shuffle
        Equalizer -> PlayerIcons.Equalizer
        SleepTimer -> PlayerIcons.SleepTimer
        AbRepeat -> PlayerIcons.Repeat
        NightMode -> PlayerIcons.NightMode
        Screenshot -> PlayerIcons.Screenshot
        Mirror -> PlayerIcons.Flip
        VerticalFlip -> PlayerIcons.SwapVert
    }

    companion object {
        /** Stored order string -> shortcuts, unknown ids dropped. */
        fun parse(ids: String): List<Shortcut> =
            ids.split(',').mapNotNull { id -> entries.firstOrNull { it.name == id.trim() } }.distinct()

        fun format(list: List<Shortcut>): String = list.joinToString(",") { it.name }

        val DEFAULT: String = format(entries)
    }
}

/** How one shortcut looks right now and what it does. Null [onClick] means not available yet. */
class ShortcutState(val active: Boolean, val onClick: (() -> Unit)?)

/** The shortcuts grid (US-32.1): one round toggle per shortcut, its state shown by its fill. */
@Composable
internal fun ShortcutsGrid(shown: List<Shortcut>, modifier: Modifier = Modifier, state: (Shortcut) -> ShortcutState) {
    FlowRow(
        modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).testTag("shortcuts"),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        shown.forEach { s ->
            val st = state(s)
            val label = stringResource(s.label)
            val on = stringResource(R.string.sc_on)
            val off = stringResource(R.string.sc_off)
            Column(Modifier.width(76.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                FilledIconToggleButton(
                    checked = st.active,
                    onCheckedChange = { st.onClick?.invoke() },
                    enabled = st.onClick != null,
                    modifier = Modifier
                        .semantics {
                            contentDescription = label
                            stateDescription = if (st.active) on else off
                        }
                        .testTag("sc-${s.name}"),
                ) { Icon(s.icon, null) }
                Text(
                    label,
                    style = MaterialTheme.typography.labelSmall,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                )
            }
        }
    }
}

/** US-32.2: choose and reorder the shortcuts. */
@Composable
internal fun ShortcutsSettings(shown: List<Shortcut>, set: (List<Shortcut>) -> Unit) {
    val order = shown + Shortcut.entries.filter { it !in shown }
    Column { order.forEach { s ->
        val on = s in shown
        val name = stringResource(s.label)
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 24.dp, end = 8.dp).testTag("sc-set-${s.name}"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            val up = stringResource(R.string.sc_move_up, name)
            IconButton(
                onClick = { set(shown.toMutableList().apply { val j = indexOf(s); removeAt(j); add(j - 1, s) }) },
                enabled = on && shown.indexOf(s) > 0,
                modifier = Modifier.semantics { contentDescription = up }.testTag("sc-up-${s.name}"),
            ) { Icon(PlayerIcons.ArrowUpward, null) }
            Switch(
                checked = on,
                onCheckedChange = { v -> set(if (v) shown + s else shown - s) },
                // TalkBack reads the shortcut's name on its switch (US-69.1).
                modifier = Modifier.semantics { contentDescription = name }.testTag("sc-on-${s.name}"),
            )
        }
    } }
}
