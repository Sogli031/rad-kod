package com.rad.shifttracker.data.repository

import com.rad.shifttracker.AbsenceRangeNetImpact
import com.rad.shifttracker.AnnualNetPayAverages
import com.rad.shifttracker.AnnualNetPayMonth
import com.rad.shifttracker.AppConfig
import com.rad.shifttracker.DayRecord
import com.rad.shifttracker.MonthKey
import com.rad.shifttracker.MonthSummary
import com.rad.shifttracker.MonthlyGrossAdditions
import com.rad.shifttracker.MonthlyNetPayBreakdown
import com.rad.shifttracker.MonthlyPayDetails
import com.rad.shifttracker.MonthlyReceipt
import com.rad.shifttracker.PayCalculator
import com.rad.shifttracker.RecentNetPayAverage
import com.rad.shifttracker.RecentNetPayMonth
import com.rad.shifttracker.ResolvedPayBases
import com.rad.shifttracker.RollingPayBaseCalculation
import com.rad.shifttracker.Shift
import com.rad.shifttracker.calculateAnnualNetPayAverages
import com.rad.shifttracker.calculateMonthlyFundHours
import com.rad.shifttracker.calculateMonthlyNetPayBreakdown
import com.rad.shifttracker.calculateMonthlyReceipt
import com.rad.shifttracker.calculateRecentNetPayAverage
import com.rad.shifttracker.calculateTransportEligibleDays
import com.rad.shifttracker.grossHourlyRateForMonth
import com.rad.shifttracker.previousMonthKey
import com.rad.shifttracker.recentNetPayAverageMonthKeys
import com.rad.shifttracker.resolveHourlyValueForMonth
import java.time.LocalDate
import java.time.YearMonth

