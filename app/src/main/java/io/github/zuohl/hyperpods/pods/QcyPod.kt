package io.github.zuohl.hyperpods.pods

import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.SharedPreferences

/**
 * [Pod] adapter for QCY earphones. Delegates to [QcyController] which owns the
 * GATT transport, advertisement scan, and QCY protocol. This wrapper only
 * adapts the QCY-specific [QcyController.StatusSnapshot] into the shared
 * [PodStatusSnapshot] so the router and hooks stay brand-agnostic.
 */
object QcyPod : Pod {
    override val brand: PodBrand = PodBrand.QCY

    override fun connectPod(context: Context, device: BluetoothDevice, prefs: SharedPreferences, appRequested: Boolean) {
        QcyController.connectPod(context, device, prefs, appRequested)
    }

    override fun disconnectedPod(context: Context, device: BluetoothDevice) {
        QcyController.disconnectedPod(context, device)
    }

    override fun queryStatus() {
        QcyController.queryStatus()
    }

    override fun currentStatusSnapshot(): PodStatusSnapshot {
        val s = QcyController.currentStatusSnapshot()
        return PodStatusSnapshot(
            battery = s.battery,
            anc = s.anc,
            transparencyVocalEnhancement = s.transparencyVocalEnhancement,
            address = s.address,
            deviceName = s.deviceName,
            connected = s.connected,
            connecting = s.connecting,
        )
    }
}
