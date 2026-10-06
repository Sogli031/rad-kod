package com.rad.shifttracker.data.repository

import com.rad.shifttracker.smene.DayRecord
import com.rad.shifttracker.smene.MonthKey
import com.rad.shifttracker.smene.MonthSummary
import com.rad.shifttracker.obracun.calculateMealEntitlementForAccountingMonth
import java.time.YearMonth

/**
 * Pravo na topli obrok po pravilima listića (prag 6h, cene po periodima, treća
 * smena poslednjeg dana prethodnog meseca). Isto pravilo koriste plata i
 * mesečni prosek za osnovicu, da TO2 u proseku bude isti kao na plati.
 */
internal fun MonthSummary.applyMealEntitlement(
    monthKey: MonthKey,
    monthRecords: List<DayRecord>,
    recordStore: ShiftRecordStore,
    payState: RepositoryPayState,
    defaultWorkHours: Int
) {
    val mealEntitlement = calculateMealEntitlementForAccountingMonth(
        records = foodAccountingCandidateRecords(monthKey, monthRecords, recordStore),
        month = monthKey,
        pricePeriods = payState.mealPricePeriods(),
        defaultWorkHours = defaultWorkHours
    )
    mealEntitlementCalculation = mealEntitlement
    foodAllowance = mealEntitlement.mealEntitlementNet.toDouble()
}

private fun foodAccountingCandidateRecords(
    monthKey: MonthKey,
    monthRecords: List<DayRecord>,
    recordStore: ShiftRecordStore
): List<DayRecord> {
    if (monthKey.year <= 0 || monthKey.month !in 1..12) return monthRecords
    val previousMonthEnd = YearMonth.of(monthKey.year, monthKey.month).minusMonths(1).atEndOfMonth()
    val previousMonthLastRecord = recordStore.get(
        previousMonthEnd.year,
        previousMonthEnd.monthValue,
        previousMonthEnd.dayOfMonth
    )
    return (listOfNotNull(previousMonthLastRecord) + monthRecords)
        .distinctBy { record -> record.key }
}
