package app.player.feature.library.smb

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.UnknownHostException

class SmbErrorsTest {
    @Test fun `wrong user or password`() {
        val e = RuntimeException("Authentication failed", IOException("STATUS_LOGON_FAILURE (0xc000006d): Authentication failed for 'lanthorn'"))
        assertEquals(SmbFailure.Login, smbFailure(e))
    }

    @Test fun `no such share`() {
        assertEquals(SmbFailure.NoShare, smbFailure(IOException("STATUS_BAD_NETWORK_NAME (0xc00000cc): Could not connect to \\\\host\\movies")))
    }

    @Test fun `server not reachable, wrapped`() {
        assertEquals(SmbFailure.Unreachable, smbFailure(IOException("wrapper", ConnectException("Connection refused"))))
        assertEquals(SmbFailure.Unreachable, smbFailure(UnknownHostException("nas.local")))
    }

    @Test fun `anything else is generic`() {
        assertEquals(SmbFailure.Other, smbFailure(IllegalStateException("odd")))
        assertEquals(SmbFailure.Other, smbFailure(null))
    }
}
