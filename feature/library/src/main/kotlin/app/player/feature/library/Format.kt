package app.player.feature.library

import java.util.Locale

/** `4:05`, or `1:02:03` past an hour. */
fun formatDuration(ms: Long): String {
    val total = (ms.coerceAtLeast(0) + 500) / 1000
    val h = total / 3600
    val m = total / 60 % 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(Locale.ROOT, h, m, s) else "%d:%02d".format(Locale.ROOT, m, s)
}

/** Decimal units, as Android's own file size formatter uses: `850 kB`, `1.4 GB`. */
fun formatSize(bytes: Long, locale: Locale = Locale.getDefault()): String {
    if (bytes < 1000) return "$bytes B"
    val units = listOf("kB", "MB", "GB", "TB")
    var value = bytes / 1000.0
    var unit = 0
    while (value >= 1000 && unit < units.lastIndex) {
        value /= 1000
        unit++
    }
    val pattern = if (value < 10) "%.1f %s" else "%.0f %s"
    return pattern.format(locale, value, units[unit])
}

fun formatResolution(width: Int, height: Int): String? =
    if (width > 0 && height > 0) "${width}×$height" else null

/** `25 fps`, `29.97 fps`. Null when unknown. */
fun formatFps(fps: Float, locale: Locale = Locale.getDefault()): String? {
    if (fps <= 0f) return null
    val rounded = Math.round(fps * 100) / 100.0
    val text = if (rounded % 1.0 == 0.0) rounded.toLong().toString() else "%.2f".format(locale, rounded).trimEnd('0')
    return "$text fps"
}

/** `MKV` from `Film.mkv`. Null when the name has no extension. */
fun extensionOf(name: String): String? =
    name.substringAfterLast('.', "").takeIf { it.isNotEmpty() && it.length <= 5 }?.uppercase(Locale.ROOT)

/** The name as shown in lists: without its extension. */
fun displayName(name: String): String {
    val dot = name.lastIndexOf('.')
    return if (dot > 0 && name.length - dot <= 6) name.substring(0, dot) else name
}
