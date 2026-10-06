package com.rad.shifttracker.repo

import com.rad.shifttracker.repo.ShiftRepository.Companion.NIGHT_WORK_PAYSLIP_CODE
import java.time.LocalDate
import com.rad.shifttracker.godisnji.AnnualLeaveCalculationInput
import com.rad.shifttracker.godisnji.AnnualLeaveEntitlement
import com.rad.shifttracker.godisnji.PayslipSeniorityPoint
import com.rad.shifttracker.godisnji.VacationBalanceSummary
import com.rad.shifttracker.godisnji.VacationYearBalance
import com.rad.shifttracker.godisnji.annualLeaveEntitlement
import com.rad.shifttracker.godisnji.seniorityOnJanuaryFirst
import com.rad.shifttracker.smene.BrigadeType
import com.rad.shifttracker.smene.MonthKey
import com.rad.shifttracker.smene.Shift

var ShiftRepository.initialVacationBalanceValue: Double
    get() = synchronized(lock) { vacationTracker.getInitialVacationBalance() }
    set(value) {
        synchronized(lock) {
            vacationTracker.setInitialVacationBalance(value)
        }
        notifyChange()
    }

fun ShiftRepository.getAnnualLeaveCalculationInput(year: Int): AnnualLeaveCalculationInput = synchronized(lock) {
    vacationTracker.getAnnualLeaveCalculationInput(year)
}

fun ShiftRepository.getAnnualLeaveLastCalculatedDays(): Int = synchronized(lock) {
    vacationTracker.getAnnualLeaveLastCalculatedDays()
}

fun ShiftRepository.setAnnualLeaveCalculation(input: AnnualLeaveCalculationInput, lastCalculatedDays: Int) {
    val changed = synchronized(lock) {
        vacationTracker.setAnnualLeaveCalculation(input, lastCalculatedDays)
    }
    if (!changed) return
    notifyChange()
}

fun ShiftRepository.setVacationYearBalance(balance: VacationYearBalance) {
    val changed = synchronized(lock) {
        vacationTracker.setVacationYearBalance(balance)
    }
    if (!changed) return
    notifyChange()
}

fun ShiftRepository.getVacationYearBalance(year: Int): VacationYearBalance? = synchronized(lock) {
    vacationTracker.getVacationYearBalance(year)
}

fun ShiftRepository.getVacationBalanceSummary(year: Int, today: LocalDate = LocalDate.now(clock)): VacationBalanceSummary =
    synchronized(lock) {
        vacationTracker.getVacationBalanceSummary(year, today)
    }

/**
 * Pravo na godišnji za [year] po KU čl. 29. Staž na 01.01. je sa sačuvanih listića
 * („Ukupni radni staž“, a za 35 godina kod poslodavca „Radni staž za MR“), bez listića iz
 * podešavanja minulog rada. Noćni rad: četvorobrigada ili treća smena / šifra 20 u podacima;
 * vikend samo četvorobrigada. Ostalo su ručni odgovori ([setAnnualLeaveCalculation]).
 */
internal fun ShiftRepository.getAnnualLeaveEntitlement(year: Int): AnnualLeaveEntitlement = synchronized(lock) {
    val payslips = savedPayslipsUnsafe()
    val totalPoints = payslips.mapNotNull { slip ->
        (slip.totalSeniorityYears ?: slip.seniorityYears)?.let { PayslipSeniorityPoint(slip.month, it) }
    }
    val employerPoints = payslips.mapNotNull { slip -> slip.seniorityYears?.let { PayslipSeniorityPoint(slip.month, it) } }
    val fourBrigade = payState.brigadeType == BrigadeType.CETVOROBRIGADA
    val nightWork = fourBrigade ||
        payslips.any { (it.items[NIGHT_WORK_PAYSLIP_CODE]?.hours?.signum() ?: 0) > 0 } ||
        recordStore.allRecords.any { it.shift == Shift.THIRD || it.shift2 == Shift.THIRD }
    val manual = vacationTracker.getAnnualLeaveCalculationInput(year)
    annualLeaveEntitlement(
        // „Beneficirani staž“ na listiću = uvećan rizik (KU čl. 29), bez ručnog prekidača.
        manual = if (payslips.any { it.benefitedSeniority }) {
            manual.copy(hasIncreasedRiskOrBenefitedSeniority = true)
        } else {
            manual
        },
        totalSeniority = seniorityOnJanuaryFirst(totalPoints, year),
        employerSeniority = seniorityOnJanuaryFirst(employerPoints, year),
        fallbackSeniorityYears = calculateYearsOfServiceUnsafe(year, 1),
        worksNightShifts = nightWork,
        worksWeekendShifts = fourBrigade
    )
}

/** Da li se „preneto iz prošle godine“ računa samo (prošla godina je praćena u aplikaciji). */
fun ShiftRepository.isVacationCarryoverAutomatic(year: Int): Boolean = synchronized(lock) {
    vacationTracker.isCarryoverAutomatic(year)
}

/**
 * Završeni meseci godine (do [today]) za koje aplikacija nema ni listić ni jedan upisan dan —
 * samo za njih ima smisla „potrošeno van aplikacije“.
 */
fun ShiftRepository.getVacationMonthsWithoutData(year: Int, today: LocalDate = LocalDate.now(clock)): List<Int> = synchronized(lock) {
    val lastCompletedMonth = when {
        year < today.year -> 12
        year == today.year -> today.monthValue - 1
        else -> 0
    }
    (1..lastCompletedMonth).filter { month ->
        MonthKey(year, month) !in savedPayslipJsonByMonth && recordStore.getMonthRecords(year, month).isEmpty()
    }
}

fun ShiftRepository.getVacationDaysUsed(): Int = synchronized(lock) {
    vacationTracker.getVacationDaysUsed()
}

fun ShiftRepository.getVacationDaysRemaining(): Double = synchronized(lock) {
    vacationTracker.getVacationDaysRemaining()
}

fun ShiftRepository.isVacationExceeded(): Boolean = synchronized(lock) {
    vacationTracker.isVacationExceeded()
}
