package com.rad.shifttracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import com.rad.shifttracker.godisnji.VacationMonthUsage
import com.rad.shifttracker.godisnji.VacationWarnings
import com.rad.shifttracker.godisnji.VacationYearBalance
import com.rad.shifttracker.godisnji.calculateVacationBalanceSummary
import com.rad.shifttracker.listici.PayslipParseResult
import com.rad.shifttracker.listici.parsePayslip
import com.rad.shifttracker.repo.BatchRecord
import com.rad.shifttracker.repo.ShiftRepository
import com.rad.shifttracker.repo.getVacationBalanceSummary
import com.rad.shifttracker.repo.getVacationMonthsWithoutData
import com.rad.shifttracker.repo.isVacationCarryoverAutomatic
import com.rad.shifttracker.repo.savePayslip
import com.rad.shifttracker.repo.setVacationYearBalance
import com.rad.shifttracker.smene.Shift
import com.rad.shifttracker.ui.dialozi.VacationSetupVisibility
import com.rad.shifttracker.ui.dialozi.vacationSetupVisibility

class VacationFromPayslipsTest {
    private val today = LocalDate.of(2026, 9, 24)

    private fun repoWithRealPayslips(): ShiftRepository {
        val repo = testRepository()
        for (month in 1..8) {
            val slip = parsePayslip(requireNotNull(PAYSLIPS_2026[month])) as PayslipParseResult.Success
            repo.savePayslip(slip.payslip)
        }
        repo.setVacationYearBalance(
            VacationYearBalance(
                year = 2026,
                trackingStartDate = LocalDate.of(2026, 1, 1),
                carryoverDaysInitial = 0.0,
                currentYearDaysTotal = 24.0,
                currentYearDaysUsedBeforeAvailability = 0.0,
                currentYearAvailableFrom = LocalDate.of(2026, 7, 1),
                manualBaseline = true
            )
        )
        return repo
    }

    @Test
    fun `godisnji 2026 se racuna iz sifre 10 na listicima`() {
        val summary = repoWithRealPayslips().getVacationBalanceSummary(2026, today)

        // Januar 40 h, jul 96 h, avgust 16 h; plaćeno odsustvo (11) se ne broji.
        // Januar je stari (preneti) odmor — pre jula se uvek koristi stari (korisnik, 27.09.2026).
        assertEquals(14.0, summary.currentYearUsed, 0.001)
        assertEquals(10.0, summary.currentYearRemaining, 0.001)
        assertEquals(10.0, summary.availableNow, 0.001)
        assertEquals(5.0, summary.carryoverUsed, 0.001)
        assertEquals(
            listOf(VacationMonthUsage(1, 5.0, true, 0), VacationMonthUsage(7, 12.0, true, 0), VacationMonthUsage(8, 2.0, true, 0)),
            summary.usageByMonth
        )
    }

    @Test
    fun `mesec sa listicem ne broji kalendar dva puta a mesec bez listica broji kalendar`() {
        val repo = repoWithRealPayslips()
        repo.setBatch(
            listOf(
                // Jul: listić kaže 12, kalendar ima samo 3 — važi listić.
                BatchRecord(2026, 7, 6, Shift.VACATION, 8),
                BatchRecord(2026, 7, 7, Shift.VACATION, 8),
                BatchRecord(2026, 7, 8, Shift.VACATION, 8),
                // Septembar još nema listić — broji se kalendar.
                BatchRecord(2026, 9, 14, Shift.VACATION, 8),
                BatchRecord(2026, 9, 15, Shift.VACATION, 8)
            )
        )

        val summary = repo.getVacationBalanceSummary(2026, today)

        assertEquals(16.0, summary.currentYearUsed, 0.001)
        assertEquals(8.0, summary.currentYearRemaining, 0.001)
        assertEquals(VacationMonthUsage(7, 12.0, true, 3), summary.usageByMonth.first { it.month == 7 })
        assertEquals(VacationMonthUsage(9, 2.0, false, 2), summary.usageByMonth.first { it.month == 9 })
    }

