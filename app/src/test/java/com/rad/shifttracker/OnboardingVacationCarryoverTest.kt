package com.rad.shifttracker

import java.math.BigDecimal
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test
import com.rad.shifttracker.godisnji.VacationYearBalance
import com.rad.shifttracker.listici.ParsedPayslip
import com.rad.shifttracker.listici.PayslipItem
import com.rad.shifttracker.listici.PayslipParseResult
import com.rad.shifttracker.uvoz.applyOnboardingVacation
import com.rad.shifttracker.listici.parsePayslip
import com.rad.shifttracker.repo.ShiftRepository
import com.rad.shifttracker.repo.getVacationBalanceSummary
import com.rad.shifttracker.repo.getVacationYearBalance
import com.rad.shifttracker.repo.initialVacationBalanceValue
import com.rad.shifttracker.repo.isVacationCarryoverAutomatic
import com.rad.shifttracker.repo.pruneIrrelevantHistory
import com.rad.shifttracker.repo.savePayslip
import com.rad.shifttracker.repo.setVacationYearBalance
import com.rad.shifttracker.smene.MonthKey
import com.rad.shifttracker.smene.Shift

/**
 * Godišnji podešen iz listića (onboarding): dani iskorišćeni januar–jun su stari (preneti) odmor,
 * ne novi. Ranije je prošla godina počinjala bez prenosa, pa su se januar–jun prošle godine
 * trošili iz njenog prava, a razlika se u tekućoj godini javljala kao „potrošeno“ bez ijednog dana.
 */
class OnboardingVacationCarryoverTest {

    private fun slip(year: Int, month: Int, vacationDays: Int): ParsedPayslip {
        val base = (parsePayslip(requireNotNull(PAYSLIPS_2026[8])) as PayslipParseResult.Success).payslip
        val hours = BigDecimal(vacationDays * 8)
        val items = base.items - 10 + (10 to PayslipItem(10, "Godisnji odmor", hours, null, BigDecimal("1000")))
        return base.copy(month = MonthKey(year, month), items = items)
    }

    /** Jan–jun 2025: 9 dana (stari odmor), jul–dec 2025: 15, jan–jun 2026: 9, jul–avg 2026: 0. */
    private fun onboardedRepo(): ShiftRepository {
        val repo = testRepository()
        val days2025 = mapOf(3 to 5, 5 to 4, 7 to 5, 8 to 10)
        val days2026 = mapOf(2 to 4, 4 to 5)
        (1..12).forEach { repo.savePayslip(slip(2025, it, days2025[it] ?: 0), withPayBase = false) }
        (1..8).forEach { repo.savePayslip(slip(2026, it, days2026[it] ?: 0), withPayBase = false) }
        applyOnboardingVacation(repo, LocalDate.of(2026, 9, 25))
        return repo
    }

    @Test
    fun firstHalfOfYearIsCarryoverNotNewLeave() {
        val repo = onboardedRepo()
        val total = repo.getVacationYearBalance(2026)!!.currentYearDaysTotal
        assertEquals("pravo iz jul 2025 – jun 2026", 24.0, total, 0.0)

        val july = repo.getVacationBalanceSummary(2026, LocalDate.of(2026, 7, 31))
        assertEquals("u julu ništa od novog nije potrošeno", 0.0, july.currentYearUsed, 0.0)
        assertEquals(24.0, july.currentYearRemaining, 0.0)
        assertEquals("jan–jun 2026 = preneti odmor", 9.0, july.carryoverUsed, 0.0)
    }

    @Test
    fun previousYearOnlyChargesJulyToDecember() {
        val summary2025 = onboardedRepo().getVacationBalanceSummary(2025, LocalDate.of(2025, 12, 31))
        assertEquals(15.0, summary2025.currentYearUsed, 0.0)
        assertEquals(9.0, summary2025.currentYearRemaining, 0.0)
    }

    /**
     * Na telefonu: smene popunjene unapred pomere granicu brisanja istorije, pa se 2025 obriše, a
     * prenos za 2026 se zamrzne — pre ispravke sa pogrešnom vrednošću (0). I tada mora 24.
     */
    @Test
    fun wronglyFrozenCarryoverIsRepairedFromFirstHalfPayslips() {
        val repo = onboardedRepo()
        val saved2026 = repo.getVacationYearBalance(2026)!!
        repo.setVacationYearBalance(saved2026.copy(carryoverDaysInitial = 0.0, carryoverFrozen = true))
        repo.retentionClock = { java.time.YearMonth.of(2027, 12) }
        repo.set(2027, 12, 1, Shift.FIRST, 0)
        repo.pruneIrrelevantHistory()
        assertEquals("2025 je obrisana", null, repo.getVacationYearBalance(2025))
        assertEquals(true, repo.getVacationYearBalance(2026)!!.carryoverFrozen)

        val july = repo.getVacationBalanceSummary(2026, LocalDate.of(2026, 7, 31))
        assertEquals(0.0, july.currentYearUsed, 0.0)
        assertEquals(24.0, july.currentYearRemaining, 0.0)
    }

