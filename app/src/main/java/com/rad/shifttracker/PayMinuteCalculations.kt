package com.rad.shifttracker

import kotlin.math.max
import kotlin.math.min

private const val MINUTES_PER_HOUR = 60

@Suppress("UnusedParameter")
internal fun getSundayMinutesForShift(
    weekday: Weekday,
    shift: Shift,
    shiftMinutes: Int,
    brigadeType: BrigadeType
): Int {
    if (shiftMinutes <= 0 || shift == Shift.SICK || shift == Shift.VACATION) return 0

    if (weekday == Weekday.SATURDAY) {
        if (shift == Shift.THIRD) {
            return max(0, shiftMinutes - 2 * MINUTES_PER_HOUR)
        }
        return 0
    }

    if (weekday == Weekday.SUNDAY) {
        if (shift == Shift.FIRST || shift == Shift.SECOND) return shiftMinutes
        if (shift == Shift.THIRD) return min(shiftMinutes, 2 * MINUTES_PER_HOUR)
    }

    return 0
}

internal fun calculatePayForSegmentMinutes(
    weekday: Weekday,
    shift: Shift,
    minutes: Int,
    baseRate: Double,
    brigadeType: BrigadeType,
    bonuses: List<Double>,
    sundayBonus: Double
): Double {
    if (minutes <= 0) return 0.0
    val sundayMinutes = getSundayMinutesForShift(weekday, shift, minutes, brigadeType).coerceIn(0, minutes)
    val regularMinutes = minutes - sundayMinutes
    return calculatePayForMinutes(regularMinutes, baseRate, bonuses) +
        calculatePayForMinutes(sundayMinutes, baseRate, bonuses + sundayBonus)
}

internal fun calculatePayForMinutes(
    minutes: Int,
    baseRate: Double,
    bonuses: List<Double>
): Double {
    if (minutes <= 0) return 0.0
    return minutes.toDouble() / MINUTES_PER_HOUR * baseRate * (1.0 + bonuses.sum())
}

internal fun calculateDifficultWorkConditionsBonusMinutes(
    minutes: Int,
    baseRate: Double,
    difficultWorkConditionsRate: Double
): Double {
    if (minutes <= 0 || baseRate <= 0.0) return 0.0
    return minutes.toDouble() / MINUTES_PER_HOUR * baseRate * difficultWorkConditionsRate
}
