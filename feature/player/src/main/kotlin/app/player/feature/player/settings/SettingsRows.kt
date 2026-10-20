package app.player.feature.player.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

// Settings rows. Built here until the design system has shared ones (proposed to brand); the
// library's settings screen and the in-player sheet use the same shapes.

/** Title, optional summary, switch. The whole row toggles. */
@Composable
fun SwitchRow(
    title: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    summary: String? = null,
    enabled: Boolean = true,
) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
            .padding(horizontal = 24.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RowText(title, summary, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

/** Title with the value on the right, a slider below. */
@Composable
fun SliderRow(
    title: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    onChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Column(modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(valueText, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Slider(value = value, onValueChange = onChange, valueRange = range, steps = steps, enabled = enabled)
    }
}

/** One choice in a single-choice list. */
@Composable
fun RadioRow(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    summary: String? = null,
) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .selectable(selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        RowText(title, summary, Modifier.weight(1f).padding(start = 16.dp))
    }
}

/** Group heading inside a settings list or menu. */
@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun RowText(title: String, summary: String?, modifier: Modifier) {
    Column(modifier) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        if (summary != null) {
            Text(summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
