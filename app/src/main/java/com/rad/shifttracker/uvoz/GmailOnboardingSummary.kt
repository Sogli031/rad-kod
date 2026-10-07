package com.rad.shifttracker.uvoz

import java.time.LocalDate
import com.rad.shifttracker.osnova.CONFIG
import com.rad.shifttracker.repo.employeeContributionsPercentValue
import com.rad.shifttracker.repo.getHourlyValueForMonth
import com.rad.shifttracker.repo.getRegressNetForMonth
import com.rad.shifttracker.repo.taxPercentValue
import com.rad.shifttracker.repo.taxReliefValue
import com.rad.shifttracker.repo.transportDailyAmountValue
import com.rad.shifttracker.smene.MonthKey
import com.rad.shifttracker.smene.monthYearText
import com.rad.shifttracker.repo.ShiftRepository
import com.rad.shifttracker.repo.getKnownPayBaseAnchors
import com.rad.shifttracker.repo.getVacationBalanceSummary
import com.rad.shifttracker.repo.getVacationYearBalance
import com.rad.shifttracker.repo.payCoefficientValue
import com.rad.shifttracker.listici.formatSerbianDecimal
import com.rad.shifttracker.listici.toDisplayDecimal
import com.rad.shifttracker.listici.GMAIL_PAYSLIP_CHANGE_LABEL

// Tekst pregleda posle uvoza ili provere listića.

/**
 * Pregled posle provere: samo ono što je promenjeno (staro → novo). Kad se sve slaže, piše
 * koliko je listića provereno.
 */
internal fun recheckSummary(result: OnboardingImportResult): OnboardingSummary {
    val rows = result.differences.map { it.label to "${it.oldText} → ${it.newText}" }
    val notes = mutableListOf<String>()
    val info = mutableListOf<String>()
    if (result.payslips.isEmpty()) {
        notes += "U Gmail-u nema konačnih listi od januara prošle godine."
    } else {
        info += "Provereno ${listCountText(result.payslips.size)} (${monthRangeText(result.payslips.map { it.month })})."
        if (rows.isEmpty()) info += "Sve vrednosti se slažu sa listama — ništa nije menjano."
    }
    result.vacationNote?.let { info += it }
    if (result.gmailNoAccess) {
        notes += GMAIL_RECONNECT_NOTE
    } else if (result.gmailFailed) {
        notes += "Gmail trenutno nije odgovorio — probaj ponovo kasnije."
    }
    return OnboardingSummary(rows, notes, info, stacked = true)
}

internal const val GMAIL_RECONNECT_NOTE =
    "Gmail dozvola je istekla ili nalog nije na telefonu — Opcije → „$GMAIL_PAYSLIP_CHANGE_LABEL“."

/** Pregled posle onboardinga: naziv → vrednost, onako kako je sada upisano u aplikaciji. */
internal data class OnboardingSummary(
    val rows: List<Pair<String, String>>,
    /** Upozorenja (žuto). */
    val notes: List<String>,
    /** Objašnjenja, npr. kako je izračunat godišnji. */
    val info: List<String> = emptyList(),
    /** Naziv pa vrednost ispod (duge vrednosti „staro → novo“), umesto u istom redu. */
    val stacked: Boolean = false
)

