package com.rad.shifttracker

import java.time.DayOfWeek
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test
import com.rad.shifttracker.listici.ParsedPayslip
import com.rad.shifttracker.listici.PayslipParseResult
import com.rad.shifttracker.listici.parsePayslip
import com.rad.shifttracker.repo.BatchRecord
import com.rad.shifttracker.repo.getGrossHourlyRateForMonth
import com.rad.shifttracker.repo.getScheduledShiftForDate
import com.rad.shifttracker.repo.payCoefficientValue
import com.rad.shifttracker.repo.savePayslip
import com.rad.shifttracker.smene.DayRecordSource
import com.rad.shifttracker.smene.MonthKey
import com.rad.shifttracker.smene.Shift
import com.rad.shifttracker.uvoz.finalPayslipCheckMonths

/** Sitnice iz provere po listama (10.7): stari meseci po svojoj listi, propuštena lista, raspored smena. */
class SmallFixes107Test {

    private fun slip(lines: List<String>): ParsedPayslip = (parsePayslip(lines) as PayslipParseResult.Success).payslip

    private fun januaryAs(month: String, extra: List<String> = emptyList()): ParsedPayslip =
        slip(requireNotNull(PAYSLIPS_2026[1]).map { it.replace("ZA MESEC 01/2026", "ZA MESEC $month") } + extra)

    @Test
    fun monthWithPayslipKeepsItsCoefficientAfterPromotion() {
        val repo = testRepository()
        repo.savePayslip(slip(requireNotNull(PAYSLIPS_2026[1])))
        repo.payCoefficientValue = 3.0

        assertEquals(2.82 * 226.0, repo.getGrossHourlyRateForMonth(2026, 1), 0.001)
        assertEquals(3.0 * 226.0, repo.getGrossHourlyRateForMonth(2026, 10), 0.001)
    }

    @Test
    fun taxReliefFollowsPayslipsOfTheSamePaymentYear() {
        val repo = testRepository()
        repo.savePayslip(januaryAs("06/2025", listOf("PORESKA OLAKŠICA 28,423.00 0.00 28,423.00")))

        assertEquals(28_423.0, repo.payQueries.taxReliefUnsafe(2025, 6), 0.001)
        // 07/2025 nema listu, ali se isplaćuje iste godine kao 06/2025.
        assertEquals(28_423.0, repo.payQueries.taxReliefUnsafe(2025, 7), 0.001)
        // 12/2025 se isplaćuje u 2026: važi podešavanje (najnovija olakšica).
        assertEquals(34_221.0, repo.payQueries.taxReliefUnsafe(2025, 12), 0.001)
        assertEquals(34_221.0, repo.payQueries.taxReliefUnsafe(2026, 10), 0.001)
    }

    @Test
    fun missedFinalPayslipIsLookedUpNextMonth() {
        val repo = testRepository()
        repo.savePayslip(januaryAs("08/2026"))

        // Lista za 09 nije preuzeta u oktobru; 03.11. se traži ona (lista za 10 još ne postoji).
        assertEquals(listOf(MonthKey(2026, 9)), finalPayslipCheckMonths(repo, LocalDate.of(2026, 11, 3)))
        assertEquals(
            listOf(MonthKey(2026, 9), MonthKey(2026, 10)),
            finalPayslipCheckMonths(repo, LocalDate.of(2026, 11, 16))
        )
        // Bez ijedne sačuvane liste ne traži se unazad.
        assertEquals(listOf(MonthKey(2026, 10)), finalPayslipCheckMonths(testRepository(), LocalDate.of(2026, 11, 16)))
    }

    @Test
    fun scheduleIsRecognizedAfterRefillWithAnotherBrigade() {
        val repo = testRepository()
        val batch = mutableListOf<BatchRecord>()
        fun fill(from: LocalDate, to: LocalDate, phase: Shift) {
            var date = from
            while (date <= to) {
                if (date.dayOfWeek.value <= DayOfWeek.FRIDAY.value) {
                    batch += BatchRecord(date.year, date.monthValue, date.dayOfMonth, rotationShiftFor(date, phase), 8, source = DayRecordSource.AUTO_FILL)
                }
                date = date.plusDays(1)
            }
        }
        // Prvo „Popuni“ sa jednom brigadom, pa posle promene brigade za naredne mesece sa drugom.
        fill(LocalDate.of(2026, 3, 2), LocalDate.of(2026, 5, 29), Shift.THIRD)
        fill(LocalDate.of(2026, 6, 1), LocalDate.of(2026, 9, 30), Shift.FIRST)
        repo.setBatch(batch)

        val date = LocalDate.of(2026, 10, 7)
        assertEquals(rotationShiftFor(date, Shift.FIRST), repo.getScheduledShiftForDate(date))
    }
}
