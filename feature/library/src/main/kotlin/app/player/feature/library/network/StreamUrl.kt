package app.player.feature.library.network

import java.net.URI
import java.util.Locale

/** Schemes US-61.1 plays. HLS and DASH arrive over http(s); mpv picks the demuxer from the content. */
val StreamSchemes = setOf("http", "https", "rtsp", "rtsps", "rtmp", "rtmps")

/**
 * The URL the viewer typed or pasted, cleaned up, or null if it is not a stream we can play.
 * Accepts a missing scheme for web addresses ("example.com/live.m3u8" becomes https).
 */
fun parseStreamUrl(text: String): String? {
    val t = text.trim()
    if (t.isEmpty() || t.any { it.isWhitespace() }) return null
    val withScheme = if ("://" in t) t else "https://$t"
    val uri = runCatching { URI(withScheme) }.getOrNull() ?: return null
    val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return null
    if (scheme !in StreamSchemes) return null
    if (uri.host.isNullOrEmpty()) return null
    return withScheme.replaceFirst(uri.scheme, scheme)
}

/** True for a stream URL, false for local content and files. */
fun isNetworkUrl(uri: String): Boolean =
    uri.substringBefore("://", "").lowercase(Locale.ROOT) in StreamSchemes

/** Why a stream failed, in the viewer's terms (US-61.4). */
enum class StreamFailure { NotFound, Timeout, Unsupported, Other }

/**
 * Reads mpv's warn and error lines from one failed open and names the reason. The first line that
 * matches decides; a format failure after a network failure is a consequence, not the cause.
 */
fun classifyFailure(lines: List<String>): StreamFailure {
    for (line in lines) {
        val l = line.lowercase(Locale.ROOT)
        when {
            "http error 404" in l || "http error 410" in l || "404 not found" in l ||
                "no such file" in l || "server returned 404" in l -> return StreamFailure.NotFound
            "timed out" in l || "timeout" in l || "connection refused" in l ||
                "network is unreachable" in l || "failed to resolve hostname" in l ||
                "no address associated" in l -> return StreamFailure.Timeout
            "failed to recognize file format" in l || "unsupported" in l ||
                "no video or audio streams" in l || "protocol not found" in l -> return StreamFailure.Unsupported
        }
    }
    return StreamFailure.Other
}
