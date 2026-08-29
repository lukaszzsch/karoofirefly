package io.github.derstrassi.karoofirefly.ble

import io.github.derstrassi.karoofirefly.data.LightModeOption
import io.github.derstrassi.karoofirefly.data.LightModeProvider

object MagicshineProtocol {

    const val SERVICE_UUID = "0000FFE1-0000-1000-8000-00805f9b34fb"
    const val CHARACTERISTIC_UUID = "0000FFE0-0000-1000-8000-00805f9b34fb"

    val SUPPORTED_NAME_PREFIXES = setOf("M1-", "M2-", "M3-")

    const val MODE_STEADY = 1
    const val MODE_SLOW_FLASH = 2
    const val MODE_FAST_FLASH = 3
    const val MODE_SOS = 4

    private const val FLAG_SAVE: Byte = 0xBB.toByte()

    fun buildBrightCommand(
        presetIndex: Int,
        channel: Int,
        model: Int,
        bright: Int,
    ): ByteArray {
        val content = ByteArray(14)
        content[0] = presetIndex.toByte()
        val offset = channel * 3 + 1
        content[offset] = 0x01
        content[offset + 1] = model.toByte()
        content[offset + 2] = bright.toByte()
        content[13] = FLAG_SAVE
        return buildFrame(0xA2.toByte(), 0x01, content)
    }

    fun buildOffCommand(channel: Int = 0): ByteArray {
        val content = ByteArray(14)
        content[0] = 0x01
        val offset = channel * 3 + 1
        content[offset] = 0x01
        content[offset + 1] = 0x01
        content[offset + 2] = 0x00
        content[13] = FLAG_SAVE
        return buildFrame(0xA2.toByte(), 0x01, content)
    }

    /**
     * Turns off multiple channels in a single frame. Used for the "OFF" mode so that
     * both the high-beam (channel 1) and low-beam (channel 0, unverified hypothesis —
     * see MagicshineDeviceConfig) outputs are switched off together.
     */
    fun buildOffCommand(channels: Set<Int>): ByteArray {
        val content = ByteArray(14)
        content[0] = 0x01
        for (channel in channels) {
            val offset = channel * 3 + 1
            content[offset] = 0x01
            content[offset + 1] = 0x01
            content[offset + 2] = 0x00
        }
        content[13] = FLAG_SAVE
        return buildFrame(0xA2.toByte(), 0x01, content)
    }

    fun buildModule2Frame(modeCode: Int, value: Int): ByteArray {
        val checksum = value xor (modeCode + 0x04)
        val hex = "DE14A2010200010A01%02X%02X000000000000BB%02XED".format(modeCode, value, checksum)
        return hexStringToBytes(hex)
    }

    val MODULE2_OFF = hexStringToBytes("DE14A20101010100000000000000000000BB0DED")

    private fun buildFrame(type: Byte, status: Byte, content: ByteArray): ByteArray {
        val totalLen = 6 + content.size
        val frame = ByteArray(totalLen)
        frame[0] = 0xDE.toByte()
        frame[1] = totalLen.toByte()
        frame[2] = type
        frame[3] = status
        System.arraycopy(content, 0, frame, 4, content.size)

        var cs: Byte = frame[1]
        for (i in 2 until totalLen - 2) {
            cs = (cs.toInt() xor frame[i].toInt()).toByte()
        }
        frame[totalLen - 2] = cs
        frame[totalLen - 1] = 0xED.toByte()
        return frame
    }

    fun buildQuery(type: Byte): ByteArray = buildFrame(type, 0x00, byteArrayOf())

    fun hexStringToBytes(hex: String): ByteArray =
        hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    fun bytesToHex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02X".format(it) }
}

enum class MagicshineModuleType { M1, M2 }

/**
 * Mode id format: "<TYPE>_<bright>" for high beam (channel 1, original behavior) or
 * "<TYPE>_LOW_<bright>" for low beam (channel 0).
 *
 * channel 0 = low beam, channel 1 = high beam. For M1 this was an unverified hypothesis
 * based on channel 1 being hardcoded for all pre-existing (confirmed high-beam) commands.
 * For M2 it is CONFIRMED: a captured real frame from the official Magicshine app
 * ("DE14A2010101010A000150000000000000BB56ED", logged as "STEADY LOW") decodes to this
 * exact frame layout with channel 0 set to STEADY — i.e. M2 devices accept the same
 * content-array frame as M1, not just the separate hex/modeCode format below.
 * FLASH/SOS low-beam on M2 are extrapolated from that confirmed layout (same frame,
 * different model byte) and are not independently verified yet.
 */
