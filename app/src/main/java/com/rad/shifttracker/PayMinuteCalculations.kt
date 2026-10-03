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

/**
 * Deli nedeljne minute smene na redovni i prekovremeni deo hronološki.
 *
 * getSundayMinutesForShift(m) vraća nedeljne minute u PRVIH m minuta smene
 * (za treću smenu nije aditivna: subota max(0, m−120), nedelja min(m, 120)),
 * pa se ne sme zvati posebno za redovni i prekovremeni pod-segment. Redovni
 * deo je početak smene, prekovremeni kraj: nedeljni minuti prekovremenog =
 * ukupno − prefiks redovnog.
 */
internal fun splitSundayMinutes(
    weekday: Weekday,
    shift: Shift,
    regularMinutes: Int,
    overtimeMinutes: Int,
    brigadeType: BrigadeType
): Pair<Int, Int> {
    val totalMinutes = regularMinutes + overtimeMinutes
    val sundayTotal = getSundayMinutesForShift(weekday, shift, totalMinutes, brigadeType)
        .coerceIn(0, totalMinutes)
    val sundayRegular = getSundayMinutesForShift(weekday, shift, regularMinutes, brigadeType)
        .coerceIn(0, minOf(regularMinutes, sundayTotal))
    val sundayOvertime = (sundayTotal - sundayRegular).coerceIn(0, overtimeMinutes)
    return sundayRegular to sundayOvertime
}

internal fun calculatePayForSegmentMinutesWithSunday(
    minutes: Int,
    sundayMinutes: Int,
    baseRate: Double,
    bonuses: List<Double>,
    sundayBonus: Double
): Double {
    if (minutes <= 0) return 0.0
    val sunday = sundayMinutes.coerceIn(0, minutes)
    return calculatePayForMinutes(minutes - sunday, baseRate, bonuses) +
        calculatePayForMinutes(sunday, baseRate, bonuses + sundayBonus)
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

/**
 * Minuti smene koji padaju na praznik, po satu kao i nedelja: treća smena
 * (22–06h) ima 2h na dan početka, a ostatak na sledeći dan. Treća smena
 * 22. na praznik 22. ima 2h prazničnog rada; treća smena 21. ima 6h.
 */
internal fun holidayMinutesForShift(
    shift: Shift,
    shiftMinutes: Int,
    startDayIsHoliday: Boolean,
    nextDayIsHoliday: Boolean
): Int {
    if (shiftMinutes <= 0) return 0
    if (shift != Shift.THIRD) return if (startDayIsHoliday) shiftMinutes else 0
    val onStartDay = min(shiftMinutes, 2 * MINUTES_PER_HOUR)
    val onNextDay = shiftMinutes - onStartDay
    return (if (startDayIsHoliday) onStartDay else 0) + (if (nextDayIsHoliday) onNextDay else 0)
}

/** Dodatak za rad na praznik (123%) na minute koji padaju na praznik. */
internal fun calculateHolidayPremiumMinutes(holidayMinutes: Int, baseRate: Double, holidayBonus: Double): Double {
    if (holidayMinutes <= 0) return 0.0
    return holidayMinutes.toDouble() / MINUTES_PER_HOUR * baseRate * holidayBonus
}

/** Da li je dan posle ovog zapisa praznik (za deo treće smene posle ponoći). */
internal fun DayRecord.nextDayIsHoliday(): Boolean {
    val next = date.plusDays(1)
    return isSerbianHoliday(next.year, next.monthValue, next.dayOfMonth)
}

/** Praznični minuti smene za ovaj dan (dan početka poštuje oznaku „rad na praznik"). */
internal fun DayRecord.holidayPremiumMinutes(shift: Shift, shiftMinutes: Int): Int {
    return holidayMinutesForShift(
        shift = shift,
        shiftMinutes = shiftMinutes,
        startDayIsHoliday = workedOnHoliday,
        nextDayIsHoliday = nextDayIsHoliday()
    )
}

/**
 * Minuti smene za koje se plaća noćni dodatak. Listić ga daje samo za pune sate
 * treće smene: 31.07.2026 rađeno 4h30 u trećoj, noćni rad na listiću 4h.
 */
internal fun nightPremiumMinutes(shift: Shift, shiftMinutes: Int): Int {
    if (shift != Shift.THIRD || shiftMinutes <= 0) return 0
    return shiftMinutes / MINUTES_PER_HOUR * MINUTES_PER_HOUR
}

/** Noćni minuti dana sa dodatnim minutima (isti izvor kao mesečni obračun). */
internal fun nightWorkMinutes(record: DayRecord): Int {
    return paidWorkMinuteSegments(record).sumOf { (shift, minutes) -> nightPremiumMinutes(shift, minutes) }
}

/** Nedeljni minuti dana sa dodatnim minutima (isti izvor kao mesečni obračun). */
internal fun sundayWorkMinutes(record: DayRecord, brigadeType: BrigadeType): Int {
    return paidWorkMinuteSegments(record).sumOf { (shift, minutes) ->
        getSundayMinutesForShift(record.weekday, shift, minutes, brigadeType)
    }
}
