package com.rad.shifttracker.obracun

import kotlin.math.max
import kotlin.math.round
import com.rad.shifttracker.smene.MonthKey

private const val MAX_MONTHLY_NET_ADJUSTMENT = 10_000_000.0

/** KU Valjaonice Bakra, član 91: akontacija >= 40% zarade isplaćene u prethodnom mesecu. */
const val ADVANCE_PAYMENT_MINIMUM_RATE = 0.4

internal const val ADVANCE_PAYMENT_DIALOG_TITLE = "Akontacija"
internal const val ADVANCE_PAYMENT_NET_LABEL = "Akontacija neto"
internal const val ADVANCE_PAYMENT_SAVE_LABEL = "Sačuvaj"
internal const val ADVANCE_PAYMENT_CANCEL_LABEL = "Otkaži"
internal const val FINAL_PAYMENT_NET_PAY_LABEL = "Neto plata"
internal const val FINAL_PAYMENT_ADVANCE_LABEL = "Akontacija"
internal const val FINAL_PAYMENT_DEDUCTIONS_LABEL = "Obustave"
internal const val FINAL_PAYMENT_REMAINING_LABEL = "Preostalo za konačnu isplatu"
internal const val FINAL_PAYMENT_REMAINING_20_LABEL = "Preostalo 20."
internal const val FINAL_PAYMENT_REMAINING_REGRESS_LABEL = "Preostalo regres"
internal const val MANUAL_ENTRY_BADGE_TEXT = "ručno uneto"
internal const val FOOD_NOT_OVERAGE_TEXT = "Hrana nije prekoračena"
internal const val ACTUAL_FOOD_SPENT_INPUT_LABEL = "Potrošeni topli obrok — neto"
internal const val MEAL_SPENT_ASSUMED_HELP_TEXT =
    "Nije unet iznos — maksimalna automatska procena koristi se kao potrošnja, bez viška u plati."
internal const val MEAL_SPENT_MANUAL_HELP_TEXT = "Koristi se ručno uneta stvarna potrošnja."
internal const val MEAL_SAFE_DEFAULT_STATUS_TEXT =
    "Pravo je izjednačeno sa unetom potrošnjom."
internal const val FINAL_PAYMENT_UNAVAILABLE_TEXT =
    "Nije moguće izračunati konačnu isplatu jer mesečna plata nije izračunata."
internal const val FINAL_PAYMENT_ADVANCE_WARNING_TEXT = "Akontacija je veća od obračunate neto plate."
internal const val ADVANCE_PREDICTION_SECTION_LABEL = "Predviđanje (KU čl. 91)"
internal const val ADVANCE_PREDICTION_PAID_PREVIOUS_LABEL = "Isplaćeno u prethodnom mesecu"
internal const val ADVANCE_PREDICTION_MINIMUM_LABEL = "Minimalna akontacija (40%)"
internal const val ADVANCE_PREDICTION_USE_MINIMUM_LABEL = "Unesi minimum"
internal const val ADVANCE_PREDICTION_UNAVAILABLE_TEXT =
    "Predviđanje nije dostupno — nema podataka o isplatama za prethodni mesec."

