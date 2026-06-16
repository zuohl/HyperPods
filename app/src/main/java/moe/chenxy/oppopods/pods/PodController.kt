package moe.chenxy.oppopods.pods

import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.SharedPreferences

object PodController {
    fun connectPod(context: Context, device: BluetoothDevice, prefs: SharedPreferences, appRequested: Boolean = false) {
        QcyController.connectPod(context, device, prefs, appRequested)
    }

    fun disconnectedPod(context: Context, device: BluetoothDevice) {
        QcyController.disconnectedPod(context, device)
    }

    fun queryStatus() {
        QcyController.queryStatus()
    }

    fun currentStatusSnapshot(): QcyController.StatusSnapshot = QcyController.currentStatusSnapshot()

    fun miuiRefreshPayload(
        battery: moe.chenxy.oppopods.utils.miuiStrongToast.data.BatteryParams?,
        anc: Int,
        transparencyVocalEnhancement: Boolean = false,
    ): String = QcyController.miuiRefreshPayload(battery, anc, transparencyVocalEnhancement)
}
