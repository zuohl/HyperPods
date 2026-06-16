package moe.chenxy.oppopods.pods

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice

object PodDetector {
    private val qcyNameKeywords = listOf(
        "qcy",
        "crossky",
        "crossky c50s",
        "crossky c50s-app",
    )

    @SuppressLint("MissingPermission")
    fun isSupportedPod(device: BluetoothDevice?): Boolean {
        if (device == null) return false
        val name = buildList {
            device.name?.let(::add)
        }.joinToString(" ").lowercase()
        return qcyNameKeywords.any { it in name }
    }

    @SuppressLint("MissingPermission")
    fun isQcyAppDevice(device: BluetoothDevice?): Boolean {
        val name = buildList {
            device?.name?.let(::add)
        }.joinToString(" ").lowercase()
        return "qcy" in name && "-app" in name
    }
}
