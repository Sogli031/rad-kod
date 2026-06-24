package com.rad.shifttracker.data.repository

import com.rad.shifttracker.AnnualLeaveCalculationInput
import com.rad.shifttracker.Shift
import com.rad.shifttracker.VacationBalanceSummary
import com.rad.shifttracker.VacationWarnings
import com.rad.shifttracker.VacationYearBalance
import com.rad.shifttracker.calculateVacationBalanceSummary
import com.rad.shifttracker.currentYearVacationAvailableFrom
import com.rad.shifttracker.sanitizeAnnualLeaveYears
import java.time.LocalDate

internal class RepositoryVacationTracker(
    private val recordStore: ShiftRecordStore,
    private val calculateYearsOfService: (year: Int, month: Int) -> Int
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
                .coerceAtLeast(0.0)
        )
        vacationYearBalances[sanitized.year] = sanitized
        initialVacationBalance = sanitized.currentYearDaysTotal
        return true
    }

    fun retainVacationYearBalances(years: Set<Int>): Boolean {
        val removedYears = vacationYearBalances.keys.filter { it !in years }
        if (removedYears.isEmpty()) return false
        removedYears.forEach(vacationYearBalances::remove)
        return true
    }

    fun getVacationYearBalance(year: Int): VacationYearBalance? = vacationYearBalances[year]

    fun getVacationBalanceSummary(year: Int, today: LocalDate = LocalDate.now()): VacationBalanceSummary {
        return calculateVacationBalanceSummary(
            balance = vacationYearBalanceForSummary(year),
            vacationDates = getVacationDates(year),
            today = today
        )
    }

    fun getVacationDaysRemaining(today: LocalDate = LocalDate.now()): Double {
        return if (vacationYearBalances.isNotEmpty()) {
            getVacationBalanceSummary(today.year, today).currentYearRemaining
        } else {
            (initialVacationBalance - getVacationDaysUsed()).coerceAtLeast(0.0)
        }
    }

    fun isVacationExceeded(today: LocalDate = LocalDate.now()): Boolean {
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

    private fun derivedVacationYearBalance(year: Int): VacationYearBalance {
        val previousYearBalance = vacationYearBalances[year - 1]
        val carryoverFromPreviousYear = if (previousYearBalance != null) {
            calculateVacationBalanceSummary(
                balance = previousYearBalance,
                vacationDates = getVacationDates(year - 1),
                today = LocalDate.of(year - 1, 12, 31)
            ).currentYearRemaining
        } else {
            0.0
        }
        return VacationYearBalance(
            year = year,
            trackingStartDate = LocalDate.of(year, 1, 1),
            carryoverDaysInitial = carryoverFromPreviousYear,
            currentYearDaysTotal = initialVacationBalance.coerceAtLeast(0.0),
            currentYearDaysUsedBeforeAvailability = 0.0,
            currentYearAvailableFrom = currentYearVacationAvailableFrom(year),
            manualBaseline = false
        )
    }

    private fun vacationYearBalanceForSummary(year: Int): VacationYearBalance {
        return vacationYearBalances[year] ?: if (vacationYearBalances.isEmpty()) {
            legacyVacationYearBalance(year)
        } else {
            derivedVacationYearBalance(year)
        }
    }
}
