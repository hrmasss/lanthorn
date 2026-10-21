package app.player.feature.subtitles

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Online subtitle search settings (US-46.4, US-46.5). Off by default; the viewer's own account. */
data class OnlineAccount(
    val enabled: Boolean = false,
    val apiKey: String = "",
    val username: String = "",
    val password: String = "",
) {
    val ready: Boolean get() = enabled && apiKey.isNotBlank()
}

/**
 * Stores the account in the subtitle settings file. The API key and password are encrypted with a
 * key kept in the Android Keystore, so a backup or a copied data folder does not reveal them.
 */
internal class OnlineAccountStore(context: Context) {
    private val store = context.applicationContext.subtitleStore

    val account: Flow<OnlineAccount> = store.data.map {
        OnlineAccount(
            enabled = it[ENABLED] ?: false,
            apiKey = it[API_KEY]?.let(Secrets::open).orEmpty(),
            username = it[USERNAME].orEmpty(),
            password = it[PASSWORD]?.let(Secrets::open).orEmpty(),
        )
    }

    suspend fun current(): OnlineAccount = account.first()
    suspend fun setEnabled(on: Boolean) = store.edit { it[ENABLED] = on }
    suspend fun setApiKey(key: String) = store.edit { it[API_KEY] = Secrets.seal(key.trim()) }
    suspend fun setUsername(name: String) = store.edit { it[USERNAME] = name.trim() }
    suspend fun setPassword(password: String) = store.edit { it[PASSWORD] = Secrets.seal(password) }

    suspend fun reset() = store.edit {
        it.remove(ENABLED); it.remove(API_KEY); it.remove(USERNAME); it.remove(PASSWORD)
    }

    companion object {
        val ENABLED = booleanPreferencesKey("online_search")
        val API_KEY = stringPreferencesKey("online_api_key")
        val USERNAME = stringPreferencesKey("online_username")
        val PASSWORD = stringPreferencesKey("online_password")
    }
}

/** AES-GCM with a Keystore key that never leaves the device. */
private object Secrets {
    private const val ALIAS = "lanthorn-online-subtitles"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build(),
        )
        return gen.generateKey()
    }

    fun seal(plain: String): String {
        if (plain.isEmpty()) return ""
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        return Base64.encodeToString(cipher.iv + cipher.doFinal(plain.toByteArray()), Base64.NO_WRAP)
    }

    fun open(sealed: String): String = runCatching {
        if (sealed.isEmpty()) return ""
        val all = Base64.decode(sealed, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, all, 0, 12)) }
        String(cipher.doFinal(all, 12, all.size - 12))
    }.getOrDefault("")
}
