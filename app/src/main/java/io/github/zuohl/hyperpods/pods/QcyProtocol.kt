package io.github.zuohl.hyperpods.pods

import io.github.zuohl.hyperpods.utils.miuiStrongToast.data.BatteryParams
import io.github.zuohl.hyperpods.utils.miuiStrongToast.data.PodParams
import java.util.UUID

object QcyUuids {
    val MAIN_SERVICE: UUID = UUID.fromString("0000a001-0000-1000-8000-00805f9b34fb")
    val COMMAND: UUID = UUID.fromString("00001001-0000-1000-8000-00805f9b34fb")
    val NOTIFY: UUID = UUID.fromString("00001002-0000-1000-8000-00805f9b34fb")
    val BATTERY: UUID = UUID.fromString("00000008-0000-1000-8000-00805f9b34fb")
    val VERSION: UUID = UUID.fromString("00000007-0000-1000-8000-00805f9b34fb")
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
}

object QcyCompany {
    const val ID = 0x521c
}

object QcyEqPreset {
    const val SPATIAL = 10
    const val DEFAULT = 1
    const val POP = 2
    const val BASS = 3
    const val ROCK = 4
    const val SOFT = 5
    const val CLASSICAL = 6
    const val CUSTOM = 0
    val ALL: List<Int> = listOf(SPATIAL, DEFAULT, POP, BASS, ROCK, SOFT, CLASSICAL, CUSTOM)
}

object QcyCmd {
    const val REQUEST_DATA = 0xFE
    const val LOW_LATENCY = 0x09
    const val NOISE_CANCEL_MODE = 0x0C
    const val SLEEP_MODE = 0x10
    const val EQ_PARAMS_V1 = 0x20
    const val EQ_PARAMS_V2 = 0x22
    const val LDAC = 0x23
    const val DUAL_DEVICE_CONNECTION = 0x24
    const val DYNAMIC_EQ = 0x27
    const val SPATIAL_AUDIO = 0x2D
    const val BATTERY = 0x2F
    const val ADAPTIVE_VOLUME = 0x40
}

object QcyNoiseMode {
    const val OFF = 0x00
    const val ANC = 0x01
    const val OUTDOOR = 0x02
    const val TRANSPARENCY = 0x03
}

object QcyPacketBuilder {
    fun frame(vararg commands: ByteArray): ByteArray {
        val body = commands.fold(ByteArray(0)) { acc, item -> acc + item }
        return byteArrayOf(0xFF.toByte(), body.size.toByte()) + body
    }

    fun command(cmd: Int, vararg params: Int): ByteArray {
        val payload = params.map { it.toByte() }.toByteArray()
        return byteArrayOf(cmd.toByte(), payload.size.toByte()) + payload
    }

    fun command(cmd: Int, params: ByteArray): ByteArray =
        byteArrayOf(cmd.toByte(), params.size.toByte()) + params

    fun request(cmd: Int): ByteArray = frame(command(QcyCmd.REQUEST_DATA, cmd))

    fun lowLatency(enabled: Boolean): ByteArray =
        frame(command(QcyCmd.LOW_LATENCY, if (enabled) 0x01 else 0x02))

    fun noiseMode(mode: Int): ByteArray =
        frame(command(QcyCmd.NOISE_CANCEL_MODE, mode))

    fun qcyToggle(cmd: Int, enabled: Boolean): ByteArray {
        val offValue = when (cmd) {
            QcyCmd.ADAPTIVE_VOLUME,
            QcyCmd.DYNAMIC_EQ -> 0x00
            else -> 0x02
        }
        return frame(command(cmd, if (enabled) 0x01 else offValue))
    }

    fun spatialAudio(mode: Int): ByteArray =
        frame(command(QcyCmd.SPATIAL_AUDIO, mode.coerceIn(0, 2)))

    fun eqPreset(preset: Int): ByteArray =
        frame(command(QcyCmd.EQ_PARAMS_V1, preset))

    fun parametricEqV2(preset: QcyEqCurve): ByteArray =
        frame(command(QcyCmd.EQ_PARAMS_V2, preset.toV2Payload()))
}

data class QcyEqCurve(
    val id: Int,
    val masterGain: Double,
    val bandTypes: List<Int>,
    val frequencies: List<Int>,
    val gains: List<Double>,
    val qs: List<Double>,
) {
    fun toV2Payload(): ByteArray {
        val bandCount = minOf(bandTypes.size, frequencies.size, gains.size, qs.size)
        val payload = ArrayList<Byte>(bandCount * 7 + 3)
        payload += id.toByte()
        payload += signedShortBytes((masterGain * 100).toInt())
        for (index in 0 until bandCount) {
            payload += unsignedShortBytes(frequencies[index])
            payload += signedShortBytes((gains[index] * 100).toInt())
            payload += unsignedShortBytes((qs[index] * 100).toInt())
            payload += bandTypes[index].toByte()
        }
        return payload.toByteArray()
    }

    private fun signedShortBytes(value: Int): List<Byte> {
        val normalized = value.toShort().toInt()
        return listOf((normalized and 0xFF).toByte(), ((normalized ushr 8) and 0xFF).toByte())
    }

    private fun unsignedShortBytes(value: Int): List<Byte> =
        listOf((value and 0xFF).toByte(), ((value ushr 8) and 0xFF).toByte())
}

