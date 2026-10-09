package com.rad.shifttracker.ui.dialozi

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import java.time.DayOfWeek
import java.time.LocalDate
import java.util.Locale
import kotlin.math.abs
import com.rad.shifttracker.osnova.CONFIG
import com.rad.shifttracker.osnova.formatCurrency
import com.rad.shifttracker.osnova.formatHours
import com.rad.shifttracker.osnova.formatMoney2
import com.rad.shifttracker.osnova.MONTH_NAMES
import com.rad.shifttracker.godisnji.VacationRangePreview
import com.rad.shifttracker.obracun.HourlyValueChange
import com.rad.shifttracker.obracun.PayBaseMonthSource
import com.rad.shifttracker.obracun.PayBaseSource
import com.rad.shifttracker.obracun.ResolvedPayBase
import com.rad.shifttracker.obracun.ResolvedPayBases
import com.rad.shifttracker.obracun.RollingPayBaseCalculation
import com.rad.shifttracker.repo.AbsenceRangeNetImpact
import com.rad.shifttracker.smene.ReferenceWorkWeek
import com.rad.shifttracker.ui.extendedColors
import com.rad.shifttracker.osnova.formatPayBaseRateText

internal fun formatAutoFillReferenceDate(date: LocalDate): String {
    val weekday = when (date.dayOfWeek) {
        DayOfWeek.MONDAY -> "Ponedeljak"
        DayOfWeek.TUESDAY -> "Utorak"
        DayOfWeek.WEDNESDAY -> "Sreda"
        DayOfWeek.THURSDAY -> "Četvrtak"
        DayOfWeek.FRIDAY -> "Petak"
        DayOfWeek.SATURDAY -> "Subota"
        DayOfWeek.SUNDAY -> "Nedelja"
    }
    return "$weekday ${date.dayOfMonth}.${date.monthValue}.${date.year}."
}

internal fun formatReferenceWorkWeek(week: ReferenceWorkWeek): String {
    return String.format(
        Locale.getDefault(),
        "%02d.%02d.%04d - %02d.%02d.%04d",
        week.start.dayOfMonth,
        week.start.monthValue,
        week.start.year,
        week.end.dayOfMonth,
        week.end.monthValue,
        week.end.year
    )
}

internal fun filterDecimalInput(value: String): String {
    val sb = StringBuilder()
    var dotSeen = false
    for (ch in value) {
        when {
            ch.isDigit() -> sb.append(ch)
            (ch == '.' || ch == ',') && !dotSeen -> {
                sb.append('.')
                dotSeen = true
            }
        }
    }
    return sb.toString()
}

internal fun formatRateInput(value: Double): String {
    val formatted = String.format(Locale.US, "%.2f", value)
    return formatted.trimEnd('0').trimEnd('.')
}

internal fun filterSignedDecimalInput(value: String): String {
    val unsigned = filterDecimalInput(value)
    return if (value.trimStart().startsWith("-")) "-$unsigned" else unsigned
}

@Composable
@ReadOnlyComposable
internal fun absenceNetDifferenceColor(value: Double): Color {
    return when {
        value > 0.5 -> MaterialTheme.extendedColors.success
        value < -0.5 -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.extendedColors.textSecondary
    }
}

internal fun formatAbsenceNetDifference(value: Double): String {
    val sign = when {
        value > 0.5 -> "+"
        value < -0.5 -> "-"
        else -> ""
    }
    return "$sign${formatCurrency(abs(value))} ${CONFIG.pay.currency}"
}

internal fun absenceReplacementText(impact: AbsenceRangeNetImpact): String {
    val base = "${impact.affectedDays} dana, ${formatHours(impact.absenceHours)} odsustva"
    if (impact.replacedWorkHours <= 0) return base

    val nightText = if (impact.replacedNightHours > 0) {
        ", od toga ${formatHours(impact.replacedNightHours)} noćnih"
    } else {
        ""
    }
    return "$base umesto ${formatHours(impact.replacedWorkHours)} rada$nightText."
}

internal const val SATNICA_COEFFICIENT_LABEL = "Koeficijent"

internal const val SATNICA_HOURLY_VALUE_LABEL = "Vrednost sata"

internal const val SATNICA_DIFFICULT_WORK_CONDITIONS_LABEL = "Otežani uslovi rada (%)"

internal const val SATNICA_TRANSPORT_DAILY_AMOUNT_LABEL = "Prevoz po danu"

internal const val SATNICA_TRANSPORT_DAILY_AMOUNT_HINT = "Unesi iznos prevoza po radnom danu"

/** Opcije: otežani uslovi (sa liste) i prevoz (iz obračuna prevoza), samo prikaz. */
internal const val MANUAL_PAY_SETTINGS_SECTION_LABEL = "Otežani uslovi i prevoz"
internal const val DIFFICULT_WORK_CONDITIONS_STATUS_LABEL = "Otežani uslovi rada"

