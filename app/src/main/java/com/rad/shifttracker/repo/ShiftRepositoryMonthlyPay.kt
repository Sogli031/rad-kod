package com.rad.shifttracker.repo

import com.rad.shifttracker.repo.ShiftRepository.Companion.ADVANCE_SAME_AMOUNT_TOLERANCE
import java.math.BigDecimal
import kotlin.math.abs
import com.rad.shifttracker.obracun.MonthlyFoodSpendingBreakdown
import com.rad.shifttracker.obracun.MonthlyGrossAdditions
import com.rad.shifttracker.obracun.MonthlyNetAdjustments
import com.rad.shifttracker.obracun.MonthlyPayBases
import com.rad.shifttracker.obracun.ResolvedPayBases
import com.rad.shifttracker.obracun.RollingPayBaseCalculation
import com.rad.shifttracker.obracun.toMealMoney
import com.rad.shifttracker.smene.MonthKey

// Mesečni iznosi: osnovice za naknade, bruto dodaci, akontacija, regres, topli obrok i izračunate osnovice.

fun ShiftRepository.setMonthlyPayBases(year: Int, month: Int, bases: MonthlyPayBases) {
    if (year <= 0 || month !in 1..12) return
    synchronized(lock) {
        val key = MonthKey(year, month)
        val sanitized = MonthlyPayBases(
            year = year,
            month = month,
            vacationHourlyBase = bases.vacationHourlyBase.sanitizePayBaseOrNull(),
            paidHolidayHourlyBase = bases.paidHolidayHourlyBase.sanitizePayBaseOrNull(),
            sickHourlyBase = bases.sickHourlyBase.sanitizePayBaseOrNull(),
            selectionMode = bases.selectionMode
        )
        if (
            sanitized.vacationHourlyBase == null &&
            sanitized.paidHolidayHourlyBase == null &&
            sanitized.sickHourlyBase == null &&
            sanitized.selectionMode == null
        ) {
            payState.monthlyPayBases.remove(key)
        } else {
            payState.monthlyPayBases[key] = sanitized
        }
        payState.invalidateSummariesAndRolling()
    }
    notifyChange()
}

fun ShiftRepository.getMonthlyPayBases(year: Int, month: Int): MonthlyPayBases? = synchronized(lock) {
    payState.monthlyPayBases[MonthKey(year, month)]
}

fun ShiftRepository.setMonthlyGrossAdditions(year: Int, month: Int, additions: MonthlyGrossAdditions) {
    if (year <= 0 || month !in 1..12) return
    synchronized(lock) {
        monthlyAdjustments.setMonthlyGrossAdditions(year, month, additions)
    }
    notifyChange()
}

fun ShiftRepository.getMonthlyGrossAdditions(year: Int, month: Int): MonthlyGrossAdditions = synchronized(lock) {
    monthlyAdjustments.getMonthlyGrossAdditions(year, month)
}

fun ShiftRepository.setMonthlyAbsencePenaltyGross(year: Int, month: Int, amount: java.math.BigDecimal) {
    if (year <= 0 || month !in 1..12) return
    synchronized(lock) {
        monthlyAdjustments.setMonthlyAbsencePenaltyGross(year, month, amount)
    }
    notifyChange()
}

@Deprecated("Legacy backup compatibility only; meal gross is always calculated from the applied meal net.")
@Suppress("DEPRECATION")
fun ShiftRepository.setMonthlyMealGrossOverride(year: Int, month: Int, amount: Double?) {
    if (year <= 0 || month !in 1..12) return
    synchronized(lock) {
        monthlyAdjustments.setMonthlyMealGrossOverride(year, month, amount)
    }
    notifyChange()
}

@Deprecated("Legacy backup compatibility only; settlement adjustment is ignored by payroll calculation.")
@Suppress("DEPRECATION")
fun ShiftRepository.setMonthlyMealSettlementAdjustmentNet(year: Int, month: Int, amount: Double) {
    if (year <= 0 || month !in 1..12) return
    synchronized(lock) {
        monthlyAdjustments.setMonthlyMealSettlementAdjustmentNet(year, month, amount)
    }
    notifyChange()
}

fun ShiftRepository.setMonthlyAdvancePaymentNet(year: Int, month: Int, amount: Double) {
    if (year <= 0 || month !in 1..12) return
    synchronized(lock) {
        monthlyAdjustments.setMonthlyAdvancePaymentNet(year, month, amount)
    }
    notifyChange()
}

/**
 * Akontacija za [month] sa obračuna akontacije (iznos uplaćen na račun). Obračun je tačniji od
 * ručnog unosa, pa ga zamenjuje; true ako je iznos promenjen.
 */
