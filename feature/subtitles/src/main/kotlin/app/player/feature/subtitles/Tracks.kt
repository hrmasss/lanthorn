package app.player.feature.subtitles

import org.json.JSONArray

/**
 * One entry of mpv's `track-list`. Copied in feature/subtitles and feature/audio: features may not
 * share code except through core, and it is a few lines.
 */
internal data class Track(
    val id: Long,
    val type: String,
    val title: String?,
    val lang: String?,
    val codec: String?,
    val external: Boolean,
    val externalFilename: String?,
    val default: Boolean,
    val selected: Boolean,
    val channels: Int?,
)

/** Parses `track-list` read as text (JSON). Bad input gives an empty list, never a crash. */
internal fun parseTracks(json: String?): List<Track> {
    if (json.isNullOrBlank()) return emptyList()
    return try {
        val array = JSONArray(json)
        List(array.length()) { i ->
            val o = array.getJSONObject(i)
            Track(
                id = o.getLong("id"),
                type = o.optString("type"),
                title = o.optString("title").ifBlank { null },
                lang = o.optString("lang").ifBlank { null },
                codec = o.optString("codec").ifBlank { null },
                external = o.optBoolean("external"),
                externalFilename = o.optString("external-filename").ifBlank { null },
                default = o.optBoolean("default"),
                selected = o.optBoolean("selected"),
                channels = o.optInt("demux-channel-count", 0).takeIf { it > 0 },
            )
        }
    } catch (e: org.json.JSONException) {
        emptyList()
    }
}

/** mpv's `sid`/`aid` text: a track id, or "no" / "auto" / null for none. */
internal fun parseTrackId(value: String?): Long? = value?.toLongOrNull()
