package com.metronomo.audio

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper

/**
 * Detecta por onde o áudio está saindo. Cada tipo de saída tem latência própria,
 * então o app guarda um ajuste (manual e de calibração) para cada uma.
 */
object OutputRoute {

    enum class Type(val label: String) {
        SPEAKER("Alto-falante"),
        WIRED("Fone com fio"),
        USB("Saída USB"),
        BLUETOOTH("Bluetooth"),
    }

    fun current(context: Context): Type {
        val manager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val types = manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map { it.type }
        return when {
            types.any { isBluetooth(it) } -> Type.BLUETOOTH
            types.any { it == AudioDeviceInfo.TYPE_USB_HEADSET || it == AudioDeviceInfo.TYPE_USB_DEVICE || it == AudioDeviceInfo.TYPE_USB_ACCESSORY } -> Type.USB
            types.any { it == AudioDeviceInfo.TYPE_WIRED_HEADPHONES || it == AudioDeviceInfo.TYPE_WIRED_HEADSET } -> Type.WIRED
            else -> Type.SPEAKER
        }
    }

    private fun isBluetooth(type: Int): Boolean {
        if (type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) return true
        if (Build.VERSION.SDK_INT >= 31 &&
            (type == AudioDeviceInfo.TYPE_BLE_HEADSET || type == AudioDeviceInfo.TYPE_BLE_SPEAKER)
        ) {
            return true
        }
        return false
    }

    /** Avisa quando um dispositivo de saída é conectado ou removido. Devolve a função que cancela. */
    fun watch(context: Context, onChange: () -> Unit): () -> Unit {
        val manager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val callback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
                onChange()
            }

            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
                onChange()
            }
        }
        manager.registerAudioDeviceCallback(callback, Handler(Looper.getMainLooper()))
        return { manager.unregisterAudioDeviceCallback(callback) }
    }
}
