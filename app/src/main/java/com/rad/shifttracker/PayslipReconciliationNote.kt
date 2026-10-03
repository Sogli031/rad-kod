package com.rad.shifttracker

import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.abs

internal const val PAYSLIP_GROSS_ROW = "Bruto ukupno"
internal const val PAYSLIP_NET_ROW = "Neto ukupno"
internal const val PAYSLIP_PAYOUT_ROW = "Za isplatu"
private const val EXPLAINED_TOLERANCE = 1.0
private const val PERCENT = 100.0

internal const val PAYSLIP_SENIORITY_AMOUNT_ROW = "Minuli rad iznos (MR)"
internal const val PAYSLIP_MEAL_ROW = "Topli obrok (TO2)"

/**
 * Zbirni redovi: njihova razlika je posledica stavki (i onih koje aplikacija ne prati, npr.
 * stimulacije, KIRU), ne posebna stavka — prikazuju se radi pregleda, ali se ne broje kao neslaganje.
 */
internal val PAYSLIP_TOTAL_ROWS = setOf(PAYSLIP_GROSS_ROW, PAYSLIP_NET_ROW, PAYSLIP_PAYOUT_ROW)

/**
 * Jedna stavka ispod tabele; [isAlert] = crveno (npr. pojedeno više nego što pripada).
 * [isNote] = neutralno objašnjenje razlike u zbirovima (stavka koju aplikacija ne prati), ne neslaganje.
 */
internal data class PayslipDifference(val text: String, val isAlert: Boolean = false, val isNote: Boolean = false)

/**
 * Kratak spisak onoga što se ne slaže, naziv i iznos („Stimulacije: 10.699,00 RSD“,
 * „Godišnji odmor (10): aplikacija 8 h, listić 16 h“).
 * Razlika u bruto se razlaže na stavke bez časova i novčane redove; ako posle toga ostane
 * razlika a časovi se slažu, dodaje se „Nepoznata razlika“ sa iznosom. Kad se sve slaže, spisak je prazan.
 *
 * @param mealOverspend koliko je pojedeno više nego što pripada (neto), ako jeste.
 */
internal fun payslipDifferences(
    rows: List<PayslipCheckRow>,
    extras: List<PayslipExtraItem>,
    appGross: Double,
    slipGross: BigDecimal?,
    mealOverspend: Double? = null
): List<PayslipDifference> {
    val items = mutableListOf<PayslipDifference>()
    val itemRows = rows.filterNot { it.isTotal }
    for (row in itemRows.filterNot { it.matches }) {
        if (row.label == PAYSLIP_MEAL_ROW && mealOverspend != null) continue
        val amount = row.difference?.let(::money) ?: "aplikacija ${row.appText}, lista ${row.payslipText}"
        items += PayslipDifference("${row.label}: $amount")
    }
    mealOverspend?.let { items += PayslipDifference("Topli obrok, pojedeno više: ${money(it)}", isAlert = true) }

    val grossMismatch = rows.any { it.label == PAYSLIP_GROSS_ROW && !it.matches }
    val difference = slipGross?.toDouble()?.minus(appGross)
    if (grossMismatch && difference != null) {
        // Novčani redovi ulaze i kad su označeni kao u redu (npr. manje pojeden topli obrok).
        val moneyParts = itemRows.sumOf { it.difference ?: 0.0 }
        // „Izostanci 1“ je zaokruživanje minuta koje upisani dani već mogu da sadrže, pa se proba i bez njega.
        val chosen = listOf(extras, extras.filterNot { it.label == ABSENCE_ROUNDING_EXTRA_LABEL })
            .minBy { set -> abs(difference - moneyParts - set.sumOf { it.amount.toDouble() }) }
        chosen.forEach { items += PayslipDifference("${it.label}: ${money(it.amount.toDouble())}", isNote = true) }
        val rest = difference - moneyParts - chosen.sumOf { it.amount.toDouble() }
        val hoursDiffer = itemRows.any { !it.matches && it.difference == null }
        if (!hoursDiffer && abs(rest) >= EXPLAINED_TOLERANCE) {
            items += PayslipDifference("Nepoznata razlika: ${money(rest)} (vrednost sata ili osnovica)", isNote = true)
        }
    } else if (rows.any { it.label == PAYSLIP_NET_ROW && !it.matches }) {
        items += PayslipDifference("Doprinosi ili porez", isNote = true)
    }
    return items
}

/** Neto toplog obroka sa listića: TO2 bruto umanjen za doprinose i porez sa istog listića. */
internal fun payslipMealEntitlementNet(payslip: ParsedPayslip): Double? {
    val gross = payslip.mealGross ?: return null
    val contributions = payslip.employeeContributionsPercent ?: return null
    val tax = payslip.taxPercent ?: return null
    val net = gross.toDouble() * (1.0 - contributions.toDouble() / PERCENT - tax.toDouble() / PERCENT)
    return net.toDisplayDecimal().setScale(2, RoundingMode.HALF_UP).toDouble()
}

/**
 * Koliko je pojedeno više nego što je priznato (obustava „Topli obrok“ − neto TO2), ili null.
 * TO2 na listiću je pojedeno, ograničeno onim što pripada; višak se skida sa plate.
 */
internal fun payslipMealOverspend(payslip: ParsedPayslip): Double? {
    val entitled = payslipMealEntitlementNet(payslip) ?: return null
    val spent = payslip.mealDeduction?.toDouble() ?: return null
    return (spent - entitled).takeIf { it >= EXPLAINED_TOLERANCE }
}

private fun money(value: Double): String =
    "${formatSerbianDecimal(value.toDisplayDecimal().setScale(2, RoundingMode.HALF_UP), minFraction = 2)} " +
        CONFIG.pay.currency
