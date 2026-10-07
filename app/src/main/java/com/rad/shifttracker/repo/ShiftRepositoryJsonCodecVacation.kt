package com.rad.shifttracker.repo

import com.rad.shifttracker.godisnji.AnnualLeaveCalculationInput
import com.rad.shifttracker.godisnji.VacationYearBalance
import com.rad.shifttracker.godisnji.calculateAnnualLeaveDays
import com.rad.shifttracker.godisnji.currentYearVacationAvailableFrom
import com.rad.shifttracker.obracun.sanitizeAnnualLeaveYears
import org.json.JSONObject
import java.util.TreeMap

// Godišnji odmor u JSON-u: stanje po godinama i ulaz za obračun dana godišnjeg.

private const val VACATION_TRACKING_START_DATE_KEY = "tracking_start_date"
private const val VACATION_CARRYOVER_DAYS_INITIAL_KEY = "carryover_days_initial"
private const val VACATION_CURRENT_YEAR_DAYS_TOTAL_KEY = "current_year_days_total"
private const val VACATION_CURRENT_YEAR_USED_BEFORE_AVAILABILITY_KEY = "current_year_days_used_before_availability"
private const val VACATION_CURRENT_YEAR_AVAILABLE_FROM_KEY = "current_year_available_from"
private const val VACATION_MANUAL_BASELINE_KEY = "manual_baseline"
private const val VACATION_CARRYOVER_FROZEN_KEY = "carryover_frozen"
private const val ANNUAL_LEAVE_TOTAL_SENIORITY_YEARS_KEY = "total_seniority_years"
private const val ANNUAL_LEAVE_YEARS_AT_EMPLOYER_KEY = "years_at_employer"
private const val ANNUAL_LEAVE_INCREASED_RISK_KEY = "increased_risk_or_benefited_seniority"
private const val ANNUAL_LEAVE_NIGHT_WORK_KEY = "night_work_shift_system"
private const val ANNUAL_LEAVE_WEEKEND_WORK_KEY = "weekend_work_shift_system"
private const val ANNUAL_LEAVE_THIRD_DEGREE_KEY = "requires_at_least_third_degree"
private const val ANNUAL_LEAVE_WORK_INVALID_KEY = "work_invalid_from_work_injury_or_disease"
private const val ANNUAL_LEAVE_WAR_INVALID_KEY = "war_military_invalid"
private const val ANNUAL_LEAVE_SINGLE_PARENT_KEY = "single_parent_child_under_14"
private const val ANNUAL_LEAVE_THREE_CHILDREN_KEY = "three_or_more_children_under_14"
private const val ANNUAL_LEAVE_LAST_CALCULATED_DAYS_KEY = "last_calculated_days"

internal data class ParsedAnnualLeaveCalculation(
    val input: AnnualLeaveCalculationInput?,
    val lastCalculatedDays: Int
)

internal fun buildVacationYearBalancesMap(balances: Map<Int, VacationYearBalance>): TreeMap<String, Any> {
    val root = TreeMap<String, Any>()
    for ((year, balance) in balances.toSortedMap()) {
        val data = TreeMap<String, Any>()
        data[VACATION_TRACKING_START_DATE_KEY] = balance.trackingStartDate.toString()
        data[VACATION_CARRYOVER_DAYS_INITIAL_KEY] = balance.carryoverDaysInitial
        data[VACATION_CURRENT_YEAR_DAYS_TOTAL_KEY] = balance.currentYearDaysTotal
        data[VACATION_CURRENT_YEAR_USED_BEFORE_AVAILABILITY_KEY] =
            balance.currentYearDaysUsedBeforeAvailability
        data[VACATION_CURRENT_YEAR_AVAILABLE_FROM_KEY] = balance.currentYearAvailableFrom.toString()
        data[VACATION_MANUAL_BASELINE_KEY] = balance.manualBaseline
        if (balance.carryoverFrozen) data[VACATION_CARRYOVER_FROZEN_KEY] = true
        root[year.toString()] = data
    }
    return root
}

