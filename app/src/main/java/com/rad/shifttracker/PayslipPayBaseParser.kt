package com.rad.shifttracker

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols

/**
 * Stavke listića iz kojih se čita osnovica naknada, po prioritetu.
 * Bolovanje se isplaćuje u procentu osnovice, pa se iznos deli i tim procentom.
 */
internal enum class PayslipPayBaseSource(val code: Int, val label: String, val usesPercent: Boolean) {
    VACATION(10, "Godišnji odmor", usesPercent = false),
    PAID_LEAVE(11, "Plaćeno odsustvo", usesPercent = false),
    STATE_HOLIDAY(12, "Državni praznik", usesPercent = false),
    SICK_LEAVE(40, "Bolovanje za tekući mesec", usesPercent = true)
}

internal data class PayslipPayBaseCandidate(
    val source: PayslipPayBaseSource,
    val hours: BigDecimal,
    val percent: BigDecimal?,
    val grossAmount: BigDecimal,
    val hourlyBase: BigDecimal
)

internal sealed interface PayslipPayBaseResult {
    data class Success(
        val month: MonthKey,
        val runLabel: String?,
        val chosen: PayslipPayBaseCandidate,
        val candidates: List<PayslipPayBaseCandidate>
    ) : PayslipPayBaseResult {
        /** Meka provera: GO, plaćeno odsustvo i praznik treba da daju istu osnovicu. */
        val hasMismatch: Boolean
            get() {
                val bases = candidates.filter { !it.source.usesPercent }.map { it.hourlyBase }
                val min = bases.minOrNull() ?: return false
                val max = bases.maxOrNull() ?: return false
                return max.subtract(min) > PAY_BASE_MISMATCH_TOLERANCE
            }
    }

    data object InvalidPdf : PayslipPayBaseResult
    data object NoText : PayslipPayBaseResult
    data object NoMonth : PayslipPayBaseResult
    data class NoSource(val month: MonthKey) : PayslipPayBaseResult
}

private val PAY_BASE_MISMATCH_TOLERANCE = BigDecimal("0.02")
private val ONE_HUNDRED = BigDecimal(100)

private val PAYSLIP_MONTH_REGEX =
    Regex("""ZA MESEC\s+(\d{1,2})/(\d{4})(?:\s+(rb\.\S+))?""", RegexOption.IGNORE_CASE)

// šifra, naziv, časovi (uvek jedna decimala), opciono ceo procenat, iznos bez separatora hiljada
private val PAYSLIP_ITEM_REGEX =
    Regex("""^(\d{1,3})\s+(\D.*?)\s+(\d+\.\d)(?:\s+(\d{1,3}))?\s+(\d+(?:\.\d{1,2})?)$""")

internal fun parsePayslipPayBase(text: String): PayslipPayBaseResult = parsePayslipPayBase(text.lines())

/** Godine koje listić sme da ima; van toga je mesec pogrešno pročitan. */
internal val PAYSLIP_YEARS = 2000..2100

// Listić ima ~60 redova od najviše ~150 znakova; više je smeće iz neispravnog PDF-a (i sporo za regex).
private const val MAX_PAYSLIP_LINES = 2_000
private const val MAX_PAYSLIP_LINE_LENGTH = 1_000

/** Redovi za parsiranje: bez praznih; predugi redovi i višak redova se ne čitaju. */
internal fun payslipTextLines(lines: List<String>): List<String> =
    lines.asSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() && it.length <= MAX_PAYSLIP_LINE_LENGTH }
        .take(MAX_PAYSLIP_LINES)
        .toList()

internal fun parsePayslipPayBase(lines: List<String>): PayslipPayBaseResult {
    val trimmed = payslipTextLines(lines)
    if (trimmed.none { line -> line.any(Char::isLetterOrDigit) }) return PayslipPayBaseResult.NoText

    val header = trimmed.firstNotNullOfOrNull { PAYSLIP_MONTH_REGEX.find(it) }
        ?: return PayslipPayBaseResult.NoMonth
    val monthNumber = header.groupValues[1].toInt()
    val year = header.groupValues[2].toInt()
    if (monthNumber !in 1..12 || year !in PAYSLIP_YEARS) return PayslipPayBaseResult.NoMonth
    val month = MonthKey(year, monthNumber)
    val runLabel = header.groupValues[3].takeIf { it.isNotEmpty() }

    val candidates = PayslipPayBaseSource.entries.mapNotNull { source ->
        trimmed.firstNotNullOfOrNull { line -> parsePayBaseCandidate(line, source) }
    }
    val chosen = candidates.firstOrNull() ?: return PayslipPayBaseResult.NoSource(month)
    return PayslipPayBaseResult.Success(month, runLabel, chosen, candidates)
}

/**
 * Osnovica iz stavki koje listić zadržava (prva stavka po šifri). Isto se računa pri skeniranju
 * i pri čitanju sačuvanog listića, pa osnovica posle ponovnog otvaranja ostaje ista.
 */
