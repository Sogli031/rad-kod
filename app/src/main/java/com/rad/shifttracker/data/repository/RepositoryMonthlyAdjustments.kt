package com.rad.shifttracker.data.repository

import com.rad.shifttracker.MonthKey
import com.rad.shifttracker.MonthlyGrossAdditions
import com.rad.shifttracker.MonthlyNetAdjustments
import com.rad.shifttracker.sanitizeMonthlyGrossAdditions
import java.math.BigDecimal

internal class RepositoryMonthlyAdjustments(
    private val payState: RepositoryPayState,
    private val invalidateSummaryAndRollingDependents: (MonthKey) -> Unit
) {
    fun setMonthlyGrossAdditions(year: Int, month: Int, additions: MonthlyGrossAdditions) {
        val key = MonthKey(year, month)
        val sanitized = additions.sanitizeMonthlyGrossAdditions(year, month)
        if (sanitized.hasAny) {
            payState.monthlyGrossAdditions[key] = sanitized
        } else {
            payState.monthlyGrossAdditions.remove(key)
        }
        invalidateSummaryAndRollingDependents(key)
    }

    fun getMonthlyGrossAdditions(year: Int, month: Int): MonthlyGrossAdditions {
        val key = MonthKey(year, month)
        return payState.monthlyGrossAdditions[key] ?: MonthlyGrossAdditions(year, month)
    }

    @Deprecated("Legacy backup compatibility only; meal gross is always calculated from the applied meal net.")
    fun setMonthlyMealGrossOverride(year: Int, month: Int, amount: Double?) {
        val key = MonthKey(year, month)
        val current = payState.monthlyGrossAdditions[key] ?: MonthlyGrossAdditions(year, month)
        val sanitized = current
            .copy(year = year, month = month, mealGross = amount)
            .sanitizeMonthlyGrossAdditions(year, month)
        if (sanitized.hasAny) {
            payState.monthlyGrossAdditions[key] = sanitized
        } else {
            payState.monthlyGrossAdditions.remove(key)
        }
        invalidateSummaryAndRollingDependents(key)
    }

    @Deprecated("Legacy backup compatibility only; settlement adjustment is ignored by payroll calculation.")
    fun setMonthlyMealSettlementAdjustmentNet(year: Int, month: Int, amount: Double) {
        val key = MonthKey(year, month)
        val current = payState.monthlyGrossAdditions[key] ?: MonthlyGrossAdditions(year, month)
        val sanitized = current
            .copy(year = year, month = month, mealSettlementAdjustmentNet = amount)
            .sanitizeMonthlyGrossAdditions(year, month)
        if (sanitized.hasAny) {
            payState.monthlyGrossAdditions[key] = sanitized
        } else {
            payState.monthlyGrossAdditions.remove(key)
        }
        invalidateSummaryAndRollingDependents(key)
    }

    fun setMonthlyAdvancePaymentNet(year: Int, month: Int, amount: Double) {
        val key = MonthKey(year, month)
        val current = payState.monthlyNetAdjustments[key] ?: MonthlyNetAdjustments(year, month)
        val sanitized = current
            .copy(year = year, month = month, advancePaymentNet = amount)
            .sanitizedFor(year, month)
        if (sanitized.hasAny) {
            payState.monthlyNetAdjustments[key] = sanitized
        } else {
            payState.monthlyNetAdjustments.remove(key)
        }
    }

    fun getMonthlyNetAdjustments(year: Int, month: Int): MonthlyNetAdjustments {
        val key = MonthKey(year, month)
        return payState.monthlyNetAdjustments[key] ?: MonthlyNetAdjustments(year, month)
    }

    fun setMonthlyActualFoodSpentNet(year: Int, month: Int, amount: Double?) {
        val key = MonthKey(year, month)
        val current = payState.monthlyGrossAdditions[key] ?: MonthlyGrossAdditions(year, month)
        val sanitized = current
            .copy(year = year, month = month, mealSpentNet = amount)
            .sanitizeMonthlyGrossAdditions(year, month)
        if (sanitized.hasAny) {
            payState.monthlyGrossAdditions[key] = sanitized
        } else {
            payState.monthlyGrossAdditions.remove(key)
        }
        invalidateSummaryAndRollingDependents(key)
    }

    fun setMonthlyMealEntitlementNetOverride(year: Int, month: Int, amount: BigDecimal?) {
        val key = MonthKey(year, month)
        val current = payState.monthlyGrossAdditions[key] ?: MonthlyGrossAdditions(year, month)
        val sanitized = current
            .copy(year = year, month = month, mealEntitlementNetOverride = amount)
            .sanitizeMonthlyGrossAdditions(year, month)
        if (sanitized.hasAny) {
            payState.monthlyGrossAdditions[key] = sanitized
        } else {
            payState.monthlyGrossAdditions.remove(key)
        }
        invalidateSummaryAndRollingDependents(key)
    }

    fun setMonthlyAbsencePenaltyGross(year: Int, month: Int, amount: BigDecimal) {
        val key = MonthKey(year, month)
        val current = payState.monthlyGrossAdditions[key] ?: MonthlyGrossAdditions(year, month)
        val sanitized = current
            .copy(year = year, month = month, absencePenaltyGross = amount)
            .sanitizeMonthlyGrossAdditions(year, month)
        if (sanitized.hasAny) {
            payState.monthlyGrossAdditions[key] = sanitized
        } else {
            payState.monthlyGrossAdditions.remove(key)
        }
        invalidateSummaryAndRollingDependents(key)
    }
}
