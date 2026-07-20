package com.rad.shifttracker

import kotlin.math.max

private const val MAX_MONTHLY_NET_ADJUSTMENT = 10_000_000.0

internal const val ADVANCE_PAYMENT_DIALOG_TITLE = "Akontacija"
internal const val ADVANCE_PAYMENT_NET_LABEL = "Akontacija neto"
internal const val ADVANCE_PAYMENT_EXPLANATION_TEXT =
    "Unesi neto iznos akontacije koji je već isplaćen. Ovaj iznos se ne koristi za obračun bruto plate, poreza i doprinosa, već samo umanjuje iznos koji ostaje za konačnu isplatu."
internal const val ADVANCE_PAYMENT_SAVE_LABEL = "Sačuvaj"
internal const val ADVANCE_PAYMENT_CANCEL_LABEL = "Otkaži"
internal const val FINAL_PAYMENT_NET_PAY_LABEL = "Neto plata"
internal const val FINAL_PAYMENT_ADVANCE_LABEL = "Isplaćena akontacija"
internal const val FINAL_PAYMENT_DEDUCTIONS_LABEL = "Obustave"
internal const val FINAL_PAYMENT_OTHER_DEDUCTIONS_LABEL = "Ostale obustave"
internal const val FINAL_PAYMENT_REMAINING_LABEL = "Preostalo za konačnu isplatu"
internal const val FINAL_PAYMENT_REMAINING_20_LABEL = "Preostalo 20."
internal const val FINAL_PAYMENT_REMAINING_REGRESS_LABEL = "Preostalo regres"
internal const val FINAL_PAYMENT_LOAN_DEDUCTION_LABEL = "Kredit"
internal const val FINAL_PAYMENT_FOOD_OVERAGE_LABEL = "Hrana prekoračeno"
internal const val MANUAL_ENTRY_BADGE_TEXT = "ručno uneto"
internal const val FOOD_NOT_OVERAGE_TEXT = "Hrana nije prekoračena"
internal const val FOOD_ALLOWANCE_STATUS_LABEL = "Pravo na topli obrok"
internal const val FOOD_ACTUAL_SPENT_STATUS_LABEL = "Potrošeno"
internal const val FOOD_UNUSED_LABEL = "Neiskorišćeno"
internal const val FOOD_NET_DEDUCTION_EXPLANATION_TEXT = "Od neto plate se odbija primenjena potrošnja"
internal const val MEAL_SPENT_ASSUMED_HELP_TEXT =
    "Nije uneta stvarna potrošnja — procena računa da je iskorišćeno celo pravo."
internal const val MEAL_SPENT_MANUAL_HELP_TEXT = "Koristi se ručno uneta stvarna potrošnja."
internal const val FINAL_PAYMENT_UNAVAILABLE_TEXT =
    "Nije moguće izračunati konačnu isplatu jer mesečna plata nije izračunata."
internal const val FINAL_PAYMENT_ADVANCE_WARNING_TEXT = "Akontacija je veća od obračunate neto plate."
internal const val CREDIT_DEDUCTION_SECTION_LABEL = "Kredit/obustava"
internal const val CREDIT_DEDUCTION_ENABLED_LABEL = "Kredit aktivan"
internal const val MONTHLY_CREDIT_DEDUCTION_AMOUNT_LABEL = "Mesečna rata kredita"
internal const val CREDIT_DEDUCTION_CURRENCY_LABEL = "RSD"

data class CreditDeductionSettings(
    val creditDeductionEnabled: Boolean = false,
    val monthlyCreditDeductionAmount: Double = 0.0
) {
    val activeDeductionNet: Double
        get() = if (creditDeductionEnabled) {
            monthlyCreditDeductionAmount.sanitizeMonthlyNetAdjustment()
        } else {
            0.0
        }

    fun sanitized(): CreditDeductionSettings {
        return CreditDeductionSettings(
            creditDeductionEnabled = creditDeductionEnabled,
            monthlyCreditDeductionAmount = monthlyCreditDeductionAmount.sanitizeMonthlyNetAdjustment()
        )
    }
}

const val DEFAULT_UNION_DUES_RATE = 0.015

internal const val UNION_DUES_SECTION_LABEL = "Sindikalna članarina"
internal const val UNION_DUES_ENABLED_LABEL = "Član sindikata"
internal const val UNION_DUES_RATE_LABEL = "Stopa članarine (%)"

