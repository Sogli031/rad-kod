package com.rad.shifttracker

import java.time.LocalDate
import java.time.YearMonth
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private const val MINUTES_PER_HOUR = 60

@Suppress("LargeClass")
class PayCalculator(private val config: PayConfig = CONFIG.pay) {
    private fun defaultPayBases(): ResolvedPayBases {
        return ResolvedPayBases.fromDefaults(
            PayBaseDefaults(
                vacationHourlyBase = config.vacationPayBaseHourly,
                paidHolidayHourlyBase = config.paidHolidayPayBaseHourly,
                sickHourlyBase = config.sickPayBaseHourly
            )
        )
    }

    private fun isCalendarWeekendOvertime(weekday: Weekday, brigadeType: BrigadeType): Boolean {
        return brigadeType == BrigadeType.TROBRIGADA && weekday.isWeekend
    }

    private fun isAutomaticWeekendOvertime(record: DayRecord, brigadeType: BrigadeType): Boolean {
        return !record.workedOnHoliday && isCalendarWeekendOvertime(record.weekday, brigadeType)
    }

    @Suppress("UnusedParameter")
    fun getSundayHoursForShift(
        weekday: Weekday,
        shift: Shift,
        shiftHours: Int,
        brigadeType: BrigadeType
    ): Int {
        if (shiftHours <= 0 || shift == Shift.SICK || shift == Shift.VACATION) return 0

        if (weekday == Weekday.SATURDAY) {
            if (shift == Shift.THIRD) {
                return max(0, shiftHours - 2)
            }
            return 0
        }

        if (weekday == Weekday.SUNDAY) {
            if (shift == Shift.FIRST || shift == Shift.SECOND) return shiftHours
            if (shift == Shift.THIRD) return min(shiftHours, 2)
        }

        return 0
    }

    fun getSundayHours(record: DayRecord, brigadeType: BrigadeType): Int {
        return paidWorkSegments(record).sumOf { (shift, hours) ->
            getSundayHoursForShift(record.weekday, shift, hours, brigadeType)
        }
    }

    fun getOvertimeHours(record: DayRecord, brigadeType: BrigadeType): Int {
        if (record.shift == Shift.SICK || record.shift == Shift.OFF || record.shift == Shift.VACATION) {
            return 0
        }
        if (brigadeType == BrigadeType.CETVOROBRIGADA) return 0
        if (record.workedOnHoliday) return 0

        val paidMinutes = paidWorkMinuteSegments(record).sumOf { (_, minutes) -> minutes }
        if (paidMinutes <= 0) return 0
        if (isAutomaticWeekendOvertime(record, brigadeType)) return paidMinutes / MINUTES_PER_HOUR

        val overtimeMinutes = (paidMinutes - config.defaultHours * MINUTES_PER_HOUR).coerceAtLeast(0)
        return overtimeMinutes / MINUTES_PER_HOUR
    }

    fun getFoodAllowance(record: DayRecord): Double {
        if (record.shift == Shift.SICK || record.shift == Shift.OFF || record.shift == Shift.VACATION) {
            return 0.0
        }

        if (record.totalHours <= 0) return 0.0
        if (record.totalHours > config.defaultHours + 2) return config.foodPerDay * 2

        return config.foodPerDay
    }

    fun getFoodAllowanceForAccountingMonth(record: DayRecord, month: MonthKey): Double {
        if (foodAccountingMonth(record.date, record.shift) != month) return 0.0
        return getFoodAllowance(record)
    }

    fun calculateFoodAllowanceForAccountingMonth(records: List<DayRecord>, month: MonthKey): Double {
        return records.sumOf { record -> getFoodAllowanceForAccountingMonth(record, month) }
    }

    fun getNightHours(record: DayRecord): Int {
        return paidWorkSegments(record)
            .filter { (shift, _) -> shift == Shift.THIRD }
            .sumOf { (_, hours) -> hours }
    }

    private fun premiumBonus(multiplier: Double): Double {
        return max(0.0, multiplier - 1.0)
    }

    private fun shiftBonuses(shift: Shift): List<Double> {
        return if (shift == Shift.THIRD) listOf(premiumBonus(config.shift3Premium)) else emptyList()
    }

    private fun difficultWorkConditionsRate(): Double {
        val percent = config.difficultWorkConditionsPercent
        return if (percent.isFinite() && percent > 0.0) percent.coerceAtMost(100.0) / 100.0 else 0.0
    }