object QcyOfficialEqCurves {
    val customFrequencies: List<Int> = listOf(31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000)

    private val curves = listOf(
        QcyEqCurve(
            id = QcyEqPreset.SPATIAL,
            masterGain = 0.0,
            bandTypes = listOf(0, 2, 2, 2, 2, 2, 3),
            frequencies = listOf(65, 250, 3500, 9000, 11000, 600, 4000),
            gains = listOf(0.0, -4.0, -7.0, -15.0, -7.0, -1.0, -3.0),
            qs = listOf(1.0, 0.7, 3.0, 3.5, 4.0, 1.0, 1.0),
        ),
        QcyEqCurve(
            id = QcyEqPreset.DEFAULT,
            masterGain = 0.0,
            bandTypes = listOf(0, 2, 2, 2, 2, 2),
            frequencies = listOf(65, 260, 3500, 9000, 12000, 500),
            gains = listOf(0.0, -5.0, -9.0, -11.0, -10.0, -1.5),
            qs = listOf(1.0, 0.8, 3.0, 3.5, 3.0, 1.0),
        ),
        QcyEqCurve(
            id = QcyEqPreset.POP,
            masterGain = -3.0,
            bandTypes = listOf(0, 2, 2, 2, 2, 2, 2, 2),
            frequencies = listOf(80, 400, 900, 3200, 3700, 9500, 11000, 200),
            gains = listOf(-6.0, 2.0, 3.0, -7.0, -4.0, -16.0, -5.0, -6.0),
            qs = listOf(0.75, 1.0, 0.8, 3.0, 2.0, 3.5, 4.0, 0.8),
        ),
        QcyEqCurve(
            id = QcyEqPreset.BASS,
            masterGain = -3.0,
            bandTypes = listOf(0, 2, 2, 2, 2, 2, 2),
            frequencies = listOf(80, 250, 400, 3200, 3700, 9500, 11000),
            gains = listOf(0.0, -1.0, -2.5, -11.0, -7.0, -12.0, -5.0),
            qs = listOf(0.75, 1.0, 0.6, 3.0, 2.0, 4.0, 4.0),
        ),
        QcyEqCurve(
            id = QcyEqPreset.ROCK,
            masterGain = -3.0,
            bandTypes = listOf(0, 2, 2, 2, 2, 2, 2, 2),
            frequencies = listOf(80, 250, 400, 3200, 3700, 9500, 11000, 200),
            gains = listOf(0.0, -1.0, -2.5, -7.0, -3.0, -15.0, -5.0, 3.0),
            qs = listOf(0.75, 1.0, 0.6, 3.0, 2.0, 4.0, 4.0, 0.75),
        ),
        QcyEqCurve(
            id = QcyEqPreset.SOFT,
            masterGain = 0.0,
            bandTypes = listOf(0, 2, 2, 2, 2, 2, 2, 2, 2),
            frequencies = listOf(80, 250, 400, 3200, 3700, 5500, 6300, 9200, 11000),
            gains = listOf(0.0, -1.0, -2.5, -7.0, -6.0, -3.0, -4.0, -16.0, -7.0),
            qs = listOf(0.75, 1.0, 0.6, 3.5, 3.0, 3.0, 4.0, 3.5, 4.0),
        ),
        QcyEqCurve(
            id = QcyEqPreset.CLASSICAL,
            masterGain = -3.0,
            bandTypes = listOf(0, 2, 2, 2, 2, 2, 2, 2),
            frequencies = listOf(80, 400, 900, 3200, 3700, 5500, 9200, 200),
            gains = listOf(0.0, -4.0, -3.0, -11.0, -3.0, 3.0, -8.0, 3.0),
            qs = listOf(0.75, 1.0, 1.0, 3.0, 2.0, 3.0, 4.2, 0.75),
        ),
    ).associateBy { it.id }

    fun byId(id: Int): QcyEqCurve? = curves[id]

    fun custom(gains: List<Int>): QcyEqCurve {
        val normalizedGains = customFrequencies.indices.map { index ->
            gains.getOrNull(index)?.coerceIn(-8, 8)?.toDouble() ?: 0.0
        }
        return QcyEqCurve(
            id = QcyEqPreset.CUSTOM,
            masterGain = 0.0,
            bandTypes = List(customFrequencies.size) { 2 },
            frequencies = customFrequencies,
            gains = normalizedGains,
            qs = List(customFrequencies.size) { 1.0 },
        )
    }
}

