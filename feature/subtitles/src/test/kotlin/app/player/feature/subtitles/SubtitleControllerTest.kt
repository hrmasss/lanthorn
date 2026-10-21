package app.player.feature.subtitles

import app.player.core.testing.Ears
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class SubtitleControllerTest {
    private val mpv = FakeMpv()
    private val controller = SubtitleController(mpv)

    @Ears("US-39.3")
    @Test fun `toggle turns on primary, then secondary, then replaces secondary (US-39_3)`() {
        val none = SubSelection()
        val one = none.toggle(1)
        assertEquals(SubSelection(1, null), one)
        val two = one.toggle(2)
        assertEquals(SubSelection(1, 2), two)
        assertEquals(SubSelection(1, 3), two.toggle(3))
    }

    @Ears("US-39.3")
    @Test fun `toggle off primary promotes the secondary, none is allowed (US-39_3)`() {
        assertEquals(SubSelection(2, null), SubSelection(1, 2).toggle(1))
        assertEquals(SubSelection(1, null), SubSelection(1, 2).toggle(2))
        assertEquals(SubSelection(), SubSelection(1, null).toggle(1))
    }

    @Test fun `select clears the secondary before moving tracks`() {
        controller.select(SubSelection(2, 1))
        assertEquals(listOf("set secondary-sid=no", "set sid=2", "set secondary-sid=1"), mpv.calls)
        mpv.calls.clear()
        controller.select(SubSelection())
        assertEquals(listOf("set secondary-sid=no", "set sid=no", "set secondary-sid=no"), mpv.calls)
    }

    @Ears("US-39.3")
    @Test fun `tracks keep subtitles only and selection follows sid (US-39_3)`() = runTest {
        mpv.emit("track-list", TRACKS)
        mpv.emit("sid", "3")
        mpv.emit("secondary-sid", "no")
        val tracks = controller.tracks.first()
        assertEquals(listOf(2L, 3L), tracks.map { it.id })
        assertEquals("eng", tracks[0].lang)
        assertEquals(true, tracks[1].external)
        assertEquals(SubSelection(3, null), controller.selection.first())
    }

    @Ears("US-41.1")
    @Test fun `delay snaps to 50 ms and clamps to 600 s (US-41_1)`() {
        controller.setDelayMs(1234)
        controller.setDelayMs(-24)
        controller.setDelayMs(700_000)
        controller.setDelayMs(-700_000)
        assertEquals(listOf("set sub-delay=1.25", "set sub-delay=0.0", "set sub-delay=600.0", "set sub-delay=-600.0"), mpv.calls)
    }

    @Ears("US-41.2")
    @Test fun `speed clamps to 0_5 to 2_0 (US-41_2)`() {
        controller.setSpeed(0.1)
        controller.setSpeed(1.25)
        controller.setSpeed(3.0)
        assertEquals(listOf("set sub-speed=0.5", "set sub-speed=1.25", "set sub-speed=2.0"), mpv.calls)
    }

    @Ears("US-42.4")
    @Test fun `ASS keeps file styling unless override is on, embedded fonts switch (US-42_4)`() {
        controller.setOverrideStyles(false)
        controller.setOverrideStyles(true)
        controller.setIgnoreEmbeddedFonts(true)
        assertEquals(listOf("set sub-ass-override=scale", "set sub-ass-override=strip", "set embeddedfonts=no"), mpv.calls)
    }

    @Ears("US-44.2")
    @Test fun `track changes redraw the frame while paused (US-44_2)`() {
        mpv.emit("pause", true)
        controller.reload(4)
        controller.select(SubSelection(1))
        assertEquals(listOf("sub-reload 4", "seek 0 relative+exact", "set secondary-sid=no", "set sid=1", "set secondary-sid=no", "seek 0 relative+exact"), mpv.calls)
    }

    @Ears("US-44.2")
    @Test fun `external file is added and selected, reload by id (US-44_2)`() {
        controller.addFile("/c/a.srt", "a.srt")
        controller.reload(4)
        assertEquals(listOf("sub-add /c/a.srt select a.srt", "sub-reload 4"), mpv.calls)
    }

    @Test fun `bad track json gives no tracks`() {
        assertEquals(emptyList<Track>(), parseTracks("not json"))
        assertEquals(emptyList<Track>(), parseTracks(null))
    }

    companion object {
        val TRACKS = """
            [{"id":1,"type":"video","codec":"h264","selected":true},
             {"id":1,"type":"audio","lang":"eng","codec":"aac","demux-channel-count":6},
             {"id":2,"type":"sub","lang":"eng","title":"English","codec":"ass","default":true},
             {"id":3,"type":"sub","codec":"subrip","external":true,"external-filename":"/c/x.srt","selected":true}]
        """.trimIndent()
    }
}
