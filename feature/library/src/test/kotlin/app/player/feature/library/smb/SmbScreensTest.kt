package app.player.feature.library.smb

import app.player.core.testing.Ears
import app.player.feature.library.network.isNetworkUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmbScreensTest {
    @Ears("US-62.1")
    @Test fun `us_62_1 discovery lists each server once, in the order found`() {
        var list = emptyList<FoundServer>()
        list = withFound(list, FoundServer("nas", "192.168.1.10", 445))
        list = withFound(list, FoundServer("mac", "192.168.1.20", 445))
        list = withFound(list, FoundServer("nas again", "192.168.1.10", 445))
        list = withFound(list, FoundServer("nas alt port", "192.168.1.10", 4445))
        assertEquals(listOf("nas", "mac", "nas alt port"), list.map { it.name })
    }

    @Ears("US-62.4")
    @Test fun `us_62_4 dialect reads as a version`() {
        assertEquals("SMB 3.1.1", dialectLabel("SMB_3_1_1"))
        assertEquals("SMB 2.1", dialectLabel("SMB_2_1"))
        assertEquals("SMB 2.0.2", dialectLabel("SMB_2_0_2"))
    }

    @Test fun `share files get the network overlay, local files do not`() {
        assertTrue(isNetworkUrl("content://app.player.smb/1234/Movies/a.mp4"))
        assertFalse(isNetworkUrl("content://media/external_primary/video/media/5"))
    }

    @Ears("US-62.1")
    @Test fun `us_62_1 a found server shows its IPv4 address before IPv6`() {
        val v6Local = java.net.InetAddress.getByName("fe80::be24:11ff:fefc:7f1b")
        val v6 = java.net.InetAddress.getByName("2001:db8::1")
        val v4 = java.net.InetAddress.getByName("192.168.1.20")
        assertEquals("192.168.1.20", preferredAddress(listOf(v6Local, v6, v4))?.hostAddress)
        assertEquals(v6, preferredAddress(listOf(v6Local, v6)))
        assertEquals(v6Local, preferredAddress(listOf(v6Local)))
        assertEquals(null, preferredAddress(emptyList()))
    }
}
