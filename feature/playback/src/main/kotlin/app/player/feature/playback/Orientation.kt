package app.player.feature.playback

import android.content.pm.ActivityInfo

/** Orientation setting (US-56.3). */
enum class OrientationMode { FollowVideo, Landscape, ReverseLandscape, SensorLandscape, Sensor, System }

/** Which way the player stands, without Android state. */
object Orientation {
    /** The requested orientation for [mode] and a video of [width] x [height] turned by [rotateDeg] (US-56.1, US-56.4). */
    fun requested(mode: OrientationMode, width: Int, height: Int, rotateDeg: Int): Int = when (mode) {
        OrientationMode.FollowVideo -> when {
            width <= 0 || height <= 0 -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            isWide(width, height, rotateDeg) -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            else -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        }
        OrientationMode.Landscape -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        OrientationMode.ReverseLandscape -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
        OrientationMode.SensorLandscape -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        OrientationMode.Sensor -> ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
        OrientationMode.System -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }

    /** Wider than tall once the file's rotation metadata is applied. */
    fun isWide(width: Int, height: Int, rotateDeg: Int): Boolean {
        val quarter = ((rotateDeg % 180) + 180) % 180 == 90
        return if (quarter) height > width else width > height
    }

    /** The rotate button (US-56.2): the other orientation from the one on screen. */
    fun other(landscapeNow: Boolean): Int =
        if (landscapeNow) ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
}
