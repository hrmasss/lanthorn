package app.player.feature.library.smb

import app.player.feature.library.network.isNetworkUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmbScreensTest {
    @Test fun `us_62_1 discovery lists each server once, in the order found`() {
        var list = emptyList<FoundServer>()
        list = withFound(list, FoundServer("nas", "192.168.1.10", 445))
        list = withFound(list, FoundServer("mac", "192.168.1.20", 445))
        list = withFound(list, FoundServer("nas again", "192.168.1.10", 445))
        list = withFound(list, FoundServer("nas alt port", "192.168.1.10", 4445))
        assertEquals(listOf("nas", "mac", "nas alt port"), list.map { it.name })
    }

    @Test fun `us_62_4 dialect reads as a version`() {
        assertEquals("SMB 3.1.1", dialectLabel("SMB_3_1_1"))
        assertEquals("SMB 2.1", dialectLabel("SMB_2_1"))
        assertEquals("SMB 2.0.2", dialectLabel("SMB_2_0_2"))
    }

    @Test fun `share files get the network overlay, local files do not`() {
        assertTrue(isNetworkUrl("content://app.player.smb/1234/Movies/a.mp4"))
        assertFalse(isNetworkUrl("content://media/external_primary/video/media/5"))
    }
}
