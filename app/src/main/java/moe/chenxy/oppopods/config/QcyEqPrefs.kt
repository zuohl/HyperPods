package moe.chenxy.oppopods.config

import android.content.SharedPreferences
import io.github.libxposed.service.XposedService
import java.util.Locale

data class QcyEqConfig(
    val preset: Int,
    val gains: IntArray,
)

object QcyEqPrefs {
    private const val BAND_COUNT = 10
    private const val CUSTOM_PRESET = 0
    private const val DEFAULT_PRESET = 1
    private const val KEY_PREFIX = "qcy_eq"
    private const val KEY_PRESET_SUFFIX = "preset"
    private const val KEY_GAINS_SUFFIX = "gains"

    fun read(prefs: SharedPreferences, address: String?): QcyEqConfig? {
        val key = addressKey(address) ?: return null
        if (!prefs.contains(key(key, KEY_PRESET_SUFFIX)) && !prefs.contains(key(key, KEY_GAINS_SUFFIX))) {
            return null
        }
        return QcyEqConfig(
            preset = prefs.getInt(key(key, KEY_PRESET_SUFFIX), DEFAULT_PRESET),
            gains = parseGains(prefs.getString(key(key, KEY_GAINS_SUFFIX), null)).toIntArray(),
        )
    }

    fun readOrDefault(prefs: SharedPreferences, address: String?): QcyEqConfig =
        read(prefs, address) ?: QcyEqConfig(DEFAULT_PRESET, IntArray(BAND_COUNT))

    fun save(
        prefs: SharedPreferences,
        service: XposedService?,
        address: String?,
        preset: Int,
        gains: IntArray,
    ): QcyEqConfig? {
        val saved = save(prefs, address, preset, gains) ?: return null
        service?.getRemotePreferences(ConfigManager.PREFS_NAME)?.let {
            save(it, address, preset, gains)
        }
        return saved
    }

    fun save(
        prefs: SharedPreferences,
        address: String?,
        preset: Int,
        gains: IntArray,
    ): QcyEqConfig? {
        val key = addressKey(address) ?: return null
        val normalized = QcyEqConfig(
            preset = preset,
            gains = normalizeGains(gains).toIntArray(),
        )
        prefs.edit()
            .putInt(key(key, KEY_PRESET_SUFFIX), normalized.preset)
            .putString(key(key, KEY_GAINS_SUFFIX), normalized.gains.joinToString(","))
            .commit()
        return normalized
    }

    fun customDefault(): QcyEqConfig =
        QcyEqConfig(CUSTOM_PRESET, IntArray(BAND_COUNT))

    private fun addressKey(address: String?): String? {
        val clean = address
            ?.trim()
            ?.uppercase(Locale.ROOT)
            ?.replace(":", "")
            ?.takeIf { it.isNotBlank() }
            ?: return null
        return clean
    }

    private fun key(addressKey: String, suffix: String): String =
        "${KEY_PREFIX}_${addressKey}_$suffix"

    private fun parseGains(raw: String?): List<Int> {
        val parsed = raw
            ?.split(",")
            ?.mapNotNull { it.trim().toIntOrNull() }
            .orEmpty()
        return normalizeGains(parsed.toIntArray())
    }

    private fun normalizeGains(gains: IntArray): List<Int> =
        List(BAND_COUNT) { index ->
            gains.getOrNull(index)?.coerceIn(-8, 8) ?: 0
        }
}