data class UnionDuesSettings(
    val unionMember: Boolean = false,
    val unionDuesRate: Double = DEFAULT_UNION_DUES_RATE
) {
    val activeRate: Double
        get() = if (unionMember) unionDuesRate.sanitizeUnionDuesRate() else 0.0

    fun sanitized(): UnionDuesSettings {
        return UnionDuesSettings(
            unionMember = unionMember,
            unionDuesRate = unionDuesRate.sanitizeUnionDuesRate()
        )
    }
}

data class MonthlyNetAdjustments(
    val year: Int,
    val month: Int,
    val advancePaymentNet: Double = 0.0
) {
    val hasAny: Boolean
        get() = advancePaymentNet.sanitizeMonthlyNetAdjustment() > 0.0

    fun sanitizedFor(year: Int = this.year, month: Int = this.month): MonthlyNetAdjustments {
        return MonthlyNetAdjustments(
            year = year,
            month = month,
            advancePaymentNet = advancePaymentNet.sanitizeMonthlyNetAdjustment()
        )
    }
}

data class MonthlyFoodSpendingBreakdown(
    val month: MonthKey,
    val foodAllowanceNet: Double,
    val manualFoodSpentNet: Double?,
    val automaticFoodSpentNet: Double?,
    val foodSpentNetApplied: Double,
    val foodSpentSource: MealSpentSource,
    val foodDifferenceNet: Double,
    val foodOverageNet: Double,
    val unusedFoodNet: Double
) {
    @Deprecated("Use manualFoodSpentNet for the manual input or foodSpentNetApplied for calculation.")
    val actualFoodSpentNet: Double?
        get() = manualFoodSpentNet
}

data class FinalNetPayBreakdown(
    val netPayBeforeAdvance: Double,
    val advancePaymentNet: Double,
    val deductionsNet: Double,
    val finalRemainingNetPay: Double,
    val advanceExceedsCalculatedNetPay: Boolean
)

data class MonthlyFinalPaymentBreakdown(
    val month: MonthKey,
    val isNetPayAvailable: Boolean,
    val netPayBeforeAdvance: Double,
    val advancePaymentNet: Double,
    val deductionsNet: Double,
    val loanDeductionNet: Double,
    val foodOverageDeductionNet: Double,
    val otherDeductionsNet: Double,
    val remainingRegressNetPay: Double,
    val finalRemainingNetPay: Double,
    val advanceExceedsCalculatedNetPay: Boolean
)

fun calculateFinalNetPayBreakdown(
    netPayBeforeAdvance: Double,
    advancePaymentNet: Double,
    deductionsNet: Double
): FinalNetPayBreakdown {
    val safeNetPay = netPayBeforeAdvance.sanitizeMonthlyNetAdjustment()
    val safeAdvance = advancePaymentNet.sanitizeMonthlyNetAdjustment()
    val safeDeductions = deductionsNet.sanitizeMonthlyNetAdjustment()
    return FinalNetPayBreakdown(
        netPayBeforeAdvance = safeNetPay,
        advancePaymentNet = safeAdvance,
        deductionsNet = safeDeductions,
        finalRemainingNetPay = max(0.0, safeNetPay - safeAdvance - safeDeductions),
        advanceExceedsCalculatedNetPay = safeAdvance > safeNetPay
    )
}

