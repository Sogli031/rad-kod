package com.rad.shifttracker

import java.time.LocalDate

private const val MINUTES_PER_HOUR = 60

data class DailyPayCategoryBreakdown(
    val regularWorkMinutes: Int = 0,
    val overtimeMinutes: Int = 0,
    val sundayWorkMinutes: Int = 0,
    val nightWorkMinutes: Int = 0,
    val holidayWorkMinutes: Int = 0,
    val difficultConditionsMinutes: Int = 0,
    val sickLeaveMinutes: Int = 0,
    val paidLeaveMinutes: Int = 0,
    val vacationMinutes: Int = 0,
    val paidHolidayMinutes: Int = 0,
    val absenceMinutes: Int = 0,
    val countsIntoWorkBalance: Boolean = false,
    val workBalanceDeltaMinutes: Int = 0,
    val payCategoriesApplied: List<String> = emptyList()
)

internal class DailyPayCategoryBreakdownCalculator(
    private val payCalculator: PayCalculator,
    private val difficultConditionsPercent: Double,
    private val defaultHours: Int = CONFIG.pay.defaultHours
) {
    fun calculateForMonth(
        records: List<DayRecord>,
        year: Int,
        month: Int,
        brigadeType: BrigadeType
    ): Map<DayKey, DailyPayCategoryBreakdown> {
        val recordsByDay = records.associateBy { it.day }
        val overtimeByDay = payCalculator.calculateMonthlyOvertimeMinutesByDay(records, year, month, brigadeType)
        return (1..java.time.YearMonth.of(year, month).lengthOfMonth()).associate { day ->
            val date = LocalDate.of(year, month, day)
            val record = recordsByDay[day]
            DayKey(year, month, day) to calculateDay(
                date = date,
                record = record,
                hasAnyMonthRecord = records.isNotEmpty(),
                brigadeType = brigadeType,
                monthlyOvertimeMinutes = record?.let {
                    payCalculator.monthlyOvertimeMinutesForRecord(it, brigadeType, overtimeByDay)
                }
            )
        }
    }

    private fun calculateDay(
        date: LocalDate,
        record: DayRecord?,
        hasAnyMonthRecord: Boolean,
        brigadeType: BrigadeType,
        monthlyOvertimeMinutes: Int?
    ): DailyPayCategoryBreakdown {
        return when (record?.shift) {
            null -> emptyDay(date, hasAnyMonthRecord)
            Shift.OFF -> offDay(record, brigadeType)
            Shift.SICK -> if (record.isPaidLeave) {
                paidAbsenceDay(record, brigadeType, "paidLeave")
            } else {
                paidAbsenceDay(record, brigadeType, "sickLeave")
            }
            Shift.VACATION -> paidAbsenceDay(record, brigadeType, "vacation")
            else -> workDay(record, brigadeType, monthlyOvertimeMinutes)
        }
    }

    private fun emptyDay(date: LocalDate, hasAnyMonthRecord: Boolean): DailyPayCategoryBreakdown {
        val isPaidHoliday = hasAnyMonthRecord &&
            Weekday.from(date).isWorkday &&
            isSerbianHoliday(date.year, date.monthValue, date.dayOfMonth)
        return DailyPayCategoryBreakdown(
            paidHolidayMinutes = if (isPaidHoliday) defaultHours * MINUTES_PER_HOUR else 0,
            payCategoriesApplied = if (isPaidHoliday) listOf("paidHoliday") else emptyList()
        )
    }

    private fun offDay(record: DayRecord, brigadeType: BrigadeType): DailyPayCategoryBreakdown {
        val paidHolidayMinutes = if (record.isHoliday && record.weekday.isWorkday) {
            defaultHours * MINUTES_PER_HOUR
        } else {
            0
        }
        val categories = mutableListOf<String>()
        addPositiveCategory(paidHolidayMinutes, "paidHoliday", categories)
        addWorkBalanceCategories(record, brigadeType, categories)
        return DailyPayCategoryBreakdown(
            paidHolidayMinutes = paidHolidayMinutes,
            absenceMinutes = record.hoursDeficitMinutesFor(brigadeType),
            countsIntoWorkBalance = true,
            workBalanceDeltaMinutes = record.netCompChangeMinutesFor(brigadeType),
            payCategoriesApplied = categories
        )
    }

    private fun paidAbsenceDay(
        record: DayRecord,
        brigadeType: BrigadeType,
        category: String
    ): DailyPayCategoryBreakdown {
        val paidMinutes = if (record.weekday.isWorkday) record.workHours * MINUTES_PER_HOUR else 0
        val categories = mutableListOf<String>()
        addPositiveCategory(paidMinutes, category, categories)
        addWorkBalanceCategories(record, brigadeType, categories)
        return DailyPayCategoryBreakdown(
            sickLeaveMinutes = if (record.shift == Shift.SICK && !record.isPaidLeave) paidMinutes else 0,
            paidLeaveMinutes = if (record.isPaidLeave) paidMinutes else 0,
            vacationMinutes = if (record.shift == Shift.VACATION) paidMinutes else 0,
            absenceMinutes = record.hoursDeficitMinutesFor(brigadeType),
            countsIntoWorkBalance = record.netCompChangeMinutesFor(brigadeType) != 0,
            workBalanceDeltaMinutes = record.netCompChangeMinutesFor(brigadeType),
            payCategoriesApplied = categories
        )
    }

    private fun workDay(
        record: DayRecord,
        brigadeType: BrigadeType,
        monthlyOvertimeMinutes: Int?
    ): DailyPayCategoryBreakdown {
        val paidWorkMinutes = paidWorkMinutes(record)
        val overtimeMinutes = overtimeMinutes(record, brigadeType, monthlyOvertimeMinutes, paidWorkMinutes)
        val sundayMinutes = sundayWorkMinutes(record, brigadeType)
        val nightMinutes = nightWorkMinutes(record)
        val holidayWorkMinutes = paidWorkMinuteSegments(record).sumOf { (shift, minutes) ->
            record.holidayPremiumMinutes(shift, minutes)
        }
        val difficultMinutes = if (difficultConditionsPercent > 0.0) paidWorkMinutes else 0
        val categories = workDayCategories(
            regularMinutes = paidWorkMinutes - overtimeMinutes,
            overtimeMinutes = overtimeMinutes,
            sundayMinutes = sundayMinutes,
            nightMinutes = nightMinutes,
            holidayWorkMinutes = holidayWorkMinutes,
            difficultConditionsMinutes = difficultMinutes,
            record = record,
            brigadeType = brigadeType
        )
        return DailyPayCategoryBreakdown(
            regularWorkMinutes = (paidWorkMinutes - overtimeMinutes).coerceAtLeast(0),
            overtimeMinutes = overtimeMinutes,
            sundayWorkMinutes = sundayMinutes,
            nightWorkMinutes = nightMinutes,
            holidayWorkMinutes = holidayWorkMinutes,
            difficultConditionsMinutes = difficultMinutes,
            absenceMinutes = record.hoursDeficitMinutesFor(brigadeType),
            countsIntoWorkBalance = true,
            workBalanceDeltaMinutes = record.netCompChangeMinutesFor(brigadeType),
            payCategoriesApplied = categories
        )
    }

    private fun paidWorkMinutes(record: DayRecord): Int {
        if (record.shift !in Shift.workShifts() || record.effectiveRegularWorkMinutes <= 0) return 0
        return record.paidWorkMinutes
    }

    private fun overtimeMinutes(
        record: DayRecord,
        brigadeType: BrigadeType,
        monthlyOvertimeMinutes: Int?,
        paidWorkMinutes: Int
    ): Int {
        if (record.workedOnHoliday) return 0
        if (monthlyOvertimeMinutes != null) {
            return monthlyOvertimeMinutes.coerceIn(0, paidWorkMinutes)
        }
        return payCalculator.getOvertimeMinutes(record, brigadeType)
    }

    private fun workDayCategories(
        regularMinutes: Int,
        overtimeMinutes: Int,
        sundayMinutes: Int,
        nightMinutes: Int,
        holidayWorkMinutes: Int,
        difficultConditionsMinutes: Int,
        record: DayRecord,
        brigadeType: BrigadeType
    ): List<String> {
        val categories = mutableListOf<String>()
        addPositiveCategory(regularMinutes, "regularWork", categories)
        addPositiveCategory(overtimeMinutes, "overtime", categories)
        addPositiveCategory(sundayMinutes, "sundayWork", categories)
        addPositiveCategory(nightMinutes, "nightWork", categories)
        addPositiveCategory(holidayWorkMinutes, "holidayWork", categories)
        addPositiveCategory(difficultConditionsMinutes, "difficultConditions", categories)
        addWorkBalanceCategories(record, brigadeType, categories)
        return categories
    }

    private fun addWorkBalanceCategories(
        record: DayRecord,
        brigadeType: BrigadeType,
        categories: MutableList<String>
    ) {
        addPositiveCategory(record.compTimeEarnedMinutes, "compTimeEarned", categories)
        addPositiveCategory(record.compHoursUsed * MINUTES_PER_HOUR, "compTimeUsed", categories)
        addPositiveCategory(record.hoursDeficitMinutesFor(brigadeType), "absence", categories)
    }

    private fun addPositiveCategory(
        minutes: Int,
        category: String,
        categories: MutableList<String>
    ) {
        if (minutes > 0) categories += category
    }
}
