package com.rad.shifttracker.godisnji

import kotlin.math.abs
import com.rad.shifttracker.smene.MonthKey

/** Staž (cele godine) pročitan sa jednog listića. */
internal data class PayslipSeniorityPoint(val month: MonthKey, val years: Int)

/**
 * Staž na 01.01. (KU čl. 29: dužina odmora se određuje po stanju na taj dan).
 * [exact] je false kad mesec povećanja staža još nije viđen na listićima pa je broj procena;
 * [anniversaryMonth] je mesec u kom listić prvi put pokaže godinu više.
 */
internal data class SeniorityOnJanuaryFirst(val years: Int, val exact: Boolean, val anniversaryMonth: Int?)

private fun MonthKey.index(): Int = year * 12 + (month - 1)

/** Mesec povećanja staža: dva uzastopna meseca gde listić pokaže tačno godinu više. */
internal fun seniorityAnniversaryMonth(points: List<PayslipSeniorityPoint>): Int? =
    points.sortedBy { it.month.index() }
        .zipWithNext()
        .firstOrNull { (a, b) -> b.month.index() - a.month.index() == 1 && b.years == a.years + 1 }
        ?.second?.month?.month

/**
 * Staž na 01.01. [year] iz listića: januarski listić te godine daje ga direktno; inače se od
 * najbližeg listića broje povećanja u mesecu [seniorityAnniversaryMonth] (ako je poznat).
 */
internal fun seniorityOnJanuaryFirst(points: List<PayslipSeniorityPoint>, year: Int): SeniorityOnJanuaryFirst? {
    if (points.isEmpty()) return null
    val anniversary = seniorityAnniversaryMonth(points)
    val january = MonthKey(year, 1).index()
    points.firstOrNull { it.month.index() == january }?.let { return SeniorityOnJanuaryFirst(it.years, true, anniversary) }
    val nearest = points.minBy { abs(it.month.index() - january) }
    val from = nearest.month.index()
    val years = if (anniversary != null) {
        // Povećanja u (listić, 01.01.] se dodaju, a u (01.01., listić] oduzimaju.
        val range = if (january > from) (from + 1)..january else (january + 1)..from
        val increases = range.count { it % 12 == anniversary - 1 }
        nearest.years + if (january > from) increases else -increases
    } else {
        // Bez viđenog povećanja: isti broj u istoj godini, inače po godinu za svaku godinu razlike.
        nearest.years + (year - nearest.month.year)
    }
    return SeniorityOnJanuaryFirst(years.coerceAtLeast(0), exact = anniversary != null, anniversary)
}

/**
 * Pravo na godišnji po KU čl. 29: staž i smenski rad aplikacija zna sama,
 * a uslovi rada, stručna sprema i socijalni uslovi su ručni odgovori iz [AnnualLeaveCalculationInput].
 */
internal data class AnnualLeaveEntitlement(
    val input: AnnualLeaveCalculationInput,
    val breakdown: AnnualLeaveBreakdown,
    /** Staž osiguranja na 01.01.; null kad nema listića pa se uzima podešavanje minulog rada. */
    val seniority: SeniorityOnJanuaryFirst?
)

internal fun annualLeaveEntitlement(
    manual: AnnualLeaveCalculationInput,
    totalSeniority: SeniorityOnJanuaryFirst?,
    employerSeniority: SeniorityOnJanuaryFirst?,
    fallbackSeniorityYears: Int,
    worksNightShifts: Boolean,
    worksWeekendShifts: Boolean
): AnnualLeaveEntitlement {
    val input = manual.copy(
        totalSeniorityYears = (totalSeniority?.years ?: fallbackSeniorityYears).toDouble(),
        yearsAtEmployer = (employerSeniority?.years ?: totalSeniority?.years ?: fallbackSeniorityYears).toDouble(),
        worksNightShiftSystem = worksNightShifts,
        worksWeekendShiftSystem = worksWeekendShifts
    )
    return AnnualLeaveEntitlement(input, calculateAnnualLeaveDays(input), totalSeniority)
}
