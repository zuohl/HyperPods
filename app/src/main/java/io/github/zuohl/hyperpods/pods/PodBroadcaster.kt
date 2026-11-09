package io.github.zuohl.hyperpods.pods

import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.Intent
import io.github.zuohl.hyperpods.BuildConfig
import io.github.zuohl.hyperpods.utils.miuiStrongToast.MiuiStrongToastUtil
import io.github.zuohl.hyperpods.utils.miuiStrongToast.data.BatteryParams
import io.github.zuohl.hyperpods.utils.miuiStrongToast.data.NotificationSettings
import io.github.zuohl.hyperpods.utils.miuiStrongToast.data.OppoPodsAction
import io.github.zuohl.hyperpods.utils.miuiStrongToast.data.putBatteryStatus

/**
 * Shared cross-process broadcast plumbing + super island / persistent notification for the
 * per-brand controllers (OPPO `RfcommController`, QCY `QcyController`, vivo `VivoController`).
 *
 * Every controller used to duplicate the same "app broadcast + system-process broadcast"
 * scaffolding (and the battery payload), so bug fixes had to be applied in all three files.
 * Centralizing the mechanics here keeps them in one place; per-brand behaviour (app-UI gating,
 * feature toggles, brand-specific action extras) stays in the controllers.
 */
object PodBroadcaster {

    /** System processes that render / inject pod status (MiLink panel, mi-bt toast, Settings). */
    private val SYSTEM_PACKAGES = listOf("com.milink.service", "com.xiaomi.bluetooth", "com.android.settings")

    // ------------------------------------------------------------------
    // Primitives
    // ------------------------------------------------------------------

    /** Foreground broadcast to the module app process (package-targeted). */
    fun sendToApp(context: Context, action: String, fill: Intent.() -> Unit = {}) {
        Intent(action).apply {
            fill()
            `package` = BuildConfig.APPLICATION_ID
            addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            context.sendBroadcast(this)
        }
    }

    /**
     * Foreground broadcast to each system process that needs pod status, carrying [address]
     * (+ [deviceName] when known) so receivers can key per-address state on it.
     */
    fun sendToSystem(
        context: Context,
        action: String,
        address: String?,
        deviceName: String? = null,
        fill: Intent.() -> Unit = {},
    ) {
        SYSTEM_PACKAGES.forEach { pkg ->
            runCatching {
                Intent(action).apply {
                    address?.let { putExtra("address", it) }
                    deviceName?.let { putExtra("device_name", it) }
                    fill()
                    setPackage(pkg)
                    addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                    context.sendBroadcast(this)
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Typed helpers (app + system)
    // ------------------------------------------------------------------

    fun sendConnected(context: Context, address: String?, deviceName: String?) {
        sendToApp(context, OppoPodsAction.ACTION_PODS_CONNECTED) {
            address?.let { putExtra("address", it) }
            deviceName?.let { putExtra("device_name", it) }
        }
        sendToSystem(context, OppoPodsAction.ACTION_PODS_CONNECTED, address, deviceName)
    }

    fun sendBattery(context: Context, address: String?, battery: BatteryParams, deviceName: String? = null) {
        sendToApp(context, OppoPodsAction.ACTION_PODS_BATTERY_CHANGED) {
            address?.let { putExtra("address", it) }
            putBatteryStatus(battery)
        }
        sendToSystem(context, OppoPodsAction.ACTION_PODS_BATTERY_CHANGED, address, deviceName) {
            putBatteryStatus(battery)
        }
    }

    fun sendAnc(context: Context, address: String?, anc: Int, deviceName: String? = null) {
        sendToApp(context, OppoPodsAction.ACTION_PODS_ANC_CHANGED) {
            address?.let { putExtra("address", it) }
            putExtra("status", anc)
        }
        sendToSystem(context, OppoPodsAction.ACTION_PODS_ANC_CHANGED, address, deviceName) {
            putExtra("status", anc)
        }
    }

    fun sendDisconnected(context: Context, address: String) {
        sendToApp(context, OppoPodsAction.ACTION_PODS_DISCONNECTED) {
            putExtra("address", address)
        }
        sendToSystem(context, OppoPodsAction.ACTION_PODS_DISCONNECTED, address)
    }

    // ------------------------------------------------------------------
    // Super island + persistent notification
    // ------------------------------------------------------------------

    /** Battery super island (status-bar overlay), fired on the first battery of a connection. */
    fun showConnectionSuperIsland(
        context: Context,
        battery: BatteryParams,
        settings: NotificationSettings,
        deviceName: String? = null,
        address: String? = null
    ) {
        MiuiStrongToastUtil.showPodsBatteryToastByMiuiBt(context, battery, settings, deviceName, address)
    }

    /** Persistent connection notification shown while the earphone is connected. */
    fun showPersistentNotification(context: Context, battery: BatteryParams, device: BluetoothDevice) {
        MiuiStrongToastUtil.showPodsNotificationByMiuiBt(context, battery, device)
    }
}