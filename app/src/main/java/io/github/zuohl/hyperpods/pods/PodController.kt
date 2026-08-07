package io.github.zuohl.hyperpods.pods

import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.SharedPreferences
import android.util.Log

/**
 * Brand-agnostic router that dispatches to the active [Pod] implementation.
 *
 * The active brand is resolved via [PodDetector] at connection time and cached
 * so downstream status queries reach the right controller. When no pod is
 * connected, callers fall back to an empty snapshot.
 */
object PodController {
    private const val TAG = "HyperPods-Router"

    @Volatile
    private var activePod: Pod? = null

    val currentBrand: PodBrand?
        get() = activePod?.brand

    /**
     * Whether [device] maps to a routable [Pod]. Used by the hook layer as the
     * connection gate so unsupported/unroutable devices are left untouched.
     */
    fun supports(device: BluetoothDevice): Boolean {
        return selectPod(PodDetector.detectBrand(device)) != null
    }

    fun connectPod(context: Context, device: BluetoothDevice, prefs: SharedPreferences, appRequested: Boolean = false) {
        val pod = selectPod(PodDetector.detectBrand(device)) ?: run {
            Log.d(TAG, "connectPod skipped: no routable pod for device=${device.address} name=${runCatching { device.name }.getOrNull()}")
            return
        }
        Log.d(TAG, "connectPod device=${device.address} name=${runCatching { device.name }.getOrNull()} brand=${pod.brand} appRequested=$appRequested")
        activePod = pod
        pod.connectPod(context, device, prefs, appRequested)
    }

    fun disconnectedPod(context: Context, device: BluetoothDevice) {
        val pod = activePod ?: selectPod(PodDetector.detectBrand(device)) ?: return
        Log.d(TAG, "disconnectedPod device=${device.address} brand=${pod.brand}")
        pod.disconnectedPod(context, device)
        activePod = null
    }

    fun queryStatus() {
        activePod?.queryStatus()
    }

    fun currentStatusSnapshot(): PodStatusSnapshot =
        activePod?.currentStatusSnapshot() ?: emptySnapshot()

    private fun selectPod(brand: PodBrand?): Pod? = when (brand) {
        PodBrand.OPPO -> OppoPod
        PodBrand.QCY -> QcyPod
        PodBrand.VIVO -> PassthroughPod
        PodBrand.GENERIC -> PassthroughPod
        null -> null
    }

    private fun emptySnapshot(): PodStatusSnapshot = PodStatusSnapshot(
        battery = null,
        anc = 1,
        transparencyVocalEnhancement = false,
        address = null,
        deviceName = null,
        connected = false,
        connecting = false,
    )
}
