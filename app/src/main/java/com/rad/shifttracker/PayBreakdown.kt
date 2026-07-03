package com.rad.shifttracker

import java.time.DateTimeException
import java.time.LocalDate
import java.time.YearMonth

private const val PAYROLL_FINAL_CUTOFF_DAY = 20
private const val RECENT_NET_PAY_MONTH_COUNT = 3
private const val MIN_TRANSPORT_WORK_HOURS = 4
private const val MINUTES_PER_HOUR = 60

data class MonthlyNetPayBreakdown(
    val grossHourlyRate: Double,
    val monthlyFundHours: Int,
    val informativeNetHourlyBase: Double,
    val grossBaseBeforeSeniority: Double,
    val grossSeniorityBonus: Double,
    val grossMealAddition: Double,
    val grossRegressAddition: Double,
    val grossBonusAddition: Double,
    val grossAdditionsTotal: Double,
    val grossTotal: Double,
    val employeeContributions: Double,
    val taxRelief: Double,
    val taxableBase: Double,
    val tax: Double,
    val netPayBeforeDeductions: Double,
    val netSeniorityBonus: Double,
    val mealAllowanceNet: Double,
    val mealSpentNet: Double,
    val mealCoveredNet: Double,
    val netRegressAddition: Double,
    val netBonusAddition: Double,
    val unionDuesNet: Double,
    val netDeductionsTotal: Double,
    val displayPay: Double,
    val hourlyValue: Double? = null
)

data class MonthlyReceipt(
    val year: Int,
    val month: Int,
    val salaryMonth: MonthKey,
    val salaryNetPay: Double,
    val regressNetPay: Double,
    val isSalaryAvailable: Boolean = true,
    val transportEligibleDays: Int = 0,
    val transportDailyAmount: Double = CONFIG.pay.transportPerWorkDay
) {
    val paymentMonth: MonthKey
        get() = MonthKey(year, month)

    val payrollMonth: MonthKey
        get() = salaryMonth

    val displayPay: Double
        get() = if (isSalaryAvailable) {
            salaryNetPay.sanitizeMoney() + regressNetPay.sanitizeMoney()
        } else {
            0.0
        }

    val transportAmount: Double
        get() = if (isSalaryAvailable) {
            calculateTransportAmount(transportEligibleDays, transportDailyAmount)
        } else {
            0.0
        }

    val totalReceivedThisMonth: Double
        get() = calculateTotalReceivedThisMonth(displayPay, transportAmount)
}

data class MonthlyGrossAdditions(
    val year: Int,
    val month: Int,
    val mealGross: Double = 0.0,
    val regressGross: Double = 0.0,
    val bonusGross: Double = 0.0,
    val mealSpentNet: Double? = null,
    val bonusNet: Double = 0.0
) {
    val totalGross: Double
        get() = mealGross.sanitizeMoney() + regressGross.sanitizeMoney() + bonusGross.sanitizeMoney()

    val hasAny: Boolean
        get() = totalGross > 0.0 ||
            mealSpentNet != null ||
            bonusNet.sanitizeMoney() > 0.0

    fun sanitizedFor(year: Int = this.year, month: Int = this.month): MonthlyGrossAdditions {
        return MonthlyGrossAdditions(
            year = year,
            month = month,
            mealGross = mealGross.sanitizeMoney(),
            regressGross = regressGross.sanitizeMoney(),
            bonusGross = bonusGross.sanitizeMoney(),
            mealSpentNet = mealSpentNet?.sanitizeMoney(),
            bonusNet = bonusNet.sanitizeMoney()
        )
    }
}

enum class PayBaseSelectionMode {
    MANUAL,
    KNOWN_BASE,
    CALCULATED_PREVIOUS_12_MONTHS,
    DEFAULT_VALUES
}

data class PayBaseDefaults(
    val vacationHourlyBase: Double = 918.81,
    val paidHolidayHourlyBase: Double = 918.81,
    val sickHourlyBase: Double = 918.81
)

data class KnownPayBaseAnchor(
    val month: MonthKey,
    val grossHourlyBase: Double
)