internal fun onboardingSummary(
    repo: ShiftRepository,
    result: OnboardingImportResult,
    today: LocalDate = LocalDate.now()
): OnboardingSummary {
    val rows = mutableListOf<Pair<String, String>>()
    val notes = mutableListOf<String>()
    val latest = result.payslips.lastOrNull()
    if (latest == null) {
        notes += "U Gmail-u nema konačnih listi od januara prošle godine — upisaće se sami kad stignu."
    } else {
        val currency = CONFIG.pay.currency
        rows += "Liste" to "${shortMonthRangeText(result.payslips.map { it.month })} (${result.payslips.size})"
        rows += "Koeficijent" to decimalText(repo.payCoefficientValue)
        rows += "Vrednost sata" to "${decimalText(repo.getHourlyValueForMonth(latest.month.year, latest.month.month), 2)} $currency"
        latest.seniorityPercent?.let { percent ->
            rows += "Minuli rad" to "${formatSerbianDecimal(percent)}%"
        }
        rows += "Doprinosi radnika" to "${decimalText(repo.employeeContributionsPercentValue)}%"
        rows += "Porez" to "${decimalText(repo.taxPercentValue)}%"
        rows += "Poreska olakšica" to "${decimalText(repo.taxReliefValue, 2)} $currency"
        repo.getKnownPayBaseAnchors().maxByOrNull { it.month }?.let { anchor ->
            rows += "Osnovica" to "${decimalText(anchor.grossHourlyBase, 2)} $currency/h (${shortMonthYearText(anchor.month)})"
        }
        latest.totalSeniorityYears?.let { rows += "Radni staž" to "$it god." }
        if (repo.getVacationYearBalance(today.year) != null) {
            val vacation = repo.getVacationBalanceSummary(today.year, today)
            rows += "Godišnji" to "${decimalText(vacation.availableNow)} od ${decimalText(vacation.currentYearTotal)} dana"
        }
        val nets = result.payslips.mapNotNull { it.netTotal }
        if (nets.isNotEmpty()) {
            val average = nets.fold(java.math.BigDecimal.ZERO, java.math.BigDecimal::add)
                .divide(java.math.BigDecimal(nets.size), 0, java.math.RoundingMode.HALF_UP)
            rows += "Prosek neto" to "${formatSerbianDecimal(average)} $currency"
        }
    }
    if (latest != null) {
        rows += "Regres" to "${decimalText(repo.getRegressNetForMonth(MonthKey(today.year, today.monthValue)), 2)} ${CONFIG.pay.currency}"
    }
    result.transportDaily?.let {
        rows += "Prevoz po danu" to "${decimalText(repo.transportDailyAmountValue, 2)} ${CONFIG.pay.currency}"
    }
    if (result.advanceMonths.isNotEmpty()) {
        rows += "Akontacija" to "upisana (${shortMonthRangeText(result.advanceMonths)})"
    }
    if (result.gmailNoAccess) {
        notes += GMAIL_RECONNECT_NOTE
    } else if (result.gmailFailed) {
        notes += "Gmail trenutno nije odgovorio — akontacija i prevoz upisaće se pri sledećem ulasku, a listu učitaj u „Listi“."
    }
    return OnboardingSummary(rows, notes, info = listOfNotNull(result.vacationNote))
}

internal fun decimalText(value: Double, minFraction: Int = 0): String =
    formatSerbianDecimal(value.toDisplayDecimal(), minFraction = minFraction)

private val SHORT_MONTH_NAMES =
    listOf("Jan", "Feb", "Mar", "Apr", "Maj", "Jun", "Jul", "Avg", "Sep", "Okt", "Nov", "Dec")

/** „1 lista“, „3 liste“, „19 listi“. */
internal fun listCountText(count: Int): String {
    val lastTwo = count % 100
    val word = when {
        count % 10 == 1 && lastTwo != 11 -> "lista"
        count % 10 in 2..4 && lastTwo !in 12..14 -> "liste"
        else -> "listi"
    }
    return "$count $word"
}

/** „Avg 2026“ — za redove koji moraju da stanu u jednu liniju. */
internal fun shortMonthYearText(key: MonthKey): String = "${SHORT_MONTH_NAMES[key.month - 1]} ${key.year}"

/** „Jan 2025 – Avg 2026“, „Jul – Avg 2026“ ili jedan mesec. */
internal fun shortMonthRangeText(months: List<MonthKey>): String {
    val first = months.minOrNull() ?: return ""
    val last = months.max()
    if (first == last) return shortMonthYearText(first)
    val firstText = if (first.year == last.year) SHORT_MONTH_NAMES[first.month - 1] else shortMonthYearText(first)
    return "$firstText – ${shortMonthYearText(last)}"
}

/** „Mart – Avgust 2026“, „Decembar 2025 – Maj 2026“ ili jedan mesec. */
internal fun monthRangeText(months: List<MonthKey>): String {
    val first = months.minOrNull() ?: return ""
    val last = months.max()
    if (first == last) return monthYearText(first)
    val firstText = if (first.year == last.year) monthYearText(first).substringBefore(' ') else monthYearText(first)
    return "$firstText – ${monthYearText(last)}"
}
