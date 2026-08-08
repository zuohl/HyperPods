package io.github.zuohl.hyperpods.pods

import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.SharedPreferences

/**
 * [Pod] adapter for vivo / iQOO TWS earphones. Delegates to [VivoController]. Vivo's battery
 * protocol (GAIA over RFCOMM) drops the A2DP link when opened, so this pod only maintains the
 * connected state (status-bar icon + module "已连接"); battery is left to the official app.
 */
object VivoPod : Pod {
    override val brand: PodBrand = PodBrand.VIVO

    override fun connectPod(context: Context, device: BluetoothDevice, prefs: SharedPreferences, appRequested: Boolean) {
        VivoController.connectPod(context, device, prefs, appRequested)
    }

    override fun disconnectedPod(context: Context, device: BluetoothDevice) {
        VivoController.disconnectedPod(context, device)
    }

    override fun queryStatus() {
        VivoController.queryStatus()
    }

    override fun currentStatusSnapshot(): PodStatusSnapshot =
        VivoController.currentStatusSnapshot()
}