data class MonthlyPayBases(
    val year: Int,
    val month: Int,
    val vacationHourlyBase: Double? = null,
    val paidHolidayHourlyBase: Double? = null,
    val sickHourlyBase: Double? = null,
    val selectionMode: PayBaseSelectionMode? = null
) {
    companion object {
        fun fromUnifiedPayBase(
            year: Int,
            month: Int,
            hourlyBase: Double
        ): MonthlyPayBases {
            return MonthlyPayBases(
                year = year,
                month = month,
                vacationHourlyBase = hourlyBase,
                paidHolidayHourlyBase = hourlyBase,
                sickHourlyBase = hourlyBase,
                selectionMode = PayBaseSelectionMode.MANUAL
            )
        }
    }
}

fun effectivePayBaseSelectionMode(
    bases: MonthlyPayBases?,
    defaultMode: PayBaseSelectionMode = PayBaseSelectionMode.CALCULATED_PREVIOUS_12_MONTHS
): PayBaseSelectionMode {
    return when {
        bases?.selectionMode == PayBaseSelectionMode.MANUAL -> PayBaseSelectionMode.MANUAL
        bases?.selectionMode == PayBaseSelectionMode.CALCULATED_PREVIOUS_12_MONTHS -> {
            PayBaseSelectionMode.CALCULATED_PREVIOUS_12_MONTHS
        }
        bases?.selectionMode == PayBaseSelectionMode.KNOWN_BASE -> PayBaseSelectionMode.KNOWN_BASE
        bases?.selectionMode == PayBaseSelectionMode.DEFAULT_VALUES -> {
            PayBaseSelectionMode.DEFAULT_VALUES
        }
        bases?.hasAnyHourlyBase() == true -> PayBaseSelectionMode.MANUAL
        defaultMode == PayBaseSelectionMode.MANUAL -> PayBaseSelectionMode.MANUAL
        defaultMode == PayBaseSelectionMode.KNOWN_BASE -> PayBaseSelectionMode.KNOWN_BASE
        defaultMode == PayBaseSelectionMode.DEFAULT_VALUES -> PayBaseSelectionMode.DEFAULT_VALUES
        else -> PayBaseSelectionMode.CALCULATED_PREVIOUS_12_MONTHS
    }
}

enum class PayBaseMonthSource {
    KNOWN_BASE,
    CARRY_FORWARD,
    REAL,
    SIMULATED
}

enum class PayBaseCalculationSource {
    REAL_12_MONTHS,
    MANUAL_ANCHOR,
    SIMULATED,
    MIXED_REAL_AND_SIMULATED
}

data class PayBaseAverageOptions(
    val includeMealAllowanceInAverage: Boolean = false,
    val includeRegressInAverage: Boolean = false
)

data class RollingPayBaseMonth(
    val year: Int,
    val month: Int,
    val grossForAverage: Double,
    val hoursForAverage: Int,
    // Minutna preciznost za prosek: gross pokriva i minute preko celih sati,
    // pa i imenilac mora u minutima — inače se osnovica sistematski naduvava.
    val minutesForAverage: Int = hoursForAverage * MINUTES_PER_HOUR,
    val source: PayBaseMonthSource = PayBaseMonthSource.REAL,
    val excludedCompensationGross: Double = 0.0,
    val excludedCompensationHours: Int = 0,
    val includedRegularGross: Double = grossForAverage,
    val includedOvertimeGross: Double = 0.0,
    val includedNightPremiumGross: Double = 0.0,
    val includedHolidayWorkedGross: Double = 0.0,
    val includedSundayPremiumGross: Double = 0.0,
    val includedSeniorityGross: Double = 0.0,
    val includedMealAllowanceGross: Double = 0.0,
    val includedRegressGross: Double = 0.0,
    val includedOtherWageGross: Double = 0.0,
    val includeMealAllowanceInAverage: Boolean = false,
    val includeRegressInAverage: Boolean = false
)

data class RollingPayBaseCalculation(
    val hourlyBase: Double,
    val grossTotal: Double,
    val hoursTotal: Int,
    val months: List<RollingPayBaseMonth>
)

data class PayBaseCalculationDebugTrace(
    val targetMonth: MonthKey,
    val calculationPeriodStart: MonthKey?,
    val calculationPeriodEnd: MonthKey?,
    val includedMonths: List<MonthKey>,
    val months: List<RollingPayBaseMonth>,
    val totalIncludedGross: Double,
    val totalIncludedHours: Int,
    val calculatedAverageGrossHourlyBase: Double,
    val source: PayBaseCalculationSource,
    val anchorMonth: MonthKey? = null,
    val anchorGrossHourlyBase: Double? = null,
    val differenceFromCalculated: Double? = null,
    val percentageDifference: Double? = null
)

