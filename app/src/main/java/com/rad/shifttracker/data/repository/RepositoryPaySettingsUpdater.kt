package com.rad.shifttracker.data.repository

import com.rad.shifttracker.KnownPayBaseAnchor
import com.rad.shifttracker.MonthKey

internal class RepositoryPaySettingsUpdater(
    private val payState: RepositoryPayState,
    private val recordStore: ShiftRecordStore
) {
    fun setPayCoefficient(value: Double) {
        payState.payCoefficient = value
        invalidateAllPayrollMonths()
    }

    fun setHourlyValueChange(key: MonthKey, value: Double) {
        payState.hourlyValueChanges[key] = value
        invalidateAllPayrollMonths()
    }

    fun removeHourlyValueChange(key: MonthKey): Boolean {
        val removed = payState.hourlyValueChanges.remove(key) != null
        if (removed) {
            invalidateAllPayrollMonths()
        }
        return removed
    }

    fun setSickPayBaseHourly(value: Double) {
        payState.sickPayBaseHourly = value
        payState.invalidateSummariesAndRolling()
    }

    fun setKnownPayBaseAnchor(anchor: KnownPayBaseAnchor) {
        payState.knownPayBaseAnchor = anchor
        invalidateAllPayrollMonths()
    }

    fun setEmployeeContributionsPercent(value: Double) {
        payState.employeeContributionsPercent = value
        invalidateAllPayrollMonths()
    }

    fun setTaxRelief(value: Double) {
        payState.taxRelief = value
        invalidateAllPayrollMonths()
    }

    fun setTaxPercent(value: Double) {
        payState.taxPercent = value
        invalidateAllPayrollMonths()
    }

    fun setDifficultWorkConditionsPercent(value: Double) {
        payState.difficultWorkConditionsPercent = value
        invalidateAllPayrollMonths()
        payState.cachedSummaryCalc = null
        payState.cachedSummaryCalcDifficultWorkConditionsPercent = Double.NaN
    }

    fun setFoodPerDay(value: Double) {
        payState.foodPerDay = value
        payState.foodPerDayChanges.clear()
        payState.foodPerDayChanges[MonthKey(1900, 1)] = value
        payState.invalidateSummariesAndRolling()
    }

    fun setFoodPerDayFromMonth(key: MonthKey, value: Double) {
        payState.foodPerDayChanges[key] = value
        payState.foodPerDay = payState.foodPerDayChanges.maxByOrNull { (month, _) -> month }?.value ?: value
        invalidateAllPayrollMonths()
    }

    fun setTransportDailyAmount(value: Double) {
        payState.transportDailyAmount = value
    }

    private fun invalidateAllPayrollMonths() {
        payState.invalidateSummariesAndRolling()
        payState.dirtyMonths.addAll(recordStore.getAllMonthsSorted())
        payState.dirtyMonths.addAll(payState.monthlyPayBases.keys)
    }
}
