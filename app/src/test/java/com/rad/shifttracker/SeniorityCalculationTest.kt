package com.rad.shifttracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import com.rad.shifttracker.obracun.PayCalculator
import com.rad.shifttracker.obracun.SeniorityBreakdown
import com.rad.shifttracker.obracun.grossHourlyRateForMonth
import com.rad.shifttracker.smene.DayRecord
import com.rad.shifttracker.smene.OvertimeType
import com.rad.shifttracker.smene.Shift
import com.rad.shifttracker.osnova.PayConfig

class SeniorityCalculationTest {
    private val standardHourlyGross = grossHourlyRateForMonth(2.82, 2026, 1, emptyMap())

    @Test
    fun `test 1 January slip fixture includes only 105 regular hours`() {
        val result = breakdown(regularHours = 105, overtimeHours = 5, years = 7)

        assertEquals(637.32, result.baseHourlyGross, 0.001)
        assertEquals(66_918.60, result.seniorityEligibleBaseGross, 0.001)
        assertEquals(2_342.15, result.grossSeniorityBonus, 0.001)
        assertEquals(5 * 60, result.overtimeBaseMinutes)
        assertExcluded(result, "overtime base", "vacation", "state holiday not worked", "KIRU")
    }

    @Test
    fun `test 2 February slip fixture includes regular and public holiday work base once`() {
        val result = breakdown(regularHours = 131, holidayWorkHours = 8, overtimeHours = 8, years = 7)

        assertEquals(83_488.92, result.regularWorkedBaseGross, 0.001)
        assertEquals(5_098.56, result.publicHolidayWorkedBaseGross, 0.001)
        assertEquals(88_587.48, result.seniorityEligibleBaseGross, 0.001)
        assertEquals(3_100.56, result.grossSeniorityBonus, 0.001)
        assertExcluded(result, "holiday premium", "night premium", "Sunday premium", "overtime premium")
    }

    @Test
    fun `test 3 March slip fixture excludes sick leave`() {
        val result = breakdown(regularHours = 134, overtimeHours = 3, years = 7)

        assertEquals(85_400.88, result.seniorityEligibleBaseGross, 0.001)
        assertEquals(2_989.03, result.grossSeniorityBonus, 0.001)
        assertExcluded(result, "sick leave")
    }

    @Test
    fun `test 4 May slip fixture includes 132 regular and 13 holiday work hours`() {
        val result = breakdown(regularHours = 132, holidayWorkHours = 13, overtimeHours = 6, years = 7)

        assertEquals(92_411.40, result.seniorityEligibleBaseGross, 0.001)
        assertEquals(3_234.40, result.grossSeniorityBonus, 0.001)
        assertExcluded(result, "paid absence", "holiday premium")
    }

    @Test
    fun `test 5 June payroll slip fixture is independent from saved calendar`() {
        val result = breakdown(regularHours = 139, overtimeHours = 6, years = 7)

        assertEquals(88_587.48, result.seniorityEligibleBaseGross, 0.001)
        assertEquals(3_100.56, result.grossSeniorityBonus, 0.001)
    }

    @Test
    fun `test 6 employee with 2 completed years uses one percent`() {
        val hourlyGross = grossHourlyRateForMonth(2.70, 2026, 1, emptyMap())
        val result = breakdown(hourlyGross = hourlyGross, regularHours = 126, years = 2)

        assertEquals(610.20, result.baseHourlyGross, 0.001)
        assertEquals(76_885.20, result.seniorityEligibleBaseGross, 0.001)
        assertEquals(0.01, result.seniorityTotalRate, 0.000_001)
        assertEquals(768.85, result.grossSeniorityBonus, 0.001)
    }

    @Test
    fun `test 7 employee with 41 completed years uses twenty point five percent`() {
        val hourlyGross = grossHourlyRateForMonth(2.70, 2026, 1, emptyMap())
        val result = breakdown(hourlyGross = hourlyGross, regularHours = 144, years = 41)

        assertEquals(87_868.80, result.seniorityEligibleBaseGross, 0.001)
        assertEquals(0.205, result.seniorityTotalRate, 0.000_001)
        assertEquals(18_013.10, result.grossSeniorityBonus, 0.001)
        assertExcluded(result, "vacation", "difficult conditions premium", "night premium")
    }