data class MagicshineDeviceConfig(
    val moduleType: MagicshineModuleType,
    val modes: List<LightModeOption>,
) {
    fun buildCommand(modeId: String): ByteArray? {
        if (modeId == "OFF") return when (moduleType) {
            MagicshineModuleType.M1 -> MagicshineProtocol.buildOffCommand(setOf(0, 1))
            MagicshineModuleType.M2 -> MagicshineProtocol.MODULE2_OFF
        }
        val parts = modeId.split("_")
        if (parts.size < 2) return null
        val bright = parts.last().toIntOrNull() ?: return null
        val isLowBeam = parts.size == 3 && parts[1] == "LOW"
        if (parts.size == 3 && !isLowBeam) return null
        val typeToken = parts[0]

        return when (moduleType) {
            MagicshineModuleType.M1 -> {
                val model = when (typeToken) {
                    "STEADY" -> MagicshineProtocol.MODE_STEADY
                    "FLASH" -> MagicshineProtocol.MODE_FAST_FLASH
                    "SOS" -> MagicshineProtocol.MODE_SOS
                    else -> return null
                }
                val channel = if (isLowBeam) 0 else 1
                MagicshineProtocol.buildBrightCommand(1, channel, model, bright)
            }
            MagicshineModuleType.M2 -> {
                if (isLowBeam) {
                    // Confirmed frame layout for M2 low beam — see class doc.
                    val model = when (typeToken) {
                        "STEADY" -> MagicshineProtocol.MODE_STEADY
                        "FLASH" -> MagicshineProtocol.MODE_FAST_FLASH
                        "SOS" -> MagicshineProtocol.MODE_SOS
                        else -> return null
                    }
                    MagicshineProtocol.buildBrightCommand(1, 0, model, bright)
                } else {
                    // Existing, already-working high-beam path (hex/modeCode format).
                    val modeCode = when (typeToken) {
                        "STEADY" -> 0x01
                        "FLASH" -> 0x03
                        "SOS" -> 0x02
                        else -> return null
                    }
                    MagicshineProtocol.buildModule2Frame(modeCode, bright)
                }
            }
        }
    }

    companion object {
        private val BRIGHTNESS_STEPS = listOf(10, 25, 50, 100)

        fun forDevice(bleName: String): MagicshineDeviceConfig {
            // The digit after "M" is the device's lamp group count; only single-group
            // devices use the M1 layout. See Magicshine app, FrontBikeLightActivity:580.
            val lampGroups = bleName.getOrNull(1)?.digitToIntOrNull()
            val moduleType = if (lampGroups == 1) {
                MagicshineModuleType.M1
            } else {
                MagicshineModuleType.M2
            }

            val modes = mutableListOf(LightModeOption("OFF", "Off"))
            for (b in BRIGHTNESS_STEPS) {
                modes.add(LightModeOption("STEADY_$b", "Steady $b% (High)"))
            }
            for (b in BRIGHTNESS_STEPS) {
                modes.add(LightModeOption("FLASH_$b", "Flash $b% (High)"))
            }
            for (b in BRIGHTNESS_STEPS) {
                modes.add(LightModeOption("SOS_$b", "SOS $b% (High)"))
            }

            when (moduleType) {
                MagicshineModuleType.M1 -> {
                    // channel 0 = low beam is an unverified hypothesis for M1.
                    for (b in BRIGHTNESS_STEPS) {
                        modes.add(LightModeOption("STEADY_LOW_$b", "Steady $b% (Low) [untested]"))
                    }
                    for (b in BRIGHTNESS_STEPS) {
                        modes.add(LightModeOption("FLASH_LOW_$b", "Flash $b% (Low) [untested]"))
                    }
                    for (b in BRIGHTNESS_STEPS) {
                        modes.add(LightModeOption("SOS_LOW_$b", "SOS $b% (Low) [untested]"))
                    }
                }
                MagicshineModuleType.M2 -> {
                    // STEADY confirmed via a captured official-app frame; FLASH/SOS extrapolated.
                    for (b in BRIGHTNESS_STEPS) {
                        modes.add(LightModeOption("STEADY_LOW_$b", "Steady $b% (Low)"))
                    }
                    for (b in BRIGHTNESS_STEPS) {
                        modes.add(LightModeOption("FLASH_LOW_$b", "Flash $b% (Low) [untested]"))
                    }
                    for (b in BRIGHTNESS_STEPS) {
                        modes.add(LightModeOption("SOS_LOW_$b", "SOS $b% (Low) [untested]"))
                    }
                }
            }

            return MagicshineDeviceConfig(moduleType = moduleType, modes = modes)
        }
    }
}

class MagicshineDeviceModeProvider(private val config: MagicshineDeviceConfig) : LightModeProvider {
    override fun availableModes(): List<LightModeOption> = config.modes
}
