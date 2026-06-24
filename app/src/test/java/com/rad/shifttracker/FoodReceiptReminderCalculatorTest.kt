package com.rad.shifttracker

import java.time.LocalDate
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FoodReceiptReminderCalculatorTest {

    @Test
    fun `food accounting date moves third shift to next calendar day`() {
        assertEquals(
            LocalDate.of(2027, 2, 27),
            foodAccountingDate(LocalDate.of(2027, 2, 26), Shift.THIRD)
        )
        assertEquals(
            LocalDate.of(2027, 2, 26),
            foodAccountingDate(LocalDate.of(2027, 2, 26), Shift.FIRST)
        )
        assertEquals(
            LocalDate.of(2027, 2, 26),
            foodAccountingDate(LocalDate.of(2027, 2, 26), Shift.SECOND)
        )
    }

    @Test
    fun `month schedule includes previous month third shift when food accounts in selected month`() {
        val schedule = foodReceiptReminderMonthSchedule(
            month = MonthKey(2027, 3),
            monthRecords = emptyList(),
            previousMonthLastRecord = DayRecord(2027, 2, 28, Shift.THIRD, 8)
        )

        val reminder = calculateFoodReceiptReminderForMonth(schedule.month, schedule.records)

        assertEquals(LocalDateTime.of(2027, 3, 1, 1, 0), reminder?.mealTime)
        assertEquals(LocalDate.of(2027, 2, 28), reminder?.shiftDate)
        assertEquals(Shift.THIRD, reminder?.shift)
        assertEquals(MonthKey(2027, 3), reminder?.month)
    }

    @Test
    fun `selected month food receipt reminder ignores future months`() {
        val reminder = calculateSelectedMonthFoodReceiptReminder(
            month = MonthKey(2026, 5),
            monthRecords = listOf(DayRecord(2026, 5, 30, Shift.THIRD, 8)),
            previousMonthLastRecord = null
        )

        assertEquals(LocalDateTime.of(2026, 5, 31, 1, 0), reminder?.mealTime)
        assertEquals(MonthKey(2026, 5), reminder?.month)
    }

    @Test
    fun `first shift on last day schedules meal reminder at 08 in same month`() {
        val reminder = calculateFoodReceiptReminderForMonth(
            month = MonthKey(2026, 5),
            records = listOf(DayRecord(2026, 5, 31, Shift.FIRST, 8))
        )

        assertEquals(LocalDateTime.of(2026, 5, 31, 8, 0), reminder?.mealTime)
        assertEquals(LocalDate.of(2026, 5, 31), reminder?.shiftDate)
        assertEquals(Shift.FIRST, reminder?.shift)
        assertEquals(MonthKey(2026, 5), reminder?.month)
    }

    @Test
    fun `second shift on last day schedules meal reminder at 17 in same month`() {
        val reminder = calculateFoodReceiptReminderForMonth(
            month = MonthKey(2026, 5),
            records = listOf(DayRecord(2026, 5, 31, Shift.SECOND, 8))
        )

        assertEquals(LocalDateTime.of(2026, 5, 31, 17, 0), reminder?.mealTime)
        assertEquals(LocalDate.of(2026, 5, 31), reminder?.shiftDate)
        assertEquals(Shift.SECOND, reminder?.shift)
    }

    @Test
    fun `third shift on penultimate day schedules meal reminder next day at 01`() {
        val reminder = calculateFoodReceiptReminderForMonth(
            month = MonthKey(2026, 5),
            records = listOf(DayRecord(2026, 5, 30, Shift.THIRD, 8))
        )

        assertEquals(LocalDateTime.of(2026, 5, 31, 1, 0), reminder?.mealTime)
        assertEquals(LocalDate.of(2026, 5, 30), reminder?.shiftDate)
        assertEquals(Shift.THIRD, reminder?.shift)
    }

    @Test
    fun `third shift on last day is excluded from current month`() {
        val reminder = calculateFoodReceiptReminderForMonth(
            month = MonthKey(2026, 5),
            records = listOf(DayRecord(2026, 5, 31, Shift.THIRD, 8))
        )

        assertNull(reminder)
    }

    @Test
    fun `third shift on last day does not override previous valid third shift`() {
        val reminder = calculateFoodReceiptReminderForMonth(
            month = MonthKey(2026, 5),
            records = listOf(
                DayRecord(2026, 5, 30, Shift.THIRD, 8),
                DayRecord(2026, 5, 31, Shift.THIRD, 8)
            )
        )

        assertEquals(LocalDateTime.of(2026, 5, 31, 1, 0), reminder?.mealTime)
        assertEquals(LocalDate.of(2026, 5, 30), reminder?.shiftDate)
    }

    @Test
    fun `month without work shift food candidate has no reminder`() {
        val reminder = calculateFoodReceiptReminderForMonth(
            month = MonthKey(2026, 5),
            records = listOf(
                DayRecord(2026, 5, 30, Shift.OFF, 0),
                DayRecord(2026, 5, 31, Shift.SICK, 8)
            )
        )

        assertNull(reminder)
    }

    @Test
    fun `next reminder skips passed current month and uses first future valid month`() {
        val reminder = findNextFoodReceiptReminderAfter(
            now = LocalDateTime.of(2026, 5, 31, 8, 1),
            schedules = listOf(
                FoodReceiptReminderMonthSchedule(
                    month = MonthKey(2026, 5),
                    records = listOf(DayRecord(2026, 5, 31, Shift.FIRST, 8))
                ),
                FoodReceiptReminderMonthSchedule(
                    month = MonthKey(2026, 6),
                    records = listOf(DayRecord(2026, 6, 30, Shift.SECOND, 8))
                )
            )
        )

        assertEquals(LocalDateTime.of(2026, 6, 30, 17, 0), reminder?.mealTime)
        assertEquals(MonthKey(2026, 6), reminder?.month)
    }

    @Test
    fun `disabled schedule action cancels future alarms`() {
        val action = planFoodReceiptReminderAlarm(
            enabled = false,
            now = LocalDateTime.of(2026, 5, 20, 10, 0),
            schedules = listOf(
                FoodReceiptReminderMonthSchedule(
                    month = MonthKey(2026, 5),
                    records = listOf(DayRecord(2026, 5, 31, Shift.FIRST, 8))
                )
            )
        )

        assertEquals(FoodReceiptReminderAlarmPlan.Cancel, action)
    }
}
