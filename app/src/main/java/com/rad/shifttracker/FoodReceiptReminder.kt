package com.rad.shifttracker

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.YearMonth

data class FoodReceiptReminder(
    val month: MonthKey,
    val mealTime: LocalDateTime,
    val shiftDate: LocalDate,
    val shift: Shift
)

data class FoodReceiptReminderMonthSchedule(
    val month: MonthKey,
    val records: List<DayRecord>
)

sealed class FoodReceiptReminderAlarmPlan {
    data object Cancel : FoodReceiptReminderAlarmPlan()
    data class Schedule(val reminder: FoodReceiptReminder) : FoodReceiptReminderAlarmPlan()
}

fun calculateFoodReceiptReminderForMonth(
    month: MonthKey,
    records: List<DayRecord>
): FoodReceiptReminder? {
    val targetMonth = YearMonth.of(month.year, month.month)
    val monthStart = targetMonth.atDay(1).atStartOfDay()
    val nextMonthStart = targetMonth.plusMonths(1).atDay(1).atStartOfDay()

    return records
        .flatMap { record -> record.foodReceiptReminderCandidates(month) }
        .filter { candidate -> candidate.mealTime >= monthStart && candidate.mealTime < nextMonthStart }
        .maxByOrNull { candidate -> candidate.mealTime }
}

internal fun foodReceiptReminderMonthSchedule(
    month: MonthKey,
    monthRecords: List<DayRecord>,
    previousMonthLastRecord: DayRecord?
): FoodReceiptReminderMonthSchedule {
    val records = (listOfNotNull(previousMonthLastRecord) + monthRecords)
        .distinctBy { record -> record.key }
        .sortedBy { record -> record.date }
    return FoodReceiptReminderMonthSchedule(month = month, records = records)
}

internal fun calculateSelectedMonthFoodReceiptReminder(
    month: MonthKey,
    monthRecords: List<DayRecord>,
    previousMonthLastRecord: DayRecord?
): FoodReceiptReminder? {
    val schedule = foodReceiptReminderMonthSchedule(
        month = month,
        monthRecords = monthRecords,
        previousMonthLastRecord = previousMonthLastRecord
    )
    return calculateFoodReceiptReminderForMonth(schedule.month, schedule.records)
}

fun findNextFoodReceiptReminderAfter(
    now: LocalDateTime,
    schedules: List<FoodReceiptReminderMonthSchedule>
): FoodReceiptReminder? {
    return schedules
        .sortedBy { schedule -> schedule.month }
        .mapNotNull { schedule -> calculateFoodReceiptReminderForMonth(schedule.month, schedule.records) }
        .firstOrNull { reminder -> reminder.mealTime > now }
}

fun planFoodReceiptReminderAlarm(
    enabled: Boolean,
    now: LocalDateTime,
    schedules: List<FoodReceiptReminderMonthSchedule>
): FoodReceiptReminderAlarmPlan {
    if (!enabled) return FoodReceiptReminderAlarmPlan.Cancel
    val reminder = findNextFoodReceiptReminderAfter(now, schedules)
        ?: return FoodReceiptReminderAlarmPlan.Cancel
    return FoodReceiptReminderAlarmPlan.Schedule(reminder)
}

private fun DayRecord.foodReceiptReminderCandidates(month: MonthKey): List<FoodReceiptReminder> {
    if (!isWorkDay) return emptyList()

    val primary = foodReceiptReminderCandidate(month, date, shift)
    val secondary = shift2?.takeIf { isDoubleShift }?.let { secondShift ->
        foodReceiptReminderCandidate(month, date, secondShift)
    }

    return listOfNotNull(primary, secondary)
}

private fun foodReceiptReminderCandidate(
    month: MonthKey,
    shiftDate: LocalDate,
    shift: Shift
): FoodReceiptReminder? {
    val accountingDate = foodAccountingDate(shiftDate, shift)
    val mealTime = when (shift) {
        Shift.FIRST -> LocalDateTime.of(accountingDate, LocalTime.of(8, 0))
        Shift.SECOND -> LocalDateTime.of(accountingDate, LocalTime.of(17, 0))
        Shift.THIRD -> LocalDateTime.of(accountingDate, LocalTime.of(1, 0))
        else -> return null
    }
    return FoodReceiptReminder(
        month = month,
        mealTime = mealTime,
        shiftDate = shiftDate,
        shift = shift
    )
}
