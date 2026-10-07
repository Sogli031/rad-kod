package com.rad.shifttracker.listici

import java.time.LocalDate
import java.time.Month
import com.rad.shifttracker.smene.MonthKey
import com.rad.shifttracker.osnova.formatVacationInput
import com.rad.shifttracker.smene.monthYearText

/**
 * Godišnji → „Proveri po listićima“ (samo informativno, ništa se ne upisuje): iz Gmail-a se čitaju
 * konačni listići za period 1.7.[startYear]–30.6.([startYear] + 1) i sabira godišnji (šifra 10 / 8 h).
 * 30.6. propada prenos, a odmor se ne ostavlja da propadne, pa je zbir za taj period pravo na godišnji.
 */
internal data class VacationUsageScan(
    val startYear: Int,
    /** Dani godišnjeg po mesecu sa listića (poslednji obračun meseca). */
    val daysByMonth: Map<MonthKey, Double>
) {
    val months: List<MonthKey> get() = (7..12).map { MonthKey(startYear, it) } + (1..6).map { MonthKey(startYear + 1, it) }
    val used: Double get() = daysByMonth.values.sum()
    val missing: List<MonthKey> get() = months.filter { it !in daysByMonth }
    val complete: Boolean get() = missing.isEmpty()
}

/** Poslednji period 1.7.–30.6. za koji je lista za jun već stigla (~20. jula). */
internal fun vacationScanStartYear(today: LocalDate): Int =
    if (today.monthValue >= Month.AUGUST.value) today.year - 1 else today.year - 2

/** Iz pročitanih PDF-ova: konačni listići perioda, za svaki mesec poslednji obračun (rb.3 posle rb.2). */
internal fun vacationUsageScan(results: List<PayslipParseResult>, startYear: Int): VacationUsageScan {
    val period = VacationUsageScan(startYear, emptyMap()).months.toSet()
    val latestByMonth = results.filterIsInstance<PayslipParseResult.Success>()
        .map { it.payslip }
        .filter { it.month in period }
        .groupBy { it.month }
        .mapValues { (_, slips) -> slips.maxBy { payslipRunNumber(it.runLabel) } }
    return VacationUsageScan(startYear, latestByMonth.mapValues { (_, slip) -> payslipVacationDays(slip) })
}

internal fun vacationUsageScanText(scan: VacationUsageScan): String {
    val used = formatVacationInput(scan.used)
    val period = "od 1.7.${scan.startYear}. do 30.6.${scan.startYear + 1}."
    return if (scan.complete) {
        "Iskorišćeno $period: $used dana (12/12 listi)."
    } else {
        val missing = scan.missing.joinToString { monthYearText(it) }
        "Iskorišćeno $period: $used dana (${12 - scan.missing.size}/12 listi, fali $missing)."
    }
}