internal fun satnicaGrossPreviewText(coefficient: Double, hourlyValue: Double): String {
    return "Bruto satnica: ${formatMoney2(coefficient * hourlyValue)} ${CONFIG.pay.currency}/h"
}

internal fun satnicaNetDifferencePreviewText(netDifference: Double): String {
    val sign = when {
        netDifference > 0.5 -> "+"
        netDifference < -0.5 -> "-"
        else -> ""
    }
    return "Razlika u plati: $sign${formatCurrency(abs(netDifference))} ${CONFIG.pay.currency}"
}

internal const val SATNICA_HOURLY_HISTORY_SECTION_LABEL = "Promene vrednosti sata"

internal const val SATNICA_HOURLY_HISTORY_MONTH_LABEL = "Od meseca"

internal const val SATNICA_HOURLY_HISTORY_YEAR_LABEL = "Godina"

internal const val SATNICA_HOURLY_HISTORY_VALUE_LABEL = "Nova vrednost sata"

internal fun satnicaHourlyChangeSummaryText(change: HourlyValueChange): String {
    val monthName = MONTH_NAMES
        .getOrElse(change.month) { "mesec" }
        .lowercase(Locale.getDefault())
    return "Od $monthName ${change.year}: ${formatRateInput(change.value)} ${CONFIG.pay.currency}"
}

internal fun payBasesSourceSummary(bases: ResolvedPayBases): String {
    val sameSource = bases.vacationHourlyBase.source == bases.paidHolidayHourlyBase.source &&
        bases.vacationHourlyBase.source == bases.sickHourlyBase.source
    val sameValue = kotlin.math.abs(bases.vacationHourlyBase.value - bases.paidHolidayHourlyBase.value) < 0.001 &&
        kotlin.math.abs(bases.vacationHourlyBase.value - bases.sickHourlyBase.value) < 0.001
    if (sameSource && sameValue) {
        knownPayBaseSummaryText(bases.sickHourlyBase.calculation)?.let { return it }
        rollingPayBaseSummaryText(bases.sickHourlyBase)?.let { return it }
        return "Izvor osnovice naknada: ${payBaseSourceShort(bases.sickHourlyBase)}"
    }
    return "Izvor: GO ${payBaseSourceShort(bases.vacationHourlyBase)}, " +
        "praznik ${payBaseSourceShort(bases.paidHolidayHourlyBase)}, " +
        "bolovanje ${payBaseSourceShort(bases.sickHourlyBase)}"
}

internal fun payBaseSourceShort(base: ResolvedPayBase): String {
    return when (base.source) {
        PayBaseSource.CURRENT_MONTH -> "ovaj mesec"
        PayBaseSource.LAST_KNOWN -> {
            val monthName = base.sourceMonth?.takeIf { it in 1..12 }?.let { MONTH_NAMES[it] }
            val year = base.sourceYear
            if (monthName != null && year != null) "$monthName $year" else "poslednja"
        }
        PayBaseSource.DEFAULT -> "918,81 ${CONFIG.pay.currency}/h"
        PayBaseSource.KNOWN_BASE -> "poznata osnovica"
        PayBaseSource.CALCULATED_PREVIOUS_12_MONTHS -> "12 meseci"
    }
}

internal fun knownPayBaseSourceDetailsText(calculation: RollingPayBaseCalculation?): String {
    val anchor = calculation?.months?.firstOrNull { it.source == PayBaseMonthSource.KNOWN_BASE }
        ?: return "Poznata osnovica nije dostupna. Unesi poznatu osnovicu ili prebaci režim na Realnih + simuliranih."
    val anchorRate = anchor.grossForAverage / anchor.hoursForAverage.toDouble()
    val realCount = calculation.months.count { it.source == PayBaseMonthSource.REAL }
    val carryForwardCount = calculation.months.count { it.source == PayBaseMonthSource.CARRY_FORWARD }
    return "Osnovica se računa od poznatog sidra: ${monthYearLabel(anchor.year, anchor.month)} = " +
        "${formatPayBaseRateText(anchorRate)}. " +
        "Meseci pre sidra se ne skeniraju i ne simuliraju. " +
        "Prazni meseci posle sidra koriste poslednju poznatu osnovicu, " +
        "a kompletno upisani realni meseci posle sidra ulaze u dalji obračun. " +
        "Realni meseci posle sidra: $realCount; preneti prazni meseci: $carryForwardCount."
}

internal fun rollingPayBaseSourceDetailsText(calculation: RollingPayBaseCalculation?): String {
    if (calculation == null) return "Proračun iz prethodnih 12 meseci trenutno nije dostupan."
    val realCount = calculation.months.count { it.source == PayBaseMonthSource.REAL }
    val simulatedCount = calculation.months.count { it.source == PayBaseMonthSource.SIMULATED }
    return if (simulatedCount > 0) {
        "Fallback simulacija: realni meseci: $realCount; simulirani meseci: $simulatedCount"
    } else {
        "Realnih meseci: $realCount; simulirani meseci: 0"
    }
}

