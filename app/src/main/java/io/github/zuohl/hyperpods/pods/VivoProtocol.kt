package io.github.zuohl.hyperpods.pods

/**
 * vivo / iQOO TWS GAIA-shaped protocol over Bluetooth Classic RFCOMM.
 *
 * Ported from the public HyperEars / TWS-Pods-PC protocol (GPL-3.0).
 * Frame: `FF [version] [flags] [payloadLen] [vendor_hi] [vendor_lo] [cmd_hi] [cmd_lo] [payload...]`.
 * Battery: query 0x0207, report 0x8207 -> payload [0, left%, right%, case%, chargingBits].
 * Noise: query 0x0230, set 0x0130, report 0x8230 / ack 0x8130 -> payload [0, mode, ...].
 */
object VivoProtocol {
    const val VIVO_VENDOR = 0x001B
    const val GAIA_VENDOR = 0x000A

    const val SET_NOISE_MODE = 0x0130
    const val QUERY_NOISE_MODE = 0x0230
    const val ACK_NOISE_MODE = 0x8130
    const val REPORT_NOISE_MODE = 0x8230
    const val QUERY_BATTERY = 0x0207
    const val REPORT_BATTERY = 0x8207
    const val HANDSHAKE = 0x0300
    const val HANDSHAKE_RESPONSE = 0x8300

    private const val PREAMBLE = 0xFF
    private const val COMMAND_BYTES = 4

    /** Noise modes in wire order. */
    object NoiseMode {
        const val ANC = 0
        const val OFF = 1
        const val TRANSPARENCY = 2
    }

    /** Air3 Pro captured profile (gaia v3, set payload `mode 04 00`). */
    private const val AIR3_GAIA_VERSION = 3
    private val AIR3_NOISE_SET_SUFFIX = byteArrayOf(4, 0)
    /** Family default (gaia v4) fallback. */
    private const val FAMILY_GAIA_VERSION = 4
    private val FAMILY_NOISE_SET_SUFFIX = byteArrayOf(3, 1)

    data class BatteryState(
        val leftPercent: Int?,
        val rightPercent: Int?,
        val casePercent: Int?,
        val leftCharging: Boolean,
        val rightCharging: Boolean,
        val caseCharging: Boolean,
    )

    data class NoiseState(
        val mode: Int,
    )

    data class Frame(
        val version: Int,
        val flags: Int,
        val vendor: Int,
        val command: Int,
        val payload: ByteArray,
        val raw: ByteArray,
    )

    fun handshake(): ByteArray = frame(version = 4, vendor = GAIA_VENDOR, command = HANDSHAKE)

    fun queryBattery(): ByteArray = frame(version = 4, vendor = VIVO_VENDOR, command = QUERY_BATTERY)

    fun queryNoiseMode(): ByteArray = frame(version = FAMILY_GAIA_VERSION, vendor = VIVO_VENDOR, command = QUERY_NOISE_MODE)

    fun setNoiseMode(mode: Int): ByteArray = frame(
        version = AIR3_GAIA_VERSION,
        vendor = VIVO_VENDOR,
        command = SET_NOISE_MODE,
        payload = byteArrayOf(mode.toByte()) + AIR3_NOISE_SET_SUFFIX,
    )

    fun frame(version: Int, vendor: Int, command: Int, payload: ByteArray = byteArrayOf()): ByteArray {
        require(version in 0..255)
        require(vendor in 0..0xFFFF)
        require(command in 0..0xFFFF)
        require(payload.size <= 254) { "Compact GAIA payload is limited to 254 bytes" }
        return ByteArray(4 + COMMAND_BYTES + payload.size).apply {
            this[0] = PREAMBLE.toByte()
            this[1] = version.toByte()
            this[2] = 0
            this[3] = payload.size.toByte()
            writeShort(vendor, 4)
            writeShort(command, 6)
            payload.copyInto(this, destinationOffset = 8)
        }
    }

    fun parseBatteryState(frame: Frame): BatteryState? {
        if (frame.vendor != VIVO_VENDOR || frame.command != REPORT_BATTERY) return null
        if (frame.payload.size < 5 || frame.payload[0].toInt() and 0xFF != 0) return null
        val charging = frame.payload[4].toInt() and 0xFF
        return BatteryState(
            leftPercent = pct(frame.payload[1]),
            rightPercent = pct(frame.payload[2]),
            casePercent = pct(frame.payload[3]),
            leftCharging = charging and 0x01 != 0,
            rightCharging = charging and 0x02 != 0,
            caseCharging = charging and 0x04 != 0,
        )
    }

    fun parseNoiseState(frame: Frame): NoiseState? {
        if (frame.vendor != VIVO_VENDOR) return null
        if (frame.command != ACK_NOISE_MODE && frame.command != REPORT_NOISE_MODE) return null
        if (frame.payload.size < 2 || frame.payload[0].toInt() and 0xFF != 0) return null
        val mode = frame.payload[1].toInt() and 0xFF
        return NoiseState(mode = mode)
    }

    private fun pct(b: Byte): Int? {
        val v = b.toInt() and 0xFF
        return if (v in 0..100) v else null
    }

    private fun ByteArray.writeShort(value: Int, offset: Int) {
        this[offset] = (value shr 8).toByte()
        this[offset + 1] = value.toByte()
    }

    private fun ByteArray.readShort(offset: Int): Int =
        ((this[offset].toInt() and 0xFF) shl 8) or (this[offset + 1].toInt() and 0xFF)
}
