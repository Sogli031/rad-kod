package com.rad.shifttracker.data.repository

import com.rad.shifttracker.AppConfig
import com.rad.shifttracker.BrigadeType
import com.rad.shifttracker.KnownPayBaseAnchor
import com.rad.shifttracker.MonthKey
import com.rad.shifttracker.MonthSummary
import com.rad.shifttracker.MonthlyGrossAdditions
import com.rad.shifttracker.MonthlyNetAdjustments
import com.rad.shifttracker.MonthlyPayBases
import com.rad.shifttracker.PayCalculator
import com.rad.shifttracker.PayBaseSelectionMode
import com.rad.shifttracker.RollingPayBaseCalculation
import com.rad.shifttracker.SeniorityIncreaseFactor
import com.rad.shifttracker.SeniorityInputMode

internal class RepositoryPayState(config: AppConfig) {
    var payCoefficient = config.pay.defaultCoefficient
    val hourlyValueChanges = mutableMapOf<MonthKey, Double>()
    var sickPayBaseHourly = config.pay.sickPayBaseHourly
    var defaultPayBaseSelectionMode = PayBaseSelectionMode.CALCULATED_PREVIOUS_12_MONTHS
    var foodPerDay = config.pay.foodPerDay
    var transportDailyAmount = config.pay.transportPerWorkDay
    var creditDeductionEnabled = false
    var monthlyCreditDeductionAmount = 0.0
    var employeeContributionsPercent = config.pay.defaultEmployeeContributionsPercent
    var taxRelief = config.pay.defaultTaxRelief
    var taxPercent = config.pay.defaultTaxPercent
    var difficultWorkConditionsPercent = config.pay.difficultWorkConditionsPercent
    var initialCompBalance = 0
    var initialCompBalanceMinutes = 0
    var minuliRadStartYear = 0
    var minuliRadStartMonth = 2
    var seniorityInputMode = SeniorityInputMode.KNOWN_START
    var minuliRadBenefitEnabled = false
    var minuliRadIncreaseFactor = SeniorityIncreaseFactor.NONE
    var minuliRadLastPayslipYear = 0
    var minuliRadLastPayslipMonth = 1
    var minuliRadPayslipPercent = 0.0
    var brigadeType = BrigadeType.TROBRIGADA
    val monthlyPayBases = mutableMapOf<MonthKey, MonthlyPayBases>()
    val monthlyGrossAdditions = mutableMapOf<MonthKey, MonthlyGrossAdditions>()
    val monthlyNetAdjustments = mutableMapOf<MonthKey, MonthlyNetAdjustments>()
    var knownPayBaseAnchor: KnownPayBaseAnchor? = null
    val summaries = mutableMapOf<MonthKey, MonthSummary>()
    val rollingPayBaseCache = mutableMapOf<MonthKey, RollingPayBaseCalculation?>()
    val dirtyMonths = mutableSetOf<MonthKey>()
    val compBalanceCache = mutableMapOf<MonthKey, Int>()
    var cachedSummaryCalc: PayCalculator? = null
    var cachedSummaryCalcFoodPerDay: Double = Double.NaN
    var cachedSummaryCalcSickPayBaseHourly: Double = Double.NaN
    var cachedSummaryCalcDifficultWorkConditionsPercent: Double = Double.NaN

    fun invalidateSummariesAndRolling() {
        summaries.clear()
        rollingPayBaseCache.clear()
    }

    fun invalidateSummariesRollingAndCompBalance() {
        invalidateSummariesAndRolling()
        compBalanceCache.clear()
    }
}
