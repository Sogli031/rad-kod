package com.rad.shifttracker.obracun

import com.rad.shifttracker.godisnji.VACATION_HOURS_PER_DAY

/** Jedan mesec rada u godišnjem pregledu; [net] je neto plata za taj mesec (null dok nije poznata). */
data class YearOverviewMonth(
    val month: Int,
    val net: Double?,
    val workHours: Int,
    val overtimeHours: Int,
    val nightHours: Int,
    val sundayHours: Int,
    val sickHours: Int,
    val vacationHours: Int
)

/**
 * Godina na okupu: zbir neto plata i sati po mesecima rada. Poređenje sa prošlom godinom ide samo
 * po [comparedMonths] — mesecima koji u obe godine imaju neto, da delimična godina ne izgleda kao pad.
 */
data class YearOverview(
    val year: Int,
    val months: List<YearOverviewMonth>,
    val comparedMonths: Set<Int> = emptySet(),
    val previousYearNet: Double? = null
) {
    val totalNet: Double get() = months.sumOf { it.net ?: 0.0 }
    val averageNet: Double? get() = months.mapNotNull { it.net }.takeIf { it.isNotEmpty() }?.average()
    val bestMonth: YearOverviewMonth? get() = months.filter { it.net != null }.maxByOrNull { it.net ?: 0.0 }
    val workHours: Int get() = months.sumOf { it.workHours }
    val overtimeHours: Int get() = months.sumOf { it.overtimeHours }
    val nightHours: Int get() = months.sumOf { it.nightHours }
    val sundayHours: Int get() = months.sumOf { it.sundayHours }
    val sickDays: Double get() = months.sumOf { it.sickHours } / VACATION_HOURS_PER_DAY
    val vacationDays: Double get() = months.sumOf { it.vacationHours } / VACATION_HOURS_PER_DAY

    /** Neto ove godine minus prošle, po istim mesecima; null kad nema šta da se poredi. */
    val netDifference: Double?
        get() = previousYearNet?.let { previous ->
            months.filter { it.month in comparedMonths }.sumOf { it.net ?: 0.0 } - previous
        }

    companion object {
        /** [current] i [previous] su meseci rada (1–12) koji imaju upisane dane. */
        fun build(year: Int, current: List<YearOverviewMonth>, previous: List<YearOverviewMonth>): YearOverview {
            val previousNet = previous.mapNotNull { m -> m.net?.let { m.month to it } }.toMap()
            val shared = current.filter { it.net != null && it.month in previousNet }.map { it.month }.toSet()
            return YearOverview(
                year = year,
                months = current.sortedBy { it.month },
                comparedMonths = shared,
                previousYearNet = shared.takeIf { it.isNotEmpty() }?.sumOf { previousNet.getValue(it) }
            )
        }
    }
}
