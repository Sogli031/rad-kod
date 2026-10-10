package com.rad.shifttracker

import org.junit.Assert.assertEquals
import org.junit.Test
import com.rad.shifttracker.listici.ParsedPayslip
import com.rad.shifttracker.listici.PayslipParseResult
import com.rad.shifttracker.listici.parsePayslip
import com.rad.shifttracker.obracun.SeniorityIncreaseFactor
import com.rad.shifttracker.obracun.calculatePayslipAnchoredSeniorityYears
import com.rad.shifttracker.repo.getHourlyValueForMonth
import com.rad.shifttracker.repo.getMonthlyPayDetails
import com.rad.shifttracker.repo.setHourlyValueChange
import com.rad.shifttracker.uvoz.applyOnboardingPayslips

/**
 * Liste Valjaonice povećavaju staž za minuli rad u januaru (12/2025: 6 god., 01/2026: 7 god.),
 * pa staž sa liste 08/2026 važi od januara 2026. do decembra 2026, a ne od avgusta do jula.
 */
class SeniorityAnniversaryTest {

    private fun years(month: Int, year: Int, anniversary: Int?) = calculatePayslipAnchoredSeniorityYears(
        lastPayslipYear = 2026,
        lastPayslipMonth = 8,
        payslipPercent = 3.5,
        targetYear = year,
        targetMonth = month,
        increaseFactor = SeniorityIncreaseFactor.NONE,
        ratePerYear = 0.005,
        anniversaryMonth = anniversary
    )

    @Test
    fun `january anniversary steps years at january`() {
        assertEquals(6, years(12, 2025, 1))
        assertEquals(7, years(1, 2026, 1))
        assertEquals(7, years(12, 2026, 1))
        assertEquals(8, years(1, 2027, 1))
        assertEquals(8, years(12, 2027, 1))
        assertEquals(9, years(1, 2028, 1))
    }

    @Test
    fun `without known anniversary years grow twelve months after the payslip`() {
        assertEquals(6, years(1, 2026, null))
        assertEquals(7, years(8, 2026, null))
        assertEquals(7, years(7, 2027, null))
        assertEquals(8, years(8, 2027, null))
    }

    private fun slip(lines: List<String>): ParsedPayslip =
        (parsePayslip(lines) as PayslipParseResult.Success).payslip

    private fun withSeniority(month: Int, years: Int): List<String> =
        requireNotNull(PAYSLIPS_2026[month]) + "Radni staž za MR:$years"

    @Test
    fun `onboarding with august payslip keeps 3 point 5 percent for january to july`() {
        val december = withSeniority(1, 6).map { line ->
            line.replace("ZA MESEC 01/2026", "ZA MESEC 12/2025").replace("MR Minuli rad 3.5", "MR Minuli rad 3")
        }
        val payslips = listOf(slip(december)) + (1..8).map { slip(withSeniority(it, 7)) }
        val repo = testRepository()
        applyOnboardingPayslips(repo, payslips)

        assertEquals(6, repo.getMonthlyPayDetails(2025, 12).completedYearsOfService)
        assertEquals(7, repo.getMonthlyPayDetails(2026, 1).completedYearsOfService)
        assertEquals(7, repo.getMonthlyPayDetails(2026, 7).completedYearsOfService)
        assertEquals(7, repo.getMonthlyPayDetails(2026, 12).completedYearsOfService)
        assertEquals(8, repo.getMonthlyPayDetails(2027, 1).completedYearsOfService)
    }

    @Test
    fun `old payslip hourly value does not change current months`() {
        val repo = testRepository()
        repo.setHourlyValueChange(2024, 6, 195.0)

        assertEquals(195.0, repo.getHourlyValueForMonth(2024, 9), 0.001)
        assertEquals(214.5, repo.getHourlyValueForMonth(2025, 9), 0.001)
        assertEquals(226.0, repo.getHourlyValueForMonth(2026, 10), 0.001)
    }
}