internal fun payslipPayBaseFromItems(
    month: MonthKey,
    runLabel: String?,
    items: Map<Int, PayslipItem>
): PayslipPayBaseResult {
    val candidates = PayslipPayBaseSource.entries.mapNotNull { source ->
        items[source.code]?.let { item -> payBaseCandidate(source, item.hours, item.percent, item.amount) }
    }
    val chosen = candidates.firstOrNull() ?: return PayslipPayBaseResult.NoSource(month)
    return PayslipPayBaseResult.Success(month, runLabel, chosen, candidates)
}

private fun parsePayBaseCandidate(line: String, source: PayslipPayBaseSource): PayslipPayBaseCandidate? {
    val match = PAYSLIP_ITEM_REGEX.matchEntire(line) ?: return null
    val groups = match.groupValues
    if (groups[1].toInt() != source.code) return null
    return payBaseCandidate(
        source = source,
        hours = BigDecimal(groups[3]),
        percent = groups[4].takeIf { it.isNotEmpty() }?.let(::BigDecimal),
        amount = BigDecimal(groups[5])
    )
}

private fun payBaseCandidate(
    source: PayslipPayBaseSource,
    hours: BigDecimal,
    percent: BigDecimal?,
    amount: BigDecimal
): PayslipPayBaseCandidate? {
    if (hours.signum() <= 0 || amount.signum() <= 0) return null
    val paidHours = if (source.usesPercent) {
        // Procenat se čita sa listića; bez njega se bolovanje ne može preračunati.
        if (percent == null || percent.signum() <= 0) return null
        hours.multiply(percent).divide(ONE_HUNDRED)
    } else {
        hours
    }
    return PayslipPayBaseCandidate(
        source = source,
        hours = hours,
        percent = percent,
        grossAmount = amount,
        hourlyBase = amount.divide(paidHours, 2, RoundingMode.HALF_UP)
    )
}

internal fun payslipImportErrorText(result: PayslipPayBaseResult): String? = when (result) {
    is PayslipPayBaseResult.Success -> null
    PayslipPayBaseResult.InvalidPdf -> "Izabrani fajl nije ispravan PDF, prevelik je ili ne može da se otvori."
    PayslipPayBaseResult.NoText ->
        "PDF ne sadrži tekst (verovatno je skeniran), pa se osnovica ne može pročitati. Unesi je ručno."
    PayslipPayBaseResult.NoMonth ->
        "Na listi nije pronađen mesec obračuna („ZA MESEC MM/GGGG“)."
    is PayslipPayBaseResult.NoSource ->
        "Na listi za ${payslipMonthText(result.month)} nema godišnjeg odmora, plaćenog odsustva, " +
            "državnog praznika ni bolovanja, pa se osnovica ne može izračunati."
}

internal fun payslipMonthText(month: MonthKey): String = "%02d/%04d".format(month.month, month.year)

internal fun payslipSourceText(candidate: PayslipPayBaseCandidate): String =
    "${candidate.source.label} (${"%02d".format(candidate.source.code)})"

/** Npr. „15.267,88 / 16 h = 954,24 RSD/h“ ili „23.888,93 / (40 h × 65%) = 918,81 RSD/h“. */
internal fun payslipFormulaText(candidate: PayslipPayBaseCandidate): String {
    val amount = formatSerbianDecimal(candidate.grossAmount, minFraction = 2)
    val hours = "${formatSerbianDecimal(candidate.hours)} h"
    val divisor = candidate.percent
        ?.takeIf { candidate.source.usesPercent }
        ?.let { "($hours × ${formatSerbianDecimal(it)}%)" }
        ?: hours
    val base = formatSerbianDecimal(candidate.hourlyBase, minFraction = 2)
    return "$amount / $divisor = $base ${CONFIG.pay.currency}/h"
}

internal fun payslipSummaryText(result: PayslipPayBaseResult.Success): String =
    "${monthYearText(result.month)} · ${payslipSourceText(result.chosen)}: ${payslipFormulaText(result.chosen)}"

internal fun payslipMismatchWarningText(result: PayslipPayBaseResult.Success): String? {
    if (!result.hasMismatch) return null
    val details = result.candidates
        .filter { !it.source.usesPercent }
        .joinToString("; ") { "${it.source.label} ${formatSerbianDecimal(it.hourlyBase, minFraction = 2)}" }
    return "Upozorenje: stavke na listi daju različite osnovice ($details ${CONFIG.pay.currency}/h). " +
        "Upisuje se ${payslipSourceText(result.chosen)}; proveri listu."
}

internal fun formatSerbianDecimal(value: BigDecimal, minFraction: Int = 0, maxFraction: Int = 2): String {
    val symbols = DecimalFormatSymbols().apply {
        groupingSeparator = '.'
        decimalSeparator = ','
    }
    val format = DecimalFormat("#,##0", symbols).apply {
        minimumFractionDigits = minFraction
        maximumFractionDigits = maxFraction
        roundingMode = RoundingMode.HALF_UP
    }
    return format.format(value)
}