data class AnnualNetPayMonth(
    val netPay: Double,
    val netPayWithoutRegress: Double,
    val netPayWithoutThirdShift: Double
)

data class AnnualNetPayAverages(
    val averagePay: Double,
    val averagePayWithoutRegress: Double,
    val averagePayWithoutThirdShift: Double,
    val monthCount: Int
)

data class RecentNetPayMonth(
    val key: MonthKey,
    val netPay: Double?
)

data class RecentNetPayAverage(
    val months: List<RecentNetPayMonth>
) {
    val monthKeys: List<MonthKey>
        get() = months.map { it.key }

    val missingMonthKeys: List<MonthKey>
        get() = months.filter { it.netPay == null }.map { it.key }

    val averageNetPay: Double?
        get() {
            if (months.size != RECENT_NET_PAY_MONTH_COUNT || missingMonthKeys.isNotEmpty()) return null
            return months.sumOf { it.netPay?.sanitizeMoney() ?: 0.0 } / RECENT_NET_PAY_MONTH_COUNT.toDouble()
        }
}

enum class PayBaseSource {
    CURRENT_MONTH,
    LAST_KNOWN,
    DEFAULT,
    KNOWN_BASE,
    CALCULATED_PREVIOUS_12_MONTHS
}

data class ResolvedPayBase(
    val value: Double,
    val source: PayBaseSource,
    val sourceYear: Int? = null,
    val sourceMonth: Int? = null,
    val calculation: RollingPayBaseCalculation? = null
)

data class ResolvedPayBases(
    val vacationHourlyBase: ResolvedPayBase,
    val paidHolidayHourlyBase: ResolvedPayBase,
    val sickHourlyBase: ResolvedPayBase
) {
    companion object {
        fun fromDefaults(defaults: PayBaseDefaults = PayBaseDefaults()): ResolvedPayBases {
            return ResolvedPayBases(
                vacationHourlyBase = ResolvedPayBase(defaults.vacationHourlyBase, PayBaseSource.DEFAULT),
                paidHolidayHourlyBase = ResolvedPayBase(defaults.paidHolidayHourlyBase, PayBaseSource.DEFAULT),
                sickHourlyBase = ResolvedPayBase(defaults.sickHourlyBase, PayBaseSource.DEFAULT)
            )
        }

        fun fromCalculation(
            calculation: RollingPayBaseCalculation,
            source: PayBaseSource = PayBaseSource.CALCULATED_PREVIOUS_12_MONTHS
        ): ResolvedPayBases {
            val sourceMonth = if (source == PayBaseSource.KNOWN_BASE) {
                calculation.months
                    .firstOrNull { it.source == PayBaseMonthSource.KNOWN_BASE }
                    ?.let { MonthKey(it.year, it.month) }
            } else {
                null
            }
            val resolved = ResolvedPayBase(
                value = calculation.hourlyBase,
                source = source,
                sourceYear = sourceMonth?.year,
                sourceMonth = sourceMonth?.month,
                calculation = calculation
            )
            return ResolvedPayBases(
                vacationHourlyBase = resolved,
                paidHolidayHourlyBase = resolved,
                sickHourlyBase = resolved
            )
        }
    }
}

fun previousTwelveMonthKeys(year: Int, month: Int): List<MonthKey> {
    if (year <= 0 || month !in 1..12) return emptyList()

    val target = try {
        YearMonth.of(year, month)
    } catch (_: DateTimeException) {
        return emptyList()
    }
    if (target.minusMonths(12).year <= 0) return emptyList()

    return (12 downTo 1).map { offset ->
        val previous = target.minusMonths(offset.toLong())
        MonthKey(previous.year, previous.monthValue)
    }
}

fun previousMonthKey(year: Int, month: Int): MonthKey? {
    if (year <= 0 || month !in 1..12) return null
    val target = try {
        YearMonth.of(year, month)
    } catch (_: DateTimeException) {
        return null
    }
    val previous = target.minusMonths(1)
    if (previous.year <= 0) return null
    return MonthKey(previous.year, previous.monthValue)
}

fun getPayrollMonth(selectedCalendarMonth: MonthKey): MonthKey {
    return previousMonthKey(selectedCalendarMonth.year, selectedCalendarMonth.month) ?: selectedCalendarMonth
}

