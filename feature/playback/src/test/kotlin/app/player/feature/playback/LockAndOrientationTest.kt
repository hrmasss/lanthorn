package app.player.feature.playback

import android.content.pm.ActivityInfo
import app.player.core.testing.Ears
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LockAndOrientationTest {
    private fun KidsUnlock.taps(vararg t: Triple<Float, Float, Long>) = t.map { (x, y, at) -> tap(x, y, at) }

    @Ears("US-28.2")
    @Test fun `four corners clockwise from top left within 5 s unlock (US-28_2)`() {
        val k = KidsUnlock()
        assertEquals(listOf(false, false, false, true), k.taps(Triple(0.1f, 0.1f, 0), Triple(0.9f, 0.1f, 500), Triple(0.9f, 0.9f, 1000), Triple(0.1f, 0.9f, 1500)))
    }

    @Ears("US-28.2")
    @Test fun `wrong order, a tap outside the corners or over 5 s does not unlock (US-28_2)`() {
        assertFalse(KidsUnlock().taps(Triple(0.1f, 0.1f, 0), Triple(0.1f, 0.9f, 100), Triple(0.9f, 0.9f, 200), Triple(0.9f, 0.1f, 300)).last())
        assertFalse(KidsUnlock().taps(Triple(0.1f, 0.1f, 0), Triple(0.9f, 0.1f, 100), Triple(0.5f, 0.5f, 150), Triple(0.9f, 0.9f, 200), Triple(0.1f, 0.9f, 300)).last())
        assertFalse(KidsUnlock().taps(Triple(0.1f, 0.1f, 0), Triple(0.9f, 0.1f, 2000), Triple(0.9f, 0.9f, 4000), Triple(0.1f, 0.9f, 5001)).last())
    }

    @Test fun `a fresh top left tap restarts the sequence`() {
        val k = KidsUnlock()
        assertTrue(k.taps(Triple(0.1f, 0.1f, 0), Triple(0.9f, 0.1f, 100), Triple(0.1f, 0.1f, 200), Triple(0.9f, 0.1f, 300), Triple(0.9f, 0.9f, 400), Triple(0.1f, 0.9f, 500)).last())
    }

    @Ears("US-28.2")
    @Test fun `corner zones are 20 percent of width and height (US-28_2)`() {
        assertEquals(KidsUnlock.Corner.TopLeft, KidsUnlock.cornerOf(0.2f, 0.2f))
        assertNull(KidsUnlock.cornerOf(0.21f, 0.1f))
        assertEquals(KidsUnlock.Corner.BottomRight, KidsUnlock.cornerOf(0.8f, 0.8f))
        assertNull(KidsUnlock.cornerOf(0.5f, 0.95f))
    }

    @Ears("US-56.1", "US-56.4")
    @Test fun `follow video picks landscape for wide and portrait for tall, with the file's rotation (US-56_1, US-56_4)`() {
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, Orientation.requested(OrientationMode.FollowVideo, 1920, 1080, 0))
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT, Orientation.requested(OrientationMode.FollowVideo, 1080, 1920, 0))
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT, Orientation.requested(OrientationMode.FollowVideo, 1920, 1080, 90))
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, Orientation.requested(OrientationMode.FollowVideo, 1920, 1080, 180))
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT, Orientation.requested(OrientationMode.FollowVideo, 1000, 1000, 0))
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED, Orientation.requested(OrientationMode.FollowVideo, 0, 0, 0))
    }

    @Ears("US-56.3")
    @Test fun `every orientation choice maps to one platform value (US-56_3)`() {
        val values = OrientationMode.entries.filter { it != OrientationMode.FollowVideo }.map { Orientation.requested(it, 1920, 1080, 0) }
        assertEquals(
            listOf(
                ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE,
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR,
                ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED,
            ),
            values,
        )
    }

    @Ears("US-56.2")
    @Test fun `rotate switches to the other orientation (US-56_2)`() {
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT, Orientation.other(landscapeNow = true))
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, Orientation.other(landscapeNow = false))
    }
}
