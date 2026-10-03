package com.rad.shifttracker

internal const val KNOWN_PAY_BASE_LIST_EMPTY_TEXT = "Još nema unetih osnovica sa lista."

internal const val KNOWN_PAY_BASE_INPUT_ERROR_TEXT =
    "Unesi ispravnu godinu, mesec (1-12) i osnovicu sa liste."

internal fun knownPayBaseAnchorSummaryText(anchor: KnownPayBaseAnchor): String {
    return "${monthYearText(anchor.month)}: ${formatPayBaseRateText(anchor.grossHourlyBase)}"
}

internal fun parseKnownPayBaseAnchorInput(
    yearInput: String,
    monthInput: String,
    baseInput: String
): KnownPayBaseAnchor? {
    val year = yearInput.toIntOrNull()?.takeIf { it in 1900..2200 } ?: return null
    val month = monthInput.toIntOrNull()?.takeIf { it in 1..12 } ?: return null
    val base = baseInput.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0.0 } ?: return null
    return KnownPayBaseAnchor(MonthKey(year, month), base)
}

/**
 * Simulirani meseci su obične smene po 8h: bez minulog rada, prekovremenog, stimulacija i
 * godišnje nagrade. Na listićima 05–08/2026 takav prosek je davao ≈810 din/h umesto ≈950.
 */
internal fun simulatedPayBaseMonthsWarningText(calculation: RollingPayBaseCalculation?): String? {
    val months = calculation?.months ?: return null
    val simulatedCount = months.count { it.source == PayBaseMonthSource.SIMULATED }
    if (simulatedCount <= 0) return null
    return "Upozorenje: $simulatedCount od ${months.size} meseci u proseku je simulirano (obične smene bez " +
        "minulog rada, prekovremenog i stimulacija), pa je ova osnovica obično preniska. " +
        "Skeniraj platnu listu u „Listi“."
}
