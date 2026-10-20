package app.player.feature.subtitles

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import org.json.JSONObject

private val Context.subtitleStore by preferencesDataStore("subtitles")

/** Saved subtitle style (US-42, US-43). One JSON value, so a style is read and written whole. */
internal class SubtitleStyleStore(context: Context) {
    private val store = context.applicationContext.subtitleStore

    suspend fun load(): SubtitleStyle = store.data.first()[KEY]?.let(::decodeStyle) ?: SubtitleStyle()

    suspend fun save(style: SubtitleStyle) {
        store.edit { it[KEY] = encodeStyle(style) }
    }

    companion object {
        private val KEY = stringPreferencesKey("style")
    }
}

internal fun encodeStyle(s: SubtitleStyle): String = JSONObject()
    .put("font", s.font).put("size", s.size).put("color", s.color).put("bold", s.bold)
    .put("outline", s.outline).put("outlineColor", s.outlineColor).put("outlineSize", s.outlineSize.toDouble())
    .put("shadow", s.shadow).put("box", s.box).put("boxColor", s.boxColor).put("boxOpacity", s.boxOpacity.toDouble())
    .put("align", s.align.name).put("bottomMarginPercent", s.bottomMarginPercent.toDouble())
    .put("fitToVideo", s.fitToVideo).put("scale", s.scale.toDouble())
    .toString()

/** Unknown or missing fields fall back to the defaults, so old saves survive new fields. */
internal fun decodeStyle(json: String): SubtitleStyle {
    val d = SubtitleStyle()
    val o = runCatching { JSONObject(json) }.getOrNull() ?: return d
    return SubtitleStyle(
        font = o.optString("font", d.font).ifBlank { d.font },
        size = o.optInt("size", d.size).coerceIn(SubtitleStyle.MIN_SIZE, SubtitleStyle.MAX_SIZE),
        color = o.optLong("color", d.color),
        bold = o.optBoolean("bold", d.bold),
        outline = o.optBoolean("outline", d.outline),
        outlineColor = o.optLong("outlineColor", d.outlineColor),
        outlineSize = o.optDouble("outlineSize", d.outlineSize.toDouble()).toFloat(),
        shadow = o.optBoolean("shadow", d.shadow),
        box = o.optBoolean("box", d.box),
        boxColor = o.optLong("boxColor", d.boxColor),
        boxOpacity = o.optDouble("boxOpacity", d.boxOpacity.toDouble()).toFloat(),
        align = runCatching { SubAlign.valueOf(o.optString("align")) }.getOrDefault(d.align),
        bottomMarginPercent = o.optDouble("bottomMarginPercent", d.bottomMarginPercent.toDouble()).toFloat(),
        fitToVideo = o.optBoolean("fitToVideo", d.fitToVideo),
        scale = o.optDouble("scale", d.scale.toDouble()).toFloat(),
    )
}
