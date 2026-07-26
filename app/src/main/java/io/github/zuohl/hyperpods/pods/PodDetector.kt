package io.github.zuohl.hyperpods.pods

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import io.github.zuohl.hyperpods.config.ConfigManager

object PodDetector {
    private val qcyNameKeywords = listOf(
        "qcy",
        "crossky",
        "crossky c50s",
        "crossky c50s-app",
    )

    private val vivoNameKeywords = listOf(
        "vivo tws",
        "vivo tq",
        "iqoo tws",
        "iqoo tq",
    )

    /**
     * Detect the earphone brand from the Bluetooth device name.
     * Also checks manually bound MAC addresses from [ConfigManager] - when a
     * MAC is manually bound, the device is treated as supported even if the
     * name doesn't match any keyword. This enables status bar icon and system
     * popup for earphones without a known protocol.
     * Returns null when the device is not recognized as a supported pod.
     */
    @SuppressLint("MissingPermission")
    fun detectBrand(device: BluetoothDevice?): PodBrand? {
        if (device == null) return null
        // Check manual MAC bindings first - these override name-based detection
        val address = runCatching { device.address?.uppercase() }.getOrNull()
        if (address != null && isManuallyBound(address)) {
            // Try name-based brand detection for the right controller, fall back to DEFAULT
            val nameBrand = detectBrandByName(device)
            return nameBrand ?: PodBrand.DEFAULT
        }
        return detectBrandByName(device)
    }

    @SuppressLint("MissingPermission")
    private fun detectBrandByName(device: BluetoothDevice): PodBrand? {
        val name = device.name?.lowercase().orEmpty()
        if (name.isBlank()) return null
        if (qcyNameKeywords.any { it in name }) return PodBrand.QCY
        if (vivoNameKeywords.any { it in name }) return PodBrand.VIVO
        return null
    }

    /**
     * Check if a MAC address (uppercase, colon-separated) is in the manual bindings.
     * Accepts both "XX:XX:XX:XX:XX:XX" and "XXXXXXXXXXXX" formats for comparison.
     */
    private fun isManuallyBound(address: String): Boolean {
        val bindings = runCatching { ConfigManager.manualMacBindings() }.getOrDefault(emptySet())
        if (bindings.isEmpty()) return false
        val normalized = address.uppercase().replace(":", "")
        return bindings.any { binding ->
            val normalizedBinding = binding.uppercase().replace(":", "")
            normalizedBinding == normalized
        }
    }

    @SuppressLint("MissingPermission")
    fun isSupportedPod(device: BluetoothDevice?): Boolean {
        return detectBrand(device) != null
    }

    @SuppressLint("MissingPermission")
    fun isQcyAppDevice(device: BluetoothDevice?): Boolean {
        val name = buildList {
            device?.name?.let(::add)
        }.joinToString(" ").lowercase()
        return "qcy" in name && "-app" in name
    }
}
