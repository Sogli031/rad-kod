package com.rad.shifttracker.repo

import java.math.BigDecimal
import com.rad.shifttracker.obracun.CONFIRMED_MONTHLY_MEAL_ENTITLEMENT_OVERRIDES
import com.rad.shifttracker.obracun.MonthlyGrossAdditions
import com.rad.shifttracker.obracun.toMealMoney
import com.rad.shifttracker.smene.MonthKey

internal const val MAX_BASE_PAY = 100_000.0

private const val MAX_MONTHLY_GROSS_ADDITION = 10_000_000.0

internal fun Double.sanitizeAnnualLeaveYears(): Double {
    return if (isFinite()) coerceAtLeast(0.0) else 0.0
}

internal fun Double?.sanitizePayBaseOrNull(): Double? {
    return if (this != null && isFinite() && this > 0.0) this else null
}

@Suppress("DEPRECATION")
internal fun MonthlyGrossAdditions.sanitizeMonthlyGrossAdditions(year: Int, month: Int): MonthlyGrossAdditions {
    val sanitizedMealSpent = mealSpentNet?.sanitizeMonthlyGrossAddition()
    val historicalOverride = CONFIRMED_MONTHLY_MEAL_ENTITLEMENT_OVERRIDES[MonthKey(year, month)]
        ?.takeIf { sanitizedMealSpent?.toMealMoney() == BigDecimal("3620.00") }
    return MonthlyGrossAdditions(
        year = year,
        month = month,
        mealGross = mealGross.sanitizeMonthlyGrossOverrideOrNull(),
        regressGross = regressGross.sanitizeMonthlyGrossAddition(),
        bonusGross = bonusGross.sanitizeMonthlyGrossAddition(),
        mealSpentNet = sanitizedMealSpent,
        bonusNet = bonusNet.sanitizeMonthlyGrossAddition(),
        mealSettlementAdjustmentNet = mealSettlementAdjustmentNet.sanitizeMonthlySignedAdjustment(),
        absencePenaltyGross = absencePenaltyGross.sanitizeMonthlyGrossPenalty(),
        mealEntitlementNetOverride = (mealEntitlementNetOverride ?: historicalOverride)
            ?.sanitizeMonthlyMealOverride()
    )
}

internal fun BigDecimal.sanitizeMonthlyMealOverride(): BigDecimal {
    if (signum() < 0) return BigDecimal.ZERO.setScale(2)
    return min(BigDecimal.valueOf(MAX_MONTHLY_GROSS_ADDITION)).toMealMoney()
}

internal fun BigDecimal.sanitizeMonthlyGrossPenalty(): BigDecimal {
    if (signum() <= 0) return BigDecimal.ZERO
    return min(BigDecimal.valueOf(MAX_MONTHLY_GROSS_ADDITION))
}

internal fun Double.sanitizeMonthlyGrossAddition(): Double {
    return if (isFinite() && this > 0.0) {
        coerceAtMost(MAX_MONTHLY_GROSS_ADDITION)
    } else {
        0.0
    }
}

internal fun Double?.sanitizeMonthlyGrossOverrideOrNull(): Double? {
    return if (this != null && isFinite() && this >= 0.0) {
        coerceAtMost(MAX_MONTHLY_GROSS_ADDITION)
    } else {
        null
    }
}

internal fun Double.sanitizeMonthlySignedAdjustment(): Double {
    return if (isFinite()) {
        coerceIn(-MAX_MONTHLY_GROSS_ADDITION, MAX_MONTHLY_GROSS_ADDITION)
    } else {
        0.0
    }
}
