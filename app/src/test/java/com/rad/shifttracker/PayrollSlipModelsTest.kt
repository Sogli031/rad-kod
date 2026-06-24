package com.rad.shifttracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PayrollSlipModelsTest {
    @Test
    fun `validation rejects negative hours and impossible monthly hours`() {
        val invalid = PayrollSlipInput(
            month = MonthKey(2026, 13),
            regularWorkHours = -1.0,
            nightHours = 401.0
        )

        val errors = invalid.validationErrors()

        assertTrue(errors.contains("Mesec mora biti između 1 i 12."))
        assertTrue(errors.contains("Rad u režiji ne može biti negativan."))
        assertTrue(errors.contains("Noćni rad ne može biti veći od 400h."))
    }

    @Test
    fun `quarter hour parsing accepts supported values and rejects unsupported values`() {
        assertEquals(420, slipHoursToMinutes(7.0))
        assertEquals(435, slipHoursToMinutes(7.25))
        assertEquals(450, slipHoursToMinutes(7.5))
        assertEquals(465, slipHoursToMinutes(7.75))
        assertNull(slipHoursToMinutes(7.001))
        assertNull(slipHoursToMinutes(7.999))
        assertNull(slipHoursToMinutes(7.10))
    }

    @Test
    fun `validation rejects invalid year`() {
        val errors = PayrollSlipInput(month = MonthKey(0, 1)).validationErrors()

        assertTrue(errors.contains("Godina mora biti veća od 0."))
    }

    @Test
    fun `validation rejects non finite hours`() {
        val errors = PayrollSlipInput(
            month = MonthKey(2026, 1),
            regularWorkHours = Double.NaN,
            nightHours = Double.POSITIVE_INFINITY,
            overtimeHours = Double.NEGATIVE_INFINITY
        ).validationErrors()

        assertTrue(errors.contains("Rad u režiji mora biti broj."))
        assertTrue(errors.contains("Noćni rad mora biti broj."))
        assertTrue(errors.contains("Prekovremeni rad mora biti broj."))
    }

    @Test
    fun `validation rejects invalid money values`() {
        val errors = PayrollSlipInput(
            month = MonthKey(2026, 1),
            mealGross = Double.NaN,
            regressGross = Double.POSITIVE_INFINITY,
            otherGross = -1.0
        ).validationErrors()

        assertTrue(errors.contains("Topli obrok mora biti broj."))
        assertTrue(errors.contains("Regres mora biti broj."))
        assertTrue(errors.contains("Ostalo bruto ne može biti negativan."))
    }

    @Test
    fun `draft conversion preserves day record overrides`() {
        val draft = PayrollSlipDayDraft(
            year = 2026,
            month = 1,
            day = 6,
            shift = Shift.FIRST,
            hours = 7,
            shift2 = Shift.SECOND,
            hours2 = 4,
            overtimeType = OvertimeType.COMP_TIME,
            weekendOvertimeType = OvertimeType.COMP_TIME,
            holidayWorkOverride = true,
            holidayOverride = false,
            balanceExempt = true,
            sickPayRate = 0.65,
            regularShortageMinutes = 15
        )

        val record = draft.toDayRecord()

        assertEquals(Shift.SECOND, record.shift2)
        assertEquals(4, record.hours2)
        assertEquals(OvertimeType.COMP_TIME, record.overtimeType)
        assertEquals(OvertimeType.COMP_TIME, record.weekendOvertimeType)
        assertEquals(true, record.holidayWorkOverride)
        assertEquals(false, record.holidayOverride)
        assertEquals(true, record.balanceExempt)
        assertEquals(15, record.regularShortageMinutes)

        val sickRecord = PayrollSlipDayDraft(
            year = 2026,
            month = 1,
            day = 7,
            shift = Shift.SICK,
            hours = 8,
            sickPayRate = 0.65
        ).toDayRecord()

        assertEquals(0.65, sickRecord.sickPayRate!!, 0.001)
    }

    @Test
    fun `warning types include every payslip warning`() {
        assertEquals(
            setOf(
                PayrollSlipWarningType.APPROXIMATE_DATES,
                PayrollSlipWarningType.NO_REFERENCE_WEEK,
                PayrollSlipWarningType.UNSUPPORTED_ABSENCE_MINUTES,
                PayrollSlipWarningType.INFEASIBLE_TARGET,
                PayrollSlipWarningType.TARGET_MISMATCH,
                PayrollSlipWarningType.EXISTING_MONTH_WILL_BE_REPLACED
            ),
            PayrollSlipWarningType.values().toSet()
        )
    }

    @Test
    fun `gross additions map payslip fields to existing monthly additions`() {
        val input = PayrollSlipInput(
            month = MonthKey(2026, 2),
            mealGross = 7_589.16,
            regressGross = 28_530.67,
            annualAwardGross = 1_000.0,
            kirGross = 2_000.0,
            otherGross = 3_000.0
        )

        val additions = input.toMonthlyGrossAdditions()

        assertEquals(2026, additions.year)
        assertEquals(2, additions.month)
        assertEquals(7_589.16, additions.mealGross, 0.01)
        assertEquals(28_530.67, additions.regressGross, 0.01)
        assertEquals(6_000.0, additions.bonusGross, 0.01)
    }

    @Test
    fun `comparison rows expose target calculated and delta`() {
        val row = PayrollSlipComparisonRow("Noćni rad", 24 * 60, 18 * 60)

        assertEquals(-360, row.deltaMinutes)
        assertEquals(false, row.isExact)
    }

    @Test
    fun `infeasible target warning blocks apply`() {
        val input = PayrollSlipInput(month = MonthKey(2026, 2), regularWorkHours = 152.0)
        val result = PayrollSlipReconstructionResult(
            input = input,
            records = listOf(PayrollSlipDayDraft(2026, 2, 2, Shift.FIRST, 8)),
            additions = input.toMonthlyGrossAdditions(),
            comparison = PayrollSlipComparison(emptyList(), calculatedNetBeforeDeductions = 0.0),
            warnings = listOf(
                PayrollSlipWarning(
                    PayrollSlipWarningType.INFEASIBLE_TARGET,
                    "Fond pokriven listićem je 152h, a fond meseca je 160h."
                )
            )
        )

        assertEquals(true, result.hasBlockingErrors)
    }
}