internal fun calculatedPayBaseForMonthText(year: Int, month: Int, hourlyBase: Double?): String {
    return if (hourlyBase != null && hourlyBase.isFinite() && hourlyBase > 0.0) {
        "Izračunata osnovica za ${monthYearLabel(year, month)}: ${formatPayBaseRateText(hourlyBase)}"
    } else {
        "Izračunata osnovica za ${monthYearLabel(year, month)} trenutno nije dostupna."
    }
}

private fun knownPayBaseSummaryText(calculation: RollingPayBaseCalculation?): String? {
    val months = calculation?.months ?: return null
    val anchor = months.firstOrNull { it.source == PayBaseMonthSource.KNOWN_BASE } ?: return null
    val followingMonths = months.dropWhile { it != anchor }.drop(1)
    val anchorText = "${monthNameGenitive(anchor.month)} ${anchor.year}"
    if (followingMonths.isEmpty()) {
        return "Izvor osnovice: poznata osnovica iz $anchorText."
    }
    val carryForwardCount = followingMonths.count { it.source == PayBaseMonthSource.CARRY_FORWARD }
    val realFollowingMonths = followingMonths.filter { it.source == PayBaseMonthSource.REAL }
    if (carryForwardCount > 0) {
        val realText = if (realFollowingMonths.isNotEmpty()) {
            " + realni obračun za " + joinSerbianList(
                realFollowingMonths.map { month ->
                    if (month.year == anchor.year) {
                        MONTH_NAMES.getOrElse(month.month) { "Mesec" }
                    } else {
                        monthYearLabel(month.year, month.month)
                    }
                }
            )
        } else {
            ""
        }
        return "Izvor osnovice: poznata osnovica iz $anchorText$realText, " +
            "uz prenos za ${monthCountText(carryForwardCount)}."
    }
    val followingText = joinSerbianList(
        followingMonths.map { month ->
            if (month.year == anchor.year) {
                MONTH_NAMES.getOrElse(month.month) { "Mesec" }
            } else {
                monthYearLabel(month.year, month.month)
            }
        }
    )
    return "Izvor osnovice: poznata osnovica iz $anchorText + obračun za $followingText."
}

private fun rollingPayBaseSummaryText(base: ResolvedPayBase): String? {
    if (base.source != PayBaseSource.CALCULATED_PREVIOUS_12_MONTHS) return null
    val calculation = base.calculation ?: return null
    val simulatedCount = calculation.months.count { it.source == PayBaseMonthSource.SIMULATED }
    return if (simulatedCount > 0) {
        "Izvor osnovice naknada: 12 meseci, fallback simulacija za ${monthCountText(simulatedCount)}"
    } else {
        "Izvor osnovice naknada: realnih 12 meseci"
    }
}

private fun monthYearLabel(year: Int, month: Int): String {
    val monthName = MONTH_NAMES.getOrElse(month) { "Mesec" }
    return "$monthName $year"
}

private fun joinSerbianList(values: List<String>): String {
    return when (values.size) {
        0 -> ""
        1 -> values.first()
        2 -> "${values[0]} i ${values[1]}"
        else -> values.dropLast(1).joinToString(", ") + " i " + values.last()
    }
}

private fun monthCountText(count: Int): String {
    val suffix = when {
        count % 10 == 1 && count % 100 != 11 -> "mesec"
        count % 10 in 2..4 && count % 100 !in 12..14 -> "meseca"
        else -> "meseci"
    }
    return "$count $suffix"
}

private fun monthNameGenitive(month: Int): String {
    return when (month) {
        1 -> "Januara"
        2 -> "Februara"
        3 -> "Marta"
        4 -> "Aprila"
        5 -> "Maja"
        6 -> "Juna"
        7 -> "Jula"
        8 -> "Avgusta"
        9 -> "Septembra"
        10 -> "Oktobra"
        11 -> "Novembra"
        12 -> "Decembra"
        else -> "meseca"
    }
}

internal fun vacationRangePreviewText(preview: VacationRangePreview): String {
    val skipped = buildList {
        if (preview.skippedWeekendDays > 0) add("${preview.skippedWeekendDays} vikend")
        if (preview.skippedHolidayDays > 0) add("${preview.skippedHolidayDays} praznik")
        if (preview.skippedExistingSickDays > 0) add("${preview.skippedExistingSickDays} bolovanje")
    }
    return if (skipped.isEmpty()) {
        "Računa se ${preview.countedDays} dana."
    } else {
        "Računa se ${preview.countedDays} dana. Preskočeno: ${skipped.joinToString(", ")}."
    }
}