    /**
     * Stanje sa telefona 27.09.2026: 2025 postoji (lanac), ali njeni listići ne daju pun ostatak
     * (npr. jul 2025 fali u Gmail-u, pa je pravo 2025 po KU). Jan 4 + apr 3 + jun 2 = 9 dana 2026
     * se brojalo kao novi odmor (avgust: „potrošeno 20/25“ uz 11 dana u avgustu).
     */
    @Test
    fun chainedCarryoverIsAtLeastFirstHalfOfThisYear() {
        val repo = testRepository()
        (1..12).forEach { repo.savePayslip(slip(2025, it, if (it == 10) 20 else 0), withPayBase = false) }
        val days2026 = mapOf(1 to 4, 4 to 3, 6 to 2, 8 to 11)
        (1..8).forEach { repo.savePayslip(slip(2026, it, days2026[it] ?: 0), withPayBase = false) }
        listOf(2025, 2026).forEach { year ->
            repo.setVacationYearBalance(
                VacationYearBalance(year, LocalDate.of(year, 1, 1), 0.0, 25.0, 0.0, LocalDate.of(year, 7, 1), false)
            )
        }
        assertEquals(true, repo.isVacationCarryoverAutomatic(2026))

        val august = repo.getVacationBalanceSummary(2026, LocalDate.of(2026, 8, 31))
        assertEquals("samo avgust je novi odmor", 11.0, august.currentYearUsed, 0.0)
        assertEquals(14.0, august.currentYearRemaining, 0.0)
    }

    /** Stara instalacija bez ijedne sačuvane godine (samo broj dana): isto pravilo. */
    @Test
    fun legacyBalanceAlsoTreatsFirstHalfAsOld() {
        val repo = testRepository()
        val days2026 = mapOf(1 to 4, 4 to 3, 6 to 2, 8 to 11)
        (1..8).forEach { repo.savePayslip(slip(2026, it, days2026[it] ?: 0), withPayBase = false) }
        repo.initialVacationBalanceValue = 25.0
        assertEquals(null, repo.getVacationYearBalance(2026))

        val august = repo.getVacationBalanceSummary(2026, LocalDate.of(2026, 8, 31))
        assertEquals(11.0, august.currentYearUsed, 0.0)
        assertEquals(14.0, august.currentYearRemaining, 0.0)
    }

    /** Veći ručni prenos ostaje; manji od jan–jun sa listića se diže na jan–jun. */
    @Test
    fun manualCarryoverIsAtLeastFirstHalf() {
        val repo = onboardedRepo()
        val saved = repo.getVacationYearBalance(2025)!!
        repo.setVacationYearBalance(saved.copy(carryoverDaysInitial = 2.0, manualBaseline = true))
        assertEquals(9.0, repo.getVacationBalanceSummary(2025, LocalDate.of(2025, 12, 31)).carryoverInitial, 0.0)
        repo.setVacationYearBalance(saved.copy(carryoverDaysInitial = 12.0, manualBaseline = true))
        assertEquals(12.0, repo.getVacationBalanceSummary(2025, LocalDate.of(2025, 12, 31)).carryoverInitial, 0.0)
    }

    /**
     * Tačno stanje sa telefona (slika 27.09.2026, v4.3 i dalje 20/25): 2026 sačuvana iz dijaloga
     * (ručno, prenos 0), 2025 više ne postoji. Jan 4 + apr 3 + jun 2 je stari odmor, avgust 11 novi.
     */
    @Test
    fun phoneStateManualYearWithoutPreviousYear() {
        val repo = testRepository()
        val days2026 = mapOf(1 to 4, 4 to 3, 6 to 2, 8 to 11)
        (1..8).forEach { repo.savePayslip(slip(2026, it, days2026[it] ?: 0), withPayBase = false) }
        repo.setVacationYearBalance(
            VacationYearBalance(2026, LocalDate.of(2026, 1, 1), 0.0, 25.0, 0.0, LocalDate.of(2026, 7, 1), true)
        )

        val august = repo.getVacationBalanceSummary(2026, LocalDate.of(2026, 8, 31))
        assertEquals(11.0, august.currentYearUsed, 0.0)
        assertEquals(14.0, august.currentYearRemaining, 0.0)
        assertEquals(25.0, repo.getVacationBalanceSummary(2026, LocalDate.of(2026, 7, 31)).currentYearRemaining, 0.0)
    }
}
