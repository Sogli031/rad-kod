package com.rad.shifttracker.obracun

import java.time.LocalDate
import java.time.YearMonth
import kotlin.math.abs

/** Neoporezivi iznos zarade (poreska olakšica) koji važi za isplate od [validFrom]. */
data class DatedTaxRelief(val validFrom: LocalDate, val amount: Double)

/**
 * Porez, doprinosi radnika i olakšica sa interneta (paragraf.rs). Lista uvek ima prednost: ove
 * vrednosti važe samo za mesece čija je isplata posle isplate poslednje liste, i to samo kad je
 * promena stigla posle te liste — npr. nova olakšica od 1.1.2027. pre nego što 20.2. stigne lista
 * za januar. [ratesSeenSince] je mesec isplate od kog su stope ovakve (kad su prvi put viđene).
 */
data class WebTaxParameters(
    val taxPercent: Double,
    val contributionsPercent: Double,
    val ratesSeenSince: YearMonth,
    val reliefs: List<DatedTaxRelief>,
    val fetchedOn: LocalDate
)

/** Mesec isplate plate za rad u [workMonth]: plata stiže sledećeg meseca. */
fun paymentMonthOf(workMonth: YearMonth): YearMonth = workMonth.plusMonths(1)

/**
 * Olakšica za isplatu u [paymentMonth], ili null kad treba ostati na vrednosti sa liste: nova
 * olakšica važi samo ako je počela posle isplate poslednje liste ([lastPayslipPayment]).
 */
fun WebTaxParameters.taxReliefFor(paymentMonth: YearMonth, lastPayslipPayment: YearMonth?): Double? =
    reliefs
        .filter { YearMonth.from(it.validFrom) <= paymentMonth }
        .maxByOrNull { it.validFrom }
        ?.takeIf { lastPayslipPayment == null || YearMonth.from(it.validFrom) > lastPayslipPayment }
        ?.amount

/** Da li stope sa interneta važe za isplatu u [paymentMonth] (promena viđena posle poslednje liste). */
fun WebTaxParameters.ratesApplyTo(paymentMonth: YearMonth, lastPayslipPayment: YearMonth?): Boolean =
    paymentMonth >= ratesSeenSince && (lastPayslipPayment == null || ratesSeenSince > lastPayslipPayment)

/**
 * Spaja novo čitanje sa prethodnim: stope zadržavaju mesec od kog su viđene dok se ne promene, a
 * olakšice se pamte sve (novo čitanje ima prednost za isti datum).
 */
fun mergeWebTaxParameters(
    previous: WebTaxParameters?,
    taxPercent: Double,
    contributionsPercent: Double,
    reliefs: List<DatedTaxRelief>,
    today: LocalDate
): WebTaxParameters {
    val sameRates = previous != null &&
        abs(previous.taxPercent - taxPercent) < RATE_TOLERANCE &&
        abs(previous.contributionsPercent - contributionsPercent) < RATE_TOLERANCE
    val mergedReliefs = (reliefs + previous?.reliefs.orEmpty())
        .distinctBy { it.validFrom }
        .sortedBy { it.validFrom }
    return WebTaxParameters(
        taxPercent = taxPercent,
        contributionsPercent = contributionsPercent,
        ratesSeenSince = previous?.takeIf { sameRates }?.ratesSeenSince ?: YearMonth.from(today),
        reliefs = mergedReliefs,
        fetchedOn = today
    )
}

private const val RATE_TOLERANCE = 0.0001