    @Test
    fun `prenos u sledecu godinu dolazi iz listica`() {
        val summary = repoWithRealPayslips().getVacationBalanceSummary(2027, LocalDate.of(2027, 2, 1))

        assertEquals(10.0, summary.carryoverInitial, 0.001)
        assertEquals(24.0, summary.currentYearTotal, 0.001)
        assertTrue(summary.warnings.contains(VacationWarnings.CARRYOVER_VALID_UNTIL_JUNE))
    }

    @Test
    fun `sacuvan balans sledece godine uzima prenos iz listica a ne rucni broj`() {
        val repo = repoWithRealPayslips()
        repo.setVacationYearBalance(
            VacationYearBalance(2027, LocalDate.of(2027, 1, 1), 99.0, 24.0, 0.0, LocalDate.of(2027, 7, 1), true)
        )

        assertTrue(repo.isVacationCarryoverAutomatic(2027))
        assertEquals(false, repo.isVacationCarryoverAutomatic(2026))
        assertEquals(10.0, repo.getVacationBalanceSummary(2027, LocalDate.of(2027, 2, 1)).carryoverInitial, 0.001)
    }

    @Test
    fun `meseci bez listica i bez upisa`() {
        val repo = repoWithRealPayslips()

        // Septembar još traje, pa se ne traži.
        assertEquals(emptyList<Int>(), repo.getVacationMonthsWithoutData(2026, today))
        assertEquals(listOf(9), repo.getVacationMonthsWithoutData(2026, LocalDate.of(2026, 10, 5)))
        repo.setBatch(listOf(BatchRecord(2026, 9, 1, Shift.FIRST, 8)))
        assertEquals(emptyList<Int>(), repo.getVacationMonthsWithoutData(2026, LocalDate.of(2026, 10, 5)))
        assertEquals((1..12).toList(), repo.getVacationMonthsWithoutData(2025, today))
    }

    @Test
    fun `polja pocetnog stanja se sakrivaju kad nemaju sta da se unese`() {
        val saved = VacationYearBalance(2026, LocalDate.of(2026, 1, 1), 0.0, 24.0, 0.0, LocalDate.of(2026, 7, 1), true)

        // Septembar 2026: prenos je istekao, svi meseci imaju listić.
        assertEquals(
            VacationSetupVisibility(carryover = false, usedOutsideApp = false),
            vacationSetupVisibility(2026, today, saved, carryoverAutomatic = false, monthsWithoutData = emptyList())
        )
        // Maj bez praćene prošle godine: prenos se unosi; mesec bez podataka traži „van aplikacije“.
        assertEquals(
            VacationSetupVisibility(carryover = true, usedOutsideApp = true),
            vacationSetupVisibility(2026, LocalDate.of(2026, 5, 9), saved, carryoverAutomatic = false, monthsWithoutData = listOf(1))
        )
        // Prenos iz praćene 2026; uneta vrednost van aplikacije ostaje vidljiva.
        assertEquals(
            VacationSetupVisibility(carryover = false, usedOutsideApp = true),
            vacationSetupVisibility(
                2027,
                LocalDate.of(2027, 3, 1),
                saved.copy(year = 2027, currentYearDaysUsedBeforeAvailability = 2.0),
                carryoverAutomatic = true,
                monthsWithoutData = emptyList()
            )
        )
    }

    @Test
    fun `stari odmor se trosi i iz listica pre 30 juna`() {
        val summary = calculateVacationBalanceSummary(
            balance = VacationYearBalance(2027, LocalDate.of(2027, 1, 1), 5.0, 24.0, 0.0, LocalDate.of(2027, 7, 1), true),
            vacationDates = emptyList(),
            today = LocalDate.of(2027, 8, 31),
            payslipDaysByMonth = mapOf(3 to 3.0, 7 to 10.0)
        )

        assertEquals(3.0, summary.carryoverUsed, 0.001)
        assertEquals(2.0, summary.carryoverExpired, 0.001)
        assertEquals(10.0, summary.currentYearUsed, 0.001)
        assertEquals(14.0, summary.currentYearRemaining, 0.001)
    }
}