    private fun paidWorkSegments(record: DayRecord): List<Pair<Shift, Int>> {
        if (record.shift !in Shift.workShifts() || record.workHours <= 0) return emptyList()

        val segments = mutableListOf<Pair<Shift, Int>>()
        val primaryPaidHours = record.paidPrimaryWorkMinutes / MINUTES_PER_HOUR
        if (primaryPaidHours > 0) {
            segments.add(record.shift to primaryPaidHours)
        }
        val shift2 = record.shift2
        val secondShiftPaidHours = record.paidSecondShiftMinutes / MINUTES_PER_HOUR
        if (secondShiftPaidHours > 0 && shift2 != null) {
            segments.add(shift2 to secondShiftPaidHours)
        }
        return segments
    }

    private fun paidWorkMinuteSegments(record: DayRecord): List<Pair<Shift, Int>> {
        if (record.shift !in Shift.workShifts() || record.effectiveRegularWorkMinutes <= 0) return emptyList()

        val segments = mutableListOf<Pair<Shift, Int>>()
        if (record.paidPrimaryWorkMinutes > 0) {
            segments.add(record.shift to record.paidPrimaryWorkMinutes)
        }
        val shift2 = record.shift2
        if (record.paidSecondShiftMinutes > 0 && shift2 != null) {
            segments.add(shift2 to record.paidSecondShiftMinutes)
        }
        return segments
    }

    private fun calculateDailyDifficultWorkConditionsBonus(
        record: DayRecord,
        baseRate: Double
    ): Double {
        return paidWorkMinuteSegments(record).sumOf { (_, minutes) ->
            calculateDifficultWorkConditionsBonusMinutes(minutes, baseRate, difficultWorkConditionsRate())
        }
    }

    internal fun calculateMonthlyOvertimeMinutesByDay(
        records: List<DayRecord>,
        year: Int,
        month: Int,
        brigadeType: BrigadeType
    ): Map<DayKey, Int> {
        if (brigadeType != BrigadeType.CETVOROBRIGADA) return emptyMap()
        var regularMinutesRemaining = calculateMonthlyFundHours(year, month, config.defaultHours) * MINUTES_PER_HOUR
        if (regularMinutesRemaining <= 0) return emptyMap()

        val overtimeByDay = mutableMapOf<DayKey, Int>()
        for (record in records.sortedWith(compareBy<DayRecord> { it.year }.thenBy { it.month }.thenBy { it.day })) {
            val recordPaidMinutes = paidWorkMinuteSegments(record).sumOf { (_, minutes) -> minutes }
            if (recordPaidMinutes <= 0) continue

            val regularMinutes = min(recordPaidMinutes, regularMinutesRemaining)
            val overtimeMinutes = recordPaidMinutes - regularMinutes
            regularMinutesRemaining -= regularMinutes
            if (overtimeMinutes > 0) {
                overtimeByDay[record.key] = overtimeMinutes
            }
        }

        return overtimeByDay
    }

    private fun monthlyOvertimeMinutesForRecord(
        record: DayRecord,
        brigadeType: BrigadeType,
        overtimeByDay: Map<DayKey, Int>
    ): Int? {
        if (record.workedOnHoliday) return 0
        return if (brigadeType == BrigadeType.CETVOROBRIGADA) {
            overtimeByDay[record.key] ?: 0
        } else {
            null
        }
    }

