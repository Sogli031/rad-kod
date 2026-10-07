package com.rad.shifttracker.obracun

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
