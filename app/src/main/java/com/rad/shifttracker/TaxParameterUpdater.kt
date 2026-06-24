package com.rad.shifttracker

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.text.Normalizer
import java.time.LocalDate
import java.util.Locale

private const val TAX_USER_AGENT = "Mozilla/5.0 rad-shifttracker/1.0"
private const val TAX_FETCH_TIMEOUT_MS = 30_000

private val TAX_PARAMETER_SOURCES = mapOf(
    "income_tax_law" to "https://www.paragraf.rs/propisi/zakon-o-porezu-na-dohodak-gradjana.html",
    "allowance" to
        "https://www.paragraf.rs/statistika/poreski_tretman_i_uskladjeni_neoporezivi_iznosi_za_odredjena_licna_primanja_zaposlenih_i_ostalih_fizickih_lica.html",
    "contributions" to "https://www.paragraf.rs/propisi/zakon-o-doprinosima-za-obavezno-socijalno-osiguranje.html"
)

data class TaxParameters(
    val salaryTaxRatePercent: Double,
    val salaryTaxAllowanceRsd: Double,
    val pioEmployeePercent: Double,
    val healthEmployeePercent: Double,
    val unemploymentEmployeePercent: Double
) {
    val employeeContributionsPercent: Double
        get() = pioEmployeePercent + healthEmployeePercent + unemploymentEmployeePercent
}

class TaxParameterUpdater(
    private val sources: Map<String, String> = TAX_PARAMETER_SOURCES
) {
    suspend fun fetchLatest(asOf: LocalDate = LocalDate.now()): TaxParameters = withContext(Dispatchers.IO) {
        collectTaxParameters(
            pages = sources.mapValues { (_, url) -> htmlToTextForTaxParameters(fetchUrl(url)) },
            asOf = asOf
        )
    }

    private fun fetchUrl(url: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = TAX_FETCH_TIMEOUT_MS
            readTimeout = TAX_FETCH_TIMEOUT_MS
            requestMethod = "GET"
            setRequestProperty("User-Agent", TAX_USER_AGENT)
        }

        return try {
            val status = connection.responseCode
            if (status !in 200..299) {
                error("Server je vratio HTTP $status za $url")
            }
            val charset = connection.contentType
                ?.substringAfter("charset=", missingDelimiterValue = "")
                ?.takeIf { it.isNotBlank() }
                ?: "UTF-8"
            connection.inputStream.bufferedReader(charset(charset)).use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }
}

fun collectTaxParameters(pages: Map<String, String>, asOf: LocalDate = LocalDate.now()): TaxParameters {
    val contributionRates = extractContributionRates(pages.requiredPage("contributions"))
    return TaxParameters(
        salaryTaxRatePercent = extractSalaryTaxRatePercent(pages.requiredPage("income_tax_law")),
        salaryTaxAllowanceRsd = extractIncomeTaxAllowanceRsd(
            text = pages["allowance"] ?: pages.requiredPage("income_tax_law"),
            asOf = asOf
        ),
        pioEmployeePercent = contributionRates.pioEmployee,
        healthEmployeePercent = contributionRates.healthEmployee,
        unemploymentEmployeePercent = contributionRates.unemploymentEmployee
    )
}

