package com.punchev.dashboard.bluetooth

data class EvTelemetry(
    val socPercent: Float = 0f,
    val packVoltage: Float = 0f,
    val packCurrent: Float = 0f,
    val powerKw: Float = 0f,
    val batteryTemp: Int = 0,
    val auxVoltage: Float = 0f,
    val isCharging: Boolean = false,
    val isRegen: Boolean = false
)

object TataEvPids {
    // ELM327 initialization commands for Tata Ziptron CAN-bus
    val INIT_COMMANDS = listOf(
        "ATZ",       // Reset
        "ATE0",      // Echo off
        "ATL0",      // Linefeeds off
        "ATS0",      // Spaces off
        "ATH1",      // Headers ON (crucial for CAN response filtering)
        "ATSP6",     // ISO 15765-4 CAN (11 bit ID, 500 kbaud)
        "ATSH 7E4"   // Set CAN transmit header to 7E4 (Tata BMS/VCU)
    )

    // Mode 22 Extended Diagnostic PIDs for Tata Ziptron
    const val PID_SOC = "22 01 01"
    const val PID_PACK_VOLTAGE = "22 01 05"
    const val PID_PACK_CURRENT = "22 01 06"
    const val PID_BATTERY_TEMP = "22 01 08"
    const val CMD_AUX_VOLTAGE = "ATRV"

    /**
     * Parse 12V Auxiliary Battery Voltage from ELM327 "ATRV" response (e.g., "12.8V" -> 12.8)
     */
    fun parseAuxVoltage(raw: String): Float {
        return try {
            val cleaned = raw.replace("V", "", ignoreCase = true).trim()
            cleaned.toFloatOrNull() ?: 12.6f
        } catch (_: Exception) {
            12.6f
        }
    }

    /**
     * Clean raw hex response from ELM327 (strip prompt '>', whitespace, headers)
     */
    private fun extractHexBytes(raw: String): List<Int> {
        val cleaned = raw.replace(">", "")
            .replace("\r", "")
            .replace("\n", "")
            .replace(" ", "")
            .trim()

        val bytes = mutableListOf<Int>()
        var i = 0
        while (i + 1 < cleaned.length) {
            val byteStr = cleaned.substring(i, i + 2)
            byteStr.toIntOrNull(16)?.let { bytes.add(it) }
            i += 2
        }
        return bytes
    }

    /**
     * Parse State of Charge (SoC %) from Mode 22 01 01
     * Tata BMS response: 62 01 01 [ByteA] [ByteB] ...
     */
    fun parseSoC(raw: String): Float {
        return try {
            val bytes = extractHexBytes(raw)
            val index = bytes.indexOfSubList(listOf(0x62, 0x01, 0x01))
            if (index != -1 && index + 4 < bytes.size) {
                val byteA = bytes[index + 3]
                val byteB = bytes[index + 4]
                val value = (byteA * 256 + byteB) / 100f
                value.coerceIn(0f, 100f)
            } else if (bytes.size >= 4) {
                // Fallback direct byte extraction
                val byteA = bytes[bytes.size - 2]
                (byteA / 2.55f).coerceIn(0f, 100f)
            } else {
                -1f
            }
        } catch (_: Exception) {
            -1f
        }
    }

    /**
     * Parse High Voltage Pack Voltage from Mode 22 01 05
     * Response: 62 01 05 [ByteA] [ByteB] -> ((A * 256) + B) / 10 Volts
     */
    fun parsePackVoltage(raw: String): Float {
        return try {
            val bytes = extractHexBytes(raw)
            val index = bytes.indexOfSubList(listOf(0x62, 0x01, 0x05))
            if (index != -1 && index + 4 < bytes.size) {
                val byteA = bytes[index + 3]
                val byteB = bytes[index + 4]
                (byteA * 256 + byteB) / 10f
            } else {
                -1f
            }
        } catch (_: Exception) {
            -1f
        }
    }

    /**
     * Parse Battery Current (Amps) from Mode 22 01 06
     * Response: 62 01 06 [ByteA] [ByteB] -> signed 16-bit / 10
     * Positive = Charging / Regen, Negative = Discharging
     */
    fun parseCurrent(raw: String): Float {
        return try {
            val bytes = extractHexBytes(raw)
            val index = bytes.indexOfSubList(listOf(0x62, 0x01, 0x06))
            if (index != -1 && index + 4 < bytes.size) {
                val byteA = bytes[index + 3]
                val byteB = bytes[index + 4]
                val raw16 = (byteA shl 8) or byteB
                val signed = if (raw16 > 32767) raw16 - 65536 else raw16
                signed / 10f
            } else {
                0f
            }
        } catch (_: Exception) {
            0f
        }
    }

    /**
     * Parse Battery Pack Temperature (°C) from Mode 22 01 08
     * Response: 62 01 08 [ByteA] -> ByteA - 40
     */
    fun parseBatteryTemp(raw: String): Int {
        return try {
            val bytes = extractHexBytes(raw)
            val index = bytes.indexOfSubList(listOf(0x62, 0x01, 0x08))
            if (index != -1 && index + 3 < bytes.size) {
                bytes[index + 3] - 40
            } else {
                28
            }
        } catch (_: Exception) {
            28
        }
    }

    private fun <T> List<T>.indexOfSubList(target: List<T>): Int {
        if (target.isEmpty() || size < target.size) return -1
        for (i in 0..size - target.size) {
            if (subList(i, i + target.size) == target) return i
        }
        return -1
    }
}

