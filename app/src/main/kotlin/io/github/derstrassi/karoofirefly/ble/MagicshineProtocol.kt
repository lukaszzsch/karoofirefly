package io.github.derstrassi.karoofirefly.ble

import io.github.derstrassi.karoofirefly.data.LightModeOption
import io.github.derstrassi.karoofirefly.data.LightModeProvider

object MagicshineProtocol {

    const val SERVICE_UUID = "0000FFE1-0000-1000-8000-00805f9b34fb"
    const val CHARACTERISTIC_UUID = "0000FFE0-0000-1000-8000-00805f9b34fb"

    val SUPPORTED_NAME_PREFIXES = setOf("M1-", "M2-", "M3-")

    // M1 model byte mapping — used only for M1's original (author-confirmed) high-beam
    // commands, and as an unverified guess for M1 low-beam. Left untouched by the M2
    // findings below since M1 hardware may use a different mapping.
    const val MODE_STEADY = 1
    const val MODE_SLOW_FLASH = 2
    const val MODE_FAST_FLASH = 3
    const val MODE_SOS = 4

    // M2 model byte mapping for the content-array frame (buildBrightCommand), confirmed
    // by on-device testing via the in-app Raw Test tool, for both channel 0 (low beam)
    // and channel 1 (high beam). Distinct from the MODE_* constants above — do not merge,
    // M1 and M2 firmware do not agree on these values (M1's SOS=4 glitches on M2).
    const val M2_MODE_STEADY = 1
    const val M2_MODE_SOS = 2
    const val M2_MODE_FAST_FLASH = 3
    const val M2_MODE_FLASH = 4
    const val M2_MODE_SLOW_FLASH = 5
    const val M2_MODE_PULSE = 6

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
     * both the high-beam (channel 1) and low-beam (channel 0) outputs are switched off
     * together.
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
 * Mode id format: "<TYPE>_<bright>" for high beam (channel 1) or "<TYPE>_LOW_<bright>"
 * for low beam (channel 0). TYPE tokens differ by module type — see buildCommand().
 *
 * M1: TYPE ∈ {STEADY, FLASH, SOS}. High beam is the original, author-confirmed mapping.
 * Low beam (channel 0) is an unverified hypothesis, untested on real M1 hardware.
 *
 * M2: TYPE ∈ {STEADY, SOS, FASTFLASH, FLASH, SLOWFLASH, PULSE}. Both high and low beam
 * use the content-array frame (buildBrightCommand) with the M2_MODE_* byte values,
 * confirmed by on-device testing via the in-app Raw Test tool for both channels.
 */
data class MagicshineDeviceConfig(
    val moduleType: MagicshineModuleType,
    val modes: List<LightModeOption>,
) {
    fun buildCommand(modeId: String): ByteArray? {
        if (modeId == "OFF") return when (moduleType) {
            MagicshineModuleType.M1 -> MagicshineProtocol.buildOffCommand(setOf(0, 1))
            // Reverted to the original, proven-safe M2 off frame — the unified
            // content-array off (setting both channels to steady/0%) was briefly
            // flashing high beam before settling into a newly-selected low mode.
            MagicshineModuleType.M2 -> MagicshineProtocol.MODULE2_OFF
        }
        // Debug/hunting escape hatch: "RAW_<channel>_<model>_<bright>" sends the
        // content-array frame directly with whatever values are given, bypassing the
        // named-mode parsing below entirely. Lets us search for unknown model bytes
        // live from the UI without rebuilding the app for every guess.
        if (modeId.startsWith("RAW_")) {
            val rawParts = modeId.removePrefix("RAW_").split("_")
            if (rawParts.size != 3) return null
            val channel = rawParts[0].toIntOrNull() ?: return null
            val model = rawParts[1].toIntOrNull() ?: return null
            val bright = rawParts[2].toIntOrNull() ?: return null
            return MagicshineProtocol.buildBrightCommand(1, channel, model, bright)
        }
        val parts = modeId.split("_")
        if (parts.size < 2) return null
        val bright = parts.last().toIntOrNull() ?: return null
        val isLowBeam = parts.size == 3 && parts[1] == "LOW"
        if (parts.size == 3 && !isLowBeam) return null
        val typeToken = parts[0]
        val channel = if (isLowBeam) 0 else 1

        return when (moduleType) {
            MagicshineModuleType.M1 -> {
                val model = when (typeToken) {
                    "STEADY" -> MagicshineProtocol.MODE_STEADY
                    "FLASH" -> MagicshineProtocol.MODE_FAST_FLASH
                    "SOS" -> MagicshineProtocol.MODE_SOS
                    else -> return null
                }
                MagicshineProtocol.buildBrightCommand(1, channel, model, bright)
            }
            MagicshineModuleType.M2 -> {
                val model = when (typeToken) {
                    "STEADY" -> MagicshineProtocol.M2_MODE_STEADY
                    "SOS" -> MagicshineProtocol.M2_MODE_SOS
                    "FASTFLASH" -> MagicshineProtocol.M2_MODE_FAST_FLASH
                    "FLASH" -> MagicshineProtocol.M2_MODE_FLASH
                    "SLOWFLASH" -> MagicshineProtocol.M2_MODE_SLOW_FLASH
                    "PULSE" -> MagicshineProtocol.M2_MODE_PULSE
                    else -> return null
                }
                MagicshineProtocol.buildBrightCommand(1, channel, model, bright)
            }
        }
    }

    companion object {
        private val BRIGHTNESS_STEPS = listOf(10, 25, 50, 100)
        private val STEADY_BRIGHTNESS_STEPS = listOf(10, 25, 50, 75, 100)

        // token (used in mode id) to Polish display label, as specified by the user.
        private val M2_TYPES = listOf(
            "STEADY" to "Stały",
            "SOS" to "SOS",
            "FASTFLASH" to "Szybki błysk",
            "FLASH" to "Błysk",
            "SLOWFLASH" to "Wolny błysk",
            "PULSE" to "Puls",
        )

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

            when (moduleType) {
                MagicshineModuleType.M1 -> {
                    for (b in STEADY_BRIGHTNESS_STEPS) {
                        modes.add(LightModeOption("STEADY_$b", "Steady $b% (High)"))
                    }
                    for (b in BRIGHTNESS_STEPS) {
                        modes.add(LightModeOption("FLASH_$b", "Flash $b% (High)"))
                    }
                    for (b in BRIGHTNESS_STEPS) {
                        modes.add(LightModeOption("SOS_$b", "SOS $b% (High)"))
                    }
                    // channel 0 = low beam is an unverified hypothesis for M1.
                    for (b in STEADY_BRIGHTNESS_STEPS) {
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
                    // Full 6-pattern × 2-beam × 5-brightness set, confirmed on real
                    // hardware via the in-app Raw Test tool.
                    for ((token, label) in M2_TYPES) {
                        for (b in STEADY_BRIGHTNESS_STEPS) {
                            modes.add(LightModeOption("${token}_$b", "$label $b% (High)"))
                        }
                    }
                    for ((token, label) in M2_TYPES) {
                        for (b in STEADY_BRIGHTNESS_STEPS) {
                            modes.add(LightModeOption("${token}_LOW_$b", "$label $b% (Low)"))
                        }
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
