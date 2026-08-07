package io.github.zuohl.hyperpods.pods

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.util.Log
import io.github.zuohl.hyperpods.utils.SystemApisUtils.setMetadata
import io.github.zuohl.hyperpods.utils.miuiStrongToast.data.BatteryParams
import io.github.zuohl.hyperpods.utils.miuiStrongToast.data.PodParams

/**
 * Marks a third-party TWS pod as an "untethered headset" in the BluetoothDevice
 * metadata so MIUI/HyperOS's native logic shows its status-bar icon + battery.
 *
 * `StatusBarManager.setIconVisibility("wireless_headset", true)` only fills the
 * status-bar slot; SystemUI gates actual rendering on the device metadata, so a
 * pod with `is_untethered_headset=null` never gets the icon. Setting the metadata
 * (the same fields a real Xiaomi TWS headset carries) makes the system recognize
 * the device and display the icon/battery reliably.
 *
 * Metadata keys are the AOSP `BluetoothDevice.METADATA_*` constants:
 * 6=IS_UNTETHERED_HEADSET, 10/11/12=left/right/case battery, 13/14/15=charging,
 * 18=main battery.
 */
object PodMetadata {
    private const val TAG = "HyperPods-Metadata"

    private const val METADATA_IS_UNTETHERED_HEADSET = 6
    private const val METADATA_UNTETHERED_LEFT_BATTERY = 10
    private const val METADATA_UNTETHERED_RIGHT_BATTERY = 11
    private const val METADATA_UNTETHERED_CASE_BATTERY = 12
    private const val METADATA_UNTETHERED_LEFT_CHARGING = 13
    private const val METADATA_UNTETHERED_RIGHT_CHARGING = 14
    private const val METADATA_UNTETHERED_CASE_CHARGING = 15
    private const val METADATA_MAIN_BATTERY = 18

    @SuppressLint("MissingPermission")
    fun markHeadset(device: BluetoothDevice) {
        runCatching {
            device.setMetadata(METADATA_IS_UNTETHERED_HEADSET, byteArrayOf(1))
            Log.d(TAG, "markHeadset ${device.address}")
        }.onFailure { Log.w(TAG, "markHeadset failed", it) }
    }

    @SuppressLint("MissingPermission")
    fun applyBattery(device: BluetoothDevice, battery: BatteryParams?) {
        if (battery == null) return
        runCatching {
            setBattery(device, METADATA_UNTETHERED_LEFT_BATTERY, battery.left)
            setBattery(device, METADATA_UNTETHERED_RIGHT_BATTERY, battery.right)
            setBattery(device, METADATA_UNTETHERED_CASE_BATTERY, battery.case)
            setCharging(device, METADATA_UNTETHERED_LEFT_CHARGING, battery.left)
            setCharging(device, METADATA_UNTETHERED_RIGHT_CHARGING, battery.right)
            setCharging(device, METADATA_UNTETHERED_CASE_CHARGING, battery.case)
            // Main battery = lowest connected earbud, drives the compact status-bar icon.
            val main = listOfNotNull(battery.left, battery.right)
                .filter { it.isConnected }
                .minOfOrNull { it.battery }
            if (main != null) {
                device.setMetadata(METADATA_MAIN_BATTERY, byteArrayOf(main.coerceIn(0, 100).toByte()))
            }
            Log.d(TAG, "applyBattery ${device.address} main=$main")
        }.onFailure { Log.w(TAG, "applyBattery failed", it) }
    }

    @SuppressLint("MissingPermission")
    fun clear(device: BluetoothDevice) {
        runCatching {
            device.setMetadata(METADATA_IS_UNTETHERED_HEADSET, byteArrayOf(0))
            Log.d(TAG, "clear ${device.address}")
        }.onFailure { Log.w(TAG, "clear failed", it) }
    }

    private fun setBattery(device: BluetoothDevice, key: Int, params: PodParams?) {
        if (params?.isConnected == true) {
            device.setMetadata(key, byteArrayOf(params.battery.coerceIn(0, 100).toByte()))
        }
    }

    private fun setCharging(device: BluetoothDevice, key: Int, params: PodParams?) {
        if (params?.isConnected == true) {
            device.setMetadata(key, byteArrayOf(if (params.isCharging) 1 else 0))
        }
    }
}
