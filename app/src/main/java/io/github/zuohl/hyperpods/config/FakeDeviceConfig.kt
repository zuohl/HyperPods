package io.github.zuohl.hyperpods.config

import android.content.SharedPreferences
import io.github.zuohl.hyperpods.utils.miuiStrongToast.data.OppoPodsPrefsKey

/**
 * The "disguised device model" — the Xiaomi TWS device ID the module claims a
 * third-party pod is, so MIUI shows the corresponding detail-page layout / icon.
 *
 * Configurable per user: an empty [OppoPodsPrefsKey.DISGUISE_DEVICE_ID] falls
 * back to the captured default ([OppoPodsPrefsKey.DEFAULT_DISGUISE_DEVICE_ID]).
 */
object FakeDeviceConfig {
    /** New-style capability bits captured from a real HyperOS 3 TWS headset. */
    const val NEW_STYLE_FEATURE_BITS = "111111001111000110101000"

    /** Known model IDs (curated from Settings.apk HeadsetIDConstants + captured). */
    val KNOWN_MODEL_IDS: List<String> = listOf(
        "01011604", // captured default (user's HyperOS 3 TWS)
        "02010400", // newer family
        "02010100",
        "01011904",
        "01011903",
        "01011803",
        "01011704",
        "01011703",
        "01011606",
        "01011607",
        "01011004",
        "01011001",
        "01010907",
        "01010904",
        "01010903",
        "01010902",
        "01010901",
        "01010707",
        "01010705",
        "01010703",
        "01010607",
        "01010606",
        "01010605",
        "01010600",
        "01010403",
        "01010402",
    )

    fun deviceId(prefs: SharedPreferences): String {
        val configured = prefs.getString(OppoPodsPrefsKey.DISGUISE_DEVICE_ID, null)
        if (!configured.isNullOrBlank()) return configured
        return prefs.getString(
            OppoPodsPrefsKey.DISGUISE_SUPPORT,
            OppoPodsPrefsKey.DEFAULT_DISGUISE_DEVICE_ID
        )?.substringBefore(',')?.takeIf { it.isNotBlank() }
            ?: OppoPodsPrefsKey.DEFAULT_DISGUISE_DEVICE_ID
    }

    fun support(prefs: SharedPreferences): String {
        val configured = prefs.getString(OppoPodsPrefsKey.DISGUISE_SUPPORT, null)
        if (!configured.isNullOrBlank()) return configured
        return "${deviceId(prefs)},$NEW_STYLE_FEATURE_BITS"
    }
}
