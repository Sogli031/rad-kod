package com.rad.shifttracker.izvoz

import com.rad.shifttracker.data.storage.JsonWriter
import java.time.Instant
import com.rad.shifttracker.repo.ShiftRepository
import com.rad.shifttracker.smene.MonthKey
import com.rad.shifttracker.smene.monthYearText

private const val ALL_MONTHS_DEBUG_EXPORT_FORMAT_VERSION = 1
internal const val ALL_MONTHS_CALCULATION_DEBUG_EXPORT_FILE_NAME = "rad_debug_obracuni_svi_meseci.json"

fun ShiftRepository.exportAllSavedMonthsCalculationDebugJson(
    appName: String = "Rad",
    appVersion: String? = null,
    exportTimestamp: String = Instant.now().toString()
): String {
    val savedMonths = getAllMonthsSorted()
    return JsonWriter.write(
        linkedMapOf(
            "debugExportInfo" to debugExportInfoMap(
                appName = appName,
                appVersion = appVersion,
                exportTimestamp = exportTimestamp,
                savedMonths = savedMonths
            ),
            "savedMonthsOverview" to savedMonths.map { month -> savedMonthOverviewMap(month) },
            "monthlyCalculationDebug" to savedMonths.map { month ->
                buildMonthlyWorkAndPayExportMap(
                    repo = this,
                    selectedYear = month.year,
                    selectedMonth = month.month,
                    appName = appName,
                    appVersion = appVersion,
                    exportTimestamp = exportTimestamp
                )
            }
        )
    )
}

private fun ShiftRepository.debugExportInfoMap(
    appName: String,
    appVersion: String?,
    exportTimestamp: String,
    savedMonths: List<MonthKey>
): Map<String, Any?> {
    return linkedMapOf(
        "formatVersion" to ALL_MONTHS_DEBUG_EXPORT_FORMAT_VERSION,
        "appName" to appName,
        "appVersion" to appVersion,
        "exportTimestamp" to exportTimestamp,
        "purpose" to "Debug obračuna svih sačuvanih meseci za analizu u ChatGPT-u.",
        "monthSelectionRule" to "Svi meseci koji imaju najmanje jedan sačuvan dnevni unos.",
        "savedMonthCount" to savedMonths.size,
        "firstSavedMonth" to savedMonths.firstOrNull()?.let { monthKeyMap(it) },
        "lastSavedMonth" to savedMonths.lastOrNull()?.let { monthKeyMap(it) },
        "containsRawDailyInputs" to true,
        "containsDailyCalculationBreakdown" to true,
        "containsMonthlyCalculationBreakdown" to true,
        "containsSettingsAndPayBaseResolution" to true,
        "containsAuditFormulasAndChecks" to true,
        "reviewHint" to "Svaki element monthlyCalculationDebug koristi isti obračunski i export tok kao " +
            "pojedinačni mesečni export. Uporediti rawRecord, dailyCalculationBreakdown, monthlySummary, " +
            "auditFormulas, auditChecks, settingsSnapshot i rawCalculationDebug."
    )
}

private fun ShiftRepository.savedMonthOverviewMap(month: MonthKey): Map<String, Any?> {
    val records = getMonthRecords(month.year, month.month)
    return linkedMapOf(
        "year" to month.year,
        "month" to month.month,
        "monthLabel" to monthYearText(month),
        "savedDayCount" to records.size,
        "savedDays" to records.map { it.day }.sorted()
    )
}

private fun monthKeyMap(month: MonthKey): Map<String, Any?> = linkedMapOf(
    "year" to month.year,
    "month" to month.month,
    "monthLabel" to monthYearText(month)
)
