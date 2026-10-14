package com.rad.shifttracker.uvoz

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.text.Normalizer
import java.time.LocalDate
import java.util.Locale
import com.rad.shifttracker.obracun.DatedTaxRelief

/*
 * Porez na zarade, doprinosi radnika i neoporezivi iznos zarade sa paragraf.rs (vraćeno iz v4.x).
 * Čitanje je namerno strogo: ako tekst stranice nije kakav se očekuje ili ima dve različite
 * vrednosti, baca grešku i ne vraća ništa — bolje bez ažuriranja nego pogrešan broj.
 */

private const val TAX_USER_AGENT = "Mozilla/5.0 rad-shifttracker/1.0"
private const val TAX_FETCH_TIMEOUT_MS = 30_000

private val TAX_PARAMETER_SOURCES = mapOf(
    "income_tax_law" to "https://www.paragraf.rs/propisi/zakon-o-porezu-na-dohodak-gradjana.html",
    "allowance" to
        "https://www.paragraf.rs/statistika/poreski_tretman_i_uskladjeni_neoporezivi_iznosi_za_odredjena_licna_primanja_zaposlenih_i_ostalih_fizickih_lica.html",
    "contributions" to "https://www.paragraf.rs/propisi/zakon-o-doprinosima-za-obavezno-socijalno-osiguranje.html"
)

/** Pročitano sa interneta: stope (trenutni tekst zakona) i sve datirane olakšice. */
internal data class ScrapedTaxParameters(
    val taxPercent: Double,
    val pioEmployeePercent: Double,
    val healthEmployeePercent: Double,
    val unemploymentEmployeePercent: Double,
    val reliefs: List<DatedTaxRelief>
) {
    val contributionsPercent: Double
        get() = pioEmployeePercent + healthEmployeePercent + unemploymentEmployeePercent
}

/** Skida stranice i čita parametre (van glavne niti). Greška mreže → [IOException], tekst → [IllegalStateException]. */
internal fun fetchTaxParameters(sources: Map<String, String> = TAX_PARAMETER_SOURCES): ScrapedTaxParameters =
    collectTaxParameters(sources.mapValues { (_, url) -> htmlToTextForTaxParameters(fetchTaxPage(url)) })

private fun fetchTaxPage(url: String): String {
    val connection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = TAX_FETCH_TIMEOUT_MS
        readTimeout = TAX_FETCH_TIMEOUT_MS
        requestMethod = "GET"
        setRequestProperty("User-Agent", TAX_USER_AGENT)
    }
    return try {
        val status = connection.responseCode
        if (status !in 200..299) throw IOException("Server je vratio HTTP $status za $url")
        val charset = connection.contentType
            ?.substringAfter("charset=", missingDelimiterValue = "")
            ?.takeIf { it.isNotBlank() }
            ?: "UTF-8"
        connection.inputStream.bufferedReader(charset(charset)).use { it.readText() }
    } finally {
        connection.disconnect()
    }
}

internal fun collectTaxParameters(pages: Map<String, String>): ScrapedTaxParameters {
    val contributions = normalizeTaxText(pages.requiredPage("contributions"))
    val pio = uniqueGroupsMatch(
        pattern = Regex(
            "penzijsko\\s+i\\s+invalidsko\\s+osiguranje\\s*-\\s*([\\d.,]+)\\s*%\\s+za\\s+lica.*?a\\s+([\\d.,]+)\\s*%\\s+za\\s+poslodavca"
        ),
        normalizedText = contributions,
        label = "podela PIO doprinosa"
    )
    return ScrapedTaxParameters(
        taxPercent = extractSalaryTaxRatePercent(pages.requiredPage("income_tax_law")),
        pioEmployeePercent = pio[0],
        healthEmployeePercent = uniquePercentMatch(
            patterns = listOf(
                Regex("kada\\s+se\\s+doprinosi\\s+placaju\\s+istovremeno.*?zdravstveno\\s+osiguranje\\s*-\\s*([\\d.,]+)\\s*%")
            ),
            normalizedText = contributions,
            label = "zdravstvo iz osnovice"
        ),
        unemploymentEmployeePercent = uniquePercentMatch(
            patterns = listOf(
                Regex("osiguranje\\s+za\\s+slucaj\\s+nezaposlenosti\\s+placa\\s+se\\s+iz\\s+osnovice\\s+po\\s+stopi\\s+od\\s+([\\d.,]+)\\s*%")
            ),
            normalizedText = contributions,
            label = "nezaposlenost iz osnovice"
        ),
        reliefs = extractDatedTaxReliefs(pages["allowance"] ?: pages.requiredPage("income_tax_law"))
    )
}

internal fun htmlToTextForTaxParameters(html: String): String = html
    .replace(Regex("(?is)<(script|style|noscript)\\b.*?</\\1>"), " ")
    .replace(Regex("(?is)<[^>]+>"), " ")
    .replace(Regex("&nbsp;|&#160;", RegexOption.IGNORE_CASE), " ")
    .replace(Regex("\\s+"), " ")
    .trim()

internal fun extractSalaryTaxRatePercent(text: String): Double = uniquePercentMatch(
    patterns = listOf(
        Regex("zarad[ae]\\s+iz\\s+cl\\.?\\s*13\\.?\\s+do\\s+14b.*?oporezuj\\w*\\s+se\\s+po\\s+stopi\\s+od\\s+([\\d.,]+)\\s*%"),
        Regex("porez\\s+na\\s+zarad[eu].{0,120}?stopi\\s+od\\s+([\\d.,]+)\\s*%")
    ),
    normalizedText = normalizeTaxText(text),
    label = "stopa poreza na zarade"
)

