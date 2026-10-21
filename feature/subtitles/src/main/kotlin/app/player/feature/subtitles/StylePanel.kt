package app.player.feature.subtitles

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.player.core.designsystem.component.SettingsSwitch
import app.player.core.playerapi.PanelHost
import app.player.core.playerapi.PlayerPanel
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Subtitle style inside the player. Every change shows on the current line at once (US-42.3). */
internal object StylePanel : PlayerPanel {
    override val id = SubtitlesExtension.STYLE_PANEL

    @Composable
    override fun Content(host: PanelHost) {
        val context = LocalContext.current
        val styles = remember { SubtitlesExtension.styles(context) }
        val style by styles.style.collectAsState()
        var families by remember { mutableStateOf(listOf(style.font)) }
        val scope = rememberCoroutineScope()
        LaunchedEffect(Unit) { families = styles.families() }
        val imported = stringResource(R.string.subs_fonts_imported)
        val folder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
            if (tree != null) scope.launch {
                val n = styles.importFonts(tree)
                families = styles.families()
                Toast.makeText(context, imported.format(n), Toast.LENGTH_SHORT).show()
            }
        }
        LazyColumn(Modifier.padding(bottom = 16.dp).testTag("sub-style")) {
            item {
                Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp)) {
                    Text(stringResource(R.string.subs_style_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    TextButton(onClick = styles::reset, modifier = Modifier.testTag("sub-style-reset")) { Text(stringResource(R.string.subs_reset)) }
                }
            }
            styleEditor(style, styles::update, families, onPickFontFolder = { folder.launch(null) })
        }
    }
}

/** The style rows, shared by the player panel and the Subtitle settings page. */
internal fun LazyListScope.styleEditor(
    style: SubtitleStyle,
    update: ((SubtitleStyle) -> SubtitleStyle) -> Unit,
    families: List<String>,
    onPickFontFolder: () -> Unit,
    highlight: String? = null,
) {
    item(key = "font") {
        Label(stringResource(R.string.subs_font))
        // Lazy, with end padding, so the last chip scrolls fully into view.
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(families, key = { it }) { family ->
                FilterChip(
                    selected = style.font == family,
                    onClick = { update { it.copy(font = family) } },
                    label = { Text(family) },
                    modifier = Modifier.testTag("sub-font-$family"),
                )
            }
        }
        OutlinedButton(onClick = onPickFontFolder, modifier = Modifier.padding(horizontal = 16.dp)) {
            Text(stringResource(R.string.subs_font_folder))
        }
    }
    item(key = "size") {
        Label(stringResource(R.string.subs_size_value, style.size))
        SliderRow(style.size.toFloat(), SubtitleStyle.MIN_SIZE.toFloat()..SubtitleStyle.MAX_SIZE.toFloat(), "sub-size") { v ->
            update { it.copy(size = v.roundToInt()) }
        }
    }
    item(key = "color") {
        Label(stringResource(R.string.subs_color))
        Swatches(style.color, "sub-color") { c -> update { it.copy(color = c) } }
    }
    item(key = "bold") { Toggle(stringResource(R.string.subs_bold), style.bold, "sub-bold", highlight == "bold") { b -> update { it.copy(bold = b) } } }
    item(key = "outline") {
        Toggle(stringResource(R.string.subs_outline), style.outline, "sub-outline", highlight == "outline") { b -> update { it.copy(outline = b) } }
        if (style.outline) {
            Swatches(style.outlineColor, "sub-outline-color") { c -> update { it.copy(outlineColor = c) } }
            Label(stringResource(R.string.subs_thickness_value, style.outlineSize))
            SliderRow(style.outlineSize, 0.5f..8f, "sub-outline-size") { v -> update { it.copy(outlineSize = (v * 2).roundToInt() / 2f) } }
        }
    }
    item(key = "shadow") { Toggle(stringResource(R.string.subs_shadow), style.shadow, "sub-shadow", highlight == "shadow") { b -> update { it.copy(shadow = b) } } }
    item(key = "box") {
        Toggle(stringResource(R.string.subs_box), style.box, "sub-box", highlight == "box") { b -> update { it.copy(box = b) } }
        if (style.box) {
            Swatches(style.boxColor, "sub-box-color") { c -> update { it.copy(boxColor = c) } }
            Label(stringResource(R.string.subs_opacity_value, (style.boxOpacity * 100).roundToInt()))
            SliderRow(style.boxOpacity, 0f..1f, "sub-box-opacity") { v -> update { it.copy(boxOpacity = (v * 20).roundToInt() / 20f) } }
        }
    }
    item(key = "align") {
        Label(stringResource(R.string.subs_alignment))
        SingleChoiceSegmentedButtonRow(Modifier.padding(horizontal = 16.dp)) {
            SubAlign.entries.forEachIndexed { i, a ->
                SegmentedButton(
                    selected = style.align == a,
                    onClick = { update { it.copy(align = a) } },
                    shape = SegmentedButtonDefaults.itemShape(i, SubAlign.entries.size),
                    modifier = Modifier.testTag("sub-align-${a.name}"),
                ) { Text(stringResource(a.label)) }
            }
        }
    }
    item(key = "margin") {
        Label(stringResource(R.string.subs_margin_value, style.bottomMarginPercent.roundToInt()))
        SliderRow(style.bottomMarginPercent, 0f..SubtitleStyle.MAX_MARGIN_PERCENT, "sub-margin") { v ->
            update { it.copy(bottomMarginPercent = v.roundToInt().toFloat()) }
        }
    }
    item(key = "fit") {
        Toggle(stringResource(R.string.subs_fit_video), style.fitToVideo, "sub-fit", highlight == "fit") { b -> update { it.copy(fitToVideo = b) } }
    }
}

private val SubAlign.label: Int get() = when (this) {
    SubAlign.Left -> R.string.subs_align_left
    SubAlign.Centre -> R.string.subs_align_centre
    SubAlign.Right -> R.string.subs_align_right
}

/** Readable picks, light to dark. A full colour picker is not worth its weight here. */
private val PALETTE = listOf(
    0xFFFFFFFF, 0xFFFFEB3B, 0xFFFFC107, 0xFF80DEEA, 0xFFA5D6A7, 0xFFF48FB1, 0xFFBDBDBD, 0xFF616161, 0xFF000000,
)

@Composable
private fun Swatches(selected: Long, tag: String, onPick: (Long) -> Unit) {
    LazyRow(
        Modifier.padding(vertical = 4.dp),
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(PALETTE) { c ->
            val on = (selected and 0xFFFFFF) == (c and 0xFFFFFF)
            Box(
                Modifier
                    .size(36.dp)
                    .background(Color(c.toInt()), CircleShape)
                    .border(if (on) 3.dp else 1.dp, if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, CircleShape)
                    .clickable { onPick(c) }
                    .testTag("$tag-%06X".format(c and 0xFFFFFF)),
            )
        }
    }
}

@Composable
private fun Label(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp))
}

@Composable
private fun SliderRow(value: Float, range: ClosedFloatingPointRange<Float>, tag: String, onChange: (Float) -> Unit) {
    Slider(value, onChange, valueRange = range, modifier = Modifier.padding(horizontal = 16.dp).testTag(tag))
}

@Composable
private fun Toggle(label: String, checked: Boolean, tag: String, highlighted: Boolean = false, onChange: (Boolean) -> Unit) {
    SettingsSwitch(label, checked, onChange, modifier = Modifier.testTag(tag), highlighted = highlighted)
}
