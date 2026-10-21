package app.player.feature.library.smb

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** A saved SMB server (US-62.1). The password is kept encrypted, see [SmbPasswords]. */
data class SmbServer(
    val id: String,
    val name: String,
    val host: String,
    val port: Int = 445,
    val share: String,
    val user: String = "",
    val domain: String = "",
    /** Encrypted with the keystore key; empty for guest access. */
    val password: String = "",
) {
    val label: String get() = name.ifBlank { "$host/$share" }

    internal fun toJson() = JSONObject()
        .put("id", id).put("name", name).put("host", host).put("port", port).put("share", share)
        .put("user", user).put("domain", domain).put("password", password)

    internal companion object {
        fun fromJson(o: JSONObject) = SmbServer(
            o.getString("id"), o.optString("name"), o.getString("host"), o.optInt("port", 445),
            o.getString("share"), o.optString("user"), o.optString("domain"), o.optString("password"),
        )
    }
}

private val Context.smbStore: DataStore<Preferences> by preferencesDataStore("smb")

class SmbServers(context: Context) {
    private val store = context.applicationContext.smbStore

    val servers: Flow<List<SmbServer>> = store.data.map { parse(it[SERVERS]) }

    suspend fun get(id: String): SmbServer? = servers.first().firstOrNull { it.id == id }

    /** Saves a new server; [password] is plain text here and encrypted before it is stored. */
    suspend fun add(name: String, host: String, port: Int, share: String, user: String, domain: String, password: String): SmbServer {
        val server = SmbServer(
            id = UUID.randomUUID().toString(), name = name.trim(), host = host.trim(), port = port,
            share = share.trim().trim('/', '\\'), user = user.trim(), domain = domain.trim(),
            password = if (password.isEmpty()) "" else SmbPasswords.encrypt(password),
        )
        store.edit { it[SERVERS] = JSONArray(parse(it[SERVERS]).map { s -> s.toJson() } + server.toJson()).toString() }
        return server
    }

    suspend fun remove(id: String) = store.edit {
        it[SERVERS] = JSONArray(parse(it[SERVERS]).filter { s -> s.id != id }.map { s -> s.toJson() }).toString()
    }

    private fun parse(text: String?): List<SmbServer> {
        if (text.isNullOrEmpty()) return emptyList()
        val arr = JSONArray(text)
        return (0 until arr.length()).map { SmbServer.fromJson(arr.getJSONObject(it)) }
    }

    private companion object {
        val SERVERS = stringPreferencesKey("servers")
    }
}

/**
 * Server passwords, encrypted with an AES key that never leaves the Android keystore (US-62.3).
 * Stored as base64 of IV + ciphertext. Never logged.
 */
object SmbPasswords {
    private const val ALIAS = "lanthorn-smb-passwords"
    private const val KEYSTORE = "AndroidKeyStore"
    private const val TRANSFORM = "AES/GCM/NoPadding"

    fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val out = cipher.iv + cipher.doFinal(plain.toByteArray())
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    fun decrypt(stored: String): String {
        if (stored.isEmpty()) return ""
        val bytes = Base64.decode(stored, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 0, 12))
        return String(cipher.doFinal(bytes, 12, bytes.size - 12))
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }
}