/** Sve datirane olakšice („za isplate od 1. januara 2026. … na 34.221 dinara mesečno“), po datumu. */
internal fun extractDatedTaxReliefs(text: String): List<DatedTaxRelief> {
    val normalized = normalizeTaxText(text)
    val candidates = DATED_RELIEF_PATTERNS.flatMap { pattern ->
        pattern.findAll(normalized).map { match ->
            DatedTaxRelief(
                validFrom = parseSerbianDate(match.groupValues[1], match.groupValues[2], match.groupValues[3]),
                amount = parseSerbianInt(match.groupValues[4]).toDouble()
            )
        }
    }
    if (candidates.isEmpty()) error("Poreska olakšica nije pronađena: nema datiranog neoporezivog iznosa zarade")
    val byDate = candidates.groupBy { it.validFrom }
    byDate.entries.firstOrNull { (_, same) -> same.map { it.amount }.distinct().size > 1 }?.let { conflict ->
        error("Poreska olakšica nije pouzdano utvrđena: više vrednosti od ${conflict.key}")
    }
    return byDate.values.map { it.first() }.sortedBy { it.validFrom }
}

private fun Map<String, String>.requiredPage(key: String): String =
    this[key] ?: error("Nije pronađena stranica $key")

private fun uniquePercentMatch(patterns: List<Regex>, normalizedText: String, label: String): Double {
    val values = patterns.flatMap { pattern ->
        pattern.findAll(normalizedText).map { match -> parseTaxPercent(match.groupValues[1]) }
    }.distinct()
    if (values.isEmpty()) error("Nije pronađeno: $label")
    if (values.size > 1) error("$label nije pouzdano utvrđeno: više različitih vrednosti")
    return values.single()
}

private fun uniqueGroupsMatch(pattern: Regex, normalizedText: String, label: String): List<Double> {
    val groups = pattern.findAll(normalizedText)
        .map { match -> match.groupValues.drop(1).map(::parseTaxPercent) }
        .toList()
        .distinct()
    if (groups.isEmpty()) error("Nije pronađeno: $label")
    if (groups.size > 1) error("$label nije pouzdano utvrđeno: više različitih vrednosti")
    return groups.single()
}

private fun normalizeTaxText(text: String): String =
    Normalizer.normalize(text, Normalizer.Form.NFKD)
        .filterNot { Character.getType(it) == Character.NON_SPACING_MARK.toInt() }
        .replace(Regex("\\s+"), " ")
        .lowercase(Locale.ROOT)

private fun parseSerbianDate(day: String, month: String, year: String): LocalDate {
    val monthNumber = SERBIAN_MONTHS[month] ?: error("Nepoznat mesec u datumu: $month")
    return LocalDate.of(year.toInt(), monthNumber, day.toInt())
}

private fun parseSerbianInt(value: String): Int {
    val digits = value.filter { it.isDigit() }
    if (digits.isEmpty()) error("Nije pronađen broj u vrednosti")
    return digits.toInt()
}

/** „12,5“ i „1.234,56“ (evropski zapis) ili „12.5“. */
private fun parseTaxPercent(value: String): Double {
    val trimmed = value.trim()
    val normalized = when {
        ',' in trimmed && '.' in trimmed -> trimmed.replace(".", "").replace(",", ".")
        ',' in trimmed -> trimmed.replace(",", ".")
        else -> trimmed
    }
    return normalized.toDouble()
}

/** Grupe: dan, mesec, godina, iznos. */
private val DATED_RELIEF_PATTERNS = listOf(
    Regex(
        "(?:za\\s+isplate\\s+)?od\\s+(\\d{1,2})\\.\\s*([a-z]+)\\s+(\\d{4})\\.?\\s*(?:godine)?\\s*.{0,180}?" +
            "neoporeziv\\w*\\s+iznos\\w*\\s+zarad[ae].{0,100}?\\s+na\\s+([\\d.\\s]+)\\s+dinara\\s+mesecno"
    ),
    Regex(
        "neoporeziv\\w*\\s+iznos\\w*\\s+zarad[ae].{0,120}?od\\s+(\\d{1,2})\\.\\s*([a-z]+)\\s+(\\d{4})\\.?\\s*" +
            "(?:godine)?\\s*.{0,100}?\\s+na\\s+([\\d.\\s]+)\\s+dinara\\s+mesecno"
    ),
    Regex(
        "(?:za\\s+isplate\\s+)?od\\s+(\\d{1,2})\\.\\s*([a-z]+)\\s+(\\d{4})\\.?\\s*(?:godine)?\\s*.{0,180}?" +
            "zarad[ae].{0,100}?umanjen\\w*\\s+za\\s+iznos\\s+od\\s+([\\d.\\s]+)\\s+dinara\\s+mesecno"
    )
)

private val SERBIAN_MONTHS = mapOf(
    "januar" to 1, "januara" to 1, "februar" to 2, "februara" to 2, "mart" to 3, "marta" to 3,
    "april" to 4, "aprila" to 4, "maj" to 5, "maja" to 5, "jun" to 6, "juna" to 6,
    "jul" to 7, "jula" to 7, "avgust" to 8, "avgusta" to 8, "septembar" to 9, "septembra" to 9,
    "oktobar" to 10, "oktobra" to 10, "novembar" to 11, "novembra" to 11, "decembar" to 12, "decembra" to 12
)