    fun calculateDailyPay(
        record: DayRecord,
        baseRate: Double,
        brigadeType: BrigadeType,
        payBases: ResolvedPayBases = defaultPayBases(),
        monthlyOvertimeMinutes: Int? = null
    ): Double {
        val weekday = record.weekday

        if (record.shift == Shift.OFF) {
            if (record.isHoliday && weekday.isWorkday) {
                return config.defaultHours * payBases.paidHolidayHourlyBase.value
            }
            return 0.0
        }

        if (record.workHours <= 0) return 0.0

        if (record.shift == Shift.SICK) {
            if (weekday.isWorkday) {
                val sickRate = record.sickPayRate ?: config.sickPayRate
                return record.workHours.toDouble() * payBases.sickHourlyBase.value * sickRate
            }
            return 0.0
        }

        if (record.shift == Shift.VACATION) {
            if (weekday.isWorkday) {
                return record.workHours.toDouble() * payBases.vacationHourlyBase.value * config.vacationPayRate
            }
            return 0.0
        }

        val shiftsData = paidWorkMinuteSegments(record)

        var totalPay = 0.0

        if (isAutomaticWeekendOvertime(record, brigadeType)) {
            for ((shift, shiftMinutes) in shiftsData) {
                val bonuses = shiftBonuses(shift).toMutableList()
                bonuses += premiumBonus(config.overtimePremium)
                if (record.isHoliday) {
                    bonuses += config.holidayBonus
                }
                var pay = calculatePayForSegmentMinutes(
                    weekday = weekday,
                    shift = shift,
                    minutes = shiftMinutes,
                    baseRate = baseRate,
                    brigadeType = brigadeType,
                    bonuses = bonuses,
                    sundayBonus = config.sundayBonus
                )
                pay += calculateDifficultWorkConditionsBonusMinutes(
                    shiftMinutes,
                    baseRate,
                    difficultWorkConditionsRate()
                )
                totalPay += pay
            }
        } else {
            val paidWorkMinutes = shiftsData.sumOf { (_, shiftMinutes) -> shiftMinutes }
            val forcedOvertimeMinutes = if (record.workedOnHoliday) {
                0
            } else {
                monthlyOvertimeMinutes?.coerceIn(0, paidWorkMinutes)
            }
            var regularMinutesRemaining = when {
                forcedOvertimeMinutes != null -> paidWorkMinutes - forcedOvertimeMinutes
                brigadeType == BrigadeType.CETVOROBRIGADA -> paidWorkMinutes
                else -> config.defaultHours * MINUTES_PER_HOUR
            }
            for ((shift, shiftMinutes) in shiftsData) {
                val regularBonuses = shiftBonuses(shift).toMutableList()
                if (record.isHoliday) {
                    regularBonuses += config.holidayBonus
                }
                val overtimeBonuses = regularBonuses + premiumBonus(config.overtimePremium)
                val regularMinutes = min(shiftMinutes, regularMinutesRemaining)
                val overtimeMinutes = shiftMinutes - regularMinutes
                regularMinutesRemaining -= regularMinutes

                var pay = calculatePayForSegmentMinutes(
                    weekday,
                    shift,
                    regularMinutes,
                    baseRate,
                    brigadeType,
                    regularBonuses,
                    config.sundayBonus
                )
                pay += calculatePayForSegmentMinutes(
                    weekday,
                    shift,
                    overtimeMinutes,
                    baseRate,
                    brigadeType,
                    overtimeBonuses,
                    config.sundayBonus
                )
                pay += calculateDifficultWorkConditionsBonusMinutes(
                    shiftMinutes,
                    baseRate,
                    difficultWorkConditionsRate()
                )
                totalPay += pay
            }
        }

        return totalPay
    }

    fun calculateDailySeniorityBonus(
        record: DayRecord,
        baseRate: Double,
        yearsOfService: Int,
        brigadeType: BrigadeType = BrigadeType.TROBRIGADA
    ): Double {
        val safeYears = yearsOfService.coerceAtLeast(0)
        if (safeYears == 0) return 0.0

        val eligibleMinutes = seniorityEligibleWorkMinutes(
            record = record,
            brigadeType = brigadeType,
            monthlyOvertimeMinutes = null
        )
        return eligibleMinutes.toDouble() / MINUTES_PER_HOUR *
            baseRate *
            safeYears *
            config.minuliRadPerYear
    }

