package com.rad.shifttracker

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
    return MonthlyGrossAdditions(
        year = year,
        month = month,
        mealGross = mealGross.sanitizeMonthlyGrossOverrideOrNull(),
        regressGross = regressGross.sanitizeMonthlyGrossAddition(),
        bonusGross = bonusGross.sanitizeMonthlyGrossAddition(),
        mealSpentNet = mealSpentNet?.sanitizeMonthlyGrossAddition(),
        bonusNet = bonusNet.sanitizeMonthlyGrossAddition(),
        mealSettlementAdjustmentNet = mealSettlementAdjustmentNet.sanitizeMonthlySignedAdjustment(),
        automaticMealSpentNet = automaticMealSpentNet?.sanitizeMonthlyGrossAddition()
    )
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
