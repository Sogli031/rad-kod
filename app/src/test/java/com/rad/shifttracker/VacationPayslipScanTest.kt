package com.rad.shifttracker

import java.math.BigDecimal
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.rad.shifttracker.godisnji.vacationScanStartYear
import com.rad.shifttracker.godisnji.vacationUsageScan
import com.rad.shifttracker.listici.PayslipItem
import com.rad.shifttracker.listici.PayslipParseResult
import com.rad.shifttracker.listici.parsePayslip
import com.rad.shifttracker.smene.MonthKey
import com.rad.shifttracker.ui.dialozi.formatVacationInput
import com.rad.shifttracker.godisnji.vacationUsageScanText
import com.rad.shifttracker.listici.PayslipSideRunKind

class VacationPayslipScanTest {
    private fun slip(month: MonthKey, vacationHours: String, run: String = "rb.3/konačna"): PayslipParseResult.Success {
        val base = (parsePayslip(requireNotNull(PAYSLIPS_2026[8])) as PayslipParseResult.Success).payslip
        val items = base.items - 10 + (10 to PayslipItem(10, "Godisnji odmor", BigDecimal(vacationHours), null, BigDecimal.ONE))
        return PayslipParseResult.Success(base.copy(month = month, runLabel = run, items = items))
    }

    private val period = (7..12).map { MonthKey(2025, it) } + (1..6).map { MonthKey(2026, it) }

    @Test
    fun periodFromJulyToJuneGivesEntitlement() {
        // Januar–jun 2025 i jul 2026 su van perioda i ne broje se.
        val results = period.map { slip(it, "16.0") } + slip(MonthKey(2025, 3), "80.0") + slip(MonthKey(2026, 7), "80.0")
        val scan = vacationUsageScan(results, 2025)
        assertTrue(scan.complete)
        assertEquals(24.0, scan.used, 0.0)
        assertEquals(
            "Iskorišćeno od 1.7.2025. do 30.6.2026.: ${formatVacationInput(24.0)} dana (12/12 listi).",
            vacationUsageScanText(scan)
        )
    }

    @Test
    fun latestRunOfTheMonthWinsAndSideRunsAreIgnored() {
        val results = listOf(
            slip(MonthKey(2025, 7), "40.0", run = "rb.2"),
            slip(MonthKey(2025, 7), "16.0", run = "rb.3/konačna"),
            PayslipParseResult.SideRun(PayslipSideRunKind.ADVANCE, MonthKey(2025, 8), "rb.1", BigDecimal.TEN)
        )
        val scan = vacationUsageScan(results, 2025)
        assertEquals(mapOf(MonthKey(2025, 7) to 2.0), scan.daysByMonth)
        assertFalse(scan.complete)
        assertTrue(vacationUsageScanText(scan), vacationUsageScanText(scan).contains("1/12 listi"))
        assertTrue(vacationUsageScanText(scan), vacationUsageScanText(scan).contains("fali Avgust 2025"))
    }

    @Test
    fun lastPeriodIsTheOneWhoseJunePayslipHasArrived() {
        assertEquals(2025, vacationScanStartYear(LocalDate.of(2026, 9, 25)))
        assertEquals(2025, vacationScanStartYear(LocalDate.of(2026, 8, 1)))
        assertEquals(2024, vacationScanStartYear(LocalDate.of(2026, 7, 15)))
        assertEquals(2024, vacationScanStartYear(LocalDate.of(2026, 1, 3)))
    }
}
