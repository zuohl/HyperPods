package io.github.zuohl.hyperpods.pods

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice

/**
 * Detects the earphone brand from the Bluetooth device name.
 *
 * Order matters: OPPO first (upstream HeyMelody), then QCY, then vivo.
 * Returns null when the device is not recognized as a supported pod.
 */
object PodDetector {
    private val oppoNameKeywords = listOf(
        "oppo",
        "oneplus",
    )

    private val qcyNameKeywords = listOf(
        "qcy",
        "crossky",
    )

    private val vivoNameKeywords = listOf(
        "vivo tws",
        "vivo tq",
        "iqoo tws",
        "iqoo tq",
    )

    @SuppressLint("MissingPermission")
    fun detectBrand(device: BluetoothDevice?): PodBrand? {
        if (device == null) return null
        val name = device.name?.lowercase().orEmpty()
        if (name.isBlank()) return null
        if (oppoNameKeywords.any { it in name }) return PodBrand.OPPO
        if (qcyNameKeywords.any { it in name }) return PodBrand.QCY
        if (vivoNameKeywords.any { it in name }) return PodBrand.VIVO
        return null
    }

    @SuppressLint("MissingPermission")
    fun isSupportedPod(device: BluetoothDevice?): Boolean {
        return detectBrand(device) != null
    }

    @SuppressLint("MissingPermission")
    fun isQcyAppDevice(device: BluetoothDevice?): Boolean {
        val name = buildList {
            device?.name?.let(::add)
        }.joinToString(" ").lowercase()
        return "qcy" in name && "-app" in name
    }
}