data class QcyEvent(
    val cmd: Int,
    val params: ByteArray,
)

object QcyPacketParser {
    fun parse(packet: ByteArray): List<QcyEvent> {
        if (packet.size < 2 || packet[0] != 0xFF.toByte()) return emptyList()
        val bodyLen = packet[1].toInt() and 0xFF
        val bodyEnd = minOf(packet.size, bodyLen + 2)
        val events = mutableListOf<QcyEvent>()
        var index = 2
        while (index + 1 < bodyEnd) {
            val cmd = packet[index].toInt() and 0xFF
            val paramLen = packet[index + 1].toInt() and 0xFF
            val start = index + 2
            val end = start + paramLen
            if (end > bodyEnd) break
            events += QcyEvent(cmd, packet.copyOfRange(start, end))
            index = end
        }
        return events
    }

    fun parseBattery(params: ByteArray): BatteryParams? {
        if (params.size < 3) return null
        return BatteryParams(
            left = parsePod(params[0]),
            right = parsePod(params[1]),
            case = parsePod(params[2]),
        )
    }

    fun parseNoiseMode(params: ByteArray): Int? {
        if (params.size != 1) return null
        return when (params[0].toInt() and 0xFF) {
            QcyNoiseMode.OFF -> 1
            QcyNoiseMode.ANC -> 2
            QcyNoiseMode.OUTDOOR -> 2
            QcyNoiseMode.TRANSPARENCY -> 3
            else -> null
        }
    }

    fun parseToggleEnabled(params: ByteArray): Boolean? {
        if (params.size != 1) return null
        return (params[0].toInt() and 0xFF) == 0x01
    }

    fun parseLowLatencyEnabled(params: ByteArray): Boolean? {
        return parseToggleEnabled(params)
    }

    fun parseSpatialAudioMode(params: ByteArray): Int? {
        if (params.size != 1) return null
        return (params[0].toInt() and 0xFF).coerceIn(0, 2)
    }

    fun parseEqPreset(params: ByteArray): Int? {
        if (params.size < 1) return null
        return (params[0].toInt() and 0xFF).takeIf { it in QcyEqPreset.ALL }
    }

    fun parseEqV2Gains(params: ByteArray): List<Int>? {
        if (params.size < 3 || (params[0].toInt() and 0xFF) != QcyEqPreset.CUSTOM) return null
        val gains = mutableListOf<Int>()
        var index = 3
        while (index + 6 < params.size && gains.size < QcyOfficialEqCurves.customFrequencies.size) {
            val gain = signedShort(params[index + 2], params[index + 3]) / 100.0
            gains += gain.toInt().coerceIn(-8, 8)
            index += 7
        }
        return gains.takeIf { it.size == QcyOfficialEqCurves.customFrequencies.size }
    }

    private fun signedShort(lo: Byte, hi: Byte): Int =
        ((hi.toInt() shl 8) or (lo.toInt() and 0xFF)).toShort().toInt()

    private fun parsePod(raw: Byte): PodParams {
        val value = raw.toInt() and 0xFF
        return PodParams(
            battery = value and 0x7F,
            isCharging = (value and 0x80) != 0,
            isConnected = value != 0,
            rawStatus = value,
        )
    }
}

data class QcyAdvertisementStatus(
    val controlAddress: String?,
    val otherAddress: String?,
    val battery: BatteryParams,
)

object QcyAdvertisementParser {
    fun parse(manufacturerData: ByteArray): QcyAdvertisementStatus? {
        if (manufacturerData.size < 8) return null
        val battery = BatteryParams(
            left = parsePod(manufacturerData[5]),
            right = parsePod(manufacturerData[6]),
            case = parsePod(manufacturerData[7]),
        )
        return QcyAdvertisementStatus(
            controlAddress = parseScrambledAddress(manufacturerData, 11, intArrayOf(1, 0, 2, 5, 4, 3)),
            otherAddress = parseScrambledAddress(manufacturerData, 18, intArrayOf(1, 0, 2, 5, 4, 3))
                ?.takeUnless { it == "00:00:00:00:00:00" },
            battery = battery,
        )
    }

   private fun parsePod(raw: Byte): PodParams {
       val value = raw.toInt() and 0xFF
       return PodParams(
           battery = (value and 0x7F).coerceIn(0, 100),
           isCharging = (value and 0x80) != 0,
           isConnected = value != 0,
           rawStatus = value,
       )
   }

    private fun parseScrambledAddress(data: ByteArray, offset: Int, order: IntArray): String? {
        if (data.size < offset + 6) return null
        return order.joinToString(":") { index ->
            "%02X".format(data[offset + index].toInt() and 0xFF)
        }
    }
}
