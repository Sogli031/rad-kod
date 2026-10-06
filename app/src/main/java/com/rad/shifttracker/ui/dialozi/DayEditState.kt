package com.rad.shifttracker.ui.dialozi

import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.roundToInt
import com.rad.shifttracker.CONFIG
import com.rad.shifttracker.formatHours
import com.rad.shifttracker.formatNumber
import com.rad.shifttracker.normalizeRegularShortageMinutesForWorkHours
import com.rad.shifttracker.smene.DayKey
import com.rad.shifttracker.smene.DayRecord
import com.rad.shifttracker.smene.DayRecordSource
import com.rad.shifttracker.smene.OvertimeType
import com.rad.shifttracker.smene.Shift
import com.rad.shifttracker.smene.isSerbianHoliday

internal data class DayEditSaveState(
    val date: DayKey,
    val shift1: Shift,
    val workHours1: Int,
    val shift2: Shift,
    val workHours2: Int,
    val hasDouble: Boolean,
    val firstShiftAllowsDouble: Boolean,
    val overtimeType: OvertimeType,
    val weekendOvertimeType: OvertimeType,
    val compHoursUsed: Int,
    val holidayWorkOverride: Boolean?,
    val holidayOverride: Boolean?,
    val balanceExempt: Boolean = false,
    val sickPayRate: Double,
    val regularShortageMinutes: Int = 0,
    /** Bolovanje kao plaćeno odsustvo (100% po osnovici, šifra 11 na listiću). */
    val paidLeave: Boolean = false
) {
    fun toDayRecord(): DayRecord {
        val saveDouble = hasDouble && firstShiftAllowsDouble
        val dayDate = LocalDate.of(date.year, date.month, date.day)
        val isWeekend = dayDate.dayOfWeek.value >= 6
        val isHoliday = holidayOverride ?: isSerbianHoliday(date.year, date.month, date.day)
        val savedWorkHours = when (shift1) {
            Shift.SICK -> CONFIG.pay.defaultHours
            Shift.OFF -> 0
            else -> workHours1
        }
        val hasPrimaryExtraHours = shift1 in Shift.workShifts() &&
            savedWorkHours > CONFIG.pay.defaultHours
        val hasPrimaryNonRegularWork = shift1 in Shift.workShifts() && (isWeekend || isHoliday)
        val savedOvertimeType = if (saveDouble || hasPrimaryExtraHours) overtimeType else OvertimeType.PAID
        return DayRecord(
            year = date.year,
            month = date.month,
            day = date.day,
            shift = shift1,
            hours = savedWorkHours,
            shift2 = if (saveDouble) shift2 else null,
            hours2 = if (saveDouble) workHours2 else 0,
            overtimeType = savedOvertimeType,
            weekendOvertimeType = if (hasPrimaryNonRegularWork) {
                weekendOvertimeType
            } else {
                OvertimeType.PAID
            },
            compHoursUsed = compHoursUsed,
            holidayWorkOverride = holidayWorkOverride,
            holidayOverride = holidayOverride,
            balanceExempt = balanceExempt,
            sickPayRate = if (shift1 == Shift.SICK && !paidLeave) sickPayRate else null,
            regularShortageMinutes = if (shift1 in Shift.workShifts()) {
                normalizeRegularShortageMinutesForWorkHours(savedWorkHours, regularShortageMinutes)
            } else {
                0
            },
            source = if (shift1 == Shift.SICK && paidLeave) DayRecordSource.PAID_LEAVE else DayRecordSource.MANUAL
        )
    }
}

internal fun formatDayEditNetDifference(
    savedNetPay: Double?,
    currentNetPay: Double,
    currency: String
): String? {
    val saved = savedNetPay ?: return null
    val roundedDifference = (currentNetPay - saved).roundToInt()
    if (roundedDifference == 0) return null
    val sign = if (roundedDifference > 0) "+" else "-"
    return "Razlika: $sign${formatNumber(abs(roundedDifference).toDouble())} $currency"
}

/**
 * Dodatna smena posle redovne: posle prve druga ili treća, posle druge samo treća,
 * posle treće samo prva.
 */
internal fun overtimeShiftsAfter(shift: Shift): List<Shift> = when (shift) {
    Shift.FIRST -> listOf(Shift.SECOND, Shift.THIRD)
    Shift.SECOND -> listOf(Shift.THIRD)
    Shift.THIRD -> listOf(Shift.FIRST)
    else -> emptyList()
}

internal fun defaultOvertimeShiftAfter(shift: Shift): Shift {
    return when (shift) {
        Shift.FIRST -> Shift.SECOND
        Shift.SECOND -> Shift.THIRD
        Shift.THIRD -> Shift.FIRST
        else -> Shift.FIRST
    }
}

private const val DEFAULT_OVERTIME_HOURS = 4

internal fun defaultOvertimeHours(shift: Shift): Int {
    return if (shift in Shift.workShifts()) DEFAULT_OVERTIME_HOURS else 0
}

internal fun previousWorkedShiftInSameWeek(
    date: LocalDate,
    recordForDate: (LocalDate) -> DayRecord?
): Shift? {
    var candidate = date.minusDays(1)
    while (candidate.dayOfWeek.value < date.dayOfWeek.value) {
        val record = recordForDate(candidate)
        if (record != null && record.isWorkDay) {
            return record.shift
        }
        candidate = candidate.minusDays(1)
    }
    return null
}

internal fun dayEditShiftButtonText(shift: Shift): String {
    return when (shift) {
        Shift.FIRST -> "Prva"
        Shift.SECOND -> "Druga"
        Shift.THIRD -> "Treća"
        Shift.SICK -> "Bolovanje"
        Shift.VACATION -> "Godišnji"
        Shift.OFF -> "Nisam radio"
    }
}

internal const val DAY_EDIT_PRIMARY_SHIFT_QUESTION_TEXT = "Izaberi smenu"

internal const val DAY_EDIT_PAID_LEAVE_LABEL = "Plaćeno odsustvo"

internal const val DAY_EDIT_PAID_LEAVE_DETAIL_TEXT =
    "Plaćeno odsustvo se vodi kao 8h i plaća 100% po osnovici naknada (šifra 11 na listi)."

internal fun isDayEditLockedByBloodDonation(record: DayRecord?): Boolean {
    return record?.isBloodDonationLeave == true
}

internal fun dayEditNonWorkDayDetailText(
    shift: Shift,
    workHours: Int = CONFIG.pay.defaultHours
): String {
    return when (shift) {
        Shift.SICK -> "Bolovanje se vodi kao ${formatHours(workHours)} za ovaj dan."
        Shift.VACATION -> "Godišnji odmor se vodi kao ${formatHours(workHours)} za ovaj dan."
        Shift.OFF -> "Nisam radio, bez radnih sati."
        else -> ""
    }
}
