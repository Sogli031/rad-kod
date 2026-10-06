package com.rad.shifttracker.obracun

import kotlin.math.min
import com.rad.shifttracker.smene.BrigadeType
import com.rad.shifttracker.smene.DayRecord
import com.rad.shifttracker.smene.Shift
import com.rad.shifttracker.smene.Weekday

// Podela plaćenih minuta smene na redovni i prekovremeni deo, sa nedeljnim, noćnim i prazničnim minutima.

private const val MINUTES_PER_HOUR = 60

internal fun isCalendarWeekendOvertime(weekday: Weekday, brigadeType: BrigadeType): Boolean {
    return brigadeType == BrigadeType.TROBRIGADA && weekday.isWeekend
}

internal fun isAutomaticWeekendOvertime(record: DayRecord, brigadeType: BrigadeType): Boolean {
    return !record.workedOnHoliday && isCalendarWeekendOvertime(record.weekday, brigadeType)
}

internal fun workBaseMinuteCategories(
    record: DayRecord,
    brigadeType: BrigadeType,
    monthlyOvertimeMinutes: Int?,
    defaultHours: Int
): WorkBaseMinuteCategories {
    return classifiedWorkSegments(record, brigadeType, monthlyOvertimeMinutes, defaultHours)
        .fold(WorkBaseMinuteCategories()) { total, segment ->
            if (segment.isOvertime) {
                total.copy(overtimeBaseMinutes = total.overtimeBaseMinutes + segment.minutes)
            } else {
                val holidayBase = segment.publicHolidayBaseMinutes(record, brigadeType).coerceIn(0, segment.minutes)
                total.copy(
                    regularWorkedBaseMinutes = total.regularWorkedBaseMinutes + segment.minutes - holidayBase,
                    publicHolidayWorkedBaseMinutes = total.publicHolidayWorkedBaseMinutes + holidayBase
                )
            }
        }
}

internal fun classifiedWorkSegments(
    record: DayRecord,
    brigadeType: BrigadeType,
    monthlyOvertimeMinutes: Int?,
    defaultHours: Int
): List<ClassifiedWorkSegment> {
    val shiftsData = paidWorkMinuteSegments(record)
    if (shiftsData.isEmpty()) return emptyList()
    if (isAutomaticWeekendOvertime(record, brigadeType)) {
        return shiftsData.map { (shift, minutes) ->
            ClassifiedWorkSegment(
                shift = shift,
                minutes = minutes,
                isOvertime = true,
                sundayMinutes = getSundayMinutesForShift(record.weekday, shift, minutes),
                nightMinutes = nightPremiumMinutes(shift, minutes),
                holidayMinutes = record.holidayPremiumMinutes(shift, minutes)
            )
        }
    }

    val paidWorkMinutes = shiftsData.sumOf { (_, minutes) -> minutes }
    val forcedOvertimeMinutes = if (record.workedOnHoliday) {
        0
    } else {
        monthlyOvertimeMinutes?.coerceIn(0, paidWorkMinutes)
    }
    var regularMinutesRemaining = when {
        forcedOvertimeMinutes != null -> paidWorkMinutes - forcedOvertimeMinutes
        brigadeType == BrigadeType.CETVOROBRIGADA -> paidWorkMinutes
        else -> defaultHours * MINUTES_PER_HOUR
    }
    return buildList {
        for ((shift, shiftMinutes) in shiftsData) {
            val regularMinutes = min(shiftMinutes, regularMinutesRemaining)
            val overtimeMinutes = shiftMinutes - regularMinutes
            regularMinutesRemaining -= regularMinutes
            val (sundayRegular, sundayOvertime) = splitSundayMinutes(
                weekday = record.weekday,
                shift = shift,
                regularMinutes = regularMinutes,
                overtimeMinutes = overtimeMinutes
            )
            // Noćni pun sat se prvo pripisuje redovnom delu smene, ostatak prekovremenom.
            val nightMinutes = nightPremiumMinutes(shift, shiftMinutes)
            val nightRegular = min(regularMinutes, nightMinutes)
            val nightOvertime = (nightMinutes - nightRegular).coerceIn(0, overtimeMinutes)
            // Praznični minuti hronološki kao nedelja: redovni deo je početak smene.
            val holidayTotal = record.holidayPremiumMinutes(shift, shiftMinutes).coerceIn(0, shiftMinutes)
            val holidayRegular = record.holidayPremiumMinutes(shift, regularMinutes)
                .coerceIn(0, minOf(regularMinutes, holidayTotal))
            val holidayOvertime = (holidayTotal - holidayRegular).coerceIn(0, overtimeMinutes)
            if (regularMinutes > 0) {
                add(
                    ClassifiedWorkSegment(shift, regularMinutes, false, sundayRegular, nightRegular, holidayRegular)
                )
            }
            if (overtimeMinutes > 0) {
                add(
                    ClassifiedWorkSegment(shift, overtimeMinutes, true, sundayOvertime, nightOvertime, holidayOvertime)
                )
            }
        }
    }
}

internal data class ClassifiedWorkSegment(
    val shift: Shift,
    val minutes: Int,
    val isOvertime: Boolean,
    val sundayMinutes: Int,
    val nightMinutes: Int,
    val holidayMinutes: Int
) {
    /**
     * Minuti redovnog segmenta koje lista broji kao rad na praznik (05). Trobrigada: po satu, isto kao
     * dodatak 123% (23) — treća smena uoči praznika daje 6 h, a ona koja počinje na praznik 2 h; ostatak
     * smene je režija (01). Četvorobrigada: cela smena po danu početka, a fond prebacuje po satu
     * ([MutablePayDetails.applyFourBrigadeFund]). 01 i 05 imaju istu satnicu i oba ulaze u minuli rad.
     */
    fun publicHolidayBaseMinutes(record: DayRecord, brigadeType: BrigadeType): Int = when {
        isOvertime -> 0
        brigadeType == BrigadeType.TROBRIGADA -> holidayMinutes
        record.workedOnHoliday -> minutes
        else -> 0
    }
}

internal data class WorkBaseMinuteCategories(
    val regularWorkedBaseMinutes: Int = 0,
    val publicHolidayWorkedBaseMinutes: Int = 0,
    val overtimeBaseMinutes: Int = 0
) {
    operator fun plus(other: WorkBaseMinuteCategories): WorkBaseMinuteCategories {
        return WorkBaseMinuteCategories(
            regularWorkedBaseMinutes + other.regularWorkedBaseMinutes,
            publicHolidayWorkedBaseMinutes + other.publicHolidayWorkedBaseMinutes,
            overtimeBaseMinutes + other.overtimeBaseMinutes
        )
    }
}
