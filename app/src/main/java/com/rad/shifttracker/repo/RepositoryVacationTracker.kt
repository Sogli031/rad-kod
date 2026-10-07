package com.rad.shifttracker.repo

import com.rad.shifttracker.godisnji.AnnualLeaveCalculationInput
import com.rad.shifttracker.smene.Shift
import com.rad.shifttracker.godisnji.VacationBalanceSummary
import com.rad.shifttracker.godisnji.VacationWarnings
import com.rad.shifttracker.godisnji.VacationYearBalance
import com.rad.shifttracker.godisnji.calculateVacationBalanceSummary
import com.rad.shifttracker.godisnji.currentYearVacationAvailableFrom
import com.rad.shifttracker.obracun.sanitizeAnnualLeaveYears
import java.time.Clock
import java.time.LocalDate

internal class RepositoryVacationTracker(
    private val recordStore: ShiftRecordStore,
    private val calculateYearsOfService: (year: Int, month: Int) -> Int,
    /** Dani godišnjeg sa sačuvanih listića za godinu (mesec → dana); mesec bez listića nije u mapi. */
    private val payslipVacationDaysByMonth: (year: Int) -> Map<Int, Double> = { emptyMap() },
    private val clock: Clock = Clock.systemDefaultZone()
) {
    private var initialVacationBalance = 0.0
    private val vacationYearBalances = mutableMapOf<Int, VacationYearBalance>()
    private var annualLeaveCalculationInput: AnnualLeaveCalculationInput? = null
    private var annualLeaveLastCalculatedDays = 0

    fun snapshotInitialVacationBalance(): Double = initialVacationBalance

    fun snapshotVacationYearBalances(): Map<Int, VacationYearBalance> = vacationYearBalances.toMap()

    fun snapshotAnnualLeaveCalculationInput(): AnnualLeaveCalculationInput? = annualLeaveCalculationInput

    fun snapshotAnnualLeaveLastCalculatedDays(): Int = annualLeaveLastCalculatedDays

    fun restore(
        initialVacationBalance: Double,
        vacationYearBalances: Map<Int, VacationYearBalance>,
        annualLeaveCalculationInput: AnnualLeaveCalculationInput?,
        annualLeaveLastCalculatedDays: Int
    ) {
        this.initialVacationBalance = initialVacationBalance
        this.vacationYearBalances.clear()
        this.vacationYearBalances.putAll(vacationYearBalances)
        this.annualLeaveCalculationInput = annualLeaveCalculationInput
        this.annualLeaveLastCalculatedDays = annualLeaveLastCalculatedDays
    }

    fun getInitialVacationBalance(): Double = initialVacationBalance

    fun setInitialVacationBalance(value: Double) {
        initialVacationBalance = value.coerceAtLeast(0.0)
    }

    fun getAnnualLeaveCalculationInput(year: Int): AnnualLeaveCalculationInput {
        return annualLeaveCalculationInput ?: defaultAnnualLeaveCalculationInput(year)
    }

    fun getAnnualLeaveLastCalculatedDays(): Int = annualLeaveLastCalculatedDays

    fun setAnnualLeaveCalculation(input: AnnualLeaveCalculationInput, lastCalculatedDays: Int): Boolean {
        val sanitizedInput = input.copy(
            totalSeniorityYears = input.totalSeniorityYears.sanitizeAnnualLeaveYears(),
            yearsAtEmployer = input.yearsAtEmployer.sanitizeAnnualLeaveYears()
        )
        val sanitizedLastCalculatedDays = lastCalculatedDays.coerceAtLeast(0)
        if (
            annualLeaveCalculationInput == sanitizedInput &&
            annualLeaveLastCalculatedDays == sanitizedLastCalculatedDays
        ) {
            return false
        }
        annualLeaveCalculationInput = sanitizedInput
        annualLeaveLastCalculatedDays = sanitizedLastCalculatedDays
        return true
    }

    fun getVacationDaysUsed(): Int {
        return recordStore.allRecords.count { it.shift == Shift.VACATION }
    }

    fun setVacationYearBalance(balance: VacationYearBalance): Boolean {
        if (balance.year !in 1900..2200) return false
        val sanitized = balance.copy(
            carryoverDaysInitial = balance.carryoverDaysInitial.coerceAtLeast(0.0),
            currentYearDaysTotal = balance.currentYearDaysTotal.coerceAtLeast(0.0),
            currentYearDaysUsedBeforeAvailability = balance.currentYearDaysUsedBeforeAvailability
                .coerceAtLeast(0.0),
            // Ponovo sačuvana godina ostaje zamrznuta: stari podaci za prenos su već obrisani.
            carryoverFrozen = balance.carryoverFrozen || vacationYearBalances[balance.year]?.carryoverFrozen == true
        )
        vacationYearBalances[sanitized.year] = sanitized
        initialVacationBalance = sanitized.currentYearDaysTotal
        return true
    }

    fun getVacationYearBalance(year: Int): VacationYearBalance? = vacationYearBalances[year]

    fun getVacationBalanceSummary(year: Int, today: LocalDate = LocalDate.now(clock)): VacationBalanceSummary {
        return calculateVacationBalanceSummary(
            balance = vacationYearBalanceForSummary(year),
            vacationDates = getVacationDates(year),
            today = today,
            payslipDaysByMonth = payslipVacationDaysByMonth(year)
        )
    }

    fun getVacationDaysRemaining(today: LocalDate = LocalDate.now(clock)): Double {
        return if (vacationYearBalances.isNotEmpty()) {
            getVacationBalanceSummary(today.year, today).currentYearRemaining
        } else {
            (initialVacationBalance - getVacationDaysUsed()).coerceAtLeast(0.0)
        }
    }

    fun isVacationExceeded(today: LocalDate = LocalDate.now(clock)): Boolean {
        return if (vacationYearBalances.isNotEmpty()) {
            getVacationBalanceSummary(today.year, today)
                .warnings
                .contains(VacationWarnings.VACATION_EXCEEDED)
        } else {
            getVacationDaysRemaining(today) <= 0.0
        }
    }

    private fun defaultAnnualLeaveCalculationInput(year: Int): AnnualLeaveCalculationInput {
        val yearsFromMinuliRad = calculateYearsOfService(year, 1).toDouble()
        return AnnualLeaveCalculationInput(
            totalSeniorityYears = yearsFromMinuliRad,
            yearsAtEmployer = yearsFromMinuliRad
        )
    }

    private fun getVacationDates(year: Int): List<LocalDate> {
        return recordStore.allRecords
            .asSequence()
            .filter { it.shift == Shift.VACATION }
            .filter { it.year == year }
            .map { it.date }
            .toList()
    }

    private fun legacyVacationYearBalance(year: Int): VacationYearBalance {
        return VacationYearBalance(
            year = year,
            trackingStartDate = LocalDate.of(year, 1, 1),
            carryoverDaysInitial = 0.0,
            currentYearDaysTotal = initialVacationBalance.coerceAtLeast(0.0),
            currentYearDaysUsedBeforeAvailability = 0.0,
            currentYearAvailableFrom = LocalDate.of(year, 1, 1),
            manualBaseline = false
        )
    }

    /** Prenos se računa sam kad je neka ranija godina praćena u aplikaciji (tada se ne unosi ručno). */
    fun isCarryoverAutomatic(year: Int): Boolean =
        vacationYearBalances[year]?.carryoverFrozen != true && vacationYearBalances.keys.any { it < year }

    /**
     * Pre brisanja stare istorije: prenos za [firstKeptYear] se izračuna dok su podaci prošle godine
     * još celi i zapamti, a starije godine se brišu. Vraća true ako je nešto promenjeno.
     */
    fun freezeCarryover(firstKeptYear: Int): Boolean {
        if (vacationYearBalances.keys.none { it < firstKeptYear }) return false
        if (isCarryoverAutomatic(firstKeptYear)) {
            vacationYearBalances[firstKeptYear] = vacationYearBalanceForSummary(firstKeptYear).copy(carryoverFrozen = true)
        }
        vacationYearBalances.keys.removeAll { it < firstKeptYear }
        return true
    }

    /** Neiskorišćeno pravo prethodne godine na dan 31.12. (lanac kroz sve praćene godine). */
    private fun carryoverFromPreviousYear(year: Int): Double {
        if (!isCarryoverAutomatic(year)) return 0.0
        return calculateVacationBalanceSummary(
            balance = vacationYearBalanceForSummary(year - 1),
            vacationDates = getVacationDates(year - 1),
            today = LocalDate.of(year - 1, 12, 31),
            payslipDaysByMonth = payslipVacationDaysByMonth(year - 1)
        ).currentYearRemaining
    }

    private fun derivedVacationYearBalance(year: Int): VacationYearBalance {
        return VacationYearBalance(
            year = year,
            trackingStartDate = LocalDate.of(year, 1, 1),
            carryoverDaysInitial = carryoverFromPreviousYear(year),
            currentYearDaysTotal = initialVacationBalance.coerceAtLeast(0.0),
            currentYearDaysUsedBeforeAvailability = 0.0,
            currentYearAvailableFrom = currentYearVacationAvailableFrom(year),
            manualBaseline = false
        )
    }

    /**
     * Godišnji iskorišćen januar–jun po sačuvanim listićima. Pravo iz listića se računa za period
     * jul–jun, pa su dani prve polovine godine stari (preneti) odmor, ne novi.
     */
    private fun firstHalfPayslipVacationDays(year: Int): Double =
        payslipVacationDaysByMonth(year).filterKeys { it in 1..6 }.values.sum()

    private fun vacationYearBalanceForSummary(year: Int): VacationYearBalance {
        val saved = vacationYearBalances[year]
        val balance = when {
            saved != null && isCarryoverAutomatic(year) -> saved.copy(carryoverDaysInitial = carryoverFromPreviousYear(year))
            saved != null -> saved
            vacationYearBalances.isEmpty() -> legacyVacationYearBalance(year)
            else -> derivedVacationYearBalance(year)
        }
        // Pravilo korisnika (27.09.2026): odmor pre jula je uvek stari. Zato je prenos najmanje ono
        // što listići pokazuju za januar–jun — bez obzira da li je prenos izračunat iz prošle godine
        // (listići prošle godine znaju da fale), zamrznut pri brisanju stare godine, ili sačuvan iz
        // dijaloga koji posle 30.06. ni ne prikazuje polje za prenos.
        return balance.copy(
            carryoverDaysInitial = maxOf(balance.carryoverDaysInitial, firstHalfPayslipVacationDays(year))
        )
    }
}
