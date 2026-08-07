package io.github.zuohl.hyperpods.pods

import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.SharedPreferences
import io.github.zuohl.hyperpods.utils.miuiStrongToast.data.BatteryParams

/**
 * Supported earphone brands. Each brand maps to a [Pod] implementation that
 * owns its own BLE/RFCOMM protocol, detection rules, and capabilities.
 */
enum class PodBrand {
    OPPO,
    QCY,
    VIVO,
    GENERIC,
}

/**
 * Brand-agnostic status snapshot consumed by the HyperOS hook layer and UI.
 *
 * [anc] follows the unified internal ANC level convention:
 * 1=Off, 2=Noise Cancellation, 3=Transparency, 4=Adaptive,
 * 5=Smart, 6=Light, 7=Medium, 8=Deep.
 */
data class PodStatusSnapshot(
    val battery: BatteryParams?,
    val anc: Int,
    val address: String?,
    val deviceName: String?,
    val connected: Boolean,
    val connecting: Boolean,
)

/**
 * Contract for a brand-specific earphone controller.
 *
 * Each implementation owns its transport (GATT, RFCOMM, advertisement scan),
 * protocol packets, and state machine. The [PodController] router selects the
 * active implementation based on [PodDetector] at connection time.
 */
interface Pod {
    val brand: PodBrand

    fun connectPod(context: Context, device: BluetoothDevice, prefs: SharedPreferences, appRequested: Boolean = false)

    fun disconnectedPod(context: Context, device: BluetoothDevice)

    fun queryStatus()

    fun currentStatusSnapshot(): PodStatusSnapshot
}