    fun calculateMonthlyPayDetails(
        records: List<DayRecord>,
        summary: MonthSummary,
        baseRate: Double,
        netBreakdown: MonthlyNetPayBreakdown,
        brigadeType: BrigadeType = BrigadeType.TROBRIGADA,
        payBases: ResolvedPayBases = defaultPayBases()
    ): MonthlyPayDetails {
        val details = MutablePayDetails()
        val recordedDays = records.map { it.day }.toSet()
        val overtimeByDay = calculateMonthlyOvertimeMinutesByDay(
            records = records,
            year = summary.year,
            month = summary.month,
            brigadeType = brigadeType
        )

        for (record in records) {
            accumulateRecordPayDetails(
                record = record,
                baseRate = baseRate,
                brigadeType = brigadeType,
                payBases = payBases,
                details = details,
                monthlyOvertimeMinutes = monthlyOvertimeMinutesForRecord(record, brigadeType, overtimeByDay)
            )
        }

        val unrecordedHolidayPay = if (records.isNotEmpty()) {
            calculateUnrecordedHolidayPay(
                summary.year,
                summary.month,
                recordedDays,
                baseRate,
                brigadeType,
                payBases
            )
        } else {
            0.0
        }
        if (unrecordedHolidayPay > 0.0) {
            val days = (unrecordedHolidayPay / (payBases.paidHolidayHourlyBase.value * config.defaultHours))
                .roundToInt()
            details.grossPaidHoliday += unrecordedHolidayPay
            details.paidHolidayMinutes += config.defaultHours * MINUTES_PER_HOUR * days
        }

        if (summary.compTimeDeficitMinutes > 0) {
            val coveredDeficitMin = summary.compTimeDeficitMinutes -
                maxOf(0, -summary.compTimeEndingMinutes)
            if (coveredDeficitMin > 0) {
                details.coveredDeficitMinutes += coveredDeficitMin
                details.grossCoveredDeficitPay += coveredDeficitMin.toDouble() / MINUTES_PER_HOUR * baseRate
            }
            val uncoveredDeficitMin = min(
                summary.compTimeDeficitMinutes,
                max(0, -summary.compTimeEndingMinutes)
            )
            if (uncoveredDeficitMin > 0) {
                details.uncoveredDeficitMinutes += uncoveredDeficitMin
                details.grossUncoveredDeficitLoss += uncoveredDeficitMin.toDouble() / MINUTES_PER_HOUR * baseRate
            }
        }

        val grossWorkBeforeSeniority = details.grossTotalBeforeSeniority
        val grossBeforeSeniority = grossWorkBeforeSeniority + netBreakdown.grossAdditionsTotal
        val netBeforeSeniority = max(0.0, netBreakdown.netPayBeforeDeductions - netBreakdown.netSeniorityBonus)

        return MonthlyPayDetails(
            totalPaidHours = details.totalPaidMinutes / MINUTES_PER_HOUR,
            paidWorkHours = details.basePaidHours,
            nightHours = details.nightHours,
            overtimeHours = details.overtimeHours,
            sundayHours = details.sundayHours,
            sickHours = details.sickHours,
            vacationHours = details.vacationHours,
            paidHolidayHours = details.paidHolidayHours,
            coveredDeficitHours = details.coveredDeficitHours,
            uncoveredDeficitHours = details.uncoveredDeficitHours,
            grossBaseRegular = details.grossBaseRegular,
            grossNightPremium = details.grossNightPremium,
            grossOvertimePremium = details.grossOvertimePremium,
            grossSundayPremium = details.grossSundayPremium,
            grossHolidayPremium = details.grossHolidayPremium,
            grossDifficultWorkConditions = details.grossDifficultWorkConditions,
            grossSickPay = details.grossSickPay,
            grossVacationPay = details.grossVacationPay,
            grossPaidHoliday = details.grossPaidHoliday,
            grossCoveredDeficitPay = details.grossCoveredDeficitPay,
            grossUncoveredDeficitLoss = details.grossUncoveredDeficitLoss,
            grossOvertimeFull = details.grossOvertimeFull,
            grossSeniorityBonus = netBreakdown.grossSeniorityBonus,
            sickPayBaseHourly = payBases.sickHourlyBase.value,
            vacationPayBase = payBases.vacationHourlyBase,
            paidHolidayPayBase = payBases.paidHolidayHourlyBase,
            sickPayBase = payBases.sickHourlyBase,
            grossMealAddition = netBreakdown.grossMealAddition,
            grossRegressAddition = netBreakdown.grossRegressAddition,
            grossBonusAddition = netBreakdown.grossBonusAddition,
            grossTotalBeforeSeniority = grossBeforeSeniority,
            grossTotalWithSeniority = grossBeforeSeniority + netBreakdown.grossSeniorityBonus,
            netTotalBeforeSeniority = netBeforeSeniority,
            netSeniorityBonus = netBreakdown.netSeniorityBonus,
            netUncoveredDeficitLoss = details.uncoveredDeficitMinutes.toDouble() / MINUTES_PER_HOUR *
                netBreakdown.informativeNetHourlyBase,
            netTotal = netBreakdown.displayPay,
            totalPaidMinutes = details.totalPaidMinutes,
            paidWorkMinutes = details.basePaidMinutes,
            nightMinutes = details.nightMinutes,
            overtimeMinutes = details.overtimeMinutes,
            sundayMinutes = details.sundayMinutes,
            sickMinutes = details.sickMinutes,
            vacationMinutes = details.vacationMinutes,
            paidHolidayMinutes = details.paidHolidayMinutes,
            coveredDeficitMinutes = details.coveredDeficitMinutes,
            uncoveredDeficitMinutes = details.uncoveredDeficitMinutes
        )
    }

