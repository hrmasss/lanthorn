package app.player.feature.audio

import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The audio delay mpv gets: the viewer's delay for this file (or the default for a new file,
 * US-48.3) plus the Bluetooth delay while audio goes to a Bluetooth device (US-48.2). The panel
 * shows and changes only the first part. Main thread.
 */
internal class AudioDelay(private val mpv: Mpv) {
    private val _baseMs = MutableStateFlow(0L)
    val baseMs: StateFlow<Long> = _baseMs.asStateFlow()

    private var bluetoothMs = 0L
    private var onBluetooth = false

    fun setBase(ms: Long) {
        _baseMs.value = AudioController.snapDelay(ms)
        apply()
    }

    fun setBluetooth(delayMs: Long, active: Boolean) {
        bluetoothMs = delayMs
        onBluetooth = active
        apply()
    }

    /** What mpv plays with, in ms. */
    val effectiveMs: Long get() = _baseMs.value + if (onBluetooth) bluetoothMs else 0

    private fun apply() = mpv.set("audio-delay", (effectiveMs / 1000.0).toString())
}

/** Whether a Bluetooth output is attached: media goes to it when it is. */
internal class BluetoothOutput(private val audio: AudioManager, private val onChange: (Boolean) -> Unit) {
    private val callback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) = onChange(active())
        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) = onChange(active())
    }

    fun start() {
        onChange(active())
        audio.registerAudioDeviceCallback(callback, Handler(Looper.getMainLooper()))
    }

    fun stop() = audio.unregisterAudioDeviceCallback(callback)

    fun active(): Boolean = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.type in TYPES }

    companion object {
        val TYPES: Set<Int> = buildSet {
            add(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)
            add(AudioDeviceInfo.TYPE_HEARING_AID)
            if (android.os.Build.VERSION.SDK_INT >= 31) {
                add(AudioDeviceInfo.TYPE_BLE_HEADSET)
                add(AudioDeviceInfo.TYPE_BLE_SPEAKER)
            }
            if (android.os.Build.VERSION.SDK_INT >= 33) add(AudioDeviceInfo.TYPE_BLE_BROADCAST)
        }
    }
}
