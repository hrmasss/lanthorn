package app.player.feature.library.smb

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.protocol.commons.EnumWithValue
import com.hierynomus.security.bc.BCSecurityProvider
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.share.DiskShare
import com.hierynomus.smbj.share.File
import app.player.feature.library.KnownExtensions
import java.util.EnumSet
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** One entry in a share folder: a subfolder or a video. */
data class SmbEntry(val name: String, val path: String, val isFolder: Boolean, val size: Long)

/**
 * SMB 2 and 3 through smbj (US-62.4). One connection per server, reused while it stays up.
 * All calls block: call them off the main thread. Passwords are decrypted only to authenticate.
 */
object SmbClient {
    private val client = SMBClient(
        SmbConfig.builder()
            // Android's crypto lacks MD4 (NTLM); Bouncy Castle's lightweight classes supply it.
            .withSecurityProvider(BCSecurityProvider())
            .withTimeout(15, TimeUnit.SECONDS)
            .withSoTimeout(20, TimeUnit.SECONDS)
            .build(),
    )

    private class Open(val connection: Connection, val share: DiskShare)

    private val open = ConcurrentHashMap<String, Open>()

    @Synchronized
    fun share(server: SmbServer): DiskShare {
        open[server.id]?.let { if (it.connection.isConnected && it.share.isConnected) return it.share }
        val connection = client.connect(server.host, server.port)
        val auth = if (server.user.isEmpty()) {
            AuthenticationContext.guest()
        } else {
            AuthenticationContext(server.user, SmbPasswords.decrypt(server.password).toCharArray(), server.domain.ifEmpty { null })
        }
        val share = connection.authenticate(auth).connectShare(server.share) as DiskShare
        open[server.id] = Open(connection, share)
        return share
    }

    /** The dialect agreed with the server, e.g. "SMB_3_1_1", for the browser's header. */
    fun dialect(server: SmbServer): String? =
        open[server.id]?.connection?.connectionContext?.negotiatedProtocol?.dialect?.name

    /** Folders first, then videos, by name. Dot entries and non-video files are left out. */
    fun list(server: SmbServer, folder: String): List<SmbEntry> {
        val share = share(server)
        return share.list(folder.ifEmpty { "" }).mapNotNull { info ->
            val name = info.fileName
            if (name == "." || name == ".." || name.startsWith(".")) return@mapNotNull null
            val isFolder = EnumWithValue.EnumUtils.isSet(info.fileAttributes, FileAttributes.FILE_ATTRIBUTE_DIRECTORY)
            val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
            if (!isFolder && ext !in KnownExtensions) return@mapNotNull null
            SmbEntry(name, if (folder.isEmpty()) name else "$folder\\$name", isFolder, info.endOfFile)
        }.sortedWith(compareBy({ !it.isFolder }, { it.name.lowercase(Locale.ROOT) }))
    }

    fun openRead(server: SmbServer, path: String): File =
        share(server).openFile(
            path,
            EnumSet.of(AccessMask.GENERIC_READ),
            null,
            SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_OPEN,
            null,
        )

    fun forget(serverId: String) {
        open.remove(serverId)?.let { runCatching { it.connection.close() } }
    }
}