    private fun accumulateRecordPayDetails(
        record: DayRecord,
        baseRate: Double,
        brigadeType: BrigadeType,
        payBases: ResolvedPayBases,
        details: MutablePayDetails,
        monthlyOvertimeMinutes: Int? = null
    ) {
        val weekday = record.weekday

        if (record.shift == Shift.OFF) {
            if (record.isHoliday && weekday.isWorkday) {
                details.paidHolidayMinutes += config.defaultHours * MINUTES_PER_HOUR
                details.grossPaidHoliday += config.defaultHours * payBases.paidHolidayHourlyBase.value
            }
            return
        }

        if (record.workHours <= 0) return

        if (record.shift == Shift.SICK) {
            if (weekday.isWorkday) {
                val sickRate = record.sickPayRate ?: config.sickPayRate
                details.sickMinutes += record.workHours * MINUTES_PER_HOUR
                details.grossSickPay += record.workHours.toDouble() * payBases.sickHourlyBase.value * sickRate
            }
            return
        }

        if (record.shift == Shift.VACATION) {
            if (weekday.isWorkday) {
                details.vacationMinutes += record.workHours * MINUTES_PER_HOUR
                details.grossVacationPay += record.workHours.toDouble() *
                    payBases.vacationHourlyBase.value *
                    config.vacationPayRate
            }
            return
        }

        val shiftsData = paidWorkMinuteSegments(record)

        if (isAutomaticWeekendOvertime(record, brigadeType)) {
            for ((shift, shiftMinutes) in shiftsData) {
                accumulateWorkSegment(
                    weekday = weekday,
                    shift = shift,
                    minutes = shiftMinutes,
                    baseRate = baseRate,
                    isOvertime = true,
                    isHoliday = record.isHoliday,
                    brigadeType = brigadeType,
                    details = details
                )
            }
        } else {
            val paidWorkMinutes = shiftsData.sumOf { (_, shiftMinutes) -> shiftMinutes }
            val forcedOvertimeMinutes = if (record.workedOnHoliday) {
                0
            } else {
                monthlyOvertimeMinutes?.coerceIn(0, paidWorkMinutes)
            }
            var regularMinutesRemaining = when {
                forcedOvertimeMinutes != null -> paidWorkMinutes - forcedOvertimeMinutes
                brigadeType == BrigadeType.CETVOROBRIGADA -> paidWorkMinutes
                else -> config.defaultHours * MINUTES_PER_HOUR
            }
            for ((shift, shiftMinutes) in shiftsData) {
                val regularMinutes = min(shiftMinutes, regularMinutesRemaining)
                val overtimeMinutes = shiftMinutes - regularMinutes
                regularMinutesRemaining -= regularMinutes

                accumulateWorkSegment(
                    weekday = weekday,
                    shift = shift,
                    minutes = regularMinutes,
                    baseRate = baseRate,
                    isOvertime = false,
                    isHoliday = record.isHoliday,
                    brigadeType = brigadeType,
                    details = details
                )
                accumulateWorkSegment(
                    weekday = weekday,
                    shift = shift,
                    minutes = overtimeMinutes,
                    baseRate = baseRate,
                    isOvertime = true,
                    isHoliday = record.isHoliday,
                    brigadeType = brigadeType,
                    details = details
                )
            }
        }
    }

