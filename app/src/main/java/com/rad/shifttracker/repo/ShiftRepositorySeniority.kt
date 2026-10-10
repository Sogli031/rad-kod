package com.rad.shifttracker.repo

import com.rad.shifttracker.repo.ShiftRepository.Companion.MAX_COMP_BALANCE
import com.rad.shifttracker.repo.ShiftRepository.Companion.MAX_COMP_BALANCE_MINUTES
import com.rad.shifttracker.repo.ShiftRepository.Companion.MAX_PERCENT
import com.rad.shifttracker.repo.ShiftRepository.Companion.MINUTES_PER_HOUR
import com.rad.shifttracker.repo.ShiftRepository.Companion.MIN_COMP_BALANCE
import java.time.LocalDate
import com.rad.shifttracker.obracun.SeniorityIncreaseFactor
import com.rad.shifttracker.obracun.SeniorityInputMode
import com.rad.shifttracker.obracun.calculateKnownStartSeniorityPreview
import com.rad.shifttracker.obracun.calculatePayslipAnchoredSeniorityYears
import com.rad.shifttracker.smene.BrigadeType
import com.rad.shifttracker.smene.Shift
import com.rad.shifttracker.godisnji.PayslipSeniorityPoint
import com.rad.shifttracker.godisnji.seniorityAnniversaryMonth

var ShiftRepository.initialCompBalanceValue: Int
    get() = synchronized(lock) { payState.initialCompBalance }
    set(value) {
        synchronized(lock) {
            val hours = value.coerceIn(MIN_COMP_BALANCE, MAX_COMP_BALANCE)
            payState.initialCompBalance = hours
            payState.initialCompBalanceMinutes = hours * MINUTES_PER_HOUR
            payState.invalidateSummariesRollingAndCompBalance()
        }
        notifyChange()
    }

var ShiftRepository.initialCompBalanceMinutesValue: Int
    get() = synchronized(lock) { payState.initialCompBalanceMinutes }
    set(value) {
        synchronized(lock) {
            val minutes = value.coerceIn(MIN_COMP_BALANCE, MAX_COMP_BALANCE_MINUTES)
            payState.initialCompBalanceMinutes = minutes
            payState.initialCompBalance = minutes / MINUTES_PER_HOUR
            payState.invalidateSummariesRollingAndCompBalance()
        }
        notifyChange()
    }

/**
 * Automatski računa godine staža na osnovu payState.minuliRadStartYear.
 * Povećava se u mesecu početka rada.
 */
internal fun ShiftRepository.calculateYearsOfServiceUnsafe(): Int {
    if (payState.minuliRadStartYear <= 0) return 0
    val now = LocalDate.now(clock)
    return calculateYearsOfServiceUnsafe(now.year, now.monthValue)
}

internal fun ShiftRepository.calculateYearsOfServiceUnsafe(year: Int, month: Int): Int {
    return when (payState.seniorityInputMode) {
        SeniorityInputMode.KNOWN_START -> calculateKnownStartYearsOfServiceUnsafe(year, month)
        SeniorityInputMode.PAYSLIP_PERCENT -> calculatePayslipAnchoredSeniorityYears(
            lastPayslipYear = payState.minuliRadLastPayslipYear,
            lastPayslipMonth = payState.minuliRadLastPayslipMonth,
            payslipPercent = payState.minuliRadPayslipPercent,
            targetYear = year,
            targetMonth = month,
            increaseFactor = payState.minuliRadIncreaseFactor,
            ratePerYear = config.pay.minuliRadPerYear,
            anniversaryMonth = seniorityAnniversaryMonth(
                savedPayslipsUnsafe().mapNotNull { slip -> slip.seniorityYears?.let { PayslipSeniorityPoint(slip.month, it) } }
            )
        )
    }
}

internal fun ShiftRepository.calculateKnownStartYearsOfServiceUnsafe(year: Int, month: Int): Int {
    if (payState.minuliRadStartYear <= 0) return 0
    val increaseFactor = if (payState.minuliRadBenefitEnabled) {
        payState.minuliRadIncreaseFactor
    } else {
        SeniorityIncreaseFactor.NONE
    }
    return calculateKnownStartSeniorityPreview(
        startYear = payState.minuliRadStartYear,
        startMonth = payState.minuliRadStartMonth,
        targetYear = year,
        targetMonth = month,
        increaseFactor = increaseFactor,
        ratePerYear = config.pay.minuliRadPerYear
    ).fullAccountingYears
}

