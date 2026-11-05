package com.mthkr.inverter

enum class InverterMode(val code: Int) {
    POWER_ON(0), STANDBY(1), LINE(2), BATTERY(3), UNKNOWN(-1);

    companion object {
        fun of(code: Int): InverterMode = entries.firstOrNull { it.code == code } ?: UNKNOWN
    }
}

/** Live values decoded from holding registers 201..234 (SRNE / SMG II register map). */
data class InverterSnapshot(
    val modeCode: Int,
    val gridVoltage: Double,
    val gridFrequency: Double,
    val gridPower: Int,
    val outputVoltage: Double,
    val outputCurrent: Double,
    val outputPower: Int,
    val outputApparentPower: Int,
    val batteryVoltage: Double,
    val pvVoltage: Double,
    val pvPower: Int,
    val pvChargingPower: Int,
    val inverterTemp: Int,
    val dcTemp: Int,
    val batterySoc: Int,
    val batteryCurrent: Double,
    val timestamp: Long,
) {
    val mode: InverterMode get() = InverterMode.of(modeCode)

    companion object {
        const val START_REGISTER = 201
        const val REGISTER_COUNT = 34 // 201..234

        fun fromRegisters(regs: IntArray, timestamp: Long = System.currentTimeMillis()): InverterSnapshot {
            require(regs.size >= REGISTER_COUNT) { "Expected $REGISTER_COUNT registers, got ${regs.size}" }
            fun r(addr: Int) = regs[addr - START_REGISTER]
            fun s(addr: Int) = r(addr).toShort().toInt()
            return InverterSnapshot(
                modeCode = r(201),
                gridVoltage = r(202) / 10.0,
                gridFrequency = r(203) / 100.0,
                gridPower = s(204),
                outputVoltage = r(205) / 10.0,
                outputCurrent = r(211) / 10.0,
                outputPower = r(213),
                outputApparentPower = r(214),
                batteryVoltage = r(215) / 10.0,
                pvVoltage = r(219) / 10.0,
                pvPower = r(223),
                pvChargingPower = r(224),
                inverterTemp = s(226),
                dcTemp = s(227),
                batterySoc = r(229),
                batteryCurrent = s(232) / 10.0,
                timestamp = timestamp,
            )
        }
    }
}