    private fun accumulateWorkSegment(
        weekday: Weekday,
        shift: Shift,
        minutes: Int,
        baseRate: Double,
        isOvertime: Boolean,
        isHoliday: Boolean,
        brigadeType: BrigadeType,
        details: MutablePayDetails
    ) {
        if (minutes <= 0) return
        val safeSundayMinutes = getSundayMinutesForShift(weekday, shift, minutes, brigadeType).coerceIn(0, minutes)
        val paidHours = minutes.toDouble() / MINUTES_PER_HOUR
        val basePay = paidHours * baseRate
        details.basePaidMinutes += minutes
        details.grossBaseRegular += basePay
        // Pun bruto pripisan prekovremenim minutima (osnovica + svi dodaci na njih);
        // koristi se za neto efekat prekovremenog (kartica "Prekovremeno") minutno precizno.
        var overtimeSegmentGross = if (isOvertime) basePay else 0.0

        if (shift == Shift.THIRD) {
            val nightPremium = basePay * premiumBonus(config.shift3Premium)
            details.nightMinutes += minutes
            details.grossNightPremium += nightPremium
            if (isOvertime) overtimeSegmentGross += nightPremium
        }
        if (isOvertime) {
            val overtimePremium = basePay * premiumBonus(config.overtimePremium)
            details.overtimeMinutes += minutes
            details.grossOvertimePremium += overtimePremium
            overtimeSegmentGross += overtimePremium
        }
        if (isHoliday) {
            val holidayPremium = basePay * config.holidayBonus
            details.grossHolidayPremium += holidayPremium
            if (isOvertime) overtimeSegmentGross += holidayPremium
        }
        if (safeSundayMinutes > 0) {
            val sundayPremium = safeSundayMinutes.toDouble() / MINUTES_PER_HOUR * baseRate * config.sundayBonus
            details.sundayMinutes += safeSundayMinutes
            details.grossSundayPremium += sundayPremium
            if (isOvertime) overtimeSegmentGross += sundayPremium
        }
        val difficultBonus = calculateDifficultWorkConditionsBonusMinutes(
            minutes,
            baseRate,
            difficultWorkConditionsRate()
        )
        details.grossDifficultWorkConditions += difficultBonus
        if (isOvertime) overtimeSegmentGross += difficultBonus

        details.grossOvertimeFull += overtimeSegmentGross
    }

    private class MutablePayDetails {
        var basePaidMinutes = 0
        var nightMinutes = 0
        var overtimeMinutes = 0
        var sundayMinutes = 0
        var sickMinutes = 0
        var vacationMinutes = 0
        var paidHolidayMinutes = 0
        var coveredDeficitMinutes = 0
        var uncoveredDeficitMinutes = 0
        var grossBaseRegular = 0.0
        var grossNightPremium = 0.0
        var grossOvertimePremium = 0.0
        var grossSundayPremium = 0.0
        var grossHolidayPremium = 0.0
        var grossDifficultWorkConditions = 0.0
        var grossSickPay = 0.0
        var grossVacationPay = 0.0
        var grossPaidHoliday = 0.0
        var grossCoveredDeficitPay = 0.0
        var grossUncoveredDeficitLoss = 0.0
        var grossOvertimeFull = 0.0

        val totalPaidMinutes: Int
            get() = basePaidMinutes + sickMinutes + vacationMinutes + paidHolidayMinutes + coveredDeficitMinutes
        val basePaidHours: Int
            get() = basePaidMinutes / MINUTES_PER_HOUR
        val nightHours: Int
            get() = nightMinutes / MINUTES_PER_HOUR
        val overtimeHours: Int
            get() = overtimeMinutes / MINUTES_PER_HOUR
        val sundayHours: Int
            get() = sundayMinutes / MINUTES_PER_HOUR
        val sickHours: Int
            get() = sickMinutes / MINUTES_PER_HOUR
        val vacationHours: Int
            get() = vacationMinutes / MINUTES_PER_HOUR
        val paidHolidayHours: Int
            get() = paidHolidayMinutes / MINUTES_PER_HOUR
        val coveredDeficitHours: Int
            get() = coveredDeficitMinutes / MINUTES_PER_HOUR
        val uncoveredDeficitHours: Int
            get() = uncoveredDeficitMinutes / MINUTES_PER_HOUR

        val grossTotalBeforeSeniority: Double
            get() = grossBaseRegular + grossNightPremium + grossOvertimePremium + grossSundayPremium +
                grossHolidayPremium + grossDifficultWorkConditions + grossSickPay + grossVacationPay +
                grossPaidHoliday + grossCoveredDeficitPay
    }

