package com.rad.shifttracker

import java.math.BigDecimal

/** Obustava u konačnoj isplati: naziv sa listića i neto iznos. */
data class FinalPaymentDeduction(val name: String, val amountNet: Double)

/**
 * Jedna obustava u % zarade (sindikat, fond...) za mesec rada: naziv i procenat sa liste i, kad je
 * lista tog meseca sačuvana, iznos sa nje. Bez liste ([amountFromPayslip] = null) iznos se računa od
 * cele mesečne neto zarade (sa akontacijom), procentom sa poslednje starije liste.
 */
data class PercentDeduction(val name: String, val percent: BigDecimal, val amountFromPayslip: BigDecimal?) {
    fun amountNet(netPayBeforeDeductions: Double): Double =
        amountFromPayslip?.toDouble()
            ?: roundMoneyHalfUp(netPayBeforeDeductions.sanitizeMoney() * percent.toDouble() / 100.0)
}

/** Sve obustave u % jednog meseca. */
data class PercentDeductions(val rows: List<PercentDeduction>) {
    fun deductions(netPayBeforeDeductions: Double): List<FinalPaymentDeduction> =
        rows.map { FinalPaymentDeduction(it.name, it.amountNet(netPayBeforeDeductions)) }

    fun amountNet(netPayBeforeDeductions: Double): Double =
        deductions(netPayBeforeDeductions).sumOf { it.amountNet }
}

/**
 * Obustave u % za mesec rada [month], samo iz listića: lista tog meseca važi i kad nema nijedan red
 * sa procentom (tada je obustava 0, null); bez nje procenat daje poslednja starija lista.
 */
internal fun percentDeductionsForMonth(month: MonthKey, payslips: Collection<ParsedPayslip>): PercentDeductions? {
    val source = payslips
        .filter { it.deductions != null && it.month <= month }
        .maxByOrNull { it.month }
        ?: return null
    val rows = source.deductions.orEmpty().filter { it.percent != null }
    if (rows.isEmpty()) return null
    return PercentDeductions(
        rows.map { row ->
            PercentDeduction(row.name, row.percent!!, row.amount.takeIf { source.month == month })
        }
    )
}

/**
 * Obustave iz plate za mesec rada [salaryMonth] (isplata u sledećem mesecu).
 *
 * Kad je lista za taj mesec sačuvana, važe njene obustave. Inače se uzimaju sa poslednje starije
 * liste: obustava sa „Ostalo rata“ N (posle te rate) ide još N meseci; obustava sa ostatkom duga 0
 * je bila poslednja; fiksna obustava bez rata ide dok je nova lista ne ukine.
 * Topli obrok nije ovde (parser ga preskače), a obustave u % idu u neto plate svog meseca
 * ([percentDeductionsForMonth]).
 */
internal fun finalPaymentDeductions(
    salaryMonth: MonthKey,
    payslips: List<ParsedPayslip>
): List<FinalPaymentDeduction> {
    val source = payslips
        .filter { it.deductions != null && it.month <= salaryMonth }
        .maxByOrNull { it.month }
        ?: return emptyList()
    val monthsAhead = monthIndex(salaryMonth) - monthIndex(source.month)
    return source.deductions.orEmpty()
        .filter { it.percent == null }
        .filter { deduction -> monthsAhead == 0 || deduction.continuesFor(monthsAhead) }
        .map { FinalPaymentDeduction(it.name, it.amount.toDouble()) }
}

private fun PayslipDeduction.continuesFor(monthsAhead: Int): Boolean {
    if (remainingDebt != null && remainingDebt.signum() <= 0) return false
    val installments = remainingInstallments ?: return true
    return installments >= monthsAhead
}

private fun monthIndex(month: MonthKey): Int = month.year * 12 + month.month - 1
