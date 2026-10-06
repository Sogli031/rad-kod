package com.rad.shifttracker.data.storage

import com.rad.shifttracker.MAX_BASE_PAY
import com.rad.shifttracker.MonthKey
import com.rad.shifttracker.MonthlyGrossAdditions
import com.rad.shifttracker.MonthlyNetAdjustments
import com.rad.shifttracker.MonthlyPayBases
import com.rad.shifttracker.PayBaseSelectionMode
import com.rad.shifttracker.sanitizeMonthlyGrossAddition
import com.rad.shifttracker.sanitizeMonthlyGrossAdditions
import com.rad.shifttracker.sanitizeMonthlyGrossOverrideOrNull
import com.rad.shifttracker.sanitizeMonthlyGrossPenalty
import com.rad.shifttracker.sanitizeMonthlySignedAdjustment
import com.rad.shifttracker.sanitizeMonthlyNetAdjustment
import com.rad.shifttracker.sanitizePayBaseOrNull
import com.rad.shifttracker.toMealMoney
import org.json.JSONObject
import java.math.BigDecimal
import java.time.LocalDate
import java.util.Locale
import java.util.TreeMap

// Mesečne mape u JSON-u: {"2026": {"05": {...}}}, po jedna za osnovice, akontaciju, vrednost sata,
// topli obrok po danu i bruto dodatke.

private const val HOURLY_VALUE_KEY = "hourly_value"
private const val FOOD_PER_DAY_VALUE_KEY = "food_per_day"
private const val VACATION_HOURLY_BASE_KEY = "vacation_hourly_base"
private const val PAID_HOLIDAY_HOURLY_BASE_KEY = "paid_holiday_hourly_base"
private const val SICK_HOURLY_BASE_KEY = "sick_hourly_base"
private const val SELECTION_MODE_KEY = "selection_mode"
private const val ADVANCE_PAYMENT_NET_KEY = "advance_payment_net"
private const val MEAL_GROSS_KEY = "meal_gross"
private const val REGRESS_GROSS_KEY = "regress_gross"
private const val BONUS_GROSS_KEY = "bonus_gross"
private const val MEAL_SPENT_NET_KEY = "meal_spent_net"
private const val MEAL_ENTITLEMENT_NET_OVERRIDE_KEY = "meal_entitlement_net_override"
private const val MEAL_SETTLEMENT_ADJUSTMENT_NET_KEY = "meal_settlement_adjustment_net"
private const val BONUS_NET_KEY = "bonus_net"
private const val ABSENCE_PENALTY_GROSS_KEY = "absence_penalty_gross"
private const val MAX_FOOD_PER_DAY_CHANGE = 10_000.0

/** Prolazi kroz ispravne mesece (godina 1900–2200, mesec 1–12) čija vrednost je JSON objekat. */
internal fun forEachMonthObject(root: JSONObject, block: (year: Int, month: Int, data: JSONObject) -> Unit) {
    val yearKeys = root.keys()
    while (yearKeys.hasNext()) {
        val yearKey = yearKeys.next()
        val year = yearKey.toIntOrNull() ?: continue
        if (year < 1900 || year > 2200) continue
        val monthsObj = root.optJSONObject(yearKey) ?: continue
        val monthKeys = monthsObj.keys()
        while (monthKeys.hasNext()) {
            val monthKey = monthKeys.next()
            val month = monthKey.toIntOrNull() ?: continue
            if (month !in 1..12) continue
            val data = monthsObj.optJSONObject(monthKey) ?: continue
            block(year, month, data)
        }
    }
}

private fun monthJsonKey(month: Int): String = String.format(Locale.ROOT, "%02d", month)

internal fun buildMonthlyPayBasesMap(monthlyPayBases: Map<MonthKey, MonthlyPayBases>): TreeMap<String, Any> {
    val root = TreeMap<String, TreeMap<String, Any>>()
    for ((key, bases) in monthlyPayBases.toSortedMap()) {
        val yearMap = root.getOrPut(key.year.toString()) { TreeMap() }
        val monthData = TreeMap<String, Any>()
        bases.selectionMode?.let { monthData[SELECTION_MODE_KEY] = it.name }
        bases.vacationHourlyBase?.let { monthData[VACATION_HOURLY_BASE_KEY] = it }
        bases.paidHolidayHourlyBase?.let { monthData[PAID_HOLIDAY_HOURLY_BASE_KEY] = it }
        bases.sickHourlyBase?.let { monthData[SICK_HOURLY_BASE_KEY] = it }
        if (monthData.isNotEmpty()) {
            yearMap[monthJsonKey(key.month)] = monthData
        }
    }
    return TreeMap(root)
}

internal fun parseMonthlyPayBases(root: JSONObject?): Map<MonthKey, MonthlyPayBases> {
    if (root == null) return emptyMap()
    val parsed = mutableMapOf<MonthKey, MonthlyPayBases>()
    forEachMonthObject(root) { year, month, data ->
        parseMonthlyPayBase(year, month, data)?.let { bases ->
            parsed[MonthKey(year, month)] = bases
        }
    }
    return parsed
}