    // Existing payroll calculation is kept centralized so the stricter rule catches new complex methods.
    @Suppress("CyclomaticComplexMethod")
    fun calculateMonthSummary(
        records: List<DayRecord>,
        baseRate: Double,
        startingCompBalance: Int = 0,
        yearsOfService: Int = 0,
        brigadeType: BrigadeType = BrigadeType.TROBRIGADA,
        summaryYear: Int = 0,
        summaryMonth: Int = 0,
        payBases: ResolvedPayBases = defaultPayBases(),
        startingCompBalanceMinutes: Int? = null
    ): MonthSummary {
        // Koristi prosledjeni year/month ili iz prvog zapisa
        val year = if (summaryYear > 0) summaryYear else records.firstOrNull()?.year ?: 0
        val month = if (summaryMonth > 0) summaryMonth else records.firstOrNull()?.month ?: 0

        if (year == 0 || month == 0) return MonthSummary(0, 0)

        val summary = MonthSummary(year = year, month = month)
        val startingMinutes = startingCompBalanceMinutes ?: startingCompBalance * MINUTES_PER_HOUR
        summary.compTimeStarting = startingMinutes / MINUTES_PER_HOUR
        summary.compTimeStartingMinutes = startingMinutes

        // Skup dana koji imaju zapise
        val recordedDays = records.map { it.day }.toSet()
        val overtimeByDay = calculateMonthlyOvertimeMinutesByDay(
            records = records,
            year = year,
            month = month,
            brigadeType = brigadeType
        )

        for (record in records) {
            if (record.shift == Shift.SICK) {
                if (record.weekday.isWorkday) {
                    summary.sickHours += record.workHours
                    summary.totalPay += calculateDailyPay(record, baseRate, brigadeType, payBases)
                }
            } else if (record.shift == Shift.VACATION) {
                if (record.weekday.isWorkday) {
                    summary.vacationHours += record.workHours
                    summary.totalPay += calculateDailyPay(record, baseRate, brigadeType, payBases)
                }
            } else if (record.shift == Shift.OFF) {
                if (record.isHoliday && record.weekday.isWorkday) {
                    summary.totalPay += calculateDailyPay(record, baseRate, brigadeType, payBases)
                }
            } else if (record.shift in Shift.workShifts() && record.workHours > 0) {
                val monthlyOvertimeMinutes = monthlyOvertimeMinutesForRecord(record, brigadeType, overtimeByDay)
                summary.workHours += record.totalHours
                summary.workMinutes += record.totalWorkMinutes
                summary.workDays += 1
                summary.overtimeHours += monthlyOvertimeMinutes?.let { it / MINUTES_PER_HOUR }
                    ?: getOvertimeHours(record, brigadeType)
                summary.sundayHours += getSundayHours(record, brigadeType)
                summary.nightHours += getNightHours(record)
                summary.totalPay += calculateDailyPay(
                    record = record,
                    baseRate = baseRate,
                    brigadeType = brigadeType,
                    payBases = payBases,
                    monthlyOvertimeMinutes = monthlyOvertimeMinutes
                )
                summary.difficultWorkConditionsBonus += calculateDailyDifficultWorkConditionsBonus(record, baseRate)
                summary.foodAllowance += getFoodAllowanceForAccountingMonth(record, MonthKey(year, month))
                if (record.isDoubleShift) {
                    summary.doubleShiftDays += 1
                }
            }

            summary.compTimeEarned += record.compTimeEarned
            summary.compTimeDeficit += record.hoursDeficitFor(brigadeType)
            summary.compTimeUsed += record.compHoursUsed
            summary.compTimeEarnedMinutes += record.compTimeEarnedMinutes
            summary.compTimeDeficitMinutes += record.hoursDeficitMinutesFor(brigadeType)
            summary.compTimeUsedMinutes += record.compHoursUsed * MINUTES_PER_HOUR
        }

        // Dodaj platu za neupisane državne praznike samo kada mesec već ima korisnički unos.
        if (records.isNotEmpty()) {
            summary.totalPay += calculateUnrecordedHolidayPay(year, month, recordedDays, baseRate, brigadeType, payBases)
        }

        summary.compTimeNetMinutes = summary.compTimeEarnedMinutes -
            summary.compTimeDeficitMinutes -
            summary.compTimeUsedMinutes
        summary.compTimeEndingMinutes = summary.compTimeStartingMinutes + summary.compTimeNetMinutes
        summary.compTimeNet = summary.compTimeNetMinutes / MINUTES_PER_HOUR
        summary.compTimeEnding = summary.compTimeEndingMinutes / MINUTES_PER_HOUR

        // Ako je deficit pokriven zaradjenim satima, vrati platu za te sate
        // coveredDeficit = koliko sati deficita je pokriveno
        // Ako je ending >= 0, sav deficit je pokriven
        // Ako je ending < 0, pokriven je (deficit + ending) sati
        val coveredDeficitMinutes = if (summary.compTimeDeficitMinutes > 0) {
            summary.compTimeDeficitMinutes - maxOf(0, -summary.compTimeEndingMinutes)
        } else {
            0
        }
        if (coveredDeficitMinutes > 0) {
            summary.totalPay += coveredDeficitMinutes.toDouble() / MINUTES_PER_HOUR * baseRate
        }

        summary.yearsOfService = yearsOfService.coerceAtLeast(0)
        summary.seniorityEligibleWorkMinutes = calculateSeniorityEligibleWorkMinutes(
            records = records,
            brigadeType = brigadeType,
            overtimeByDay = overtimeByDay
        ) + coveredDeficitMinutes
        if (summary.yearsOfService > 0) {
            val minuliRate = summary.yearsOfService * config.minuliRadPerYear
            val seniorityBase = summary.seniorityEligibleWorkMinutes.toDouble() /
                MINUTES_PER_HOUR *
                baseRate
            summary.minuliRadBonus = seniorityBase * minuliRate
        }

        return summary
    }

