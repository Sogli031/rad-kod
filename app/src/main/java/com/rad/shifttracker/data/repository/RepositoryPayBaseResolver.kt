package com.rad.shifttracker.data.repository

import com.rad.shifttracker.AppConfig
import com.rad.shifttracker.BrigadeType
import com.rad.shifttracker.DayRecord
import com.rad.shifttracker.KnownPayBaseAnchor
import com.rad.shifttracker.MonthKey
import com.rad.shifttracker.MonthlyGrossAdditions
import com.rad.shifttracker.PayBaseDefaults
import com.rad.shifttracker.PayBaseMonthSource
import com.rad.shifttracker.PayBaseSelectionMode
import com.rad.shifttracker.PayCalculator
import com.rad.shifttracker.ResolvedPayBases
import com.rad.shifttracker.RollingPayBaseCalculation
import com.rad.shifttracker.RollingPayBaseMonth
import com.rad.shifttracker.Shift
import com.rad.shifttracker.ShiftScheduler
import com.rad.shifttracker.Weekday
import com.rad.shifttracker.calculateKnownPayBaseCalculation
import com.rad.shifttracker.calculateMonthlyFundHours
import com.rad.shifttracker.calculateMonthlyNetPayBreakdown
import com.rad.shifttracker.calculatePayBaseMonthForAverage
import com.rad.shifttracker.calculateRollingPayBaseCalculation
import com.rad.shifttracker.effectivePayBaseSelectionMode
import com.rad.shifttracker.isSerbianHoliday
import com.rad.shifttracker.previousMonthKey
import com.rad.shifttracker.previousTwelveMonthKeys
import com.rad.shifttracker.resolvePayBasesForMonth
import java.time.LocalDate
import java.time.YearMonth

private const val FOUR_BRIGADE_CYCLE_DAYS = 8

private fun payBaseSourceMonthForWorkMonth(year: Int, month: Int): MonthKey {
    return previousMonthKey(year, month) ?: MonthKey(year, month)
}