fun calculateTransportEligibleDays(monthRecords: List<DayRecord>): Int {
    return monthRecords.count { record ->
        record.shift in Shift.workShifts() && record.totalHours >= MIN_TRANSPORT_WORK_HOURS
    }
}

fun calculateTransportAmount(eligibleDays: Int, dailyTransportAmount: Double): Double {
    if (eligibleDays <= 0 || !dailyTransportAmount.isFinite() || dailyTransportAmount <= 0.0) return 0.0
    return eligibleDays * dailyTransportAmount
}

fun calculateTotalReceivedThisMonth(netSalary: Double, transportAmount: Double): Double {
    return netSalary.sanitizeMoney() + transportAmount.sanitizeMoney()
}

fun calculateMonthlyReceipt(
    year: Int,
    month: Int,
    salaryMonth: MonthKey,
    previousMonthSalaryNet: Double,
    currentMonthRegressNet: Double,
    isSalaryAvailable: Boolean = true,
    transportEligibleDays: Int = 0,
    transportDailyAmount: Double = CONFIG.pay.transportPerWorkDay
): MonthlyReceipt {
    return MonthlyReceipt(
        year = year,
        month = month,
        salaryMonth = salaryMonth,
        salaryNetPay = previousMonthSalaryNet.sanitizeMoney(),
        regressNetPay = if (isSalaryAvailable) currentMonthRegressNet.sanitizeMoney() else 0.0,
        isSalaryAvailable = isSalaryAvailable,
        transportEligibleDays = if (isSalaryAvailable) transportEligibleDays.coerceAtLeast(0) else 0,
        transportDailyAmount = transportDailyAmount.sanitizeMoney()
    )
}

fun recentNetPayAverageMonthKeys(year: Int, month: Int, asOfDate: LocalDate): List<MonthKey> {
    if (year <= 0 || month !in 1..12) return emptyList()
    val target = try {
        YearMonth.of(year, month)
    } catch (_: DateTimeException) {
        return emptyList()
    }
    val lastIncludedOffset = if (asOfDate.dayOfMonth <= PAYROLL_FINAL_CUTOFF_DAY) 2L else 1L
    val lastIncluded = target.minusMonths(lastIncludedOffset)
    val months = ((RECENT_NET_PAY_MONTH_COUNT - 1) downTo 0).map { offset ->
        val salaryMonth = lastIncluded.minusMonths(offset.toLong())
        MonthKey(salaryMonth.year, salaryMonth.monthValue)
    }
    return if (months.any { it.year <= 0 }) emptyList() else months
}

fun calculateRecentNetPayAverage(months: List<RecentNetPayMonth>): RecentNetPayAverage {
    return RecentNetPayAverage(months)
}

fun calculateRollingPayBaseCalculation(
    months: List<RollingPayBaseMonth>
): RollingPayBaseCalculation? {
    return calculatePayBaseCalculation(months, requiredMonthCount = 12, requireKnownAnchor = false)
}

fun calculateKnownPayBaseCalculation(
    months: List<RollingPayBaseMonth>
): RollingPayBaseCalculation? {
    if (months.any { it.source == PayBaseMonthSource.SIMULATED }) return null
    return calculatePayBaseCalculation(months, requiredMonthCount = null, requireKnownAnchor = true)
}

private fun calculatePayBaseCalculation(
    months: List<RollingPayBaseMonth>,
    requiredMonthCount: Int?,
    requireKnownAnchor: Boolean
): RollingPayBaseCalculation? {
    if (requiredMonthCount != null && months.size != requiredMonthCount) return null
    if (months.isEmpty()) return null
    if (requireKnownAnchor && months.first().source != PayBaseMonthSource.KNOWN_BASE) return null
    if (months.any { it.year <= 0 || it.month !in 1..12 }) return null
    if (months.any { !it.grossForAverage.isFinite() || it.grossForAverage < 0.0 }) return null
    if (months.any { it.hoursForAverage < 0 || it.minutesForAverage < 0 }) return null

    val grossTotal = months.sumOf { it.grossForAverage }
    val hoursTotal = months.sumOf { it.hoursForAverage }
    val minutesTotal = months.sumOf { it.minutesForAverage }
    if (grossTotal <= 0.0 || minutesTotal <= 0) return null

    return RollingPayBaseCalculation(
        hourlyBase = grossTotal / (minutesTotal.toDouble() / MINUTES_PER_HOUR),
        grossTotal = grossTotal,
        hoursTotal = hoursTotal,
        months = months
    )
}