internal const val CURRENCY_LABEL = "RSD"

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
    /** Obustave sa listića (kredit, članarina...) pod nazivom sa listića; zbir je [deductionsNet]. */
    val deductions: List<FinalPaymentDeduction>,
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
    deductions: List<FinalPaymentDeduction> = emptyList(),
    remainingRegressNetPay: Double = 0.0
): MonthlyFinalPaymentBreakdown {
    val safeDeductions = if (isNetPayAvailable) {
        deductions.map { it.copy(amountNet = it.amountNet.sanitizeMonthlyNetAdjustment()) }.filter { it.amountNet > 0.0 }
    } else {
        emptyList()
    }
    val totalDeductions = safeDeductions.sumOf { it.amountNet }
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
        deductions = safeDeductions,
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
    confirmedEntitlementNetOverride: Double? = null
): MonthlyFoodSpendingBreakdown {
    val estimatedAllowance = foodAllowanceNet.sanitizeMonthlyNetAdjustment()
    val safeManual = manualFoodSpentNet.sanitizeMonthlyNetOverrideOrNull()
    val confirmedEntitlement = confirmedEntitlementNetOverride.sanitizeMonthlyNetOverrideOrNull()
    val source = if (safeManual != null) MealSpentSource.MANUAL else MealSpentSource.ENTITLEMENT_ASSUMED
    val applied = safeManual ?: confirmedEntitlement ?: estimatedAllowance
    val appliedEntitlement = confirmedEntitlement ?: applied
    val difference = appliedEntitlement - applied
    return MonthlyFoodSpendingBreakdown(
        month = month,
        foodAllowanceNet = appliedEntitlement,
        manualFoodSpentNet = safeManual,
        foodSpentNetApplied = applied,
        foodSpentSource = source,
        foodDifferenceNet = difference,
        foodOverageNet = max(0.0, -difference),
        unusedFoodNet = max(0.0, difference)
    )
}

internal fun mealSpentSupportingText(food: MonthlyFoodSpendingBreakdown): String {
    return when (food.foodSpentSource) {
        MealSpentSource.MANUAL -> MEAL_SPENT_MANUAL_HELP_TEXT
        MealSpentSource.ENTITLEMENT_ASSUMED -> MEAL_SPENT_ASSUMED_HELP_TEXT
    }
}

/**
 * Predviđanje akontacije za mesec isplate [paymentMonth] po KU članu 91: akontacija ne može
 * biti manja od 40% zarade isplaćene u prethodnom mesecu, umanjene za obustave. "Isplaćeno u
 * prethodnom mesecu" je zbir akontacije i konačne isplate (posle obustava) koje su legle u
 * prethodnom kalendarskom mesecu. Regres i prevoz ne ulaze: 02/2026 akontacija 22.805 je ispod
 * 40% sa regresom (26.121), a iznad bez njega (18.121).
 */
data class AdvancePaymentPrediction(
    val paymentMonth: MonthKey,
    val previousPaymentMonth: MonthKey?,
    val isAvailable: Boolean,
    val previousMonthAdvanceNet: Double,
    val previousMonthFinalNet: Double,
    val previousMonthPaidTotalNet: Double,
    val minimumAdvanceNet: Double
)

fun calculateAdvancePaymentPrediction(
    paymentMonth: MonthKey,
    previousPaymentMonth: MonthKey?,
    isPreviousMonthNetPayAvailable: Boolean,
    previousMonthAdvanceNet: Double,
    previousMonthFinalNet: Double
): AdvancePaymentPrediction {
    val safeAdvance = previousMonthAdvanceNet.sanitizeMonthlyNetAdjustment()
    val safeFinal = previousMonthFinalNet.sanitizeMonthlyNetAdjustment()
    val paidTotal = safeAdvance + safeFinal
    val available = previousPaymentMonth != null && isPreviousMonthNetPayAvailable && paidTotal > 0.0
    return AdvancePaymentPrediction(
        paymentMonth = paymentMonth,
        previousPaymentMonth = previousPaymentMonth,
        isAvailable = available,
        previousMonthAdvanceNet = safeAdvance,
        previousMonthFinalNet = safeFinal,
        previousMonthPaidTotalNet = paidTotal,
        minimumAdvanceNet = if (available) round(paidTotal * ADVANCE_PAYMENT_MINIMUM_RATE) else 0.0
    )
}

internal fun unavailableAdvancePaymentPrediction(paymentMonth: MonthKey): AdvancePaymentPrediction {
    return calculateAdvancePaymentPrediction(
        paymentMonth = paymentMonth,
        previousPaymentMonth = null,
        isPreviousMonthNetPayAvailable = false,
        previousMonthAdvanceNet = 0.0,
        previousMonthFinalNet = 0.0
    )
}