    private fun calculateSeniorityEligibleWorkMinutes(
        records: List<DayRecord>,
        brigadeType: BrigadeType,
        overtimeByDay: Map<DayKey, Int>
    ): Int {
        return records.sumOf { record ->
            seniorityEligibleWorkMinutes(
                record = record,
                brigadeType = brigadeType,
                monthlyOvertimeMinutes = monthlyOvertimeMinutesForRecord(
                    record = record,
                    brigadeType = brigadeType,
                    overtimeByDay = overtimeByDay
                )
            )
        }
    }

    private fun seniorityEligibleWorkMinutes(
        record: DayRecord,
        brigadeType: BrigadeType,
        monthlyOvertimeMinutes: Int?
    ): Int {
        if (record.shift !in Shift.workShifts()) return 0
        val paidWorkMinutes = paidWorkMinuteSegments(record).sumOf { (_, minutes) -> minutes }
        if (paidWorkMinutes <= 0) return 0

        val overtimeMinutes = when {
            record.workedOnHoliday -> 0
            monthlyOvertimeMinutes != null -> monthlyOvertimeMinutes.coerceIn(0, paidWorkMinutes)
            brigadeType == BrigadeType.CETVOROBRIGADA -> 0
            isAutomaticWeekendOvertime(record, brigadeType) -> paidWorkMinutes
            else -> (paidWorkMinutes - config.defaultHours * MINUTES_PER_HOUR).coerceAtLeast(0)
        }
        return (paidWorkMinutes - overtimeMinutes).coerceAtLeast(0)
    }

    /**
     * Računa platu za državne praznike koji nisu upisani.
     * Praznici na radnim danima (pon-pet) se računaju kao plaćeni čak i ako nisu upisani.
     */
    fun calculateUnrecordedHolidayPay(
        year: Int,
        month: Int,
        recordedDays: Set<Int>,
        @Suppress("UnusedParameter")
        baseRate: Double,
        @Suppress("UnusedParameter")
        brigadeType: BrigadeType = BrigadeType.TROBRIGADA,
        payBases: ResolvedPayBases = defaultPayBases()
    ): Double {
        val daysInMonth = YearMonth.of(year, month).lengthOfMonth()
        var holidayPay = 0.0

        for (day in 1..daysInMonth) {
            // Preskoči dane koji već imaju zapis
            if (day in recordedDays) continue

            // Proveri da li je državni praznik
            if (!isSerbianHoliday(year, month, day)) continue

            val date = LocalDate.of(year, month, day)
            val weekday = Weekday.from(date)

            // Proveri da li je radni dan (pon-pet)
            if (!weekday.isWorkday) continue

            // Dodaj platu za 8 sati po osnovici plaćenog praznika
            holidayPay += config.defaultHours * payBases.paidHolidayHourlyBase.value
        }

        return holidayPay
    }
}
