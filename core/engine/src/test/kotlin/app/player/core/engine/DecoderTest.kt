package app.player.core.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class DecoderTest {
    @Test fun hwdecCurrentMapsToBadge() {
        assertEquals(ActiveDecoder.HW, ActiveDecoder.fromHwdecCurrent("mediacodec"))
        assertEquals(ActiveDecoder.HW_PLUS, ActiveDecoder.fromHwdecCurrent("mediacodec-copy"))
        assertEquals(ActiveDecoder.SW, ActiveDecoder.fromHwdecCurrent("no"))
        assertEquals(ActiveDecoder.SW, ActiveDecoder.fromHwdecCurrent(""))
        assertEquals(ActiveDecoder.SW, ActiveDecoder.fromHwdecCurrent(null))
    }

    @Test fun unknownHwdecFallsIntoTheRightFamily() {
        assertEquals(ActiveDecoder.HW_PLUS, ActiveDecoder.fromHwdecCurrent("vulkan-copy"))
        assertEquals(ActiveDecoder.HW, ActiveDecoder.fromHwdecCurrent("vulkan"))
    }

    @Test fun everyModeEndsInSoftware() {
        // mpv falls back to software after the last listed hwdec, so each list stays hwdec-only.
        DecoderMode.entries.forEach { assert(!it.hwdec.contains("auto")) { it } }
        assertEquals("mediacodec,mediacodec-copy", DecoderMode.AUTO.hwdec)
        assertEquals(DecoderMode.AUTO.hwdec, DecoderMode.HW.hwdec)
        assertEquals("no", DecoderMode.SW.hwdec)
    }
}
