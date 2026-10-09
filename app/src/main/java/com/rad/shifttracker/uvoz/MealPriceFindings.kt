package com.rad.shifttracker.uvoz

import java.math.BigDecimal
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import com.rad.shifttracker.listici.ParsedPayslip
import com.rad.shifttracker.listici.payslipMealEntitlementNet
import com.rad.shifttracker.listici.payslipMealOverspend
import com.rad.shifttracker.repo.ShiftRepository
import com.rad.shifttracker.repo.getSavedPayslip
import com.rad.shifttracker.repo.getSavedPayslipMonths
import com.rad.shifttracker.smene.MonthKey

private const val MIN_PLAUSIBLE_MEAL_PRICE = 150
private const val MAX_PLAUSIBLE_MEAL_PRICE = 600
private const val WORK_ITEM_CODE = 1
private const val OVERTIME_ITEM_CODE = 30
private const val HOURS_PER_MEAL = 8.0
/** Broj obroka sme da odstupi od dana rada sa listića (dodatni obroci, plaćeno odsustvo). */
private const val MIN_MEAL_COUNT_SLACK = 3.0
private const val MEAL_COUNT_SLACK_SHARE = 0.35
/** Toliko lista sa prekoračenjem u periodu da se cena upisuje sama (uz jedini mogući delilac). */
private const val RELIABLE_MIN_PAYSLIPS = 3

/** Lista na kojoj je pojedeno više nego što pripada: neto TO2 je tada pun iznos (obroci × cena). */
internal data class MealOverspendMonth(
    val month: MonthKey,
    /** Neto TO2 u celim dinarima. */
    val allowanceNet: Int,
    val eatenNet: Double,
    /** Dani rada sa listića (rad + prekovremeno, po 8 h), ako ih listić ima. */
    val workDays: Double?
)

/** Cena obroka pročitana sa uzastopnih lista sa prekoračenjem; važi bar od [from] do [until]. */
internal data class MealPriceFromPayslips(
    val price: Int,
    val from: MonthKey,
    val until: MonthKey,
    /** Najnovija prvo. */
    val months: List<MealOverspendMonth>,
    /**
     * Bar [RELIABLE_MIN_PAYSLIPS] liste i samo jedna moguća cena: upisuje se sama. Inače je cena
     * izabrana po danima rada (npr. 5.320 = 19 × 280 = 20 × 266) i korisnik je potvrđuje ručno.
     */
    val reliable: Boolean = false
) {
    fun mealCount(month: MealOverspendMonth): Int = month.allowanceNet / price
}

internal fun mealOverspendMonth(payslip: ParsedPayslip): MealOverspendMonth? {
    payslipMealOverspend(payslip) ?: return null
    val allowance = payslipMealEntitlementNet(payslip)?.roundToInt()?.takeIf { it > 0 } ?: return null
    val eaten = payslip.mealDeduction?.toDouble() ?: return null
    val hours = listOfNotNull(payslip.items[WORK_ITEM_CODE]?.hours, payslip.items[OVERTIME_ITEM_CODE]?.hours)
    val workDays = hours.takeIf { it.isNotEmpty() }?.sumOf { it.toDouble() }?.div(HOURS_PER_MEAL)
    return MealOverspendMonth(payslip.month, allowance, eaten, workDays)
}

/**
 * Cena obroka samo sa lista, bez smena. TO2 je pojedeno, ograničeno onim što pripada; samo kad
 * je pojedeno više, neto TO2 je broj obroka × cena (višak se skida sa plate). Uzastopne takve
 * liste imaju istu cenu, pa je cena njihov najveći zajednički delilac — kad bi sledeća lista
 * spustila delilac ispod moguće cene, počinje drugi period. Od delilaca u opsegu bira se onaj
 * čiji broj obroka najbolje odgovara danima rada sa lista; period se odbacuje ako broj obroka
 * ni u jednom mesecu ne liči na dane rada.
 */
internal fun mealPricesFromPayslips(payslips: Collection<ParsedPayslip>): List<MealPriceFromPayslips> {
    val overspent = payslips
        .associateBy { it.month }
        .values
        .mapNotNull(::mealOverspendMonth)
        .sortedByDescending { it.month }
    val groups = mutableListOf<List<MealOverspendMonth>>()
    var current = mutableListOf<MealOverspendMonth>()
    var divisor = 0
    for (month in overspent) {
        val joined = gcd(divisor, month.allowanceNet)
        if (current.isNotEmpty() && joined < MIN_PLAUSIBLE_MEAL_PRICE) {
            groups += current
            current = mutableListOf()
            divisor = month.allowanceNet
        } else {
            divisor = joined
        }
        current += month
    }
    if (current.isNotEmpty()) groups += current
    return groups.mapNotNull(::priceForGroup)
}

private fun priceForGroup(months: List<MealOverspendMonth>): MealPriceFromPayslips? {
    val divisor = months.fold(0) { acc, month -> gcd(acc, month.allowanceNet) }
    val candidates = (MIN_PLAUSIBLE_MEAL_PRICE..MAX_PLAUSIBLE_MEAL_PRICE).filter { divisor % it == 0 }
    val withDays = months.filter { it.workDays != null }
    val price = when {
        candidates.isEmpty() -> return null
        withDays.isEmpty() -> candidates.singleOrNull() ?: return null
        else -> candidates.minBy { candidate ->
            withDays.sumOf { abs(it.allowanceNet.toDouble() / candidate - it.workDays!!) }
        }
    }
    val plausible = withDays.all { month ->
        val days = month.workDays!!
        abs(month.allowanceNet.toDouble() / price - days) <= max(MIN_MEAL_COUNT_SLACK, days * MEAL_COUNT_SLACK_SHARE)
    }
    if (!plausible) return null
    return MealPriceFromPayslips(
        price = price,
        from = months.last().month,
        until = months.first().month,
        months = months,
        reliable = months.size >= RELIABLE_MIN_PAYSLIPS && candidates.size == 1
    )
}

private tailrec fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)

internal fun savedPayslips(repo: ShiftRepository): List<ParsedPayslip> =
    repo.getSavedPayslipMonths().mapNotNull { repo.getSavedPayslip(it.year, it.month) }

/** Cene sa sačuvanih lista, najnovija prvo. */
internal fun mealPriceFindings(repo: ShiftRepository): List<MealPriceFromPayslips> =
    mealPricesFromPayslips(savedPayslips(repo))

/** Period sa cenom za [month]: onaj koji ga sadrži, inače poslednji pre njega. */
internal fun mealPriceSourceFor(findings: List<MealPriceFromPayslips>, month: MonthKey): MealPriceFromPayslips? =
    findings.filter { it.from <= month }.maxByOrNull { it.from }

internal fun MealPriceFromPayslips.priceDecimal(): BigDecimal = BigDecimal(price).setScale(2)

/** Meseci od [MealPriceFromPayslips.from] do [MealPriceFromPayslips.until], redom. */
internal fun MealPriceFromPayslips.coveredMonths(): List<MonthKey> =
    generateSequence(from) { key ->
        if (key.month == 12) MonthKey(key.year + 1, 1) else MonthKey(key.year, key.month + 1)
    }.takeWhile { it <= until }.toList()
