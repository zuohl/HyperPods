package io.github.zuohl.hyperpods.pods

import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.SharedPreferences

/**
 * Brand-agnostic router that dispatches to the active [Pod] implementation.
 *
 * The active brand is resolved via [PodDetector] at connection time and cached
 * so that downstream status queries and MIUI payload requests reach the right
 * controller. When no pod is connected, callers fall back to an empty snapshot.
 */
object PodController {
    @Volatile
    private var activePod: Pod? = null

    val currentBrand: PodBrand?
        get() = activePod?.brand

    fun connectPod(context: Context, device: BluetoothDevice, prefs: SharedPreferences, appRequested: Boolean = false) {
        val pod = selectPod(PodDetector.detectBrand(device))
        activePod = pod
        pod.connectPod(context, device, prefs, appRequested)
    }

    fun disconnectedPod(context: Context, device: BluetoothDevice) {
        val pod = activePod ?: selectPod(PodDetector.detectBrand(device))
        pod.disconnectedPod(context, device)
        if (PodDetector.detectBrand(device) == pod.brand) {
            activePod = null
        }
    }

    fun queryStatus() {
        activePod?.queryStatus()
    }

    fun currentStatusSnapshot(): PodStatusSnapshot =
        activePod?.currentStatusSnapshot() ?: emptySnapshot()

    fun miuiRefreshPayload(
        battery: io.github.zuohl.hyperpods.utils.miuiStrongToast.data.BatteryParams?,
        anc: Int,
        transparencyVocalEnhancement: Boolean = false,
    ): String = activePod?.miuiRefreshPayload(battery, anc, transparencyVocalEnhancement).orEmpty()

    private fun selectPod(brand: PodBrand?): Pod = when (brand) {
        PodBrand.QCY -> QcyPod
        PodBrand.VIVO -> QcyPod // TODO: replace with VivoPod once protocol lands
        null -> QcyPod
    }

    private fun emptySnapshot(): PodStatusSnapshot = PodStatusSnapshot(
        battery = null,
        anc = 1,
        transparencyVocalEnhancement = false,
        address = null,
        deviceName = null,
        connected = false,
        connecting = false,
        reconnectPending = false,
    )
}
