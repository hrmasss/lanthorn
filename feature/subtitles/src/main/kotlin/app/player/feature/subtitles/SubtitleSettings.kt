package app.player.feature.subtitles

import android.content.Context
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.player.core.designsystem.component.SettingsHeader
import app.player.core.settingsapi.SettingsEntry
import app.player.core.settingsapi.SettingsGroup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** The Subtitle settings group (order 50). P1.28: style and placement with a live preview. */
object SubtitleSettings : SettingsGroup {
    override val id = "subtitle"
    override val order = 50
    override val title = R.string.subs_settings_title

    private const val DEFAULT_DELAY = "default_delay"
    private val KEYS = listOf("font", "size", "color", "bold", "outline", "shadow", "box", "align", "margin", "fit")
    private val TITLES = listOf(
        R.string.subs_font, R.string.subs_size, R.string.subs_color, R.string.subs_bold, R.string.subs_outline,
        R.string.subs_shadow, R.string.subs_box, R.string.subs_alignment, R.string.subs_margin, R.string.subs_fit_video,
    )
    override val entries = KEYS.zip(TITLES) { k, t -> SettingsEntry(k, t) } +
        SettingsEntry(DEFAULT_DELAY, R.string.subs_setting_default_delay, R.string.subs_setting_default_delay_summary)

    @Composable
    override fun Content(highlight: String?) {
        val context = LocalContext.current
        val styles = remember { SubtitlesExtension.styles(context) }
        val style by styles.style.collectAsState()
        var families by remember { mutableStateOf(listOf(style.font)) }
        val scope = rememberCoroutineScope()
        LaunchedEffect(Unit) {
            styles.ensureLoaded()
            families = styles.families()
        }
        val imported = stringResource(R.string.subs_fonts_imported)
        val folder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
            if (tree != null) scope.launch {
                val n = styles.importFonts(tree)
                families = styles.families()
                Toast.makeText(context, imported.format(n), Toast.LENGTH_SHORT).show()
            }
        }
        val state = rememberLazyListState()
        LaunchedEffect(highlight) {
            val i = KEYS.indexOf(highlight)
            // Timing sits first; the style header and preview sit above the style rows.
            if (highlight == DEFAULT_DELAY) state.scrollToItem(1) else if (i >= 0) state.scrollToItem(i + 4)
        }
        val store = remember { SubtitleStyleStore(context) }
        val defaultDelay by store.defaultDelayMs.collectAsState(0L)
        LazyColumn(Modifier.fillMaxSize().testTag("settings-page"), state) {
            item("timing") { SettingsHeader(stringResource(R.string.subs_setting_timing)) }
            item(DEFAULT_DELAY) {
                DelayRow(defaultDelay, highlight == DEFAULT_DELAY) { ms -> scope.launch { store.setDefaultDelay(ms) } }
            }
            item("header") { SettingsHeader(stringResource(R.string.subs_style_title)) }
            item("preview") { SubtitlePreview(style, Modifier.padding(horizontal = 16.dp)) }
            styleEditor(style, styles::update, families, onPickFontFolder = { folder.launch(null) }, highlight = highlight)
        }
    }

    override suspend fun reset(context: Context) {
        SubtitleStyleStore(context).resetDelay()
        withContext(Dispatchers.Main) { SubtitlesExtension.styles(context).reset() }
    }

    override suspend fun export(context: Context): JSONObject = JSONObject()
        .put("style", JSONObject(encodeStyle(SubtitlesExtension.styles(context).current())))
        .put("defaultDelayMs", SubtitleStyleStore(context).defaultDelayMs.first())

    override suspend fun import(context: Context, data: JSONObject) {
        if (data.has("defaultDelayMs")) SubtitleStyleStore(context).setDefaultDelay(data.optLong("defaultDelayMs"))
        val style = data.optJSONObject("style")?.let { decodeStyle(it.toString()) } ?: return
        withContext(Dispatchers.Main) { SubtitlesExtension.styles(context).update { style } }
    }
}

/** Default delay: the value, and steps of 50 ms and 1 s either way (US-41.3). */
@Composable
private fun DelayRow(ms: Long, highlighted: Boolean, onChange: (Long) -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(if (highlighted) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .testTag("sub-setting-default-delay"),
    ) {
        Text(stringResource(R.string.subs_setting_default_delay), style = MaterialTheme.typography.bodyLarge)
        Text(
            stringResource(R.string.subs_setting_default_delay_summary) + "  " + stringResource(R.string.subs_delay_value, ms / 1000.0),
            style = MaterialTheme.typography.bodyMedium,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(-1000L, -SubtitleController.DELAY_STEP_MS, SubtitleController.DELAY_STEP_MS, 1000L).forEach { step ->
                OutlinedButton(onClick = { onChange(ms + step) }, modifier = Modifier.testTag("sub-setting-delay$step")) {
                    Text("%+.2f".format(step / 1000.0))
                }
            }
        }
    }
}

/**
 * A still of the subtitle style over a picture-like backdrop, for settings, where no video plays.
 * Close to what libass draws, not identical: libass is the reference in the player.
 */
@Composable
internal fun SubtitlePreview(style: SubtitleStyle, modifier: Modifier = Modifier) {
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(180.dp)
            .background(Brush.linearGradient(listOf(Color(0xFF3A5F7D), Color(0xFFB57F50), Color(0xFF2E3B2C))))
            .testTag("sub-preview"),
    ) {
        val px = with(LocalDensity.current) { maxHeight.toPx() }
        // mpv sizes text against a 720-line script height; scale that to the preview's height.
        val fontPx = style.size * style.scale * px / 720f
        val fontSize = with(LocalDensity.current) { fontPx.toSp() }
        val family = when (style.font) {
            "serif" -> FontFamily.Serif
            "monospace" -> FontFamily.Monospace
            else -> FontFamily.SansSerif
        }
        val base = TextStyle(
            fontSize = fontSize,
            fontFamily = family,
            fontWeight = if (style.bold) FontWeight.Bold else FontWeight.Normal,
            textAlign = when (style.align) {
                SubAlign.Left -> TextAlign.Start
                SubAlign.Centre -> TextAlign.Center
                SubAlign.Right -> TextAlign.End
            },
            shadow = if (style.shadow) Shadow(Color(0x80000000), Offset(2f, 2f), 1f) else null,
        )
        val align = when (style.align) {
            SubAlign.Left -> Alignment.BottomStart
            SubAlign.Centre -> Alignment.BottomCenter
            SubAlign.Right -> Alignment.BottomEnd
        }
        val text = stringResource(R.string.subs_preview_text)
        Box(
            Modifier
                .align(align)
                .padding(horizontal = 12.dp)
                .padding(bottom = maxHeight * (style.bottomMarginPercent / 100f))
                .then(if (style.box) Modifier.background(Color(SubtitleStyle.withAlpha(style.boxColor, style.boxOpacity).toInt())) else Modifier)
                .padding(4.dp),
        ) {
            if (style.outline && !style.box) {
                Text(text, style = base.copy(color = Color(style.outlineColor.toInt()), shadow = null, drawStyle = Stroke(width = style.outlineSize * px / 720f * 2)))
            }
            Text(text, style = base.copy(color = Color(style.color.toInt())))
        }
    }
}