/** Staž 0 god. je ispravan za novog radnika; upozorava se samo kad početak uopšte nije unet. */
val ShiftRepository.isSeniorityConfiguredValue: Boolean
    get() = synchronized(lock) {
        when (payState.seniorityInputMode) {
            SeniorityInputMode.KNOWN_START -> payState.minuliRadStartYear > 0
            SeniorityInputMode.PAYSLIP_PERCENT -> payState.minuliRadLastPayslipYear > 0
        }
    }

var ShiftRepository.seniorityInputModeValue: SeniorityInputMode
    get() = synchronized(lock) { payState.seniorityInputMode }
    set(value) {
        synchronized(lock) {
            payState.seniorityInputMode = value
            payState.invalidateSummariesAndRolling()
        }
        notifyChange()
    }

var ShiftRepository.minuliRadStartYearValue: Int
    get() = synchronized(lock) { payState.minuliRadStartYear }
    set(value) {
        synchronized(lock) {
            payState.minuliRadStartYear = value.coerceAtLeast(0)
            payState.invalidateSummariesAndRolling()
        }
        notifyChange()
    }

var ShiftRepository.minuliRadStartMonthValue: Int
    get() = synchronized(lock) { payState.minuliRadStartMonth }
    set(value) {
        synchronized(lock) {
            payState.minuliRadStartMonth = value.coerceIn(1, 12)
            payState.invalidateSummariesAndRolling()
        }
        notifyChange()
    }

var ShiftRepository.minuliRadBenefitEnabledValue: Boolean
    get() = synchronized(lock) { payState.minuliRadBenefitEnabled }
    set(value) {
        synchronized(lock) {
            payState.minuliRadBenefitEnabled = value
            payState.invalidateSummariesAndRolling()
        }
        notifyChange()
    }

var ShiftRepository.minuliRadIncreaseFactorValue: SeniorityIncreaseFactor
    get() = synchronized(lock) { payState.minuliRadIncreaseFactor }
    set(value) {
        synchronized(lock) {
            payState.minuliRadIncreaseFactor = value
            payState.invalidateSummariesAndRolling()
        }
        notifyChange()
    }

var ShiftRepository.minuliRadLastPayslipYearValue: Int
    get() = synchronized(lock) { payState.minuliRadLastPayslipYear }
    set(value) {
        synchronized(lock) {
            payState.minuliRadLastPayslipYear = value.coerceAtLeast(0)
            payState.invalidateSummariesAndRolling()
        }
        notifyChange()
    }

var ShiftRepository.minuliRadLastPayslipMonthValue: Int
    get() = synchronized(lock) { payState.minuliRadLastPayslipMonth }
    set(value) {
        synchronized(lock) {
            payState.minuliRadLastPayslipMonth = value.coerceIn(1, 12)
            payState.invalidateSummariesAndRolling()
        }
        notifyChange()
    }

var ShiftRepository.minuliRadPayslipPercentValue: Double
    get() = synchronized(lock) { payState.minuliRadPayslipPercent }
    set(value) {
        synchronized(lock) {
            payState.minuliRadPayslipPercent = value
                .takeIf { it.isFinite() }
                ?.coerceIn(0.0, MAX_PERCENT)
                ?: 0.0
            payState.invalidateSummariesAndRolling()
        }
        notifyChange()
    }

var ShiftRepository.brigadeTypeValue: BrigadeType
    get() = synchronized(lock) { payState.brigadeType }
    set(value) {
        synchronized(lock) {
            if (payState.brigadeType != value) {
                payState.brigadeType = value
                payState.invalidateSummariesRollingAndCompBalance()
            } else {
                return
            }
        }
        notifyChange()
    }

fun ShiftRepository.getScheduledShiftForDate(date: LocalDate): Shift? = synchronized(lock) {
    RollingPayBaseMonthSimulator(config, payState, recordStore).simulateShiftForDate(date)
}