fun htmlToTextForTaxParameters(html: String): String {
    return html
        .replace(Regex("(?is)<(script|style|noscript)\\b.*?</\\1>"), " ")
        .replace(Regex("(?is)<[^>]+>"), " ")
        .replace(Regex("&nbsp;|&#160;", RegexOption.IGNORE_CASE), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
}

fun extractSalaryTaxRatePercent(text: String): Double {
    return uniqueTaxPercentMatch(
        patterns = listOf(
            Regex("zarad[ae]\\s+iz\\s+cl\\.?\\s*13\\.?\\s+do\\s+14b.*?oporezuj\\w*\\s+se\\s+po\\s+stopi\\s+od\\s+([\\d.,]+)\\s*%"),
            Regex("porez\\s+na\\s+zarad[eu].{0,120}?stopi\\s+od\\s+([\\d.,]+)\\s*%")
        ),
        text = text,
        label = "stopa poreza na zarade"
    )
}

private fun extractIncomeTaxAllowanceRsd(text: String, asOf: LocalDate): Double {
    val normalized = normalizeTaxText(text)
    val candidates = extractDatedIncomeTaxAllowanceCandidates(normalized)
    if (candidates.isEmpty()) {
        error("Poreska olakšica nije pouzdano utvrđena za $asOf: nije pronađen datirani neoporezivi iznos zarade")
    }

    val candidatesByStart = candidates
        .groupBy { it.validFrom }
        .mapValues { (_, sameStartCandidates) -> sameStartCandidates.map { it.value }.distinct() }

    val conflictingStart = candidatesByStart.entries.firstOrNull { (_, values) -> values.size > 1 }
    if (conflictingStart != null) {
        error(
            "Poreska olakšica nije pouzdano utvrđena za $asOf: " +
                "više vrednosti počinje ${conflictingStart.key}"
        )
    }

    val datedValues = candidatesByStart
        .map { (validFrom, values) -> DatedTaxAmount(value = values.single(), validFrom = validFrom) }
        .sortedBy { it.validFrom }

    return datedValues
        .lastOrNull { !it.validFrom.isAfter(asOf) }
        ?.value
        ?: error(
            "Poreska olakšica nije pouzdano utvrđena za $asOf: " +
                "nema datiranog kandidata koji važi na taj datum"
        )
}

private fun extractContributionRates(text: String): ContributionRates {
    val normalized = normalizeTaxText(text)
    val pioRates = uniqueTaxPercentGroupsMatch(
        pattern = Regex(
            "penzijsko\\s+i\\s+invalidsko\\s+osiguranje\\s*-\\s*([\\d.,]+)\\s*%\\s+za\\s+lica.*?a\\s+([\\d.,]+)\\s*%\\s+za\\s+poslodavca"
        ),
        normalizedText = normalized,
        label = "podela PIO doprinosa"
    )

    return ContributionRates(
        pioEmployee = pioRates[0],
        healthEmployee = uniqueTaxPercentMatch(
            patterns = listOf(
                Regex("kada\\s+se\\s+doprinosi\\s+placaju\\s+istovremeno.*?zdravstveno\\s+osiguranje\\s*-\\s*([\\d.,]+)\\s*%")
            ),
            normalizedText = normalized,
            label = "zdravstvo iz osnovice"
        ),
        unemploymentEmployee = uniqueTaxPercentMatch(
            patterns = listOf(
                Regex("osiguranje\\s+za\\s+slucaj\\s+nezaposlenosti\\s+placa\\s+se\\s+iz\\s+osnovice\\s+po\\s+stopi\\s+od\\s+([\\d.,]+)\\s*%")
            ),
            normalizedText = normalized,
            label = "nezaposlenost iz osnovice"
        )
    )
}

private fun Map<String, String>.requiredPage(key: String): String {
    return this[key] ?: error("Nije pronađeno polje: stranica $key")
}

private fun uniqueTaxPercentMatch(
    patterns: List<Regex>,
    text: String? = null,
    normalizedText: String = normalizeTaxText(text.orEmpty()),
    label: String
): Double {
    val values = patterns.flatMap { pattern ->
        pattern.findAll(normalizedText).map { match -> parseTaxPercent(match.groupValues[1]) }
    }
    return requireSingleTaxPercent(values, label)
}

private fun uniqueTaxPercentGroupsMatch(pattern: Regex, normalizedText: String, label: String): List<Double> {
    val groups = pattern.findAll(normalizedText)
        .map { match -> match.groupValues.drop(1).map(::parseTaxPercent) }
        .toList()
    if (groups.isEmpty()) error("Nije pronađeno polje: $label")

    val distinctGroups = groups.distinct()
    if (distinctGroups.size > 1) {
        error("$label nije pouzdano utvrđeno: pronađeno je više različitih vrednosti")
    }
    return distinctGroups.single()
}

private fun requireSingleTaxPercent(values: List<Double>, label: String): Double {
    if (values.isEmpty()) error("Nije pronađeno polje: $label")

    val distinctValues = values.distinct()
    if (distinctValues.size > 1) {
        error("$label nije pouzdano utvrđeno: pronađeno je više različitih vrednosti")
    }
    return distinctValues.single()
}

private fun normalizeTaxText(text: String): String {
    val decomposed = Normalizer.normalize(text, Normalizer.Form.NFKD)
    return decomposed
        .filterNot { Character.getType(it) == Character.NON_SPACING_MARK.toInt() }
        .replace(Regex("\\s+"), " ")
        .lowercase(Locale.ROOT)
}

private fun extractDatedIncomeTaxAllowanceCandidates(normalizedText: String): List<DatedTaxAmount> {
    return DATED_INCOME_TAX_ALLOWANCE_PATTERNS.flatMap { candidatePattern ->
        candidatePattern.regex.findAll(normalizedText).map { match ->
            DatedTaxAmount(
                value = parseSerbianInt(match.groupValues[candidatePattern.amountGroup]).toDouble(),
                validFrom = parseSerbianDate(
                    day = match.groupValues[candidatePattern.dayGroup],
                    month = match.groupValues[candidatePattern.monthGroup],
                    year = match.groupValues[candidatePattern.yearGroup]
                )
            )
        }
    }
}

private fun parseSerbianDate(day: String, month: String, year: String): LocalDate {
    val monthNumber = SERBIAN_MONTHS[month.lowercase(Locale.ROOT)]
        ?: error("Nije pronađeno polje: mesec u datumu")
    return LocalDate.of(year.toInt(), monthNumber, day.toInt())
}

private fun parseSerbianInt(value: String): Int {
    val digits = value.filter { it.isDigit() }
    if (digits.isEmpty()) error("Nije pronađeno polje: broj u vrednosti")
    return digits.toInt()
}

private fun parseTaxPercent(value: String): Double {
    val trimmed = value.trim()
    val hasComma = ',' in trimmed
    val hasDot = '.' in trimmed
    val normalized = when {
        // Evropski format sa hiljadama i decimalama: "1.234,56" -> "1234.56"
        hasComma && hasDot -> trimmed.replace(".", "").replace(",", ".")
        // Evropski decimal: "12,5" -> "12.5"
        hasComma -> trimmed.replace(",", ".")
        // Bez zareza: "12" ili "12.5" — tačka je decimalna
        else -> trimmed
    }
    return normalized.toDouble()
}

private data class ContributionRates(
    val pioEmployee: Double,
    val healthEmployee: Double,
    val unemploymentEmployee: Double
)

private data class DatedTaxAmount(
    val value: Double,
    val validFrom: LocalDate
)

private data class DatedAmountPattern(
    val regex: Regex,
    val dayGroup: Int,
    val monthGroup: Int,
    val yearGroup: Int,
    val amountGroup: Int
)

private val DATED_INCOME_TAX_ALLOWANCE_PATTERNS = listOf(
    DatedAmountPattern(
        regex = Regex(
            "(?:za\\s+isplate\\s+)?od\\s+(\\d{1,2})\\.\\s*([a-z]+)\\s+(\\d{4})\\.?\\s*" +
                "(?:godine)?\\s*.{0,180}?neoporeziv\\w*\\s+iznos\\w*\\s+zarad[ae].{0,100}?" +
                "\\s+na\\s+([\\d.\\s]+)\\s+dinara\\s+mesecno"
        ),
        dayGroup = 1,
        monthGroup = 2,
        yearGroup = 3,
        amountGroup = 4
    ),
    DatedAmountPattern(
        regex = Regex(
            "neoporeziv\\w*\\s+iznos\\w*\\s+zarad[ae].{0,120}?od\\s+(\\d{1,2})\\.\\s*" +
                "([a-z]+)\\s+(\\d{4})\\.?\\s*(?:godine)?\\s*.{0,100}?" +
                "\\s+na\\s+([\\d.\\s]+)\\s+dinara\\s+mesecno"
        ),
        dayGroup = 1,
        monthGroup = 2,
        yearGroup = 3,
        amountGroup = 4
    ),
    DatedAmountPattern(
        regex = Regex(
            "(?:za\\s+isplate\\s+)?od\\s+(\\d{1,2})\\.\\s*([a-z]+)\\s+(\\d{4})\\.?\\s*" +
                "(?:godine)?\\s*.{0,180}?zarad[ae].{0,100}?umanjen\\w*\\s+za\\s+iznos\\s+od\\s+" +
                "([\\d.\\s]+)\\s+dinara\\s+mesecno"
        ),
        dayGroup = 1,
        monthGroup = 2,
        yearGroup = 3,
        amountGroup = 4
    )
)

private val SERBIAN_MONTHS = mapOf(
    "januar" to 1,
    "januara" to 1,
    "februar" to 2,
    "februara" to 2,
    "mart" to 3,
    "marta" to 3,
    "april" to 4,
    "aprila" to 4,
    "maj" to 5,
    "maja" to 5,
    "jun" to 6,
    "juna" to 6,
    "jul" to 7,
    "jula" to 7,
    "avgust" to 8,
    "avgusta" to 8,
    "septembar" to 9,
    "septembra" to 9,
    "oktobar" to 10,
    "oktobra" to 10,
    "novembar" to 11,
    "novembra" to 11,
    "decembar" to 12,
    "decembra" to 12
)
