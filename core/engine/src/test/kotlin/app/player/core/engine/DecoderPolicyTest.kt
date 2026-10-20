package app.player.core.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DecoderPolicyTest {
    private val uri = "content://media/external/video/media/42/movie.MKV"

    @Test fun nothingSetMeansHardwareFirst() {
        assertEquals(DecoderMode.AUTO, DecoderPolicy.pick(uri, DecoderPrefs()))
    }

    @Test fun orderIsFileThenExtensionThenPreferSoftware() { // US-36.1
        val all = DecoderPrefs(preferSoftware = true, byExtension = mapOf("mkv" to DecoderMode.HW_PLUS))
        assertEquals(DecoderMode.HW, DecoderPolicy.pick(uri, all, remembered = DecoderMode.HW))
        assertEquals(DecoderMode.HW_PLUS, DecoderPolicy.pick(uri, all))
        assertEquals(DecoderMode.SW, DecoderPolicy.pick(uri, all.copy(byExtension = emptyMap())))
    }

    @Test fun extensionAppliesToEveryFileWithIt() { // US-36.4
        val prefs = DecoderPrefs(byExtension = mapOf("avi" to DecoderMode.SW))
        assertEquals(DecoderMode.SW, DecoderPolicy.pick("file:///sdcard/a.avi", prefs))
        assertEquals(DecoderMode.SW, DecoderPolicy.pick("file:///sdcard/b.AVI", prefs))
        assertEquals(DecoderMode.AUTO, DecoderPolicy.pick("file:///sdcard/c.mp4", prefs))
    }

    @Test fun extensionParsing() {
        assertEquals("mkv", DecoderPolicy.extensionOf(uri))
        assertEquals("mp4", DecoderPolicy.extensionOf("https://host/v.mp4?token=a.b#t=3"))
        assertNull(DecoderPolicy.extensionOf("content://media/external/video/media/42"))
        assertNull(DecoderPolicy.extensionOf("file:///sdcard/.hidden"))
        assertNull(DecoderPolicy.extensionOf("file:///sdcard/trailingdot."))
    }

    @Test fun hardwareModesFallThroughInOrder() { // US-36.2: HW, then HW+, then SW
        assertEquals("mediacodec,mediacodec-copy", DecoderMode.HW.hwdec)
        assertEquals("mediacodec-copy", DecoderMode.HW_PLUS.hwdec)
        assertEquals("no", DecoderMode.SW.hwdec)
    }

    @Test fun featuresNeedingCpuFramesAreOffOnHw() { // US-35.4
        assertFalse(DecoderFeature.VideoFilters.runsOn(ActiveDecoder.HW))
        assertTrue(DecoderFeature.VideoFilters.runsOn(ActiveDecoder.HW_PLUS))
        assertTrue(DecoderFeature.Deinterlace.runsOn(ActiveDecoder.SW))
        assertEquals(DecoderMode.HW_PLUS, DecoderFeature.Deinterlace.enabledBy)
    }

    @Test fun fallbackNoticeOnlyWhenTheChosenDecoderIsNotRunning() { // US-35.3
        assertNull(fallbackNotice(DecoderMode.AUTO, ActiveDecoder.SW))
        assertNull(fallbackNotice(DecoderMode.HW, ActiveDecoder.HW))
        assertNull(fallbackNotice(DecoderMode.SW, ActiveDecoder.SW))
        assertEquals("HW cannot play this video. Playing with HW+.", fallbackNotice(DecoderMode.HW, ActiveDecoder.HW_PLUS))
        assertEquals("HW+ cannot play this video. Playing with SW.", fallbackNotice(DecoderMode.HW_PLUS, ActiveDecoder.SW))
    }
}