private fun parseMonthlyPayBase(year: Int, month: Int, data: JSONObject): MonthlyPayBases? {
    val selectionMode = data.optString(SELECTION_MODE_KEY, "")
        .takeIf { it.isNotBlank() }
        ?.let { runCatching { PayBaseSelectionMode.valueOf(it) }.getOrNull() }
    val bases = MonthlyPayBases(
        year = year,
        month = month,
        vacationHourlyBase = data.optPositiveFiniteDoubleOrNull(VACATION_HOURLY_BASE_KEY),
        paidHolidayHourlyBase = data.optPositiveFiniteDoubleOrNull(PAID_HOLIDAY_HOURLY_BASE_KEY),
        sickHourlyBase = data.optPositiveFiniteDoubleOrNull(SICK_HOURLY_BASE_KEY),
        selectionMode = selectionMode
    )
    return bases.takeIf { it.hasAnyConfiguredPayBase() }
}

private fun MonthlyPayBases.hasAnyConfiguredPayBase(): Boolean {
    return vacationHourlyBase != null ||
        paidHolidayHourlyBase != null ||
        sickHourlyBase != null ||
        selectionMode != null
}

internal fun buildMonthlyNetAdjustmentsMap(
    monthlyNetAdjustments: Map<MonthKey, MonthlyNetAdjustments>
): TreeMap<String, Any> {
    val root = TreeMap<String, TreeMap<String, Any>>()
    for ((key, adjustments) in monthlyNetAdjustments.toSortedMap()) {
        val sanitized = adjustments.sanitizedFor(key.year, key.month)
        if (!sanitized.hasAny) continue
        val yearMap = root.getOrPut(key.year.toString()) { TreeMap() }
        yearMap[monthJsonKey(key.month)] = TreeMap<String, Any>().apply {
            put(ADVANCE_PAYMENT_NET_KEY, sanitized.advancePaymentNet)
        }
    }
    return TreeMap(root)
}

internal fun parseMonthlyNetAdjustments(root: JSONObject?): Map<MonthKey, MonthlyNetAdjustments> {
    if (root == null) return emptyMap()
    val parsed = mutableMapOf<MonthKey, MonthlyNetAdjustments>()
    forEachMonthObject(root) { year, month, data ->
        val adjustments = MonthlyNetAdjustments(
            year = year,
            month = month,
            advancePaymentNet = data.optMonthlyNetAdjustment(ADVANCE_PAYMENT_NET_KEY)
        ).sanitizedFor(year, month)
        if (adjustments.hasAny) {
            parsed[MonthKey(year, month)] = adjustments
        }
    }
    return parsed
}

internal fun buildHourlyValueChangesMap(hourlyValueChanges: Map<MonthKey, Double>): TreeMap<String, Any> =
    buildMonthlyValueMap(hourlyValueChanges, HOURLY_VALUE_KEY)

internal fun parseHourlyValueChanges(root: JSONObject?): Map<MonthKey, Double> {
    if (root == null) return emptyMap()
    val parsed = mutableMapOf<MonthKey, Double>()
    forEachMonthObject(root) { year, month, data ->
        val value = data.optDouble(HOURLY_VALUE_KEY, Double.NaN)
        if (value.isFinite() && value > 0.0) {
            parsed[MonthKey(year, month)] = value.coerceAtMost(MAX_BASE_PAY)
        }
    }
    return parsed
}

internal fun buildFoodPerDayChangesMap(foodPerDayChanges: Map<MonthKey, Double>): TreeMap<String, Any> =
    buildMonthlyValueMap(foodPerDayChanges, FOOD_PER_DAY_VALUE_KEY)

internal fun parseFoodPerDayChanges(root: JSONObject?): Map<MonthKey, Double> {
    if (root == null) return emptyMap()
    val parsed = mutableMapOf<MonthKey, Double>()
    forEachMonthObject(root) { year, month, data ->
        val value = data.optDouble(FOOD_PER_DAY_VALUE_KEY, Double.NaN)
        if (value.isFinite() && value >= 0.0) {
            parsed[MonthKey(year, month)] = value.coerceAtMost(MAX_FOOD_PER_DAY_CHANGE)
        }
    }
    return parsed
}

private fun buildMonthlyValueMap(values: Map<MonthKey, Double>, valueKey: String): TreeMap<String, Any> {
    val root = TreeMap<String, TreeMap<String, Any>>()
    for ((key, value) in values.toSortedMap()) {
        val yearMap = root.getOrPut(key.year.toString()) { TreeMap() }
        yearMap[monthJsonKey(key.month)] = TreeMap<String, Any>().apply {
            put(valueKey, value)
        }
    }
    return TreeMap(root)
}