internal class RepositoryPayBaseResolver(
    private val config: AppConfig,
    private val payState: RepositoryPayState,
    private val recordStore: ShiftRecordStore,
    private val getSummaryCalculator: () -> PayCalculator,
    private val createSummaryCalculator: () -> PayCalculator,
    private val grossHourlyRateUnsafe: (Int, Int) -> Double,
    private val getCompBalanceAtMonthStartUnsafe: (Int, Int) -> Int,
    private val getCompBalanceMinutesAtMonthStartUnsafe: (Int, Int) -> Int,
    private val getCompBalanceAtMonthStartReadOnly: (Int, Int) -> Int,
    private val getCompBalanceMinutesAtMonthStartReadOnly: (Int, Int) -> Int,
    private val calculateYearsOfServiceUnsafe: (Int, Int) -> Int
) {
    fun payBaseResolutionMonthForWorkMonth(year: Int, month: Int): MonthKey {
        val workMonth = MonthKey(year, month)
        val exactKnownAnchorApplies = payState.defaultPayBaseSelectionMode == PayBaseSelectionMode.KNOWN_BASE &&
            payState.knownPayBaseAnchor?.month == workMonth
        return if (exactKnownAnchorApplies) {
            workMonth
        } else {
            payBaseSourceMonthForWorkMonth(year, month)
        }
    }

    fun resolvedPayBasesForWorkMonth(year: Int, month: Int): ResolvedPayBases {
        return payBaseResolutionMonthForWorkMonth(year, month).let { sourceMonth ->
            getResolvedPayBasesUnsafe(sourceMonth.year, sourceMonth.month)
        }
    }

    fun getResolvedPayBasesUnsafe(year: Int, month: Int): ResolvedPayBases {
        val key = MonthKey(year, month)
        val selectedMode = effectivePayBaseSelectionMode(
            payState.monthlyPayBases[key],
            payState.defaultPayBaseSelectionMode
        )
        val calculatedBase = when (selectedMode) {
            PayBaseSelectionMode.CALCULATED_PREVIOUS_12_MONTHS -> getRollingPayBaseCalculationUnsafe(year, month)
            PayBaseSelectionMode.KNOWN_BASE -> getKnownPayBaseCalculationUnsafe(year, month)
            PayBaseSelectionMode.MANUAL,
            PayBaseSelectionMode.DEFAULT_VALUES -> null
        }
        val manualDefault = payState.sickPayBaseHourly.takeIf { it.isFinite() && it > 0.0 }
            ?: config.pay.sickPayBaseHourly
        val payBaseDefaults = when (selectedMode) {
            PayBaseSelectionMode.CALCULATED_PREVIOUS_12_MONTHS,
            PayBaseSelectionMode.KNOWN_BASE,
            PayBaseSelectionMode.DEFAULT_VALUES -> PayBaseDefaults(
                vacationHourlyBase = config.pay.vacationPayBaseHourly,
                paidHolidayHourlyBase = config.pay.paidHolidayPayBaseHourly,
                sickHourlyBase = config.pay.sickPayBaseHourly
            )
            PayBaseSelectionMode.MANUAL -> PayBaseDefaults(
                vacationHourlyBase = manualDefault,
                paidHolidayHourlyBase = manualDefault,
                sickHourlyBase = manualDefault
            )
        }
        return resolvePayBasesForMonth(
            year = year,
            month = month,
            monthlyBases = payState.monthlyPayBases,
            defaults = payBaseDefaults,
            calculatedBase = calculatedBase,
            defaultSelectionMode = payState.defaultPayBaseSelectionMode
        )
    }

    fun resolvedPayBasesForWorkMonthReadOnly(year: Int, month: Int): ResolvedPayBases {
        return payBaseResolutionMonthForWorkMonth(year, month).let { sourceMonth ->
            getResolvedPayBasesReadOnly(sourceMonth.year, sourceMonth.month)
        }
    }

    private fun getResolvedPayBasesReadOnly(year: Int, month: Int): ResolvedPayBases {
        val key = MonthKey(year, month)
        val selectedMode = effectivePayBaseSelectionMode(
            payState.monthlyPayBases[key],
            payState.defaultPayBaseSelectionMode
        )
        val calculatedBase = when (selectedMode) {
            PayBaseSelectionMode.CALCULATED_PREVIOUS_12_MONTHS -> calculateRollingPayBaseCalculationReadOnly(year, month)
            PayBaseSelectionMode.KNOWN_BASE -> getKnownPayBaseCalculationReadOnly(year, month)
            PayBaseSelectionMode.MANUAL,
            PayBaseSelectionMode.DEFAULT_VALUES -> null
        }
        val manualDefault = payState.sickPayBaseHourly.takeIf { it.isFinite() && it > 0.0 }
            ?: config.pay.sickPayBaseHourly
        val payBaseDefaults = when (selectedMode) {
            PayBaseSelectionMode.CALCULATED_PREVIOUS_12_MONTHS,
            PayBaseSelectionMode.KNOWN_BASE,
            PayBaseSelectionMode.DEFAULT_VALUES -> PayBaseDefaults(
                vacationHourlyBase = config.pay.vacationPayBaseHourly,
                paidHolidayHourlyBase = config.pay.paidHolidayPayBaseHourly,
                sickHourlyBase = config.pay.sickPayBaseHourly
            )
            PayBaseSelectionMode.MANUAL -> PayBaseDefaults(
                vacationHourlyBase = manualDefault,
                paidHolidayHourlyBase = manualDefault,
                sickHourlyBase = manualDefault
            )
        }
        return resolvePayBasesForMonth(
            year = year,
            month = month,
            monthlyBases = payState.monthlyPayBases,
            defaults = payBaseDefaults,
            calculatedBase = calculatedBase,
            defaultSelectionMode = payState.defaultPayBaseSelectionMode
        )
    }

    fun getRollingPayBaseCalculationUnsafe(year: Int, month: Int): RollingPayBaseCalculation? {
        val target = MonthKey(year, month)
        if (payState.rollingPayBaseCache.containsKey(target)) {
            return payState.rollingPayBaseCache[target]
        }

        return cacheRollingPayBaseCalculationUnsafe(
            target,
            calculateRollingPayBaseCalculationUnsafe(year, month)
        )
    }

    fun getCompleteMonthRecordsForRollingPayBaseUnsafe(monthKey: MonthKey): List<DayRecord>? {
        val lengthOfMonth = try {
            YearMonth.of(monthKey.year, monthKey.month).lengthOfMonth()
        } catch (_: Exception) {
            return null
        }
        val monthRecords = recordStore.getMonthRecords(monthKey)

        val expectedWorkdays = (1..lengthOfMonth)
            .filter { day ->
                val date = LocalDate.of(monthKey.year, monthKey.month, day)
                Weekday.from(date).isWorkday && !isSerbianHoliday(monthKey.year, monthKey.month, day)
            }
            .toSet()
        val actualDays = monthRecords.map { record ->
            if (record.year != monthKey.year || record.month != monthKey.month) return null
            record.day
        }
        if (actualDays.size != actualDays.toSet().size) return null
        if (payState.brigadeType == BrigadeType.CETVOROBRIGADA) {
            return if (isCompleteFourBrigadeMonth(monthKey, lengthOfMonth, monthRecords)) {
                monthRecords
            } else {
                null
            }
        }
        if (!actualDays.toSet().containsAll(expectedWorkdays)) return null

        return monthRecords
    }

    private fun calculateRollingPayBaseCalculationUnsafe(year: Int, month: Int): RollingPayBaseCalculation? {
        val months = previousTwelveMonthKeys(year, month)
        if (months.size != 12) {
            return null
        }

        val monthSimulator = RollingPayBaseMonthSimulator(config, payState, recordStore)
        val simulationPattern = monthSimulator.findPattern()
        val rollingMonths = mutableListOf<RollingPayBaseMonth>()
        for (key in months) {
            val monthKey = MonthKey(key.year, key.month)
            val payBaseSourceMonth = payBaseSourceMonthForWorkMonth(key.year, key.month)
            val realRecords = getCompleteMonthRecordsForRollingPayBaseUnsafe(monthKey)
            val monthRecords = realRecords
                ?: monthSimulator.simulateEmptyMonth(monthKey, simulationPattern)
                ?: return null
            val selectedMode = effectivePayBaseSelectionMode(
                payState.monthlyPayBases[payBaseSourceMonth],
                payState.defaultPayBaseSelectionMode
            )
            val manualDefault = payState.sickPayBaseHourly.takeIf { it.isFinite() && it > 0.0 }
                ?: config.pay.sickPayBaseHourly
            val payBaseDefaults = when (selectedMode) {
                PayBaseSelectionMode.CALCULATED_PREVIOUS_12_MONTHS,
                PayBaseSelectionMode.KNOWN_BASE,
                PayBaseSelectionMode.DEFAULT_VALUES -> PayBaseDefaults(
                    vacationHourlyBase = config.pay.vacationPayBaseHourly,
                    paidHolidayHourlyBase = config.pay.paidHolidayPayBaseHourly,
                    sickHourlyBase = config.pay.sickPayBaseHourly
                )
                PayBaseSelectionMode.MANUAL -> PayBaseDefaults(
                    vacationHourlyBase = manualDefault,
                    paidHolidayHourlyBase = manualDefault,
                    sickHourlyBase = manualDefault
                )
            }
            val payBases = resolvePayBasesForMonth(
                year = payBaseSourceMonth.year,
                month = payBaseSourceMonth.month,
                monthlyBases = payState.monthlyPayBases,
                defaults = payBaseDefaults,
                calculatedBase = null,
                defaultSelectionMode = payState.defaultPayBaseSelectionMode
            )
            rollingMonths += calculatePayBaseMonthUnsafe(
                monthKey = monthKey,
                monthRecords = monthRecords,
                payBases = payBases,
                source = if (realRecords != null) PayBaseMonthSource.REAL else PayBaseMonthSource.SIMULATED
            )
        }

        return calculateRollingPayBaseCalculation(rollingMonths)
    }

    private fun calculateRollingPayBaseCalculationReadOnly(year: Int, month: Int): RollingPayBaseCalculation? {
        val months = previousTwelveMonthKeys(year, month)
        if (months.size != 12) {
            return null
        }

        val monthSimulator = RollingPayBaseMonthSimulator(config, payState, recordStore)
        val simulationPattern = monthSimulator.findPattern()
        val rollingMonths = mutableListOf<RollingPayBaseMonth>()
        for (key in months) {
            val monthKey = MonthKey(key.year, key.month)
            val payBaseSourceMonth = payBaseSourceMonthForWorkMonth(key.year, key.month)
            val realRecords = getCompleteMonthRecordsForRollingPayBaseUnsafe(monthKey)
            val monthRecords = realRecords
                ?: monthSimulator.simulateEmptyMonth(monthKey, simulationPattern)
                ?: return null
            val selectedMode = effectivePayBaseSelectionMode(
                payState.monthlyPayBases[payBaseSourceMonth],
                payState.defaultPayBaseSelectionMode
            )
            val manualDefault = payState.sickPayBaseHourly.takeIf { it.isFinite() && it > 0.0 }
                ?: config.pay.sickPayBaseHourly
            val payBaseDefaults = when (selectedMode) {
                PayBaseSelectionMode.CALCULATED_PREVIOUS_12_MONTHS,
                PayBaseSelectionMode.KNOWN_BASE,
                PayBaseSelectionMode.DEFAULT_VALUES -> PayBaseDefaults(
                    vacationHourlyBase = config.pay.vacationPayBaseHourly,
                    paidHolidayHourlyBase = config.pay.paidHolidayPayBaseHourly,
                    sickHourlyBase = config.pay.sickPayBaseHourly
                )
                PayBaseSelectionMode.MANUAL -> PayBaseDefaults(
                    vacationHourlyBase = manualDefault,
                    paidHolidayHourlyBase = manualDefault,
                    sickHourlyBase = manualDefault
                )
            }
            val payBases = resolvePayBasesForMonth(
                year = payBaseSourceMonth.year,
                month = payBaseSourceMonth.month,
                monthlyBases = payState.monthlyPayBases,
                defaults = payBaseDefaults,
                calculatedBase = null,
                defaultSelectionMode = payState.defaultPayBaseSelectionMode
            )
            rollingMonths += calculatePayBaseMonthReadOnly(
                monthKey = monthKey,
                monthRecords = monthRecords,
                payBases = payBases,
                source = if (realRecords != null) PayBaseMonthSource.REAL else PayBaseMonthSource.SIMULATED
            )
        }

        return calculateRollingPayBaseCalculation(rollingMonths)
    }

    private fun getKnownPayBaseCalculationUnsafe(year: Int, month: Int): RollingPayBaseCalculation? {
        val target = MonthKey(year, month)
        val anchor = payState.knownPayBaseAnchor ?: return null
        if (anchor.month > target || !anchor.grossHourlyBase.isFinite() || anchor.grossHourlyBase <= 0.0) return null

        val followingMonths = mutableListOf<RollingPayBaseMonth>()

        for (monthKey in monthsBetweenAnchorAndTarget(anchor.month, target)) {
            val payBaseSourceMonth = payBaseSourceMonthForWorkMonth(monthKey.year, monthKey.month)
            val previousCalculation = calculateKnownPayBaseCalculationForTargetUnsafe(
                anchor = anchor,
                target = monthKey,
                followingMonths = followingMonths,
                defaultHours = config.pay.defaultHours
            ) ?: return null
            val realRecords = getCompleteMonthRecordsForRollingPayBaseUnsafe(monthKey)
            if (realRecords == null) {
                val carryForwardHours = calculateMonthlyFundHours(
                    monthKey.year,
                    monthKey.month,
                    config.pay.defaultHours
                )
                if (carryForwardHours <= 0) return null
                followingMonths += RollingPayBaseMonth(
                    year = monthKey.year,
                    month = monthKey.month,
                    grossForAverage = previousCalculation.hourlyBase * carryForwardHours.toDouble(),
                    hoursForAverage = carryForwardHours,
                    source = PayBaseMonthSource.CARRY_FORWARD
                )
                continue
            }
            val payBases = resolvePayBasesForMonth(
                year = payBaseSourceMonth.year,
                month = payBaseSourceMonth.month,
                monthlyBases = payState.monthlyPayBases,
                defaults = PayBaseDefaults(
                    vacationHourlyBase = config.pay.vacationPayBaseHourly,
                    paidHolidayHourlyBase = config.pay.paidHolidayPayBaseHourly,
                    sickHourlyBase = config.pay.sickPayBaseHourly
                ),
                calculatedBase = previousCalculation,
                defaultSelectionMode = payState.defaultPayBaseSelectionMode
            )
            followingMonths += calculatePayBaseMonthUnsafe(
                monthKey = monthKey,
                monthRecords = realRecords,
                payBases = payBases,
                source = PayBaseMonthSource.REAL
            )
        }

        return calculateKnownPayBaseCalculationForTargetUnsafe(
            anchor = anchor,
            target = target,
            followingMonths = followingMonths,
            defaultHours = config.pay.defaultHours
        )
    }

    private fun getKnownPayBaseCalculationReadOnly(year: Int, month: Int): RollingPayBaseCalculation? {
        val target = MonthKey(year, month)
        val anchor = payState.knownPayBaseAnchor ?: return null
        if (anchor.month > target || !anchor.grossHourlyBase.isFinite() || anchor.grossHourlyBase <= 0.0) return null

        val followingMonths = mutableListOf<RollingPayBaseMonth>()

        for (monthKey in monthsBetweenAnchorAndTarget(anchor.month, target)) {
            val payBaseSourceMonth = payBaseSourceMonthForWorkMonth(monthKey.year, monthKey.month)
            val previousCalculation = calculateKnownPayBaseCalculationForTargetUnsafe(
                anchor = anchor,
                target = monthKey,
                followingMonths = followingMonths,
                defaultHours = config.pay.defaultHours
            ) ?: return null
            val realRecords = getCompleteMonthRecordsForRollingPayBaseUnsafe(monthKey)
            if (realRecords == null) {
                val carryForwardHours = calculateMonthlyFundHours(
                    monthKey.year,
                    monthKey.month,
                    config.pay.defaultHours
                )
                if (carryForwardHours <= 0) return null
                followingMonths += RollingPayBaseMonth(
                    year = monthKey.year,
                    month = monthKey.month,
                    grossForAverage = previousCalculation.hourlyBase * carryForwardHours.toDouble(),
                    hoursForAverage = carryForwardHours,
                    source = PayBaseMonthSource.CARRY_FORWARD
                )
                continue
            }
            val payBases = resolvePayBasesForMonth(
                year = payBaseSourceMonth.year,
                month = payBaseSourceMonth.month,
                monthlyBases = payState.monthlyPayBases,
                defaults = PayBaseDefaults(
                    vacationHourlyBase = config.pay.vacationPayBaseHourly,
                    paidHolidayHourlyBase = config.pay.paidHolidayPayBaseHourly,
                    sickHourlyBase = config.pay.sickPayBaseHourly
                ),
                calculatedBase = previousCalculation,
                defaultSelectionMode = payState.defaultPayBaseSelectionMode
            )
            followingMonths += calculatePayBaseMonthReadOnly(
                monthKey = monthKey,
                monthRecords = realRecords,
                payBases = payBases,
                source = PayBaseMonthSource.REAL
            )
        }

        return calculateKnownPayBaseCalculationForTargetUnsafe(
            anchor = anchor,
            target = target,
            followingMonths = followingMonths,
            defaultHours = config.pay.defaultHours
        )
    }

    private fun calculatePayBaseMonthUnsafe(
        monthKey: MonthKey,
        monthRecords: List<DayRecord>,
        payBases: ResolvedPayBases,
        source: PayBaseMonthSource
    ): RollingPayBaseMonth {
        val grossHourlyRate = grossHourlyRateUnsafe(monthKey.year, monthKey.month)
        val summary = getSummaryCalculator().calculateMonthSummary(
            records = monthRecords,
            baseRate = grossHourlyRate,
            startingCompBalance = getCompBalanceAtMonthStartUnsafe(monthKey.year, monthKey.month),
            startingCompBalanceMinutes = getCompBalanceMinutesAtMonthStartUnsafe(monthKey.year, monthKey.month),
            yearsOfService = calculateYearsOfServiceUnsafe(monthKey.year, monthKey.month),
            brigadeType = payState.brigadeType,
            summaryYear = monthKey.year,
            summaryMonth = monthKey.month,
            payBases = payBases
        )
        val grossAdditions = payState.monthlyGrossAdditions[monthKey]
            ?: MonthlyGrossAdditions(monthKey.year, monthKey.month)
        val breakdown = calculateMonthlyNetPayBreakdown(
            summary = summary,
            grossHourlyRate = grossHourlyRate,
            monthlyFundHours = calculateMonthlyFundHours(monthKey.year, monthKey.month, config.pay.defaultHours),
            contributionsPercent = payState.employeeContributionsPercent,
            taxRelief = payState.taxRelief,
            taxPercent = payState.taxPercent,
            grossAdditions = grossAdditions
        )
        val details = getSummaryCalculator().calculateMonthlyPayDetails(
            records = monthRecords,
            summary = summary,
            baseRate = grossHourlyRate,
            netBreakdown = breakdown,
            brigadeType = payState.brigadeType,
            payBases = payBases
        )
        return calculatePayBaseMonthForAverage(
            year = monthKey.year,
            month = monthKey.month,
            details = details,
            source = source,
            grossAdditions = grossAdditions
        )
    }

    private fun calculatePayBaseMonthReadOnly(
        monthKey: MonthKey,
        monthRecords: List<DayRecord>,
        payBases: ResolvedPayBases,
        source: PayBaseMonthSource
    ): RollingPayBaseMonth {
        val grossHourlyRate = grossHourlyRateUnsafe(monthKey.year, monthKey.month)
        val calculator = createSummaryCalculator()
        val summary = calculator.calculateMonthSummary(
            records = monthRecords,
            baseRate = grossHourlyRate,
            startingCompBalance = getCompBalanceAtMonthStartReadOnly(monthKey.year, monthKey.month),
            startingCompBalanceMinutes = getCompBalanceMinutesAtMonthStartReadOnly(monthKey.year, monthKey.month),
            yearsOfService = calculateYearsOfServiceUnsafe(monthKey.year, monthKey.month),
            brigadeType = payState.brigadeType,
            summaryYear = monthKey.year,
            summaryMonth = monthKey.month,
            payBases = payBases
        )
        val grossAdditions = payState.monthlyGrossAdditions[monthKey]
            ?: MonthlyGrossAdditions(monthKey.year, monthKey.month)
        val breakdown = calculateMonthlyNetPayBreakdown(
            summary = summary,
            grossHourlyRate = grossHourlyRate,
            monthlyFundHours = calculateMonthlyFundHours(monthKey.year, monthKey.month, config.pay.defaultHours),
            contributionsPercent = payState.employeeContributionsPercent,
            taxRelief = payState.taxRelief,
            taxPercent = payState.taxPercent,
            grossAdditions = grossAdditions
        )
        val details = calculator.calculateMonthlyPayDetails(
            records = monthRecords,
            summary = summary,
            baseRate = grossHourlyRate,
            netBreakdown = breakdown,
            brigadeType = payState.brigadeType,
            payBases = payBases
        )
        return calculatePayBaseMonthForAverage(
            year = monthKey.year,
            month = monthKey.month,
            details = details,
            source = source,
            grossAdditions = grossAdditions
        )
    }

    private fun cacheRollingPayBaseCalculationUnsafe(
        key: MonthKey,
        calculation: RollingPayBaseCalculation?
    ): RollingPayBaseCalculation? {
        payState.rollingPayBaseCache[key] = calculation
        return calculation
    }

    private fun isCompleteFourBrigadeMonth(
        monthKey: MonthKey,
        lengthOfMonth: Int,
        monthRecords: List<DayRecord>
    ): Boolean {
        val recordDays = monthRecords.map { it.day }.toSet()
        val firstDay = LocalDate.of(monthKey.year, monthKey.month, 1)
        val scheduler = ShiftScheduler()

        return (0 until FOUR_BRIGADE_CYCLE_DAYS).any { cycleStartIndex ->
            (1..lengthOfMonth).all { day ->
                val date = LocalDate.of(monthKey.year, monthKey.month, day)
                val expectedShift = scheduler.calculateFourBrigadeShiftForDate(
                    date = date,
                    anchorDate = firstDay,
                    cycleStartIndex = cycleStartIndex
                )
                val isWorkdayHoliday = Weekday.from(date).isWorkday &&
                    isSerbianHoliday(monthKey.year, monthKey.month, day)
                isWorkdayHoliday || expectedShift !in Shift.workShifts() || day in recordDays
            }
        }
    }
}

