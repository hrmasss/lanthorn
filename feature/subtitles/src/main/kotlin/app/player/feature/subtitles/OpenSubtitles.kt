package app.player.feature.subtitles

import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * The OpenSubtitles REST API (US-46), called only when the viewer taps search or picks a result
 * (NF-8). Uses the viewer's own API key and login from settings; the app ships no credentials
 * (US-46.5). Blocking calls: run them on an IO dispatcher.
 */
internal class OpenSubtitles(
    private val apiKey: String,
    private val baseUrl: String = BASE_URL,
    private val userAgent: String = USER_AGENT,
) {
    data class Result(
        val fileId: Long,
        val language: String,
        val release: String,
        val rating: Double,
        val downloads: Long,
        val hashMatch: Boolean,
    )

    class Download(val fileName: String, val bytes: ByteArray)

    class ServiceException(val code: Int, message: String) : Exception(message)

    /** Logs in with the viewer's account; returns the token for downloads. */
    fun login(username: String, password: String): String {
        val body = JSONObject().put("username", username).put("password", password)
        return JSONObject(request("POST", "/login", body)).getString("token")
    }

    /** US-46.1: by file hash and name, in [languages] (comma-separated ISO 639-1, may be empty). */
    fun search(hash: String?, query: String, languages: String): List<Result> {
        val params = buildList {
            hash?.let { add("moviehash" to it) }
            if (query.isNotBlank()) add("query" to query)
            if (languages.isNotBlank()) add("languages" to languages)
        }.joinToString("&") { (k, v) -> "$k=" + URLEncoder.encode(v, "UTF-8") }
        return parseResults(request("GET", "/subtitles?$params", null))
    }

    /** US-46.2: the file for [fileId], through the link the service hands out. */
    fun download(token: String?, fileId: Long): Download {
        val reply = JSONObject(request("POST", "/download", JSONObject().put("file_id", fileId), token))
        val link = reply.getString("link")
        val bytes = (URL(link).openConnection() as HttpURLConnection).run {
            setRequestProperty("User-Agent", userAgent)
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            try {
                if (responseCode !in 200..299) throw ServiceException(responseCode, "download failed")
                inputStream.use(InputStream::readBytes)
            } finally {
                disconnect()
            }
        }
        return Download(reply.optString("file_name").ifBlank { "subtitle-$fileId.srt" }, bytes)
    }

    private fun request(method: String, path: String, body: JSONObject?, token: String? = null): String {
        val connection = URL(baseUrl + path).openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = method
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.setRequestProperty("Api-Key", apiKey)
            connection.setRequestProperty("User-Agent", userAgent)
            connection.setRequestProperty("Accept", "application/json")
            token?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toString().toByteArray()) }
            }
            val code = connection.responseCode
            val text = (if (code in 200..299) connection.inputStream else connection.errorStream)?.use { it.readBytes().decodeToString() }.orEmpty()
            if (code !in 200..299) throw ServiceException(code, runCatching { JSONObject(text).optString("message") }.getOrNull().orEmpty().ifBlank { "HTTP $code" })
            text
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        const val BASE_URL = "https://api.opensubtitles.com/api/v1"
        const val USER_AGENT = "Lanthorn v1.0"
        private const val TIMEOUT_MS = 15_000

        /** Results, best first: hash matches, then rating, then downloads. */
        fun parseResults(json: String): List<Result> {
            val data = JSONObject(json).optJSONArray("data") ?: JSONArray()
            return List(data.length()) { i -> data.getJSONObject(i).getJSONObject("attributes") }.mapNotNull { a ->
                val file = a.optJSONArray("files")?.optJSONObject(0) ?: return@mapNotNull null
                Result(
                    fileId = file.getLong("file_id"),
                    language = a.optString("language"),
                    release = a.optString("release").ifBlank { file.optString("file_name") },
                    rating = a.optDouble("ratings", 0.0),
                    downloads = a.optLong("download_count"),
                    hashMatch = a.optBoolean("moviehash_match"),
                )
            }.sortedWith(compareByDescending<Result> { it.hashMatch }.thenByDescending { it.rating }.thenByDescending { it.downloads })
        }

        /**
         * The OpenSubtitles file hash: the size plus the 64-bit little-endian sum of the first and
         * last 64 KiB, as 16 hex digits. Null for files under 64 KiB.
         */
        fun hash(file: RandomAccessFile): String? = hash(file.length()) { start, buf ->
            file.seek(start)
            file.readFully(buf)
        }

        /** The same, reading through [read] (position, buffer to fill), for files behind a descriptor. */
        fun hash(size: Long, read: (Long, ByteArray) -> Unit): String? {
            if (size < CHUNK) return null
            var sum = size
            for (start in longArrayOf(0, size - CHUNK)) {
                val buf = ByteArray(CHUNK.toInt())
                read(start, buf)
                for (i in 0 until CHUNK.toInt() step 8) {
                    var word = 0L
                    for (b in 7 downTo 0) word = (word shl 8) or (buf[i + b].toLong() and 0xFF)
                    sum += word
                }
            }
            return "%016x".format(sum)
        }

        private const val CHUNK = 65536L
    }
}
