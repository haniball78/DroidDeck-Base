package com.droiddeck.launcher.session

data class BatteryReading(
    val levelPercent: Int,
    val charging: Boolean,
    val nowMs: Long,
)

enum class SafetyAction {
    NONE,
    WARN_BATTERY_15,
    END_TWEAKS,
    STOP_WITH_COUNTDOWN,
    BRIDGE_LOST_RESTORE,
    DEESCALATE_AGGRESSIVE,
}

data class SafetyInput(
    val battery: BatteryReading?,
    val thermalStatus: Int,
    val bridgeOk: Boolean,
    val tweaksActive: Boolean,
    val tweaksAlreadyEnded: Boolean,
    val warnedAt15: Boolean,
    val stopCountdownActive: Boolean,
    val bridgeLostAlreadyReported: Boolean,
    val bridgeUnknown: Boolean = false,
    val aggressiveActive: Boolean = false,
    val deescalated: Boolean = false,
    val deescalatedAtMs: Long = 0L,
    val nowMs: Long = 0L,
)

data class SafetyDecision(
    val action: SafetyAction,
    val tweaksAlreadyEnded: Boolean,
    val warnedAt15: Boolean,
    val bridgeLostAlreadyReported: Boolean,
    val deescalated: Boolean = false,
    val deescalatedAtMs: Long = 0L,
)

data class SafetyThresholds(
    val warnLevel: Int = 15,
    val tweaksLevel: Int = 10,
    val emergencyLevel: Int = 5,
    val thermalSevereLevel: Int = SafetyPolicy.THERMAL_SEVERE,
)

internal object SafetyPolicy {
    const val WARN_LEVEL = 15
    const val TWEAKS_LEVEL = 10
    const val EMERGENCY_LEVEL = 5
    const val HYSTERESIS = 3
    const val COUNTDOWN_SECONDS = 20
    const val THERMAL_SEVERE = 3
    const val THERMAL_UNKNOWN = -1

    fun evaluate(input: SafetyInput, thresholds: SafetyThresholds = SafetyThresholds()): SafetyDecision {
        var tweaksEnded = input.tweaksAlreadyEnded
        var warned = input.warnedAt15
        var bridgeReported = input.bridgeLostAlreadyReported
        var deescalated = input.deescalated
        var deescalatedAt = input.deescalatedAtMs
        fun decision(action: SafetyAction): SafetyDecision =
            SafetyDecision(action, tweaksEnded, warned, bridgeReported, deescalated, deescalatedAt)
        val battery = input.battery
        if (battery != null && !battery.charging && battery.levelPercent >= 0) {
            if (battery.levelPercent >= thresholds.warnLevel + HYSTERESIS) warned = false
            if (battery.levelPercent >= thresholds.tweaksLevel + HYSTERESIS) tweaksEnded = false
            if (battery.levelPercent <= thresholds.emergencyLevel && !input.stopCountdownActive) {
                return decision(SafetyAction.STOP_WITH_COUNTDOWN)
            }
            if (battery.levelPercent <= thresholds.tweaksLevel && input.tweaksActive && !tweaksEnded) {
                tweaksEnded = true
                return decision(SafetyAction.END_TWEAKS)
            }
            if (battery.levelPercent <= thresholds.warnLevel && !warned) {
                warned = true
                return decision(SafetyAction.WARN_BATTERY_15)
            }
        }
        if (input.thermalStatus != THERMAL_UNKNOWN && input.thermalStatus >= thresholds.thermalSevereLevel && input.tweaksActive && !tweaksEnded) {
            // Thermal safety never ends tweaks: the load-reducing tweaks
            // (freezer, doze, background limit, governor) lower load and
            // heat, so removing them at high heat would add load. Only the
            // heat-making aggressive keys are rolled back, once per episode;
            // afterwards the coolers stay on and the governor keeps
            // managing in COOLING_RELIEF. The severe level is configurable
            // (raise-to-4 option) via thresholds.thermalSevereLevel.
            if (input.aggressiveActive && !deescalated) {
                deescalated = true
                deescalatedAt = input.nowMs
                return decision(SafetyAction.DEESCALATE_AGGRESSIVE)
            }
            return decision(SafetyAction.NONE)
        }
        if (input.bridgeUnknown) {
            return decision(SafetyAction.NONE)
        }
        if (!input.bridgeOk && !bridgeReported) {
            bridgeReported = true
            return decision(SafetyAction.BRIDGE_LOST_RESTORE)
        }
        if (input.bridgeOk) bridgeReported = false
        return decision(SafetyAction.NONE)
    }
}

internal class BatteryTrend(private val maxSamples: Int = 12) {
    private val samples = ArrayList<BatteryReading>()

    fun add(reading: BatteryReading) {
        if (reading.levelPercent < 0) return
        samples.add(reading)
        while (samples.size > maxSamples) samples.removeAt(0)
    }

    fun minutesToLevel(targetPercent: Int): Double? {
        if (samples.size < 2) return null
        val first = samples.first()
        val last = samples.last()
        val dtMinutes = (last.nowMs - first.nowMs) / 60000.0
        if (dtMinutes <= 0.0) return null
        val dropPerMinute = (first.levelPercent - last.levelPercent) / dtMinutes
        if (dropPerMinute <= 0.0) return null
        val remaining = last.levelPercent - targetPercent
        if (remaining <= 0) return 0.0
        return remaining / dropPerMinute
    }
}