fun calculateAnnualNetPayAverages(months: List<AnnualNetPayMonth>): AnnualNetPayAverages {
    if (months.isEmpty()) {
        return AnnualNetPayAverages(
            averagePay = 0.0,
            averagePayWithoutRegress = 0.0,
            averagePayWithoutThirdShift = 0.0,
            monthCount = 0
        )
    }

    val monthCount = months.size
    return AnnualNetPayAverages(
        averagePay = months.sumOf { it.netPay.sanitizeMoney() } / monthCount,
        averagePayWithoutRegress = months.sumOf { it.netPayWithoutRegress.sanitizeMoney() } / monthCount,
        averagePayWithoutThirdShift = months.sumOf { it.netPayWithoutThirdShift.sanitizeMoney() } / monthCount,
        monthCount = monthCount
    )
}

fun resolvePayBasesForMonth(
    year: Int,
    month: Int,
    monthlyBases: Map<MonthKey, MonthlyPayBases>,
    defaults: PayBaseDefaults = PayBaseDefaults(),
    calculatedBase: RollingPayBaseCalculation? = null,
    defaultSelectionMode: PayBaseSelectionMode = PayBaseSelectionMode.CALCULATED_PREVIOUS_12_MONTHS
): ResolvedPayBases {
    val target = MonthKey(year, month)
    return when (effectivePayBaseSelectionMode(monthlyBases[target], defaultSelectionMode)) {
        PayBaseSelectionMode.CALCULATED_PREVIOUS_12_MONTHS -> {
            calculatedBase?.let(ResolvedPayBases::fromCalculation)
                ?: ResolvedPayBases.fromDefaults(defaults)
        }
        PayBaseSelectionMode.KNOWN_BASE -> {
            calculatedBase?.let { ResolvedPayBases.fromCalculation(it, PayBaseSource.KNOWN_BASE) }
                ?: ResolvedPayBases.fromDefaults(defaults)
        }
        PayBaseSelectionMode.DEFAULT_VALUES -> ResolvedPayBases.fromDefaults(defaults)
        PayBaseSelectionMode.MANUAL -> ResolvedPayBases(
            vacationHourlyBase = resolvePayBaseForMonth(
                year = year,
                month = month,
                monthlyBases = monthlyBases,
                defaultValue = defaults.vacationHourlyBase,
                valueSelector = { it.vacationHourlyBase }
            ),
            paidHolidayHourlyBase = resolvePayBaseForMonth(
                year = year,
                month = month,
                monthlyBases = monthlyBases,
                defaultValue = defaults.paidHolidayHourlyBase,
                valueSelector = { it.paidHolidayHourlyBase }
            ),
            sickHourlyBase = resolvePayBaseForMonth(
                year = year,
                month = month,
                monthlyBases = monthlyBases,
                defaultValue = defaults.sickHourlyBase,
                valueSelector = { it.sickHourlyBase }
            )
        )
    }
}

private fun MonthlyPayBases.hasAnyHourlyBase(): Boolean {
    return vacationHourlyBase.isPositiveFinite() ||
        paidHolidayHourlyBase.isPositiveFinite() ||
        sickHourlyBase.isPositiveFinite()
}

private fun resolvePayBaseForMonth(
    year: Int,
    month: Int,
    monthlyBases: Map<MonthKey, MonthlyPayBases>,
    defaultValue: Double,
    valueSelector: (MonthlyPayBases) -> Double?
): ResolvedPayBase {
    val target = MonthKey(year, month)
    val currentMonthValue = monthlyBases[target]?.let(valueSelector)
    if (currentMonthValue.isPositiveFinite()) {
        return ResolvedPayBase(
            value = currentMonthValue!!,
            source = PayBaseSource.CURRENT_MONTH,
            sourceYear = year,
            sourceMonth = month
        )
    }

    val lastKnown = monthlyBases
        .asSequence()
        .filter { (key, bases) -> key < target && valueSelector(bases).isPositiveFinite() }
        .maxByOrNull { (key, _) -> key }

    if (lastKnown != null) {
        return ResolvedPayBase(
            value = valueSelector(lastKnown.value)!!,
            source = PayBaseSource.LAST_KNOWN,
            sourceYear = lastKnown.key.year,
            sourceMonth = lastKnown.key.month
        )
    }

    return ResolvedPayBase(
        value = defaultValue,
        source = PayBaseSource.DEFAULT
    )
}