internal fun calculateMonthlyFinalPaymentBreakdown(
    month: MonthKey,
    isNetPayAvailable: Boolean,
    netPayBeforeAdvance: Double,
    advancePaymentNet: Double,
    loanDeductionNet: Double = 0.0,
    foodOverageDeductionNet: Double = 0.0,
    otherDeductionsNet: Double = 0.0,
    remainingRegressNetPay: Double = 0.0
): MonthlyFinalPaymentBreakdown {
    val safeLoanDeduction = if (isNetPayAvailable) loanDeductionNet.sanitizeMonthlyNetAdjustment() else 0.0
    val safeFoodOverageDeduction = if (isNetPayAvailable) {
        foodOverageDeductionNet.sanitizeMonthlyNetAdjustment()
    } else {
        0.0
    }
    val safeOtherDeductions = if (isNetPayAvailable) otherDeductionsNet.sanitizeMonthlyNetAdjustment() else 0.0
    val totalDeductions = safeLoanDeduction + safeFoodOverageDeduction + safeOtherDeductions
    val finalPay = if (isNetPayAvailable) {
        calculateFinalNetPayBreakdown(netPayBeforeAdvance, advancePaymentNet, totalDeductions)
    } else {
        calculateFinalNetPayBreakdown(0.0, advancePaymentNet, 0.0)
    }
    return MonthlyFinalPaymentBreakdown(
        month = month,
        isNetPayAvailable = isNetPayAvailable,
        netPayBeforeAdvance = finalPay.netPayBeforeAdvance,
        advancePaymentNet = finalPay.advancePaymentNet,
        deductionsNet = finalPay.deductionsNet,
        loanDeductionNet = safeLoanDeduction,
        foodOverageDeductionNet = safeFoodOverageDeduction,
        otherDeductionsNet = safeOtherDeductions,
        remainingRegressNetPay = if (isNetPayAvailable) remainingRegressNetPay.sanitizeMonthlyNetAdjustment() else 0.0,
        finalRemainingNetPay = if (isNetPayAvailable) finalPay.finalRemainingNetPay else 0.0,
        advanceExceedsCalculatedNetPay = isNetPayAvailable && finalPay.advanceExceedsCalculatedNetPay
    )
}

internal fun Double.sanitizeMonthlyNetAdjustment(): Double {
    return if (isFinite() && this > 0.0) {
        coerceAtMost(MAX_MONTHLY_NET_ADJUSTMENT)
    } else {
        0.0
    }
}

internal fun Double.sanitizeUnionDuesRate(): Double {
    return if (isFinite() && this > 0.0) coerceAtMost(1.0) else DEFAULT_UNION_DUES_RATE
}

internal fun Double?.sanitizeMonthlyNetOverrideOrNull(): Double? {
    return if (this != null && isFinite() && this >= 0.0) {
        coerceAtMost(MAX_MONTHLY_NET_ADJUSTMENT)
    } else {
        null
    }
}

internal fun calculateMonthlyFoodSpendingBreakdown(
    month: MonthKey,
    foodAllowanceNet: Double,
    manualFoodSpentNet: Double?,
    automaticFoodSpentNet: Double? = null
): MonthlyFoodSpendingBreakdown {
    val safeAllowance = foodAllowanceNet.sanitizeMonthlyNetAdjustment()
    val safeManual = manualFoodSpentNet.sanitizeMonthlyNetOverrideOrNull()
    val safeAutomatic = automaticFoodSpentNet.sanitizeMonthlyNetOverrideOrNull()
    val source = when {
        safeManual != null -> MealSpentSource.MANUAL
        safeAutomatic != null -> MealSpentSource.AUTOMATIC
        else -> MealSpentSource.ENTITLEMENT_ASSUMED
    }
    val applied = when (source) {
        MealSpentSource.MANUAL -> safeManual ?: 0.0
        MealSpentSource.AUTOMATIC -> safeAutomatic ?: 0.0
        MealSpentSource.ENTITLEMENT_ASSUMED -> safeAllowance
    }
    val difference = safeAllowance - applied
    return MonthlyFoodSpendingBreakdown(
        month = month,
        foodAllowanceNet = safeAllowance,
        manualFoodSpentNet = safeManual,
        automaticFoodSpentNet = safeAutomatic,
        foodSpentNetApplied = applied,
        foodSpentSource = source,
        foodDifferenceNet = difference,
        foodOverageNet = max(0.0, -difference),
        unusedFoodNet = max(0.0, difference)
    )
}

internal fun actualFoodSpentInputLabel(month: MonthKey): String {
    return "Potrošeni topli obrok — neto (${monthYearText(month)})"
}

internal fun mealSpentSupportingText(food: MonthlyFoodSpendingBreakdown): String {
    return when (food.foodSpentSource) {
        MealSpentSource.MANUAL -> MEAL_SPENT_MANUAL_HELP_TEXT
        MealSpentSource.AUTOMATIC ->
            "Automatski evidentirano: ${formatMoney2(food.foodSpentNetApplied)} ${CONFIG.pay.currency}"
        MealSpentSource.ENTITLEMENT_ASSUMED -> MEAL_SPENT_ASSUMED_HELP_TEXT
    }
}