internal fun parseVacationYearBalances(root: JSONObject?): Map<Int, VacationYearBalance> {
    if (root == null) return emptyMap()
    val parsed = mutableMapOf<Int, VacationYearBalance>()
    val yearKeys = root.keys()
    while (yearKeys.hasNext()) {
        val yearKey = yearKeys.next()
        val year = yearKey.toIntOrNull() ?: continue
        if (year < 1900 || year > 2200) continue
        val data = root.optJSONObject(yearKey) ?: continue
        val trackingStart = data.optLocalDateOrNull(VACATION_TRACKING_START_DATE_KEY) ?: continue
        val availableFrom = data.optLocalDateOrNull(VACATION_CURRENT_YEAR_AVAILABLE_FROM_KEY)
            ?: currentYearVacationAvailableFrom(year)
        parsed[year] = VacationYearBalance(
            year = year,
            trackingStartDate = trackingStart,
            carryoverDaysInitial = data.optDouble(VACATION_CARRYOVER_DAYS_INITIAL_KEY, 0.0).coerceAtLeast(0.0),
            currentYearDaysTotal = data.optDouble(VACATION_CURRENT_YEAR_DAYS_TOTAL_KEY, 0.0).coerceAtLeast(0.0),
            currentYearDaysUsedBeforeAvailability = data
                .optDouble(VACATION_CURRENT_YEAR_USED_BEFORE_AVAILABILITY_KEY, 0.0)
                .coerceAtLeast(0.0),
            currentYearAvailableFrom = availableFrom,
            manualBaseline = data.optBoolean(VACATION_MANUAL_BASELINE_KEY, true),
            carryoverFrozen = data.optBoolean(VACATION_CARRYOVER_FROZEN_KEY, false)
        )
    }
    return parsed
}

internal fun buildAnnualLeaveCalculationMap(
    input: AnnualLeaveCalculationInput?,
    lastCalculatedDays: Int
): TreeMap<String, Any> {
    val data = TreeMap<String, Any>()
    input?.let {
        data[ANNUAL_LEAVE_TOTAL_SENIORITY_YEARS_KEY] = it.totalSeniorityYears
        data[ANNUAL_LEAVE_YEARS_AT_EMPLOYER_KEY] = it.yearsAtEmployer
        data[ANNUAL_LEAVE_INCREASED_RISK_KEY] = it.hasIncreasedRiskOrBenefitedSeniority
        data[ANNUAL_LEAVE_NIGHT_WORK_KEY] = it.worksNightShiftSystem
        data[ANNUAL_LEAVE_WEEKEND_WORK_KEY] = it.worksWeekendShiftSystem
        data[ANNUAL_LEAVE_THIRD_DEGREE_KEY] = it.requiresAtLeastThirdDegree
        data[ANNUAL_LEAVE_WORK_INVALID_KEY] = it.isWorkInvalidFromWorkInjuryOrDisease
        data[ANNUAL_LEAVE_WAR_INVALID_KEY] = it.isWarMilitaryInvalid
        data[ANNUAL_LEAVE_SINGLE_PARENT_KEY] = it.isSingleParentOfChildUnder14
        data[ANNUAL_LEAVE_THREE_CHILDREN_KEY] = it.hasThreeOrMoreChildrenUnder14
    }
    data[ANNUAL_LEAVE_LAST_CALCULATED_DAYS_KEY] = lastCalculatedDays
    return data
}

internal fun parseAnnualLeaveCalculation(root: JSONObject?): ParsedAnnualLeaveCalculation {
    if (root == null) return ParsedAnnualLeaveCalculation(input = null, lastCalculatedDays = 0)
    val input = AnnualLeaveCalculationInput(
        totalSeniorityYears = root.optDouble(ANNUAL_LEAVE_TOTAL_SENIORITY_YEARS_KEY, 0.0)
            .sanitizeAnnualLeaveYears(),
        yearsAtEmployer = root.optDouble(ANNUAL_LEAVE_YEARS_AT_EMPLOYER_KEY, 0.0)
            .sanitizeAnnualLeaveYears(),
        hasIncreasedRiskOrBenefitedSeniority = root.optBoolean(ANNUAL_LEAVE_INCREASED_RISK_KEY, false),
        worksNightShiftSystem = root.optBoolean(ANNUAL_LEAVE_NIGHT_WORK_KEY, false),
        worksWeekendShiftSystem = root.optBoolean(ANNUAL_LEAVE_WEEKEND_WORK_KEY, false),
        requiresAtLeastThirdDegree = root.optBoolean(ANNUAL_LEAVE_THIRD_DEGREE_KEY, false),
        isWorkInvalidFromWorkInjuryOrDisease = root.optBoolean(ANNUAL_LEAVE_WORK_INVALID_KEY, false),
        isWarMilitaryInvalid = root.optBoolean(ANNUAL_LEAVE_WAR_INVALID_KEY, false),
        isSingleParentOfChildUnder14 = root.optBoolean(ANNUAL_LEAVE_SINGLE_PARENT_KEY, false),
        hasThreeOrMoreChildrenUnder14 = root.optBoolean(ANNUAL_LEAVE_THREE_CHILDREN_KEY, false)
    )
    return ParsedAnnualLeaveCalculation(
        input = input,
        lastCalculatedDays = root.optInt(
            ANNUAL_LEAVE_LAST_CALCULATED_DAYS_KEY,
            calculateAnnualLeaveDays(input).totalDays
        ).coerceAtLeast(0)
    )
}