private fun calculateKnownPayBaseCalculationForTargetUnsafe(
    anchor: KnownPayBaseAnchor,
    target: MonthKey,
    followingMonths: List<RollingPayBaseMonth>,
    defaultHours: Int
): RollingPayBaseCalculation? {
    val windowMonths = previousTwelveMonthKeys(target.year, target.month)
    if (windowMonths.size != 12) return null

    val windowMonthSet = windowMonths.toSet()
    val anchorHistoryHours = windowMonths
        .filter { it <= anchor.month }
        .sumOf { calculateMonthlyFundHours(it.year, it.month, defaultHours) }
    val rollingMonths = mutableListOf<RollingPayBaseMonth>()
    if (anchorHistoryHours > 0) {
        rollingMonths += RollingPayBaseMonth(
            year = anchor.month.year,
            month = anchor.month.month,
            grossForAverage = anchor.grossHourlyBase * anchorHistoryHours.toDouble(),
            hoursForAverage = anchorHistoryHours,
            source = PayBaseMonthSource.KNOWN_BASE
        )
    }
    rollingMonths += followingMonths.filter { MonthKey(it.year, it.month) in windowMonthSet }

    return if (rollingMonths.firstOrNull()?.source == PayBaseMonthSource.KNOWN_BASE) {
        calculateKnownPayBaseCalculation(rollingMonths)
    } else {
        calculateRollingPayBaseCalculation(rollingMonths)
    }
}

private fun monthsBetweenAnchorAndTarget(anchor: MonthKey, target: MonthKey): List<MonthKey> {
    val anchorYearMonth = runCatching { YearMonth.of(anchor.year, anchor.month) }.getOrNull()
        ?: return emptyList()
    val targetYearMonth = runCatching { YearMonth.of(target.year, target.month) }.getOrNull()
        ?: return emptyList()
    val months = mutableListOf<MonthKey>()
    var cursor = anchorYearMonth.plusMonths(1)
    while (cursor.isBefore(targetYearMonth)) {
        months += MonthKey(cursor.year, cursor.monthValue)
        cursor = cursor.plusMonths(1)
    }
    return months
}
