package app.player.feature.audio

import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper

/**
 * US-51.2: mpv downmixes surround to what the output takes. Android accepts 5.1 PCM even on a
 * stereo phone and downmixes it in the mixer, out of our control, so we ask mpv for stereo unless
 * a multichannel output (HDMI, USB) is attached.
 */
internal class OutputChannels(private val audio: AudioManager, private val mpv: Mpv) {
    private var applied: String? = null

    private val callback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) = update()
        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) = update()
    }

    fun start() {
        update()
        audio.registerAudioDeviceCallback(callback, Handler(Looper.getMainLooper()))
    }

    fun stop() = audio.unregisterAudioDeviceCallback(callback)

    private fun update() {
        val counts = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .filter { it.type in MULTICHANNEL_TYPES }
            .flatMap { it.channelCounts.toList() }
        val value = mpvChannels(counts)
        if (value != applied) {
            applied = value
            mpv.set("audio-channels", value)
        }
    }

    companion object {
        private val MULTICHANNEL_TYPES = setOf(
            AudioDeviceInfo.TYPE_HDMI, AudioDeviceInfo.TYPE_HDMI_ARC, AudioDeviceInfo.TYPE_HDMI_EARC,
            AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_DOCK,
        )

        /** Channel counts of attached multichannel outputs (empty: none, or any count) to mpv's value. */
        fun mpvChannels(counts: List<Int>): String = if ((counts.maxOrNull() ?: 2) > 2) "auto-safe" else "stereo"
    }
}
