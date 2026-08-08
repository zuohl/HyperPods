package io.github.zuohl.hyperpods.pods

import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import io.github.zuohl.hyperpods.BuildConfig
import android.util.Log
import io.github.zuohl.hyperpods.utils.miuiStrongToast.data.BatteryParams
import io.github.zuohl.hyperpods.utils.miuiStrongToast.data.OppoPodsAction

/**
 * A [Pod] implementation that does not attempt any protocol-level connection
 * (no GATT, no RFCOMM, no advertisement scan). Used for:
 *
 * - Manually bound earphones via MAC address (no known protocol at all)
 * - vivo earphones until a native protocol implementation lands
 *
 * This pod maintains a "connected" state driven entirely by the A2DP/HFP
 * profile connection managed by the system. It sends [OppoPodsAction.ACTION_PODS_CONNECTED]
 * so the HyperOS hook layer can fake system integration (status bar icon,
 * popup framework), and returns an empty battery snapshot since there is no
 * protocol data available.
 *
 * The key difference from [QcyPod]: PassthroughPod never triggers a GATT
 * disconnect that would tear down the UI. The A2DP profile stays connected,
 * so the status bar icon persists.
 */
object PassthroughPod : Pod {
    private const val TAG = "HyperPods-Passthrough"

    override val brand: PodBrand = PodBrand.VIVO

    @Volatile
    private var connected: Boolean = false
    @Volatile
    private var address: String? = null
    @Volatile
    private var deviceName: String? = null
    @Volatile
    private var connectTimeMs: Long = 0L

    private var context: Context? = null
    private var receiverRegistered = false
    private var appUiActive = false
    private var appUiActiveUntilMs = 0L
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    override fun connectPod(context: Context, device: BluetoothDevice, prefs: SharedPreferences, appRequested: Boolean) {
        this.context = context
        this.address = device.address
        this.deviceName = device.name
        this.connectTimeMs = SystemClock.elapsedRealtime()
        this.connected = true
        if (appRequested) markAppUiActive()
        Log.i(TAG, "connectPod address=$address name=$deviceName (passthrough, no protocol)")
        registerReceiverIfNeeded(context)
        broadcastConnected()
    }

    override fun disconnectedPod(context: Context, device: BluetoothDevice) {
        Log.i(TAG, "disconnectedPod address=${device.address}")
        connected = false
        address = null
        deviceName = null
        broadcastDisconnected(context, device.address)
        if (receiverRegistered) {
            runCatching { context.unregisterReceiver(broadcastReceiver) }
            receiverRegistered = false
        }
        this.context = null
    }

    override fun queryStatus() {
        // No protocol to query; just re-emit current state
        if (connected) {
            broadcastConnected()
        }
    }

    override fun currentStatusSnapshot(): PodStatusSnapshot = PodStatusSnapshot(
        battery = null,
        anc = 1,
        transparencyVocalEnhancement = false,
        address = address,
        deviceName = deviceName,
        connected = connected,
        connecting = false,
    )

    private val broadcastReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: android.content.Intent?) {
            when (intent?.action) {
                OppoPodsAction.ACTION_PODS_UI_INIT -> {
                    markAppUiActive()
                    if (connected) broadcastConnected()
                }
                OppoPodsAction.ACTION_PODS_UI_CLOSED -> {
                    appUiActive = false
                    appUiActiveUntilMs = 0L
                }
                OppoPodsAction.ACTION_REFRESH_STATUS -> {
                    if (connected) broadcastConnected()
                }
            }
        }
    }

    private fun registerReceiverIfNeeded(context: Context) {
        if (receiverRegistered) return
        context.registerReceiver(broadcastReceiver, android.content.IntentFilter().apply {
            addAction(OppoPodsAction.ACTION_PODS_UI_INIT)
            addAction(OppoPodsAction.ACTION_PODS_UI_CLOSED)
            addAction(OppoPodsAction.ACTION_REFRESH_STATUS)
        }, Context.RECEIVER_EXPORTED)
        receiverRegistered = true
    }

    private fun broadcastConnected() {
        val ctx = context ?: return
        sendBroadcast(OppoPodsAction.ACTION_PODS_CONNECTION_STATE_CHANGED) {
            address?.let { putExtra("address", it) }
            deviceName?.let { putExtra("device_name", it) }
            putExtra("state", "connected")
        }
        sendBroadcast(OppoPodsAction.ACTION_PODS_CONNECTED) {
            address?.let { putExtra("address", it) }
            deviceName?.let { putExtra("device_name", it) }
        }
        // Clear any stale battery from a previously connected protocol pod (e.g. QCY). This
        // pod has no battery protocol, so the app must not keep showing the old earphone's
        // battery. Broadcast an all-zero/not-connected BatteryParams to reset batteryParams.
        sendBroadcast(OppoPodsAction.ACTION_PODS_BATTERY_CHANGED) {
            putExtra("address", address)
            putExtra("left_battery", 0); putExtra("left_connected", false)
            putExtra("right_battery", 0); putExtra("right_connected", false)
            putExtra("case_battery", 0); putExtra("case_connected", false)
        }
        // Also broadcast the same reset to system processes (com.android.settings /
        // com.milink.service) so the bluetooth detail page drops the previous device's
        // battery too — sendBroadcast above is gated on app UI active and targets only the
        // module app, but the settings hook listens in its own process.
        val ctx2 = context ?: return
        listOf("com.android.settings", "com.milink.service", "com.xiaomi.bluetooth").forEach { pkg ->
            runCatching {
                android.content.Intent(OppoPodsAction.ACTION_PODS_BATTERY_CHANGED).apply {
                    putExtra("address", address)
                    putExtra("left_battery", 0); putExtra("left_connected", false)
                    putExtra("right_battery", 0); putExtra("right_connected", false)
                    putExtra("case_battery", 0); putExtra("case_connected", false)
                    setPackage(pkg)
                    ctx2.sendBroadcast(this)
                }
            }
        }
    }

    private fun broadcastDisconnected(ctx: Context, deviceAddress: String) {
        sendBroadcast(OppoPodsAction.ACTION_PODS_CONNECTION_STATE_CHANGED) {
            putExtra("address", deviceAddress)
            putExtra("state", "disconnected")
        }
        sendBroadcast(OppoPodsAction.ACTION_PODS_DISCONNECTED) {
            putExtra("address", deviceAddress)
        }
    }

    private fun sendBroadcast(action: String, fill: android.content.Intent.() -> Unit = {}) {
        val ctx = context ?: return
        if (!isAppUiActive()) return
        android.content.Intent(action).apply {
            fill()
            this.`package` = BuildConfig.APPLICATION_ID
            addFlags(android.content.Intent.FLAG_RECEIVER_FOREGROUND)
            ctx.sendBroadcast(this)
        }
    }

    private fun markAppUiActive() {
        appUiActive = true
        appUiActiveUntilMs = SystemClock.elapsedRealtime() + 75_000L
    }

    private fun isAppUiActive(): Boolean {
        if (!appUiActive) return false
        if (SystemClock.elapsedRealtime() <= appUiActiveUntilMs) return true
        appUiActive = false
        appUiActiveUntilMs = 0L
        return false
    }
}
