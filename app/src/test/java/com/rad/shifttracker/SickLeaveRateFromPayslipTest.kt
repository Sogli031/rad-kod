package com.rad.shifttracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import com.rad.shifttracker.listici.FillDayKind
import com.rad.shifttracker.listici.PayslipItem
import com.rad.shifttracker.smene.Shift
import com.rad.shifttracker.uvoz.MonthFillResult
import com.rad.shifttracker.uvoz.planMonthFromPayslip

/**
 * Lista popunjava „% RADA“ samo kad stavka nije 100%: 40 Bolovanje ima 65, a 50 „Bolovanje-Povreda
 * na radu“ (06/2025) nema procenat i plaća se 100%. Popuna po listi upisuje istu stopu.
 */
class SickLeaveRateFromPayslipTest {

    private fun sickRatesAfterFill(replaceWithInjury: Boolean): List<Double?> {
        val truth = truthMonth(2026, 3, Shift.THIRD, listOf(TruthDay(10, Shift.SICK, sickRate = 0.65)))
        val slip = payslipFromRepo(truth, 2026, 3).let { slip ->
            if (!replaceWithInjury) return@let slip
            val sick = requireNotNull(slip.items[40])
            slip.copy(
                items = slip.items - 40 + (50 to PayslipItem(50, "Bolovanje-Povreda na radu", sick.hours, null, sick.amount))
            )
        }
        val result = planMonthFromPayslip(templateRepoBefore(2026, 3, Shift.THIRD), slip)
        assertTrue(result.toString(), result is MonthFillResult.Success)
        return (result as MonthFillResult.Success).days.filter { it.kind == FillDayKind.SICK }.map { it.sickRate }
    }

    @Test
    fun sickLeaveWithPercentKeepsIt() {
        assertEquals(listOf(0.65), sickRatesAfterFill(replaceWithInjury = false))
    }

    @Test
    fun workInjuryWithoutPercentIsFullPay() {
        assertEquals(listOf(1.0), sickRatesAfterFill(replaceWithInjury = true))
    }
}
