package app.player.feature.library.smb

import android.os.ParcelFileDescriptor
import android.os.Process
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.player.core.testing.Ears
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.KeyStore
import java.util.UUID
import javax.crypto.SecretKey

/** US-62.3: server passwords are stored encrypted with a keystore key and never logged. */
@RunWith(AndroidJUnit4::class)
class SmbPasswordsTest {
    private val instr = InstrumentationRegistry.getInstrumentation()
    private val context = instr.targetContext

    private fun shell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(instr.uiAutomation.executeShellCommand(command)).bufferedReader().readText()

    @Ears("US-62.3")
    @Test fun us_62_3_saved_password_is_keystore_encrypted_and_never_logged() {
        val password = "pw-" + UUID.randomUUID().toString().take(12)
        shell("logcat -c")
        val servers = SmbServers(context)
        val server = runBlocking { servers.add("Ears test", "192.0.2.10", 445, "media", "viewer", "", password) }
        try {
            val stored = runBlocking { servers.get(server.id) }!!.password
            assertNotEquals(password, stored)
            val raw = Base64.decode(stored, Base64.NO_WRAP)
            assertFalse("plain text inside the stored value", String(raw, Charsets.ISO_8859_1).contains(password))
            assertEquals(password, SmbPasswords.decrypt(stored))
            // Two encryptions differ (fresh IV each time).
            assertNotEquals(SmbPasswords.encrypt(password), SmbPasswords.encrypt(password))

            // The key lives in the Android keystore and cannot be read out.
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            val key = ks.getKey("lanthorn-smb-passwords", null) as SecretKey
            assertNull("keystore key material is exportable", key.encoded)

            // Nothing on disk holds it in plain text.
            val files = File(context.filesDir, "datastore").listFiles().orEmpty()
            assertTrue(files.isNotEmpty())
            files.forEach { assertFalse("plain password in ${it.name}", it.readBytes().toString(Charsets.ISO_8859_1).contains(password)) }

            // And the log of this process never shows it.
            val log = shell("logcat -d -v raw --pid=${Process.myPid()}")
            assertFalse("password in logcat", log.contains(password))
        } finally {
            runBlocking { servers.remove(server.id) }
            assertTrue(runBlocking { servers.servers.first() }.none { it.id == server.id })
        }
    }
}