    @Test
    fun `test 8 changing overtime changes gross pay but not seniority base or bonus`() {
        val calculator = PayCalculator(testConfig())
        val regular = DayRecord(2026, 6, 1, Shift.FIRST, 8, holidayOverride = false)
        val withOvertime = DayRecord(
            2026, 6, 1, Shift.FIRST, 8,
            shift2 = Shift.SECOND,
            hours2 = 6,
            overtimeType = OvertimeType.PAID,
            holidayOverride = false
        )
        val withoutOvertimeSummary = calculator.calculateMonthSummary(
            listOf(regular), standardHourlyGross, yearsOfService = 7
        )
        val withOvertimeSummary = calculator.calculateMonthSummary(
            listOf(withOvertime), standardHourlyGross, yearsOfService = 7
        )

        assertEquals(
            withoutOvertimeSummary.seniorityEligibleBaseGross,
            withOvertimeSummary.seniorityEligibleBaseGross,
            0.001
        )
        assertEquals(withoutOvertimeSummary.minuliRadBonus, withOvertimeSummary.minuliRadBonus, 0.001)
        assertNotEquals(withoutOvertimeSummary.totalPay, withOvertimeSummary.totalPay, 0.001)
        assertEquals(6 * 60, withOvertimeSummary.overtimeBaseMinutes)
    }

    @Test
    fun `test 9 changing premiums does not change seniority base or bonus`() {
        val records = listOf(
            DayRecord(
                2026, 5, 1, Shift.THIRD, 8,
                holidayWorkOverride = true,
                holidayOverride = true
            ),
            DayRecord(2026, 5, 3, Shift.THIRD, 8, holidayOverride = false)
        )
        val lowPremiumSummary = PayCalculator(
            testConfig().copy(
                shift3Premium = 1.05,
                overtimePremium = 1.10,
                sundayBonus = 0.05,
                holidayBonus = 0.10,
                difficultWorkConditionsPercent = 5.0
            )
        ).calculateMonthSummary(records, standardHourlyGross, yearsOfService = 7)
        val highPremiumSummary = PayCalculator(
            testConfig().copy(
                shift3Premium = 2.0,
                overtimePremium = 1.80,
                sundayBonus = 0.50,
                holidayBonus = 1.50,
                difficultWorkConditionsPercent = 40.0
            )
        ).calculateMonthSummary(records, standardHourlyGross, yearsOfService = 7)

        assertEquals(lowPremiumSummary.seniorityEligibleBaseGross, highPremiumSummary.seniorityEligibleBaseGross, 0.001)
        assertEquals(lowPremiumSummary.minuliRadBonus, highPremiumSummary.minuliRadBonus, 0.001)
        assertNotEquals(lowPremiumSummary.totalPay, highPremiumSummary.totalPay, 0.001)
    }

    @Test
    fun `test 10 public holiday base already included in regular work is not duplicated`() {
        val result = PayCalculator(testConfig()).calculateSeniorityBreakdown(
            baseHourlyGross = standardHourlyGross,
            regularWorkedBaseMinutes = 8 * 60,
            publicHolidayWorkedBaseMinutes = 8 * 60,
            overtimeBaseMinutes = 0,
            completedYearsOfService = 7,
            publicHolidayBaseIncludedInRegular = true
        )

        assertEquals(8 * 60, result.regularWorkedBaseMinutes)
        assertEquals(0, result.publicHolidayWorkedBaseMinutes)
        assertEquals(8 * 60, result.seniorityEligibleMinutes)
        assertEquals(5_098.56, result.seniorityEligibleBaseGross, 0.001)
        assertTrue(result.publicHolidayBaseIncludedInRegular)
    }

    private fun breakdown(
        hourlyGross: Double = standardHourlyGross,
        regularHours: Int,
        holidayWorkHours: Int = 0,
        overtimeHours: Int = 0,
        years: Int
    ): SeniorityBreakdown {
        return PayCalculator(testConfig()).calculateSeniorityBreakdown(
            baseHourlyGross = hourlyGross,
            regularWorkedBaseMinutes = regularHours * 60,
            publicHolidayWorkedBaseMinutes = holidayWorkHours * 60,
            overtimeBaseMinutes = overtimeHours * 60,
            completedYearsOfService = years
        )
    }

    private fun assertExcluded(result: SeniorityBreakdown, vararg categoryFragments: String) {
        categoryFragments.forEach { fragment ->
            assertTrue(result.excludedCategories.any { it.contains(fragment, ignoreCase = true) })
        }
    }

    private fun testConfig(): PayConfig {
        return PayConfig(
            defaultBasePay = standardHourlyGross,
            defaultHours = 8,
            shift3Premium = 1.41,
            overtimePremium = 1.41,
            sundayBonus = 0.25,
            holidayBonus = 1.23,
            foodPerDay = 0.0,
            minuliRadPerYear = 0.005,
            vacationPayBaseHourly = standardHourlyGross,
            paidHolidayPayBaseHourly = standardHourlyGross,
            sickPayBaseHourly = standardHourlyGross
        )
    }
}