fun ShiftRepository.recordAdvancePaymentFromPayslip(month: MonthKey, amount: Double): Boolean {
    val changed = synchronized(lock) {
        if (month.year <= 0 || month.month !in 1..12 || amount <= 0.0 || !amount.isFinite()) {
            return@synchronized false
        }
        val current = monthlyAdjustments.getMonthlyNetAdjustments(month.year, month.month)
        if (abs(current.advancePaymentNet - amount) < ADVANCE_SAME_AMOUNT_TOLERANCE) return@synchronized false
        monthlyAdjustments.setMonthlyAdvancePaymentNet(month.year, month.month, amount)
        true
    }
    if (changed) notifyChange()
    return changed
}

/** Neto regres koji važi za [month] (poslednji sa obračuna regresa, inače 20.000). */
fun ShiftRepository.getRegressNetForMonth(month: MonthKey): Double = synchronized(lock) { payState.regressNetForMonth(month) }

fun ShiftRepository.isRegressPaidInMonth(month: MonthKey): Boolean = synchronized(lock) { payState.regressPaidInMonth(month) }

/**
 * Regres sa obračuna regresa za mesec [month] (iznos uplaćen na račun). Mesec se pamti uvek, pa se
 * regres računa u tom mesecu i kad nije 2/5/8/11 (npr. dve rate 20.000 u oktobru i novembru);
 * iznos važi i za kasnije mesece. True samo kad se iznos razlikuje od onog koji je za mesec važio.
 */
fun ShiftRepository.recordRegressFromPayslip(month: MonthKey, amount: Double): Boolean {
    var amountChanged = false
    val changed = synchronized(lock) {
        if (month.year <= 0 || month.month !in 1..12 || amount <= 0.0 || !amount.isFinite()) {
            return@synchronized false
        }
        amountChanged = abs(payState.regressNetForMonth(month) - amount) >= ADVANCE_SAME_AMOUNT_TOLERANCE
        val stored = payState.regressNetChanges[month]
        if (!amountChanged && stored != null && abs(stored - amount) < ADVANCE_SAME_AMOUNT_TOLERANCE) {
            return@synchronized false
        }
        payState.regressNetChanges[month] = amount
        true
    }
    if (changed) notifyChange()
    return amountChanged
}

fun ShiftRepository.getMonthlyNetAdjustments(year: Int, month: Int): MonthlyNetAdjustments = synchronized(lock) {
    monthlyAdjustments.getMonthlyNetAdjustments(year, month)
}

fun ShiftRepository.setMonthlyActualFoodSpentNet(year: Int, month: Int, amount: Double?) {
    if (year <= 0 || month !in 1..12) return
    if (amount != null && (!amount.isFinite() || amount < 0.0)) return
    synchronized(lock) {
        monthlyAdjustments.setMonthlyActualFoodSpentNet(year, month, amount)
    }
    notifyChange()
}

internal fun ShiftRepository.setMonthlyMealEntitlementNetOverride(year: Int, month: Int, amount: BigDecimal?) {
    if (year <= 0 || month !in 1..12 || amount?.signum() == -1) return
    synchronized(lock) {
        monthlyAdjustments.setMonthlyMealEntitlementNetOverride(year, month, amount?.toMealMoney())
    }
    notifyChange()
}

fun ShiftRepository.getMonthlyFoodSpendingBreakdown(year: Int, month: Int): MonthlyFoodSpendingBreakdown = synchronized(lock) {
    getMonthlyFoodSpendingBreakdownUnsafe(MonthKey(year, month))
}

fun ShiftRepository.getResolvedPayBases(year: Int, month: Int): ResolvedPayBases = synchronized(lock) {
    getResolvedPayBasesUnsafe(year, month)
}

fun ShiftRepository.getRollingPayBaseCalculation(year: Int, month: Int): RollingPayBaseCalculation? = synchronized(lock) {
    getRollingPayBaseCalculationUnsafe(year, month)
}

fun ShiftRepository.getPayBaseSourceMonthForWorkMonth(year: Int, month: Int): MonthKey = synchronized(lock) {
    payQueries.getPayBaseResolutionMonthForWorkMonthUnsafe(year, month)
}

fun ShiftRepository.getResolvedPayBasesForWorkMonth(year: Int, month: Int): ResolvedPayBases = synchronized(lock) {
    payQueries.getResolvedPayBasesForWorkMonthUnsafe(year, month)
}

fun ShiftRepository.getRollingPayBaseCalculationForWorkMonth(year: Int, month: Int): RollingPayBaseCalculation? = synchronized(lock) {
    payQueries.getResolvedPayBasesForWorkMonthUnsafe(year, month)
        .sickHourlyBase
        .calculation
}

internal fun ShiftRepository.getResolvedPayBasesUnsafe(year: Int, month: Int): ResolvedPayBases {
    return payQueries.getResolvedPayBasesUnsafe(year, month)
}

internal fun ShiftRepository.getRollingPayBaseCalculationUnsafe(year: Int, month: Int): RollingPayBaseCalculation? {
    return payQueries.getRollingPayBaseCalculationUnsafe(year, month)
}
