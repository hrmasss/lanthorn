package app.player.core.engine

import dev.jdtech.mpv.MPVLib.MpvFormat

/**
 * How an mpv property is read and observed. Names and meanings are mpv's own:
 * https://mpv.io/manual/stable/#properties
 *
 * List and map properties (`track-list`, `chapter-list`, `metadata`) read as [Text] come back as
 * JSON. Prefer indexed sub-properties (`track-list/count`, `track-list/0/lang`) when you only need
 * a few fields.
 */
sealed class PropertyType<T : Any>(internal val format: Int) {
    data object Text : PropertyType<String>(MpvFormat.MPV_FORMAT_STRING)
    data object Flag : PropertyType<Boolean>(MpvFormat.MPV_FORMAT_FLAG)
    data object Int64 : PropertyType<Long>(MpvFormat.MPV_FORMAT_INT64)
    data object Real : PropertyType<Double>(MpvFormat.MPV_FORMAT_DOUBLE)
}