internal class RepositoryPayQueries(
    private val config: AppConfig,
    private val payState: RepositoryPayState,
    private val recordStore: ShiftRecordStore,
    private val getCompBalanceAtMonthStartUnsafe: (Int, Int) -> Int,
    private val getCompBalanceMinutesAtMonthStartUnsafe: (Int, Int) -> Int,
    private val getCompBalanceAtMonthStartReadOnly: (Int, Int) -> Int,
    private val getCompBalanceMinutesAtMonthStartReadOnly: (Int, Int) -> Int,
    private val calculateYearsOfServiceUnsafe: (Int, Int) -> Int
) {
    private val payBaseResolver = RepositoryPayBaseResolver(
        config = config,
        payState = payState,
        recordStore = recordStore,
        getSummaryCalculator = ::getSummaryCalculator,
        createSummaryCalculator = ::createSummaryCalculator,
        grossHourlyRateUnsafe = ::grossHourlyRateUnsafe,
        getCompBalanceAtMonthStartUnsafe = getCompBalanceAtMonthStartUnsafe,
        getCompBalanceMinutesAtMonthStartUnsafe = getCompBalanceMinutesAtMonthStartUnsafe,
        getCompBalanceAtMonthStartReadOnly = getCompBalanceAtMonthStartReadOnly,
        getCompBalanceMinutesAtMonthStartReadOnly = getCompBalanceMinutesAtMonthStartReadOnly,
        calculateYearsOfServiceUnsafe = calculateYearsOfServiceUnsafe
    )

    private fun createSummaryCalculator(): PayCalculator {
        return PayCalculator(
            config.pay.copy(
                foodPerDay = payState.foodPerDay,
                sickPayBaseHourly = payState.sickPayBaseHourly,
                difficultWorkConditionsPercent = payState.difficultWorkConditionsPercent
            )
        )
    }

    fun getSummaryCalculator(): PayCalculator {
        val currentFood = payState.foodPerDay
        val currentSickPayBase = payState.sickPayBaseHourly
        val currentDifficultWorkConditionsPercent = payState.difficultWorkConditionsPercent
        val cached = payState.cachedSummaryCalc
        if (
            cached != null &&
            payState.cachedSummaryCalcFoodPerDay == currentFood &&
            payState.cachedSummaryCalcSickPayBaseHourly == currentSickPayBase &&
            payState.cachedSummaryCalcDifficultWorkConditionsPercent == currentDifficultWorkConditionsPercent
        ) {
            return cached
        }
        val calc = createSummaryCalculator()
        payState.cachedSummaryCalc = calc
        payState.cachedSummaryCalcFoodPerDay = currentFood
        payState.cachedSummaryCalcSickPayBaseHourly = currentSickPayBase
        payState.cachedSummaryCalcDifficultWorkConditionsPercent = currentDifficultWorkConditionsPercent
        return calc
    }

    fun grossHourlyRateUnsafe(year: Int, month: Int): Double {
        return grossHourlyRateForMonth(payState.payCoefficient, year, month, payState.hourlyValueChanges)
    }

    fun hourlyValueUnsafe(year: Int, month: Int): Double {
        return resolveHourlyValueForMonth(year, month, payState.hourlyValueChanges).value
    }

    fun getPayBaseResolutionMonthForWorkMonthUnsafe(year: Int, month: Int): MonthKey {
        return payBaseResolver.payBaseResolutionMonthForWorkMonth(year, month)
    }

    fun getResolvedPayBasesForWorkMonthUnsafe(year: Int, month: Int): ResolvedPayBases {
        return payBaseResolver.resolvedPayBasesForWorkMonth(year, month)
    }

    fun withHourlyValueUnsafe(
        year: Int,
        month: Int,
        breakdown: MonthlyNetPayBreakdown
    ): MonthlyNetPayBreakdown {
        return breakdown.copy(hourlyValue = hourlyValueUnsafe(year, month))
    }

    fun calculateMonthSummaryUnsafe(year: Int, month: Int, monthRecords: List<DayRecord>): MonthSummary {
        return calculateMonthSummaryUnsafe(
            year = year,
            month = month,
            monthRecords = monthRecords,
            grossHourlyRate = grossHourlyRateUnsafe(year, month)
        )
    }

    fun calculateMonthSummaryUnsafe(
        year: Int,
        month: Int,
        monthRecords: List<DayRecord>,
        grossHourlyRate: Double
    ): MonthSummary {
        val monthKey = MonthKey(year, month)
        val calculator = getSummaryCalculator()
        val summary = calculator.calculateMonthSummary(
            records = monthRecords,
            baseRate = grossHourlyRate,
            startingCompBalance = getCompBalanceAtMonthStartUnsafe(year, month),
            startingCompBalanceMinutes = getCompBalanceMinutesAtMonthStartUnsafe(year, month),
            yearsOfService = calculateYearsOfServiceUnsafe(year, month),
            brigadeType = payState.brigadeType,
            summaryYear = year,
            summaryMonth = month,
            payBases = payBaseResolver.resolvedPayBasesForWorkMonth(year, month)
        )
        summary.foodAllowance = calculator.calculateFoodAllowanceForAccountingMonth(
            records = foodAccountingCandidateRecords(year, month, monthRecords),
            month = monthKey
        )
        return summary
    }

    private fun foodAccountingCandidateRecords(
        year: Int,
        month: Int,
        monthRecords: List<DayRecord>
    ): List<DayRecord> {
        if (year <= 0 || month !in 1..12) return monthRecords
        val monthStart = YearMonth.of(year, month)
        val previousMonthEnd = monthStart.minusMonths(1).atEndOfMonth()
        val previousMonthLastRecord = recordStore.get(
            previousMonthEnd.year,
            previousMonthEnd.monthValue,
            previousMonthEnd.dayOfMonth
        )
        return (listOfNotNull(previousMonthLastRecord) + monthRecords)
            .distinctBy { record -> record.key }
    }

    fun calculateDailyPayUnsafe(record: DayRecord): Double {
        return calculateDailyPayUnsafe(
            record = record,
            grossHourlyRate = grossHourlyRateUnsafe(record.year, record.month)
        )
    }

    fun calculateDailyPayUnsafe(record: DayRecord, grossHourlyRate: Double): Double {
        return getSummaryCalculator().calculateDailyPay(
            record,
            grossHourlyRate,
            payState.brigadeType,
            payBaseResolver.resolvedPayBasesForWorkMonth(record.year, record.month)
        )
    }

    fun calculateDailySeniorityBonusUnsafe(record: DayRecord, grossHourlyRate: Double): Double {
        return getSummaryCalculator().calculateDailySeniorityBonus(
            record,
            grossHourlyRate,
            calculateYearsOfServiceUnsafe(record.year, record.month),
            payState.brigadeType
        )
    }

    fun getNightHoursUnsafe(record: DayRecord): Int {
        return getSummaryCalculator().getNightHours(record)
    }

    fun getResolvedPayBasesUnsafe(year: Int, month: Int): ResolvedPayBases {
        return payBaseResolver.getResolvedPayBasesUnsafe(year, month)
    }

    fun getRollingPayBaseCalculationUnsafe(year: Int, month: Int): RollingPayBaseCalculation? {
        return payBaseResolver.getRollingPayBaseCalculationUnsafe(year, month)
    }

    fun calculateMonthlyNetPayBreakdownUnsafe(
        year: Int,
        month: Int,
        summary: MonthSummary,
        grossHourlyRate: Double = grossHourlyRateUnsafe(year, month),
        contributionsPercent: Double = payState.employeeContributionsPercent,
        taxRelief: Double = payState.taxRelief,
        taxPercent: Double = payState.taxPercent,
        grossAdditions: MonthlyGrossAdditions = monthlyGrossAdditionsUnsafe(year, month),
        includeRegress: Boolean = true,
        includeHourlyValue: Boolean = false
    ): MonthlyNetPayBreakdown {
        val breakdown = calculateMonthlyNetPayBreakdown(
            summary = summary,
            grossHourlyRate = grossHourlyRate,
            monthlyFundHours = calculateMonthlyFundHours(year, month, config.pay.defaultHours),
            contributionsPercent = contributionsPercent,
            taxRelief = taxRelief,
            taxPercent = taxPercent,
            grossAdditions = grossAdditions,
            includeRegress = includeRegress
        )
        return if (includeHourlyValue) withHourlyValueUnsafe(year, month, breakdown) else breakdown
    }

    fun calculateNetPayBreakdownWithPreviewUnsafe(
        record: DayRecord,
        monthRecords: List<DayRecord>,
        includeFoodAllowance: Boolean,
        includeGrossAdditions: Boolean
    ): MonthlyNetPayBreakdown {
        val summary = calculateMonthSummaryUnsafe(record.year, record.month, monthRecords)
            .let { if (includeFoodAllowance) it else it.copy(foodAllowance = 0.0) }
        return calculateMonthlyNetPayBreakdownUnsafe(
            year = record.year,
            month = record.month,
            summary = summary,
            grossAdditions = if (includeGrossAdditions) {
                monthlyGrossAdditionsUnsafe(record.year, record.month)
            } else {
                MonthlyGrossAdditions(record.year, record.month)
            }
        )
    }

    fun calculateMonthlyPayDetailsUnsafe(
        year: Int,
        month: Int,
        monthRecords: List<DayRecord>,
        summary: MonthSummary
    ): MonthlyPayDetails {
        val grossHourlyRate = grossHourlyRateUnsafe(year, month)
        val payBases = getResolvedPayBasesForWorkMonthUnsafe(year, month)
        return getSummaryCalculator().calculateMonthlyPayDetails(
            records = monthRecords,
            summary = summary,
            baseRate = grossHourlyRate,
            netBreakdown = calculateMonthlyNetPayBreakdownUnsafe(
                year = year,
                month = month,
                summary = summary,
                grossHourlyRate = grossHourlyRate
            ),
            brigadeType = payState.brigadeType,
            payBases = payBases
        )
    }

    fun calculatePreviewNetPayBreakdownUnsafe(
        year: Int,
        month: Int,
        monthRecords: List<DayRecord>,
        grossHourlyRate: Double,
        contributionsPercent: Double,
        taxRelief: Double,
        taxPercent: Double
    ): MonthlyNetPayBreakdown {
        val summary = getSummaryCalculator().calculateMonthSummary(
            monthRecords,
            grossHourlyRate,
            getCompBalanceAtMonthStartUnsafe(year, month),
            calculateYearsOfServiceUnsafe(year, month),
            payState.brigadeType,
            summaryYear = year,
            summaryMonth = month,
            payBases = payBaseResolver.resolvedPayBasesForWorkMonth(year, month),
            startingCompBalanceMinutes = getCompBalanceMinutesAtMonthStartUnsafe(year, month)
        )
        return calculateMonthlyNetPayBreakdownUnsafe(
            year = year,
            month = month,
            summary = summary,
            grossHourlyRate = grossHourlyRate,
            contributionsPercent = contributionsPercent,
            taxRelief = taxRelief,
            taxPercent = taxPercent
        )
    }

    fun calculateAnnualMonthNetPayUnsafe(
        year: Int,
        month: Int,
        monthRecords: List<DayRecord>,
        includeRegress: Boolean
    ): MonthlyNetPayBreakdown {
        return calculateMonthlyNetPayBreakdownUnsafe(
            year = year,
            month = month,
            summary = calculateMonthSummaryUnsafe(year, month, monthRecords),
            includeRegress = includeRegress
        )
    }

    fun calculateMonthlyReceiptUnsafe(year: Int, month: Int): MonthlyReceipt {
        val salaryMonth = previousMonthKey(year, month) ?: MonthKey(year, month)
        val salaryRecords = recordStore.getMonthRecords(salaryMonth)
        val salaryAvailable = salaryMonth != MonthKey(year, month) && salaryRecords.isNotEmpty()
        val salaryBreakdown = if (salaryAvailable) {
            calculateAnnualMonthNetPayUnsafe(
                year = salaryMonth.year,
                month = salaryMonth.month,
                monthRecords = salaryRecords,
                includeRegress = false
            )
        } else {
            null
        }
        val paymentMonthKey = MonthKey(year, month)
        val paymentRecords = recordStore.getMonthRecords(paymentMonthKey)
        val paymentMonthRegressNetPay = if (salaryAvailable && paymentRecords.isNotEmpty()) {
            calculateAnnualMonthNetPayUnsafe(
                year = year,
                month = month,
                monthRecords = paymentRecords,
                includeRegress = true
            ).netRegressAddition
        } else {
            0.0
        }
        val salaryNetPay = salaryBreakdown?.displayPay ?: 0.0

        return calculateMonthlyReceipt(
            year = year,
            month = month,
            salaryMonth = salaryMonth,
            previousMonthSalaryNet = salaryNetPay,
            currentMonthRegressNet = paymentMonthRegressNetPay,
            isSalaryAvailable = salaryAvailable,
            transportEligibleDays = if (salaryAvailable) calculateTransportEligibleDays(salaryRecords) else 0,
            transportDailyAmount = payState.transportDailyAmount
        )
    }

    fun getAnnualNetPayAverages(year: Int): AnnualNetPayAverages {
        if (year <= 0) return calculateAnnualNetPayAverages(emptyList())

        val months = (1..12).mapNotNull { month ->
            val monthKey = MonthKey(year, month)
            val monthRecords = recordStore.getMonthRecords(monthKey)
            if (monthRecords.isEmpty()) return@mapNotNull null
            AnnualNetPayMonth(
                netPay = calculateAnnualMonthNetPayUnsafe(
                    year = year,
                    month = month,
                    monthRecords = monthRecords,
                    includeRegress = true
                ).displayPay,
                netPayWithoutRegress = calculateAnnualMonthNetPayUnsafe(
                    year = year,
                    month = month,
                    monthRecords = monthRecords,
                    includeRegress = false
                ).displayPay,
                netPayWithoutThirdShift = calculateAnnualMonthNetPayUnsafe(
                    year = year,
                    month = month,
                    monthRecords = withoutThirdShift(monthRecords),
                    includeRegress = false
                ).displayPay
            )
        }

        return calculateAnnualNetPayAverages(months)
    }

    fun getRecentNetPayAverage(year: Int, month: Int, asOfDate: LocalDate): RecentNetPayAverage {
        val months = recentNetPayAverageMonthKeys(year, month, asOfDate).map { key ->
            val monthRecords = payBaseResolver.getCompleteMonthRecordsForRollingPayBaseUnsafe(key)
            RecentNetPayMonth(
                key = key,
                netPay = monthRecords?.let { records ->
                    calculateAnnualMonthNetPayUnsafe(
                        year = key.year,
                        month = key.month,
                        monthRecords = records,
                        includeRegress = true
                    ).displayPay
                }
            )
        }
        return calculateRecentNetPayAverage(months)
    }

    fun withoutThirdShift(records: List<DayRecord>): List<DayRecord> {
        return records.map { record -> record.withoutThirdShift() }
    }

    fun emptyAbsenceRangeNetImpact(): AbsenceRangeNetImpact {
        return AbsenceRangeNetImpact(
            currentNetPay = 0.0,
            previewNetPay = 0.0,
            netDifference = 0.0,
            affectedDays = 0,
            absenceHours = 0,
            replacedWorkHours = 0,
            replacedNightHours = 0
        )
    }

    private fun monthlyGrossAdditionsUnsafe(year: Int, month: Int): MonthlyGrossAdditions {
        return payState.monthlyGrossAdditions[MonthKey(year, month)] ?: MonthlyGrossAdditions(year, month)
    }

}

private fun DayRecord.withoutThirdShift(): DayRecord {
    return DayRecord(
        year = year,
        month = month,
        day = day,
        shift = shift.withoutThirdShift(),
        hours = hours,
        shift2 = shift2?.withoutThirdShift(),
        hours2 = hours2,
        overtimeType = overtimeType,
        weekendOvertimeType = weekendOvertimeType,
        compHoursUsed = compHoursUsed,
        holidayWorkOverride = holidayWorkOverride,
        holidayOverride = holidayOverride,
        balanceExempt = balanceExempt,
        sickPayRate = sickPayRate,
        regularShortageMinutes = regularShortageMinutes
    )
}

private fun Shift.withoutThirdShift(): Shift {
    return if (this == Shift.THIRD) Shift.SECOND else this
}