@Suppress("DEPRECATION")
internal fun buildMonthlyGrossAdditionsMap(
    monthlyGrossAdditions: Map<MonthKey, MonthlyGrossAdditions>
): TreeMap<String, Any> {
    val root = TreeMap<String, TreeMap<String, Any>>()
    for ((key, additions) in monthlyGrossAdditions.toSortedMap()) {
        val sanitized = additions.sanitizeMonthlyGrossAdditions(key.year, key.month)
        if (!sanitized.hasAny) continue
        val yearMap = root.getOrPut(key.year.toString()) { TreeMap() }
        val monthData = TreeMap<String, Any>()
        sanitized.mealGross?.let { monthData[MEAL_GROSS_KEY] = it }
        if (sanitized.regressGross > 0.0) monthData[REGRESS_GROSS_KEY] = sanitized.regressGross
        if (sanitized.bonusGross > 0.0) monthData[BONUS_GROSS_KEY] = sanitized.bonusGross
        sanitized.mealSpentNet?.let { monthData[MEAL_SPENT_NET_KEY] = it }
        sanitized.mealEntitlementNetOverride?.let { monthData[MEAL_ENTITLEMENT_NET_OVERRIDE_KEY] = it }
        if (sanitized.bonusNet > 0.0) monthData[BONUS_NET_KEY] = sanitized.bonusNet
        if (sanitized.absencePenaltyGross.signum() > 0) {
            monthData[ABSENCE_PENALTY_GROSS_KEY] = sanitized.absencePenaltyGross
        }
        monthData.putIfNonZero(MEAL_SETTLEMENT_ADJUSTMENT_NET_KEY, sanitized.mealSettlementAdjustmentNet)
        if (monthData.isNotEmpty()) {
            yearMap[monthJsonKey(key.month)] = monthData
        }
    }
    return TreeMap(root)
}

@Suppress("DEPRECATION")
internal fun parseMonthlyGrossAdditions(root: JSONObject?): Map<MonthKey, MonthlyGrossAdditions> {
    if (root == null) return emptyMap()
    val parsed = mutableMapOf<MonthKey, MonthlyGrossAdditions>()
    forEachMonthObject(root) { year, month, data ->
        val additions = MonthlyGrossAdditions(
            year = year,
            month = month,
            mealGross = data.optMonthlyGrossOverrideOrNull(MEAL_GROSS_KEY),
            regressGross = data.optMonthlyGrossAddition(REGRESS_GROSS_KEY),
            bonusGross = data.optMonthlyGrossAddition(BONUS_GROSS_KEY),
            mealSpentNet = data.optMonthlyNetDeductionOrNull(MEAL_SPENT_NET_KEY),
            bonusNet = data.optMonthlyGrossAddition(BONUS_NET_KEY),
            mealSettlementAdjustmentNet = data.optMonthlySignedAdjustment(MEAL_SETTLEMENT_ADJUSTMENT_NET_KEY),
            absencePenaltyGross = data.optMonthlyGrossPenalty(ABSENCE_PENALTY_GROSS_KEY),
            mealEntitlementNetOverride = data.optMonthlyMoneyOrNull(MEAL_ENTITLEMENT_NET_OVERRIDE_KEY)
        ).sanitizeMonthlyGrossAdditions(year, month)
        if (additions.hasAny) {
            parsed[MonthKey(year, month)] = additions
        }
    }
    return parsed
}

private fun JSONObject.optMonthlyGrossPenalty(key: String): BigDecimal {
    if (!has(key)) return BigDecimal.ZERO
    return optString(key).toBigDecimalOrNull()?.sanitizeMonthlyGrossPenalty() ?: BigDecimal.ZERO
}

private fun JSONObject.optMonthlyMoneyOrNull(key: String): BigDecimal? {
    if (!has(key) || isNull(key)) return null
    return optString(key).toBigDecimalOrNull()?.takeIf { it.signum() >= 0 }?.toMealMoney()
}

private fun JSONObject.optPositiveFiniteDoubleOrNull(key: String): Double? {
    if (!has(key)) return null
    val value = optDouble(key, Double.NaN)
    return value.sanitizePayBaseOrNull()
}

private fun JSONObject.optMonthlyGrossAddition(key: String): Double {
    if (!has(key)) return 0.0
    val value = optDouble(key, Double.NaN)
    return value.sanitizeMonthlyGrossAddition()
}

private fun MutableMap<String, Any>.putIfNonZero(key: String, value: Double) {
    if (value != 0.0) this[key] = value
}

private fun JSONObject.optMonthlyGrossOverrideOrNull(key: String): Double? {
    if (!has(key)) return null
    val value = optDouble(key, Double.NaN)
    return value.sanitizeMonthlyGrossOverrideOrNull()
}

private fun JSONObject.optMonthlyNetDeductionOrNull(key: String): Double? {
    if (!has(key)) return null
    val value = optDouble(key, Double.NaN)
    return value.sanitizeMonthlyGrossAddition()
}

private fun JSONObject.optMonthlySignedAdjustment(key: String): Double {
    if (!has(key)) return 0.0
    return optDouble(key, Double.NaN).sanitizeMonthlySignedAdjustment()
}

private fun JSONObject.optMonthlyNetAdjustment(key: String): Double {
    if (!has(key)) return 0.0
    val value = optDouble(key, Double.NaN)
    return value.sanitizeMonthlyNetAdjustment()
}

internal fun JSONObject.optLocalDateOrNull(key: String): LocalDate? {
    if (!has(key)) return null
    return runCatching { LocalDate.parse(optString(key)) }.getOrNull()
}
