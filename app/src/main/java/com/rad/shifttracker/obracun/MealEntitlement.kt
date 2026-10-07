package com.rad.shifttracker.obracun

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.YearMonth
import com.rad.shifttracker.osnova.CONFIG
import com.rad.shifttracker.smene.DayRecord
import com.rad.shifttracker.smene.MonthKey
import com.rad.shifttracker.smene.Shift

internal const val MANUAL_SPENDING_SAFE_DEFAULT_MODE =
    "MANUAL_SPENDING_SAFE_DEFAULT"

@Deprecated("Use MANUAL_SPENDING_SAFE_DEFAULT_MODE.")
internal const val AUTOMATIC_ENTITLEMENT_MANUAL_SPENDING_MODE =
    MANUAL_SPENDING_SAFE_DEFAULT_MODE

enum class MealEntitlementSource {
    SAFE_DEFAULT_EQUALS_SPENDING,
    CONFIRMED_MONTHLY_OVERRIDE,
    VERIFIED_COMPANY_RULE,
    UNVERIFIED_ESTIMATE
}

/**
 * Prag priznavanja obroka, kalibrisan na listiće 05/2026 i 06/2026.
 *
 * 06/2026: obračun je priznao 16 obroka (3.840,00 / 240,00). Sa pragom od 4h
 * dobija se 21, a sa 6h tačno 16 (14 dana ≥ 6h + 2 dodatna obroka).
 * 05/2026: 20 obroka × 274,00 = 5.480,00 — 18 dana ≥ 6h + 2 dana plaćenog
 * odsustva. Dani sa 5h i manje (npr. 22, 23, 24, 26. i 30.06.) se ne priznaju.
 */
private const val MINIMUM_MEAL_WORK_MINUTES = 6 * 60
private const val ADDITIONAL_MEAL_THRESHOLD_MINUTES = 2 * 60
private val MONEY_ZERO = BigDecimal.ZERO.setScale(2)

/**
 * Potvrđeni istorijski podaci sa platnog listića. Ovo nije pravilo firme i ne
 * primenjuje se ni na jedan drugi mesec.
 */
internal val CONFIRMED_MONTHLY_MEAL_ENTITLEMENT_OVERRIDES: Map<MonthKey, BigDecimal> = mapOf(
    MonthKey(2026, 6) to BigDecimal("3840.00")
)

data class MealPricePeriod(
    val validFrom: LocalDate,
    val netAmountPerMeal: BigDecimal
) {
    fun sanitized(): MealPricePeriod = copy(
        netAmountPerMeal = netAmountPerMeal.max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP)
    )
}

/**
 * Jedina ugrađena tabela istorijskih cena. Kasnije korisničke izmene se čuvaju
 * kao periodi i mogu da zamene iznos od prvog dana izabranog meseca.
 */
val DEFAULT_MEAL_PRICE_PERIODS: List<MealPricePeriod> = listOf(
    // Cena do 31.05.2026 sa listića 05/2026: 5.480,00 neto / 20 priznatih obroka.
    MealPricePeriod(LocalDate.of(2026, 1, 1), BigDecimal("274.00")),
    MealPricePeriod(LocalDate.of(2026, 6, 1), BigDecimal("240.00")),
    MealPricePeriod(LocalDate.of(2026, 7, 1), BigDecimal("280.00"))
)

data class RecognizedMealDecision(
    val accountingDate: LocalDate,
    val workDate: LocalDate,
    val mealCount: Int,
    val reasons: List<String>,
    val netAmountPerMeal: BigDecimal,
    val entitlementNet: BigDecimal
)

data class RejectedMealDecision(
    val accountingDate: LocalDate,
    val workDate: LocalDate?,
    val reason: String
)

data class MealEntitlementCalculation(
    val month: MonthKey,
    val pricePeriods: List<MealPricePeriod>,
    val recognizedMeals: List<RecognizedMealDecision>,
    val rejectedMeals: List<RejectedMealDecision>,
    val mealEntitlementNet: BigDecimal
) {
    val recognizedMealCount: Int
        get() = recognizedMeals.sumOf { it.mealCount }

    val recognizedMealDates: List<LocalDate>
        get() = recognizedMeals.flatMap { decision ->
            List(decision.mealCount) { decision.accountingDate }
        }

    val mealPriceNetPerMeal: BigDecimal?
        get() = recognizedMeals
            .map { it.netAmountPerMeal }
            .distinct()
            .singleOrNull()

    companion object {
        fun fromNet(month: MonthKey, entitlementNet: Double): MealEntitlementCalculation {
            return MealEntitlementCalculation(
                month = month,
                pricePeriods = emptyList(),
                recognizedMeals = emptyList(),
                rejectedMeals = emptyList(),
                mealEntitlementNet = entitlementNet.toMealMoney()
            )
        }
    }
}

