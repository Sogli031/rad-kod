package com.rad.shifttracker

import android.app.Notification
import java.text.Normalizer
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.abs

private const val MAX_NOTIFICATION_TEXT_LENGTH = 4000

private val radBankPackageCandidates = setOf(
    "com.nlb.mklik",
    "rs.nlbklik.mobile",
    "rs.nlb.mbank",
    "com.nlb.mbank",
    "hr.asseco.android.jimba.kombank"
)

private val radBankDateTimeFormatters = listOf(
    DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss"),
    DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")
)

private val radBankDateFormatters = listOf(
    DateTimeFormatter.ofPattern("dd.MM.yyyy")
)

private val radBankAmountRegex =
    Regex("(?i)(?:Iznos|Ukupno|Suma|Zaduzenje|Potroseno|Trosak|Odliv|Priliv|Uplata)(?:\\s+\\w+){0,2}\\s*:?\\s*([\\d.,\\s]+)")
private val radBankAmountFallbackRegex = Regex("(?i)([\\d.,\\s]+)\\s*(RSD|EUR|USD|GBP|CHF|\\u20AC|\\$|\\u00A3)")
private val radBankCurrencyRegex = Regex("(?i)\\b(RSD|EUR|USD|GBP|CHF|\\u20AC|\\$|\\u00A3)\\b")
private val radBankDateRegex =
    Regex("\\b\\d{1,2}[./-]\\d{1,2}[./-]\\d{4}(?:\\s+\\d{1,2}:\\d{2}(?::\\d{2})?)?\\b")

private val radBankPlaceLabels = listOf(
    "Mesto",
    "Trgovac",
    "Prodavac",
    "Naziv",
    "Opis",
    "Svrha",
    "Primaoc",
    "Merchant",
    "Lokacija"
)

private val radBankPlaceIgnoreRegex =
    Regex("(?i)obavest|informativ|raspoloz|saldo|stanje|kartica|racun|iban|ref|valuta|kurs|rezerv|status|rsd|eur")

enum class RadBankTransactionType {
    INCOME,
    EXPENSE
}

data class ParsedRadBankNotification(
    val amount: Double,
    val dateTime: LocalDateTime,
    val place: String,
    val type: RadBankTransactionType,
    val originalCurrency: String,
    val originalAmount: Double
)

data class RadAdvanceImportCandidate(
    val month: MonthKey,
    val amount: Double,
    val dateTime: LocalDateTime,
    val place: String
)

private data class AmountWithCurrency(val amount: Double, val currency: String)

fun collectRadNotificationText(notification: Notification): String? {
    val extras = notification.extras ?: return null
    val textLines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
        ?.mapNotNull { it?.toString()?.trim()?.takeIf { value -> value.isNotEmpty() } }
        .orEmpty()
    val parts = listOf(
        extras.getCharSequence(Notification.EXTRA_TITLE),
        extras.getCharSequence(Notification.EXTRA_TITLE_BIG),
        extras.getCharSequence(Notification.EXTRA_SUB_TEXT),
        extras.getCharSequence(Notification.EXTRA_TEXT),
        extras.getCharSequence(Notification.EXTRA_BIG_TEXT),
        extras.getCharSequence(Notification.EXTRA_INFO_TEXT),
        extras.getCharSequence(Notification.EXTRA_SUMMARY_TEXT)
    ).mapNotNull { it?.toString()?.trim()?.takeIf { value -> value.isNotEmpty() } }
    val allParts = parts + textLines
    if (allParts.isEmpty()) return null
    return allParts.joinToString("\n")
}

fun isRadBankNotificationPackage(packageName: String): Boolean =
    packageName.lowercase() in radBankPackageCandidates || packageName.contains("nlb", ignoreCase = true)

