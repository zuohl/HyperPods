package io.github.zuohl.hyperpods.pods

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice

object PodDetector {
    private val qcyNameKeywords = listOf(
        "qcy",
        "crossky",
        "crossky c50s",
        "crossky c50s-app",
    )

    private val vivoNameKeywords = listOf(
        "vivo tws",
        "vivo tq",
        "iqoo tws",
        "iqoo tq",
    )

    /**
     * Detect the earphone brand from the Bluetooth device name.
     * Returns null when the device is not recognized as a supported pod.
     */
    @SuppressLint("MissingPermission")
    fun detectBrand(device: BluetoothDevice?): PodBrand? {
        if (device == null) return null
        val name = device.name?.lowercase().orEmpty()
        if (name.isBlank()) return null
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