fun resolveMealPriceForDate(
    date: LocalDate,
    pricePeriods: List<MealPricePeriod>
): BigDecimal {
    val periods = pricePeriods
        .map(MealPricePeriod::sanitized)
        .sortedBy { it.validFrom }
    return periods.lastOrNull { !it.validFrom.isAfter(date) }?.netAmountPerMeal
        ?: periods.firstOrNull()?.netAmountPerMeal
        ?: MONEY_ZERO
}

fun calculateMealEntitlementForAccountingMonth(
    records: List<DayRecord>,
    month: MonthKey,
    pricePeriods: List<MealPricePeriod>,
    defaultWorkHours: Int = CONFIG.pay.defaultHours
): MealEntitlementCalculation {
    val targetMonth = YearMonth.of(month.year, month.month)
    val sanitizedPeriods = pricePeriods
        .map(MealPricePeriod::sanitized)
        .distinctBy { it.validFrom }
        .sortedBy { it.validFrom }
    val recordsByAccountingDate = records
        .distinctBy { it.key }
        .groupBy { foodAccountingDate(it.date, it.shift) }
    val recognized = mutableListOf<RecognizedMealDecision>()
    val rejected = mutableListOf<RejectedMealDecision>()

    for (day in 1..targetMonth.lengthOfMonth()) {
        val accountingDate = targetMonth.atDay(day)
        val candidates = recordsByAccountingDate[accountingDate].orEmpty()
        if (candidates.isEmpty()) {
            rejected += RejectedMealDecision(accountingDate, null, "NO_RECORDED_WORK")
            continue
        }
        candidates.forEach { record ->
            val rejectionReason = mealRejectionReason(record)
            if (rejectionReason != null) {
                rejected += RejectedMealDecision(accountingDate, record.date, rejectionReason)
                return@forEach
            }
            val isPaidLeave = record.isPaidLeave
            val additionalWorkMinutes = if (isPaidLeave) {
                0
            } else {
                (record.totalWorkMinutes - defaultWorkHours * 60).coerceAtLeast(0)
            }
            val hasAdditionalMeal = additionalWorkMinutes > ADDITIONAL_MEAL_THRESHOLD_MINUTES
            val count = if (hasAdditionalMeal) 2 else 1
            val price = resolveMealPriceForDate(accountingDate, sanitizedPeriods)
            recognized += RecognizedMealDecision(
                accountingDate = accountingDate,
                workDate = record.date,
                mealCount = count,
                reasons = buildList {
                    if (isPaidLeave) {
                        add("PAID_LEAVE_COUNTS_AS_WORK_DAY")
                    } else {
                        add("EFFECTIVE_WORK_AT_LEAST_6_HOURS")
                    }
                    if (hasAdditionalMeal) add("ADDITIONAL_WORK_OVER_2_HOURS")
                },
                netAmountPerMeal = price,
                entitlementNet = price.multiply(BigDecimal.valueOf(count.toLong())).toMealMoney()
            )
        }
    }

    return MealEntitlementCalculation(
        month = month,
        pricePeriods = sanitizedPeriods,
        recognizedMeals = recognized,
        rejectedMeals = rejected,
        mealEntitlementNet = recognized
            .fold(BigDecimal.ZERO) { total, item -> total + item.entitlementNet }
            .toMealMoney()
    )
}

private fun mealRejectionReason(record: DayRecord): String? {
    if (record.shift == Shift.OFF) return "DAY_OFF_WITHOUT_WORK"
    if (record.shift == Shift.SICK) {
        if (!record.isPaidLeave) return "SICK_LEAVE_WITHOUT_WORK"
        // Plaćeno odsustvo (dobrovoljno davanje krvi) obračun tretira kao dan na
        // radu i priznaje obrok — potvrđeno listićem 05/2026 (25. i 26.05.).
        return if (record.totalWorkMinutes < MINIMUM_MEAL_WORK_MINUTES) {
            "PAID_LEAVE_UNDER_6_HOURS"
        } else {
            null
        }
    }
    if (record.shift == Shift.VACATION) return "VACATION_WITHOUT_WORK"
    if (record.shift !in Shift.workShifts() || record.totalWorkMinutes <= 0) return "ABSENCE_WITHOUT_WORK"
    if (record.totalWorkMinutes < MINIMUM_MEAL_WORK_MINUTES) return "EFFECTIVE_WORK_UNDER_6_HOURS"
    return null
}

internal fun BigDecimal.toMealMoney(): BigDecimal =
    max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP)

internal fun Double.toMealMoney(): BigDecimal =
    if (isFinite()) BigDecimal.valueOf(this).toMealMoney() else MONEY_ZERO