fun parseRadBankNotificationText(text: String): ParsedRadBankNotification? {
    if (text.length > MAX_NOTIFICATION_TEXT_LENGTH) return null
    val rawText = text.replace('\u00A0', ' ')
    val normalized = normalizeRadBankText(rawText)
    val rawLines = splitRadBankLines(rawText)
    val normalizedLines = splitRadBankLines(normalized)
    val amountWithCurrency = extractRadBankAmountWithCurrency(normalizedLines) ?: return null
    val dateTime = extractRadBankDateTime(normalized) ?: return null
    val place = extractRadBankPlace(rawLines, normalizedLines) ?: return null
    val cleanPlace = place.replace(Regex("\\s+"), " ").trim()
    if (cleanPlace.isEmpty()) return null
    val originalAmount = abs(amountWithCurrency.amount)
    if (originalAmount <= 0.0) return null
    return ParsedRadBankNotification(
        amount = originalAmount,
        dateTime = dateTime,
        place = cleanPlace,
        type = detectRadBankTransactionType(normalizedLines),
        originalCurrency = amountWithCurrency.currency,
        originalAmount = originalAmount
    )
}

fun parseRadAdvancePaymentNotification(text: String): RadAdvanceImportCandidate? {
    val parsed = parseRadBankNotificationText(text) ?: return null
    if (parsed.type != RadBankTransactionType.INCOME) return null
    if (parsed.originalCurrency != "RSD") return null
    if (!isValjaonicaAdvancePlace(parsed.place)) return null
    if (!isAkontacijaNotificationDate(parsed.dateTime.toLocalDate())) return null
    return RadAdvanceImportCandidate(
        month = MonthKey(parsed.dateTime.year, parsed.dateTime.monthValue),
        amount = parsed.amount,
        dateTime = parsed.dateTime,
        place = parsed.place
    )
}

fun isAkontacijaNotificationDate(date: LocalDate): Boolean {
    return date.dayOfMonth in 2..10
}

private fun normalizeRadBankText(text: String): String {
    val cleaned = text.replace('\u00A0', ' ')
    val normalized = Normalizer.normalize(cleaned, Normalizer.Form.NFD)
    return normalized.replace(Regex("\\p{Mn}+"), "")
}

private fun splitRadBankLines(text: String): List<String> =
    text.lines().map { it.trim() }.filter { it.isNotEmpty() }

private fun lineHasRadBankCurrency(line: String): Boolean =
    radBankCurrencyRegex.containsMatchIn(line)

private fun extractRadBankCurrencyFromLine(line: String): String {
    val match = radBankCurrencyRegex.find(line)?.value?.uppercase() ?: return "RSD"
    return when (match) {
        "\u20AC" -> "EUR"
        "$" -> "USD"
        "\u00A3" -> "GBP"
        else -> match
    }
}

private fun extractRadBankAmountWithCurrency(normalizedLines: List<String>): AmountWithCurrency? {
    for ((index, line) in normalizedLines.withIndex()) {
        val match = radBankAmountRegex.find(line)?.groupValues?.getOrNull(1)
        val parsed = match?.let { parseRadBankAmount(it) }
        if (parsed != null && (lineHasRadBankCurrency(line) || match.contains(',') || match.contains('.'))) {
            return AmountWithCurrency(parsed, extractRadBankCurrencyFromLine(line))
        }
        if (match != null) {
            val nextLine = normalizedLines.getOrNull(index + 1)
            val nextParsed = nextLine?.let { parseRadBankAmount(it) }
            if (nextLine != null &&
                nextParsed != null &&
                (lineHasRadBankCurrency(nextLine) || nextLine.contains(',') || nextLine.contains('.'))
            ) {
                return AmountWithCurrency(nextParsed, extractRadBankCurrencyFromLine(nextLine))
            }
        }
    }
    return extractRadBankFallbackAmount(normalizedLines)
}

private fun extractRadBankFallbackAmount(normalizedLines: List<String>): AmountWithCurrency? {
    for (line in normalizedLines) {
        if (!lineHasRadBankCurrency(line)) continue
        val fallbackMatch = radBankAmountFallbackRegex.find(line)
        val amountStr = fallbackMatch?.groupValues?.getOrNull(1)
        val currencyStr = fallbackMatch?.groupValues?.getOrNull(2)
        val parsed = amountStr?.let { parseRadBankAmount(it) }
        if (parsed != null) {
            return AmountWithCurrency(parsed, normalizeRadBankCurrency(currencyStr))
        }
    }
    return null
}

private fun normalizeRadBankCurrency(raw: String?): String {
    return when (raw?.uppercase()) {
        "\u20AC" -> "EUR"
        "$" -> "USD"
        "\u00A3" -> "GBP"
        null -> "RSD"
        else -> raw.uppercase()
    }
}

