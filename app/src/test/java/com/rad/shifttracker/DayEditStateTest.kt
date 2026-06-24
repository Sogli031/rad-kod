package com.rad.shifttracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DayEditStateTest {
    @Test
    fun saveStateIgnoresSecondShiftWhenDoubleShiftIsDisabled() {
        val state = DayEditSaveState(
            date = DayKey(2026, 2, 2),
            shift1 = Shift.FIRST,
            workHours1 = 8,
            shift2 = Shift.SECOND,
            workHours2 = 8,
            hasDouble = false,
            firstShiftAllowsDouble = true,
            overtimeType = OvertimeType.PAID,
            weekendOvertimeType = OvertimeType.PAID,
            compHoursUsed = 0,
            holidayWorkOverride = null,
            holidayOverride = null,
            balanceExempt = false,
            sickPayRate = CONFIG.pay.sickPayRate
        )

        val record = state.toDayRecord()

        assertEquals(Shift.FIRST, record.shift)
        assertNull(record.shift2)
        assertEquals(8, record.totalHours)
    }

    @Test
    fun overtimeTypeIsPreservedForPrimaryHoursAboveRegularDay() {
        val record = DayEditSaveState(
            date = DayKey(2026, 2, 2),
            shift1 = Shift.FIRST,
            workHours1 = 12,
            shift2 = Shift.SECOND,
            workHours2 = 0,
            hasDouble = false,
            firstShiftAllowsDouble = true,
            overtimeType = OvertimeType.COMP_TIME,
            weekendOvertimeType = OvertimeType.PAID,
            compHoursUsed = 0,
            holidayWorkOverride = null,
            holidayOverride = null,
            balanceExempt = false,
            sickPayRate = CONFIG.pay.sickPayRate
        ).toDayRecord()

        assertNull(record.shift2)
        assertEquals(12, record.workHours)
        assertEquals(OvertimeType.COMP_TIME, record.overtimeType)
    }

    @Test
    fun sickRateIsPreservedOnlyForSickShift() {
        val sickState = DayEditSaveState(
            date = DayKey(2026, 2, 3),
            shift1 = Shift.SICK,
            workHours1 = 8,
            shift2 = Shift.FIRST,
            workHours2 = 0,
            hasDouble = false,
            firstShiftAllowsDouble = false,
            overtimeType = OvertimeType.PAID,
            weekendOvertimeType = OvertimeType.PAID,
            compHoursUsed = 0,
            holidayWorkOverride = null,
            holidayOverride = null,
            balanceExempt = false,
            sickPayRate = 0.8
        )
        val workState = sickState.copy(shift1 = Shift.FIRST)

        assertEquals(0.8, sickState.toDayRecord().sickPayRate ?: 0.0, 0.001)
        assertNull(workState.toDayRecord().sickPayRate)
    }

    @Test
    fun sickDayFromDayEditAlwaysSavesDefaultHours() {
        val record = DayEditSaveState(
            date = DayKey(2026, 2, 3),
            shift1 = Shift.SICK,
            workHours1 = 6,
            shift2 = Shift.FIRST,
            workHours2 = 0,
            hasDouble = false,
            firstShiftAllowsDouble = false,
            overtimeType = OvertimeType.PAID,
            weekendOvertimeType = OvertimeType.PAID,
            compHoursUsed = 0,
            holidayWorkOverride = null,
            holidayOverride = null,
            balanceExempt = false,
            sickPayRate = 0.65
        ).toDayRecord()

        assertEquals(Shift.SICK, record.shift)
        assertEquals(CONFIG.pay.defaultHours, record.hours)
        assertEquals(CONFIG.pay.defaultHours, record.totalHours)
    }

    @Test
    fun weekendOvertimeTypeIsPreservedOnlyForWeekendWorkDay() {
        val weekendState = DayEditSaveState(
            date = DayKey(2026, 2, 7),
            shift1 = Shift.FIRST,
            workHours1 = 8,
            shift2 = Shift.SECOND,
            workHours2 = 0,
            hasDouble = false,
            firstShiftAllowsDouble = true,
            overtimeType = OvertimeType.PAID,
            weekendOvertimeType = OvertimeType.COMP_TIME,
            compHoursUsed = 0,
            holidayWorkOverride = null,
            holidayOverride = null,
            balanceExempt = false,
            sickPayRate = CONFIG.pay.sickPayRate
        )

        assertEquals(OvertimeType.COMP_TIME, weekendState.toDayRecord().weekendOvertimeType)
        assertEquals(
            OvertimeType.PAID,
            weekendState.copy(date = DayKey(2026, 2, 6)).toDayRecord().weekendOvertimeType
        )
        assertEquals(
            OvertimeType.PAID,
            weekendState.copy(shift1 = Shift.OFF, workHours1 = 0).toDayRecord().weekendOvertimeType
        )
    }

    @Test
    fun saveStatePreservesHolidayOverridesCompHoursAndBalanceExemption() {
        val record = DayEditSaveState(
            date = DayKey(2026, 2, 2),
            shift1 = Shift.OFF,
            workHours1 = 8,
            shift2 = Shift.THIRD,
            workHours2 = 0,
            hasDouble = false,
            firstShiftAllowsDouble = false,
            overtimeType = OvertimeType.COMP_TIME,
            weekendOvertimeType = OvertimeType.COMP_TIME,
            compHoursUsed = 3,
            holidayWorkOverride = true,
            holidayOverride = false,
            balanceExempt = true,
            sickPayRate = CONFIG.pay.sickPayRate
        ).toDayRecord()

        assertEquals(3, record.compHoursUsed)
        assertEquals(true, record.holidayWorkOverride)
        assertEquals(false, record.holidayOverride)
        assertEquals(true, record.balanceExempt)
    }

    @Test
    fun saveStatePreservesShortageMinutesOnlyForRegularWork() {
        val state = DayEditSaveState(
            date = DayKey(2026, 2, 2),
            shift1 = Shift.FIRST,
            workHours1 = 6,
            shift2 = Shift.SECOND,
            workHours2 = 4,
            hasDouble = true,
            firstShiftAllowsDouble = true,
            overtimeType = OvertimeType.PAID,
            weekendOvertimeType = OvertimeType.PAID,
            compHoursUsed = 0,
            holidayWorkOverride = null,
            holidayOverride = null,
            balanceExempt = false,
            sickPayRate = CONFIG.pay.sickPayRate,
            regularShortageMinutes = 30
        )

        assertEquals(30, state.toDayRecord().regularShortageMinutes)
        assertEquals(0, state.copy(shift1 = Shift.SICK).toDayRecord().regularShortageMinutes)
        assertEquals(0, state.copy(shift1 = Shift.OFF, workHours1 = 0).toDayRecord().regularShortageMinutes)
    }

    @Test
    fun fullRegularDayDropsShortageMinutesBeforeSaving() {
        val record = DayEditSaveState(
            date = DayKey(2026, 2, 2),
            shift1 = Shift.FIRST,
            workHours1 = CONFIG.pay.defaultHours,
            shift2 = Shift.SECOND,
            workHours2 = 4,
            hasDouble = true,
            firstShiftAllowsDouble = true,
            overtimeType = OvertimeType.PAID,
            weekendOvertimeType = OvertimeType.PAID,
            compHoursUsed = 0,
            holidayWorkOverride = null,
            holidayOverride = null,
            balanceExempt = false,
            sickPayRate = CONFIG.pay.sickPayRate,
            regularShortageMinutes = 30
        ).toDayRecord()

        assertEquals(0, record.regularShortageMinutes)
        assertEquals(12, record.totalHours)
        assertEquals(720, record.totalWorkMinutes)
    }
}
