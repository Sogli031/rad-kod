package com.rad.shifttracker.listici

import com.rad.shifttracker.smene.MonthKey

/** Nađen listić: ime fajla (ili poruke) i pročitan listić. */
internal data class FoundPayslip(val fileName: String, val result: PayslipParseResult.Success)

/**
 * Posebni obračuni (akontacija, prevoz, regres) za mesec rada [month], svaki jednom: ista kopija
 * u Gmail-u i folderu ili dvaput preuzet PDF se ne broji dvaput. Dva različita obračuna iste
 * vrste (i bez pročitanog rb.) ostaju oba.
 */
internal fun sideRuns(results: Sequence<PayslipParseResult>, month: MonthKey): List<PayslipParseResult.SideRun> =
    results.filterIsInstance<PayslipParseResult.SideRun>()
        .filter { it.month == month }
        .distinct()
        .toList()

/**
 * Akontacija po mesecu ISPLATE (aplikacija od plate isplaćene u mesecu X oduzima akontaciju
 * upisanu pod X): zbir onoga što je uplaćeno na račun po obračunima akontacije. Mesec isplate je
 * iz „Datum isplate“, a bez njega mesec posle meseca rada. Regres i prevoz ne ulaze — obračun
 * akontacije ih samo prenosi kao „isplaćeno“. Obračun bez pročitanog iznosa se preskače.
 */
internal fun advanceTotalsByPaymentMonth(runs: List<PayslipParseResult.SideRun>): Map<MonthKey, Double> =
    runs.filter { it.kind == PayslipSideRunKind.ADVANCE && (it.paidNet?.signum() ?: 0) > 0 }
        .groupBy(::advancePaymentMonth)
        .mapValues { (_, group) -> group.sumOf { it.paidNet!!.toDouble() } }

/**
 * Regres po mesecu rada („ZA MESEC“ — za 08/2026 isplaćen 28.08): zbir onoga što je uplaćeno
 * po obračunima regresa. Obračun bez pročitanog iznosa se preskače.
 */
internal fun regressTotalsByMonth(runs: List<PayslipParseResult.SideRun>): Map<MonthKey, Double> =
    runs.filter { it.kind == PayslipSideRunKind.REGRESS && (it.paidNet?.signum() ?: 0) > 0 }
        .groupBy { it.month }
        .mapValues { (_, group) -> group.sumOf { it.paidNet!!.toDouble() } }

/** Mesec isplate obračuna: iz „Datum isplate“, a bez njega mesec posle meseca rada. */
internal fun advancePaymentMonth(run: PayslipParseResult.SideRun): MonthKey = run.paymentMonth ?: nextMonthKey(run.month)

/** Najnoviji obračun prevoza sa pročitanim iznosom po danu (po mesecu rada, pa po rb.). */
internal fun latestTransportRun(runs: List<PayslipParseResult.SideRun>): PayslipParseResult.SideRun? =
    runs.filter { it.kind == PayslipSideRunKind.TRANSPORT && (it.transportDaily?.signum() ?: 0) > 0 }
        .maxWithOrNull(compareBy<PayslipParseResult.SideRun> { it.month }.thenBy { payslipRunNumber(it.runLabel) })

/** Prevoz po danu iz najnovijeg obračuna prevoza. */
internal fun latestTransportDaily(runs: List<PayslipParseResult.SideRun>): Double? =
    latestTransportRun(runs)?.transportDaily?.toDouble()

private fun nextMonthKey(month: MonthKey): MonthKey {
    val next = java.time.YearMonth.of(month.year, month.month).plusMonths(1)
    return MonthKey(next.year, next.monthValue)
}