private fun extractRadBankDateTime(normalizedText: String): LocalDateTime? {
    val match = radBankDateRegex.find(normalizedText)?.value ?: return null
    return parseRadBankDateTime(match)
}

private fun parseRadBankAmount(raw: String): Double? {
    val cleaned = raw.replace('\u00A0', ' ')
        .replace(Regex("[^0-9,\\-. ]"), "")
        .trim()
    val noSpaces = cleaned.replace(" ", "")
    if (noSpaces.isEmpty()) return null
    val hasComma = noSpaces.contains(',')
    val hasDot = noSpaces.contains('.')
    val normalized = when {
        hasComma && hasDot -> noSpaces.replace(".", "").replace(",", ".")
        hasComma -> noSpaces.replace(",", ".")
        else -> noSpaces
    }
    return normalized.toDoubleOrNull()
}

private fun parseRadBankDateTime(raw: String): LocalDateTime? {
    val cleaned = raw.replace('\u00A0', ' ').trim()
        .replace('/', '.')
        .replace('-', '.')
    for (formatter in radBankDateTimeFormatters) {
        val parsed = runCatching { LocalDateTime.parse(cleaned, formatter) }.getOrNull()
        if (parsed != null) return parsed
    }
    for (formatter in radBankDateFormatters) {
        val parsed = runCatching { LocalDate.parse(cleaned, formatter) }.getOrNull()
        if (parsed != null) return parsed.atStartOfDay()
    }
    return null
}

private fun detectRadBankTransactionType(normalizedLines: List<String>): RadBankTransactionType {
    if (normalizedLines.any { it.startsWith("Priliv", ignoreCase = true) || it.startsWith("Uplata", ignoreCase = true) }) {
        return RadBankTransactionType.INCOME
    }
    return RadBankTransactionType.EXPENSE
}

private fun extractRadBankPlace(rawLines: List<String>, normalizedLines: List<String>): String? {
    if (rawLines.isEmpty()) return null
    val normalized = if (rawLines.size == normalizedLines.size) {
        normalizedLines
    } else {
        rawLines.map { normalizeRadBankText(it) }
    }
    val index = normalized.indexOfFirst { line ->
        radBankPlaceLabels.any { label -> line.startsWith(label, ignoreCase = true) }
    }
    if (index != -1) {
        return extractRadBankPlaceAfterLabel(rawLines, normalized, index)
    }
    return rawLines.zip(normalized).asReversed().firstOrNull { (_, normalizedLine) ->
        normalizedLine.any { it.isLetter() } &&
            !normalizedLine.contains(":") &&
            !radBankPlaceIgnoreRegex.containsMatchIn(normalizedLine)
    }?.first?.trim()?.takeIf { it.isNotEmpty() }
}

private fun extractRadBankPlaceAfterLabel(
    rawLines: List<String>,
    normalizedLines: List<String>,
    index: Int
): String? {
    val label = radBankPlaceLabels.firstOrNull { normalizedLines[index].startsWith(it, ignoreCase = true) }
        ?: return null
    val parts = mutableListOf<String>()
    val rawLine = rawLines[index]
    var first = rawLine.substringAfter(":", "").trim()
    if (first.isEmpty()) first = rawLine.substringAfter("-", "").trim()
    if (first.isEmpty()) first = rawLine.drop(label.length).trim()
    if (first.isNotEmpty()) parts.add(first)
    var i = index + 1
    while (i < rawLines.size) {
        val nextRaw = rawLines[i].trim()
        val nextNormalized = normalizedLines[i]
        if (nextNormalized.contains(":")) break
        if (radBankPlaceIgnoreRegex.containsMatchIn(nextNormalized)) break
        if (radBankPlaceLabels.any { nextNormalized.startsWith(it, ignoreCase = true) }) break
        if (nextRaw.isNotBlank()) parts.add(nextRaw)
        i += 1
    }
    return parts.joinToString(" ").replace(Regex("\\s+"), " ").trim().ifEmpty { null }
}

private fun isValjaonicaAdvancePlace(place: String): Boolean =
    normalizeRadBankText(place).lowercase().contains("valjaonica bakra sevojno")
