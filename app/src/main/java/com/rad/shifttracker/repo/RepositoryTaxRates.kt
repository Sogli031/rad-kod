package com.rad.shifttracker.repo

import java.time.YearMonth
import com.rad.shifttracker.listici.ParsedPayslip
import com.rad.shifttracker.obracun.WebTaxParameters
import com.rad.shifttracker.obracun.paymentMonthOf
import com.rad.shifttracker.obracun.ratesApplyTo
import com.rad.shifttracker.obracun.taxReliefFor
import com.rad.shifttracker.smene.MonthKey

/**
 * Poreska olakšica, doprinosi radnika i porez za mesec rada. Lista uvek ima prednost; vrednosti sa
 * interneta ([WebTaxParameters]) važe samo za mesece posle poslednje liste.
 */
internal class RepositoryTaxRates(
    private val payState: RepositoryPayState,
    private val savedPayslipUnsafe: (MonthKey) -> ParsedPayslip?,
    private val savedPayslipsUnsafe: () -> List<ParsedPayslip>
) {
    /**
     * Poreska olakšica meseca rada. Olakšica se menja od januara, a važi po datumu isplate (lista 12/2025,
     * isplaćena u januaru, ima 34.221 kao 2026). Zato: sa liste tog meseca, inače sa najnovije liste iste
     * godine isplate, inače sa interneta ako je nova olakšica počela posle poslednje liste, inače iz
     * podešavanja (najnovija lista).
     */
    fun taxReliefUnsafe(year: Int, month: Int): Double {
        val key = MonthKey(year, month)
        fun ParsedPayslip.relief() = taxReliefRsd?.toDouble()?.takeIf { it.isFinite() && it >= 0.0 }
        savedPayslipUnsafe(key)?.relief()?.let { return it }
        val paymentYear = payslipPaymentYear(key)
        return savedPayslipsUnsafe()
            .filter { payslipPaymentYear(it.month) == paymentYear }
            .maxByOrNull { it.month }
            ?.relief()
            ?: payState.webTaxParameters?.taxReliefFor(paymentMonthOf(key.yearMonth()), lastPayslipPaymentUnsafe())
            ?: payState.taxRelief
    }

    /** Doprinosi radnika meseca: sa liste, a posle poslednje liste sa interneta ako su se promenili. */
    fun contributionsPercentUnsafe(year: Int, month: Int): Double =
        webRatesUnsafe(year, month)?.contributionsPercent ?: payState.employeeContributionsPercent

    /** Porez na zarade meseca: sa liste, a posle poslednje liste sa interneta ako se promenio. */
    fun taxPercentUnsafe(year: Int, month: Int): Double =
        webRatesUnsafe(year, month)?.taxPercent ?: payState.taxPercent

    private fun webRatesUnsafe(year: Int, month: Int): WebTaxParameters? {
        val key = MonthKey(year, month)
        if (savedPayslipUnsafe(key) != null) return null
        return payState.webTaxParameters
            ?.takeIf { it.ratesApplyTo(paymentMonthOf(key.yearMonth()), lastPayslipPaymentUnsafe()) }
    }

    /** Mesec isplate najnovije sačuvane liste (lista za septembar se isplaćuje u oktobru). */
    private fun lastPayslipPaymentUnsafe(): YearMonth? =
        savedPayslipsUnsafe().maxByOrNull { it.month }?.month?.let { paymentMonthOf(it.yearMonth()) }

    private fun MonthKey.yearMonth(): YearMonth = YearMonth.of(year, month)

    private fun payslipPaymentYear(month: MonthKey): Int = if (month.month == 12) month.year + 1 else month.year
}
