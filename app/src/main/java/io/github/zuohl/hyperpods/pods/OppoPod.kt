package io.github.zuohl.hyperpods.pods

import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.SharedPreferences

/**
 * Adapter exposing the upstream OPPO RFCOMM controller ([RfcommController])
 * behind the brand-agnostic [Pod] contract so [PodController] can route to it.
 */
object OppoPod : Pod {
    override val brand: PodBrand = PodBrand.OPPO

    override fun connectPod(context: Context, device: BluetoothDevice, prefs: SharedPreferences, appRequested: Boolean) {
        RfcommController.connectPod(context, device, prefs)
    }

    override fun disconnectedPod(context: Context, device: BluetoothDevice) {
        RfcommController.disconnectedPod(context, device)
    }

    override fun queryStatus() {
        RfcommController.queryStatus()
    }

    override fun currentStatusSnapshot(): PodStatusSnapshot {
        val anc = RfcommController.currentAncMode()
        return PodStatusSnapshot(
            battery = RfcommController.currentBatterySnapshotCompat(),
            anc = anc,
            transparencyVocalEnhancement = anc == 3,
            address = RfcommController.currentDeviceAddress(),
            deviceName = RfcommController.currentDeviceName(),
            connected = RfcommController.isPodConnectedNow(),
            connecting = false,
        )
    }
}
