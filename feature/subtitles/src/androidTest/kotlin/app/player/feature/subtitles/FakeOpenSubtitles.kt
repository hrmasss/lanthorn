package app.player.feature.subtitles

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import kotlin.concurrent.thread

/**
 * A stand-in for api.opensubtitles.com on 127.0.0.1, enough for one search, login and download.
 * Records every request (method, path, headers) so a test can count them (NF-8).
 */
internal class FakeOpenSubtitles(private val subtitle: String) : AutoCloseable {
    data class Request(val method: String, val path: String, val headers: Map<String, String>, val body: String)

    val requests: MutableList<Request> = Collections.synchronizedList(mutableListOf())
    private val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    val baseUrl = "http://127.0.0.1:${server.localPort}/api/v1"

    init {
        thread(isDaemon = true, name = "fake-opensubtitles") {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                thread(isDaemon = true) { socket.use(::serve) }
            }
        }
    }

    private fun serve(socket: Socket) {
        val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
        val first = reader.readLine() ?: return
        val (method, path) = first.split(" ").let { it[0] to it[1] }
        val headers = mutableMapOf<String, String>()
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break
            headers[line.substringBefore(':').trim().lowercase()] = line.substringAfter(':').trim()
        }
        val length = headers["content-length"]?.toIntOrNull() ?: 0
        val body = if (length > 0) CharArray(length).also { reader.read(it, 0, length) }.concatToString() else ""
        requests += Request(method, path, headers, body)
        val reply = when {
            path.startsWith("/api/v1/login") -> """{"token":"T0K3N","user":{"allowed_downloads":20}}"""
            path.startsWith("/api/v1/subtitles") -> """{"data":[
              {"attributes":{"language":"en","release":"Clip 2026 WEB","ratings":7.5,"download_count":120,"moviehash_match":true,"files":[{"file_id":4242,"file_name":"clip.en.srt"}]}},
              {"attributes":{"language":"fr","release":"Clip 2026 BluRay","ratings":6.0,"download_count":30,"moviehash_match":false,"files":[{"file_id":4343,"file_name":"clip.fr.srt"}]}}]}"""
            path.startsWith("/api/v1/download") -> """{"link":"${baseUrl.replace("/api/v1", "")}/file/4242","file_name":"clip.en.srt"}"""
            path.startsWith("/file/") -> subtitle
            else -> "{}"
        }
        val bytes = reply.toByteArray()
        socket.getOutputStream().apply {
            write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
            write(bytes)
            flush()
        }
    }

    override fun close() = server.close()
}
