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
        return getOvertimeMinutes(record, brigadeType) / MINUTES_PER_HOUR
    }

    /** Dnevni prekovremeni minuti bez mesečnog netiranja; vikend trobrigade je ceo prekovremeni. */
    fun getOvertimeMinutes(record: DayRecord, brigadeType: BrigadeType): Int {
        if (record.shift == Shift.SICK || record.shift == Shift.OFF || record.shift == Shift.VACATION) {
            return 0
        }
        if (brigadeType == BrigadeType.CETVOROBRIGADA) return 0
        if (record.workedOnHoliday) return 0

        val paidMinutes = paidWorkMinuteSegments(record).sumOf { (_, minutes) -> minutes }
        if (paidMinutes <= 0) return 0
        if (isAutomaticWeekendOvertime(record, brigadeType)) return paidMinutes

        return (paidMinutes - config.defaultHours * MINUTES_PER_HOUR).coerceAtLeast(0)
    }

    fun getFoodAllowance(record: DayRecord): Double {
        if (record.shift !in Shift.workShifts()) return 0.0
        if (record.totalWorkMinutes < 4 * MINUTES_PER_HOUR) return 0.0
        val additionalWorkMinutes =
            (record.totalWorkMinutes - config.defaultHours * MINUTES_PER_HOUR).coerceAtLeast(0)
        val mealCount = if (additionalWorkMinutes > 2 * MINUTES_PER_HOUR) 2 else 1
        return config.foodPerDay * mealCount
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

    private fun calculateNightPremium(shift: Shift, shiftMinutes: Int, baseRate: Double): Double {
        return nightPremiumMinutes(shift, shiftMinutes).toDouble() / MINUTES_PER_HOUR *
            baseRate * premiumBonus(config.shift3Premium)
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
        val sorted = records.sortedWith(compareBy<DayRecord> { it.year }.thenBy { it.month }.thenBy { it.day })
        return when (brigadeType) {
            BrigadeType.CETVOROBRIGADA -> fourBrigadeOvertimeMinutesByDay(sorted, year, month, config.defaultHours)
            BrigadeType.TROBRIGADA -> threeBrigadeOvertimeMinutesByDay(sorted, config.defaultHours)
        }
    }

    internal fun monthlyOvertimeMinutesForRecord(
        record: DayRecord,
        brigadeType: BrigadeType,
        overtimeByDay: Map<DayKey, Int>
    ): Int? {
        if (record.workedOnHoliday) return 0
        // Vikend kod trobrigade ostaje automatski prekovremeni — dnevna logika.
        if (isAutomaticWeekendOvertime(record, brigadeType)) return null
        return overtimeByDay[record.key] ?: 0
    }

    @Suppress("CyclomaticComplexMethod")
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

        if (record.isPaidLeave) {
            return if (weekday.isWorkday) {
                record.workHours.toDouble() * payBases.vacationHourlyBase.value
            } else {
                0.0
            }
        }

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
            // Dan početka nije rad na praznik (vidi isAutomaticWeekendOvertime), ali
            // treća smena posle ponoći može pasti na praznik.
            for ((shift, shiftMinutes) in shiftsData) {
                val bonuses = listOf(premiumBonus(config.overtimePremium))
                var pay = calculatePayForSegmentMinutes(
                    weekday = weekday,
                    shift = shift,
                    minutes = shiftMinutes,
                    baseRate = baseRate,
                    brigadeType = brigadeType,
                    bonuses = bonuses,
                    sundayBonus = config.sundayBonus
                )
                pay += calculateNightPremium(shift, shiftMinutes, baseRate)
                pay += calculateHolidayPremiumMinutes(
                    record.holidayPremiumMinutes(shift, shiftMinutes),
                    baseRate,
                    config.holidayBonus
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
                val regularBonuses = emptyList<Double>()
                val overtimeBonuses = listOf(premiumBonus(config.overtimePremium))
                val regularMinutes = min(shiftMinutes, regularMinutesRemaining)
                val overtimeMinutes = shiftMinutes - regularMinutes
                regularMinutesRemaining -= regularMinutes

                // Nedeljni minuti se računaju za celu smenu pa dele hronološki
                // na redovni/prekovremeni deo (vidi splitSundayMinutes).
                val (sundayRegular, sundayOvertime) = splitSundayMinutes(
                    weekday = weekday,
                    shift = shift,
                    regularMinutes = regularMinutes,
                    overtimeMinutes = overtimeMinutes,
                    brigadeType = brigadeType
                )
                var pay = calculatePayForSegmentMinutesWithSunday(
                    minutes = regularMinutes,
                    sundayMinutes = sundayRegular,
                    baseRate = baseRate,
                    bonuses = regularBonuses,
                    sundayBonus = config.sundayBonus
                )
                pay += calculatePayForSegmentMinutesWithSunday(
                    minutes = overtimeMinutes,
                    sundayMinutes = sundayOvertime,
                    baseRate = baseRate,
                    bonuses = overtimeBonuses,
                    sundayBonus = config.sundayBonus
                )
                pay += calculateNightPremium(shift, shiftMinutes, baseRate)
                // 123% samo za minute stvarnog rada na praznik, po satu (treća smena
                // deli dan u ponoć); dan označen „nije rad na praznik" ga nema.
                pay += calculateHolidayPremiumMinutes(
                    record.holidayPremiumMinutes(shift, shiftMinutes),
                    baseRate,
                    config.holidayBonus
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
        val categories = workBaseMinuteCategories(
            record = record,
            brigadeType = brigadeType,
            monthlyOvertimeMinutes = null
        )
        return calculateSeniorityBreakdown(
            baseHourlyGross = baseRate,
            regularWorkedBaseMinutes = categories.regularWorkedBaseMinutes,
            publicHolidayWorkedBaseMinutes = categories.publicHolidayWorkedBaseMinutes,
            overtimeBaseMinutes = categories.overtimeBaseMinutes,
            completedYearsOfService = yearsOfService
        ).grossSeniorityBonus
    }

    fun calculateSeniorityBreakdown(
        baseHourlyGross: Double,
        regularWorkedBaseMinutes: Int,
        publicHolidayWorkedBaseMinutes: Int,
        overtimeBaseMinutes: Int,
        completedYearsOfService: Int,
        publicHolidayBaseIncludedInRegular: Boolean = false
    ): SeniorityBreakdown {
        return calculateSeniorityBreakdownForCategories(
            baseHourlyGross = baseHourlyGross,
            regularWorkedBaseMinutes = regularWorkedBaseMinutes,
            publicHolidayWorkedBaseMinutes = publicHolidayWorkedBaseMinutes,
            overtimeBaseMinutes = overtimeBaseMinutes,
            completedYearsOfService = completedYearsOfService,
            seniorityRatePerYear = config.minuliRadPerYear,
            publicHolidayBaseIncludedInRegular = publicHolidayBaseIncludedInRegular
        )
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

        if (brigadeType == BrigadeType.CETVOROBRIGADA) {
            details.applyFourBrigadeFund(summary.fundTopUpMinutes, clockHolidayWorkMinutes(records), baseRate)
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

        // Isti pokriveni/nepokriveni manjak kao u calculateMonthSummary (bez
        // manjka koji je odrada ili fond četvorobrigade već platio kao redovan rad).
        val coveredDeficitMin = summary.coveredDeficitMinutes
        if (coveredDeficitMin > 0) {
            details.coveredDeficitMinutes += coveredDeficitMin
            details.grossCoveredDeficitPay += coveredDeficitMin.toDouble() / MINUTES_PER_HOUR * baseRate
            details.regularWorkedBaseMinutes += coveredDeficitMin
        }
        val uncoveredDeficitMin = summary.uncoveredDeficitMinutes
        if (uncoveredDeficitMin > 0) {
            details.uncoveredDeficitMinutes += uncoveredDeficitMin
            details.grossUncoveredDeficitLoss += uncoveredDeficitMin.toDouble() / MINUTES_PER_HOUR * baseRate
        }

        val baseComponents = calculateSeniorityBreakdown(
            baseHourlyGross = baseRate,
            regularWorkedBaseMinutes = details.regularWorkedBaseMinutes,
            publicHolidayWorkedBaseMinutes = details.publicHolidayWorkedBaseMinutes,
            overtimeBaseMinutes = details.overtimeBaseMinutes,
            completedYearsOfService = summary.completedYearsOfService
        )
        val grossWorkBeforeSeniority = details.grossTotalBeforeSeniority
        val grossBeforeSeniority = (
            grossWorkBeforeSeniority +
                netBreakdown.grossAdditionsTotal -
                netBreakdown.absencePenaltyGross.toDouble()
            ).coerceAtLeast(0.0)
        val netBeforeSeniority = max(0.0, netBreakdown.netPayBeforeDeductions - netBreakdown.netSeniorityBonus)

        return MonthlyPayDetails(
            totalPaidHours = details.totalPaidMinutes / MINUTES_PER_HOUR,
            paidWorkHours = details.basePaidHours,
            nightHours = details.nightHours,
            overtimeHours = details.overtimeHours,
            sundayHours = details.sundayHours,
            sickHours = details.sickHours,
            paidLeaveHours = details.paidLeaveHours,
            vacationHours = details.vacationHours,
            paidHolidayHours = details.paidHolidayHours,
            coveredDeficitHours = details.coveredDeficitHours,
            uncoveredDeficitHours = details.uncoveredDeficitHours,
            grossBaseRegular = details.grossBaseRegular,
            baseHourlyGross = baseComponents.baseHourlyGross,
            regularWorkedBaseMinutes = baseComponents.regularWorkedBaseMinutes,
            regularWorkedBaseGross = baseComponents.regularWorkedBaseGross,
            publicHolidayWorkedBaseMinutes = baseComponents.publicHolidayWorkedBaseMinutes,
            publicHolidayWorkedBaseGross = baseComponents.publicHolidayWorkedBaseGross,
            overtimeBaseMinutes = baseComponents.overtimeBaseMinutes,
            overtimeBaseGross = baseComponents.overtimeBaseGross,
            seniorityEligibleMinutes = baseComponents.seniorityEligibleMinutes,
            seniorityEligibleBaseGross = baseComponents.seniorityEligibleBaseGross,
            completedYearsOfService = baseComponents.completedYearsOfService,
            seniorityRatePerYear = baseComponents.seniorityRatePerYear,
            seniorityTotalRate = baseComponents.seniorityTotalRate,
            grossNightPremium = details.grossNightPremium,
            grossOvertimePremium = details.grossOvertimePremium,
            grossSundayPremium = details.grossSundayPremium,
            grossHolidayPremium = details.grossHolidayPremium,
            grossDifficultWorkConditions = details.grossDifficultWorkConditions,
            grossSickPay = details.grossSickPay,
            grossPaidLeave = details.grossPaidLeave,
            grossVacationPay = details.grossVacationPay,
            grossPaidHoliday = details.grossPaidHoliday,
            grossCoveredDeficitPay = details.grossCoveredDeficitPay,
            grossUncoveredDeficitLoss = details.grossUncoveredDeficitLoss,
            grossOvertimeFull = details.grossOvertimeFull,
            grossSeniorityBonus = netBreakdown.grossSeniorityBonus,
            sickPayBaseHourly = payBases.sickHourlyBase.value,
            paidLeavePayBase = payBases.vacationHourlyBase,
            vacationPayBase = payBases.vacationHourlyBase,
            paidHolidayPayBase = payBases.paidHolidayHourlyBase,
            sickPayBase = payBases.sickHourlyBase,
            grossMealAddition = netBreakdown.grossMealAddition,
            grossRegressAddition = netBreakdown.grossRegressAddition,
            grossBonusAddition = netBreakdown.grossBonusAddition,
            absencePenaltyGross = netBreakdown.absencePenaltyGross,
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
            paidLeaveMinutes = details.paidLeaveMinutes,
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

        if (record.isPaidLeave) {
            if (weekday.isWorkday) {
                details.paidLeaveMinutes += record.workHours * MINUTES_PER_HOUR
                details.grossPaidLeave += record.workHours.toDouble() * payBases.vacationHourlyBase.value
            }
            return
        }

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

        for (segment in classifiedWorkSegments(record, brigadeType, monthlyOvertimeMinutes)) {
            accumulateWorkSegment(
                minutes = segment.minutes,
                baseRate = baseRate,
                isOvertime = segment.isOvertime,
                publicHolidayBaseMinutes = segment.publicHolidayBaseMinutes(record, brigadeType),
                details = details,
                sundayMinutes = segment.sundayMinutes,
                nightMinutes = segment.nightMinutes,
                holidayMinutes = segment.holidayMinutes
            )
        }
    }

    @Suppress("LongParameterList")
    private fun accumulateWorkSegment(
        minutes: Int,
        baseRate: Double,
        isOvertime: Boolean,
        publicHolidayBaseMinutes: Int,
        details: MutablePayDetails,
        sundayMinutes: Int,
        nightMinutes: Int,
        holidayMinutes: Int
    ) {
        if (minutes <= 0) return
        val safeSundayMinutes = sundayMinutes.coerceIn(0, minutes)
        val safeNightMinutes = nightMinutes.coerceIn(0, minutes)
        val safeHolidayMinutes = holidayMinutes.coerceIn(0, minutes)
        val paidHours = minutes.toDouble() / MINUTES_PER_HOUR
        val basePay = paidHours * baseRate
        details.basePaidMinutes += minutes
        details.grossBaseRegular += basePay
        if (isOvertime) {
            details.overtimeBaseMinutes += minutes
        } else {
            val holidayBase = publicHolidayBaseMinutes.coerceIn(0, minutes)
            details.publicHolidayWorkedBaseMinutes += holidayBase
            details.regularWorkedBaseMinutes += minutes - holidayBase
        }
        // Pun bruto pripisan prekovremenim minutima (osnovica + svi dodaci na njih);
        // koristi se za neto efekat prekovremenog (kartica "Prekovremeno") minutno precizno.
        var overtimeSegmentGross = if (isOvertime) basePay else 0.0

        if (safeNightMinutes > 0) {
            val nightPremium = safeNightMinutes.toDouble() / MINUTES_PER_HOUR * baseRate *
                premiumBonus(config.shift3Premium)
            details.nightMinutes += safeNightMinutes
            details.grossNightPremium += nightPremium
            if (isOvertime) overtimeSegmentGross += nightPremium
        }
        if (isOvertime) {
            val overtimePremium = basePay * premiumBonus(config.overtimePremium)
            details.overtimeMinutes += minutes
            details.grossOvertimePremium += overtimePremium
            overtimeSegmentGross += overtimePremium
        }
        if (safeHolidayMinutes > 0) {
            val holidayPremium = calculateHolidayPremiumMinutes(safeHolidayMinutes, baseRate, config.holidayBonus)
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
        var regularWorkedBaseMinutes = 0
        var publicHolidayWorkedBaseMinutes = 0
        var overtimeBaseMinutes = 0
        var nightMinutes = 0
        var overtimeMinutes = 0
        var sundayMinutes = 0
        var sickMinutes = 0
        var paidLeaveMinutes = 0
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
        var grossPaidLeave = 0.0
        var grossVacationPay = 0.0
        var grossPaidHoliday = 0.0
        var grossCoveredDeficitPay = 0.0
        var grossUncoveredDeficitLoss = 0.0
        var grossOvertimeFull = 0.0

        /**
         * Četvorobrigada: listić uvek plaća pun fond, pa sati do fonda ([fundTopUpMinutes]) idu kao rad
         * u režiji (01); rad na praznik (05) se broji po satu ([clockHolidayMinutes]), a 01 i 05 imaju
         * istu satnicu, pa se bruto pri tom prebacivanju ne menja.
         */
        fun applyFourBrigadeFund(fundTopUpMinutes: Int, clockHolidayMinutes: Int, baseRate: Double) {
            val moved = (clockHolidayMinutes - publicHolidayWorkedBaseMinutes)
                .coerceIn(-publicHolidayWorkedBaseMinutes, regularWorkedBaseMinutes)
            regularWorkedBaseMinutes += fundTopUpMinutes - moved
            publicHolidayWorkedBaseMinutes += moved
            basePaidMinutes += fundTopUpMinutes
            grossBaseRegular += fundTopUpMinutes.toDouble() / MINUTES_PER_HOUR * baseRate
        }

        val totalPaidMinutes: Int
            get() = basePaidMinutes + sickMinutes + paidLeaveMinutes + vacationMinutes +
                paidHolidayMinutes + coveredDeficitMinutes
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
        val paidLeaveHours: Int
            get() = paidLeaveMinutes / MINUTES_PER_HOUR
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
                grossPaidHoliday + grossPaidLeave + grossCoveredDeficitPay
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
            if (record.isPaidLeave) {
                if (record.weekday.isWorkday) {
                    summary.paidLeaveMinutes += record.workHours * MINUTES_PER_HOUR
                    summary.paidLeaveHours = summary.paidLeaveMinutes / MINUTES_PER_HOUR
                    summary.totalPay += calculateDailyPay(record, baseRate, brigadeType, payBases)
                }
            } else if (record.shift == Shift.SICK) {
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

        // Net se računa po danu (manjak i iskorišćeni sati istog dana se
        // preklapaju — vidi DayRecord.balanceDebitMinutesFor), ne iz mesečnih suma.
        // Manjak koji je odrada (ili fond četvorobrigade) već platila kao redovan
        // rad ne skida saldo i ne plaća se ponovo iz njega.
        val shortageCoveredByWorkMinutes = shortageMinutesCoveredByExtraWork(
            records = records,
            year = year,
            month = month,
            brigadeType = brigadeType,
            defaultHours = config.defaultHours
        )
        summary.compTimeNetMinutes = records.sumOf { it.netCompChangeMinutesFor(brigadeType) } +
            shortageCoveredByWorkMinutes
        summary.compTimeEndingMinutes = summary.compTimeStartingMinutes + summary.compTimeNetMinutes
        summary.compTimeNet = summary.compTimeNetMinutes / MINUTES_PER_HOUR
        summary.compTimeEnding = summary.compTimeEndingMinutes / MINUTES_PER_HOUR

        // Ako je deficit pokriven zaradjenim satima, vrati platu za te sate
        // coveredDeficit = koliko sati deficita je pokriveno
        // Ako je ending >= 0, sav deficit je pokriven
        // Ako je ending < 0, pokriven je (deficit + ending) sati
        val remainingDeficitMinutes = (summary.compTimeDeficitMinutes - shortageCoveredByWorkMinutes).coerceAtLeast(0)
        val coveredDeficitMinutes = if (remainingDeficitMinutes > 0) {
            (remainingDeficitMinutes - maxOf(0, -summary.compTimeEndingMinutes)).coerceAtLeast(0)
        } else {
            0
        }
        summary.coveredDeficitMinutes = coveredDeficitMinutes
        summary.uncoveredDeficitMinutes = (remainingDeficitMinutes - coveredDeficitMinutes).coerceAtLeast(0)
        if (coveredDeficitMinutes > 0) {
            summary.totalPay += coveredDeficitMinutes.toDouble() / MINUTES_PER_HOUR * baseRate
        }

        val workBaseMinutes = calculateMonthlyWorkBaseMinutes(
            records = records,
            brigadeType = brigadeType,
            overtimeByDay = overtimeByDay
        )
        if (brigadeType == BrigadeType.CETVOROBRIGADA && isEveryWorkdayRecorded(records, year, month)) {
            summary.fundTopUpMinutes = fourBrigadeFundTopUpMinutes(
                records = records,
                year = year,
                month = month,
                workedMinutes = workBaseMinutes.regularWorkedBaseMinutes + workBaseMinutes.publicHolidayWorkedBaseMinutes,
                deficitMinutes = coveredDeficitMinutes + summary.uncoveredDeficitMinutes,
                defaultHours = config.defaultHours
            )
            summary.totalPay += summary.fundTopUpMinutes.toDouble() / MINUTES_PER_HOUR * baseRate
        }
        summary.seniorityBreakdown = calculateSeniorityBreakdown(
            baseHourlyGross = baseRate,
            regularWorkedBaseMinutes = workBaseMinutes.regularWorkedBaseMinutes + coveredDeficitMinutes +
                summary.fundTopUpMinutes,
            publicHolidayWorkedBaseMinutes = workBaseMinutes.publicHolidayWorkedBaseMinutes,
            overtimeBaseMinutes = workBaseMinutes.overtimeBaseMinutes,
            completedYearsOfService = yearsOfService
        )
        summary.yearsOfService = summary.completedYearsOfService
        summary.seniorityEligibleWorkMinutes = summary.seniorityEligibleMinutes
        summary.minuliRadBonus = summary.seniorityBreakdown.grossSeniorityBonus

        return summary
    }

    private fun calculateMonthlyWorkBaseMinutes(
        records: List<DayRecord>,
        brigadeType: BrigadeType,
        overtimeByDay: Map<DayKey, Int>
    ): WorkBaseMinuteCategories {
        return records.fold(WorkBaseMinuteCategories()) { total, record ->
            total + workBaseMinuteCategories(
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

    private fun workBaseMinuteCategories(
        record: DayRecord,
        brigadeType: BrigadeType,
        monthlyOvertimeMinutes: Int?
    ): WorkBaseMinuteCategories {
        return classifiedWorkSegments(record, brigadeType, monthlyOvertimeMinutes)
            .fold(WorkBaseMinuteCategories()) { total, segment ->
                if (segment.isOvertime) {
                    total.copy(overtimeBaseMinutes = total.overtimeBaseMinutes + segment.minutes)
                } else {
                    val holidayBase = segment.publicHolidayBaseMinutes(record, brigadeType).coerceIn(0, segment.minutes)
                    total.copy(
                        regularWorkedBaseMinutes = total.regularWorkedBaseMinutes + segment.minutes - holidayBase,
                        publicHolidayWorkedBaseMinutes = total.publicHolidayWorkedBaseMinutes + holidayBase
                    )
                }
            }
    }


    private fun classifiedWorkSegments(
        record: DayRecord,
        brigadeType: BrigadeType,
        monthlyOvertimeMinutes: Int?
    ): List<ClassifiedWorkSegment> {
        val shiftsData = paidWorkMinuteSegments(record)
        if (shiftsData.isEmpty()) return emptyList()
        if (isAutomaticWeekendOvertime(record, brigadeType)) {
            return shiftsData.map { (shift, minutes) ->
                ClassifiedWorkSegment(
                    shift = shift,
                    minutes = minutes,
                    isOvertime = true,
                    sundayMinutes = getSundayMinutesForShift(record.weekday, shift, minutes, brigadeType),
                    nightMinutes = nightPremiumMinutes(shift, minutes),
                    holidayMinutes = record.holidayPremiumMinutes(shift, minutes)
                )
            }
        }

        val paidWorkMinutes = shiftsData.sumOf { (_, minutes) -> minutes }
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
        return buildList {
            for ((shift, shiftMinutes) in shiftsData) {
                val regularMinutes = min(shiftMinutes, regularMinutesRemaining)
                val overtimeMinutes = shiftMinutes - regularMinutes
                regularMinutesRemaining -= regularMinutes
                val (sundayRegular, sundayOvertime) = splitSundayMinutes(
                    weekday = record.weekday,
                    shift = shift,
                    regularMinutes = regularMinutes,
                    overtimeMinutes = overtimeMinutes,
                    brigadeType = brigadeType
                )
                // Noćni pun sat se prvo pripisuje redovnom delu smene, ostatak prekovremenom.
                val nightMinutes = nightPremiumMinutes(shift, shiftMinutes)
                val nightRegular = min(regularMinutes, nightMinutes)
                val nightOvertime = (nightMinutes - nightRegular).coerceIn(0, overtimeMinutes)
                // Praznični minuti hronološki kao nedelja: redovni deo je početak smene.
                val holidayTotal = record.holidayPremiumMinutes(shift, shiftMinutes).coerceIn(0, shiftMinutes)
                val holidayRegular = record.holidayPremiumMinutes(shift, regularMinutes)
                    .coerceIn(0, minOf(regularMinutes, holidayTotal))
                val holidayOvertime = (holidayTotal - holidayRegular).coerceIn(0, overtimeMinutes)
                if (regularMinutes > 0) {
                    add(
                        ClassifiedWorkSegment(shift, regularMinutes, false, sundayRegular, nightRegular, holidayRegular)
                    )
                }
                if (overtimeMinutes > 0) {
                    add(
                        ClassifiedWorkSegment(shift, overtimeMinutes, true, sundayOvertime, nightOvertime, holidayOvertime)
                    )
                }
            }
        }
    }

    private data class ClassifiedWorkSegment(
        val shift: Shift,
        val minutes: Int,
        val isOvertime: Boolean,
        val sundayMinutes: Int,
        val nightMinutes: Int,
        val holidayMinutes: Int
    ) {
        /**
         * Minuti redovnog segmenta koje lista broji kao rad na praznik (05). Trobrigada: po satu, isto kao
         * dodatak 123% (23) — treća smena uoči praznika daje 6 h, a ona koja počinje na praznik 2 h; ostatak
         * smene je režija (01). Četvorobrigada: cela smena po danu početka, a fond prebacuje po satu
         * ([MutablePayDetails.applyFourBrigadeFund]). 01 i 05 imaju istu satnicu i oba ulaze u minuli rad.
         */
        fun publicHolidayBaseMinutes(record: DayRecord, brigadeType: BrigadeType): Int = when {
            isOvertime -> 0
            brigadeType == BrigadeType.TROBRIGADA -> holidayMinutes
            record.workedOnHoliday -> minutes
            else -> 0
        }
    }

    private data class WorkBaseMinuteCategories(
        val regularWorkedBaseMinutes: Int = 0,
        val publicHolidayWorkedBaseMinutes: Int = 0,
        val overtimeBaseMinutes: Int = 0
    ) {
        operator fun plus(other: WorkBaseMinuteCategories): WorkBaseMinuteCategories {
            return WorkBaseMinuteCategories(
                regularWorkedBaseMinutes + other.regularWorkedBaseMinutes,
                publicHolidayWorkedBaseMinutes + other.publicHolidayWorkedBaseMinutes,
                overtimeBaseMinutes + other.overtimeBaseMinutes
            )
        }
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

internal fun paidWorkMinuteSegments(record: DayRecord): List<Pair<Shift, Int>> {
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

private fun fourBrigadeOvertimeMinutesByDay(
    sorted: List<DayRecord>,
    year: Int,
    month: Int,
    defaultHours: Int
): Map<DayKey, Int> {
    var regularMinutesRemaining = calculateMonthlyFundHours(year, month, defaultHours) * MINUTES_PER_HOUR
    if (regularMinutesRemaining <= 0) return emptyMap()

    // Naknade (bolovanje, GO, plaćeno odsustvo, plaćeni praznici) na listiću
    // pune mesečni fond, pa je norma za redovan rad fond minus ti sati —
    // inače bi mesec sa bolovanjem tražio pun fond stvarnog rada pre prvog
    // prekovremenog minuta.
    regularMinutesRemaining -= min(paidAbsenceFundMinutes(sorted, year, month, defaultHours), regularMinutesRemaining)

    // Radni praznici prvo troše fond i nikad ne nose prekovremene —
    // njihovi sati se plaćaju kroz prazničnu premiju
    // (monthlyOvertimeMinutesForRecord za njih vraća 0). Da im se višak
    // dodeli pa nulira, ti minuti bi propali, a ukupna plata bi zavisila
    // od toga gde u mesecu praznik pada.
    for (record in sorted) {
        if (!record.workedOnHoliday) continue
        val recordPaidMinutes = paidWorkMinuteSegments(record).sumOf { (_, minutes) -> minutes }
        regularMinutesRemaining -= min(recordPaidMinutes, regularMinutesRemaining)
    }

    val overtimeByDay = mutableMapOf<DayKey, Int>()
    for (record in sorted) {
        if (record.workedOnHoliday) continue
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

/**
 * Listić broji rad na praznik (05) po satu, isto kao dodatak 123% (23): treća smena uoči praznika
 * daje 6 h na praznik, a ona koja počinje na praznik samo 2 h (a ne cela smena po danu početka).
 */
private fun clockHolidayWorkMinutes(records: List<DayRecord>): Int = records.sumOf { record ->
    paidWorkMinuteSegments(record).sumOf { (shift, minutes) -> record.holidayPremiumMinutes(shift, minutes) }
}

// Dopuna do fonda važi samo za popunjen mesec (svaki pon–pet upisan, kao posle „Popuni smene“),
// da mesec sa par upisanih dana ne dobije ceo fond.
private fun isEveryWorkdayRecorded(records: List<DayRecord>, year: Int, month: Int): Boolean {
    val recordedDays = records.map { it.day }.toSet()
    return (1..YearMonth.of(year, month).lengthOfMonth()).all { day ->
        day in recordedDays || !Weekday.from(LocalDate.of(year, month, day)).isWorkday
    }
}

/**
 * Četvorobrigada: svih 19 listića livnice (12/2024–08/2026) ima rad u režiji + naknade +
 * izostanke tačno jednako fondu, iako raspored smena daje manje ili više sati. Firma plaća
 * pun fond, pa se sati koji fali do fonda posle rada, naknada i upisanog manjka plaćaju kao 01.
 */
private fun fourBrigadeFundTopUpMinutes(
    records: List<DayRecord>,
    year: Int,
    month: Int,
    workedMinutes: Int,
    deficitMinutes: Int,
    defaultHours: Int
): Int {
    val fundMinutes = calculateMonthlyFundHours(year, month, defaultHours) * MINUTES_PER_HOUR
    val filledMinutes = workedMinutes + deficitMinutes + paidAbsenceFundMinutes(records, year, month, defaultHours)
    return (fundMinutes - filledMinutes).coerceAtLeast(0)
}

// Zakonski maksimum ukupnog dnevnog rada (Zakon o radu: redovan + prekovremeni
// ne duže od 12h dnevno). Duži dan ne može biti odrada, pa je ceo višak prekovremeni.
private const val MAX_LEGAL_DAILY_WORK_MINUTES = 12 * MINUTES_PER_HOUR

/**
 * Prekovremeni po danima za trobrigadu, sa automatskom odradom kao na listiću:
 * višak preko 8h radnim danom prvo odrađuje CELE radne dane koje u tom mesecu
 * nisi radio (neradni radni dan, bez praznika i bez „iskoristi sate") — plaća se
 * kao redovan rad sa noćnim dodatkom — a prekovremeni je samo ostatak.
 * - Listić 06/2026: neradni 08, 10. i 17.06. (24h); +2h 03.06. i +3h 25.06. su
 *   režija, a 01.06. (8h + 6h = 14h, preko 12h) je 6h prekovremenog.
 * - Listić 07/2026: nema celog neradnog dana (31.07. je samo kraći dan), pa je
 *   +3h od 01.07. prekovremeno uz 3h izostanaka.
 * Sati „Za sate" se ne plaćaju (idu u saldo), pa ovde ni ne ulaze. Vikend rad
 * ostaje automatski prekovremeni, a rad na praznik nikad nije prekovremeni.
 */
private fun threeBrigadeOvertimeMinutesByDay(
    sorted: List<DayRecord>,
    defaultHours: Int
): Map<DayKey, Int> = threeBrigadeOvertimeAllocation(sorted, defaultHours).overtimeByDay

private data class ThreeBrigadeOvertimeAllocation(
    val overtimeByDay: Map<DayKey, Int>,
    val madeUpShortageMinutes: Int
)

private fun threeBrigadeOvertimeAllocation(
    sorted: List<DayRecord>,
    defaultHours: Int
): ThreeBrigadeOvertimeAllocation {
    var missedDayMinutesRemaining = missedWholeWorkdayMinutes(sorted)
    var madeUpShortageMinutes = 0
    val defaultMinutes = defaultHours * MINUTES_PER_HOUR
    val overtimeByDay = mutableMapOf<DayKey, Int>()
    for (record in sorted) {
        if (record.workedOnHoliday) continue
        if (record.weekday.isWeekend) continue
        val recordPaidMinutes = paidWorkMinuteSegments(record).sumOf { (_, minutes) -> minutes }
        val extraMinutes = recordPaidMinutes - defaultMinutes
        if (extraMinutes <= 0) continue
        val madeUpMinutes = if (record.totalWorkMinutes <= MAX_LEGAL_DAILY_WORK_MINUTES) {
            min(extraMinutes, missedDayMinutesRemaining)
        } else {
            0
        }
        missedDayMinutesRemaining -= madeUpMinutes
        madeUpShortageMinutes += madeUpMinutes
        val overtimeMinutes = extraMinutes - madeUpMinutes
        if (overtimeMinutes > 0) {
            overtimeByDay[record.key] = overtimeMinutes
        }
    }
    return ThreeBrigadeOvertimeAllocation(overtimeByDay, madeUpShortageMinutes)
}

/** Manjak celih neradnih radnih dana (odsustvo bez praznika i bez „iskoristi sate"). */
private fun missedWholeWorkdayMinutes(records: List<DayRecord>): Int {
    return records
        .filter { record -> record.shift == Shift.OFF }
        .sumOf { record ->
            (record.hoursDeficitMinutesFor(BrigadeType.TROBRIGADA) - record.compHoursUsed * MINUTES_PER_HOUR)
                .coerceAtLeast(0)
        }
}

/** Manjak meseca koji ne pokriva "iskoristi sate" (i koji zato mogu da upiju viškovi). */
private fun shortageMinutesNotCoveredByCompUse(records: List<DayRecord>, brigadeType: BrigadeType): Int {
    // Dan pokriven "iskoristi sate" sa balansa je slobodan dan, ne izostanak,
    // pa njegov manjak ne upija viškove.
    return records.sumOf { record ->
        (record.hoursDeficitMinutesFor(brigadeType) - record.compHoursUsed * MINUTES_PER_HOUR)
            .coerceAtLeast(0)
    }
}

/**
 * Manjak meseca koji su već pokrili dodatni sati plaćeni kao redovan rad:
 * automatska odrada kod trobrigade, mesečni fond kod četvorobrigade (dan od 10h
 * i dan od 6h daju 16h bez prekovremenog). Ti minuti su plaćeni kroz rad, pa ne
 * smeju ponovo da se plate iz salda sati niti da ga umanje.
 */
internal fun shortageMinutesCoveredByExtraWork(
    records: List<DayRecord>,
    year: Int,
    month: Int,
    brigadeType: BrigadeType,
    defaultHours: Int
): Int {
    val sorted = records.sortedWith(compareBy<DayRecord> { it.year }.thenBy { it.month }.thenBy { it.day })
    if (brigadeType == BrigadeType.TROBRIGADA) {
        return threeBrigadeOvertimeAllocation(sorted, defaultHours).madeUpShortageMinutes
    }
    val defaultMinutes = defaultHours * MINUTES_PER_HOUR
    val extraMinutes = sorted.filterNot { it.workedOnHoliday }.sumOf { record ->
        (paidWorkMinuteSegments(record).sumOf { (_, minutes) -> minutes } - defaultMinutes)
            .coerceAtLeast(0)
    }
    val overtimeMinutes = fourBrigadeOvertimeMinutesByDay(sorted, year, month, defaultHours).values.sum()
    return min(
        shortageMinutesNotCoveredByCompUse(sorted, brigadeType),
        (extraMinutes - overtimeMinutes).coerceAtLeast(0)
    )
}

/** Minuti naknada koji na listiću pune mesečni fond (norma = fond − naknade). */
private fun paidAbsenceFundMinutes(
    records: List<DayRecord>,
    year: Int,
    month: Int,
    defaultHours: Int
): Int {
    var minutes = 0
    for (record in records) {
        if (!record.weekday.isWorkday) continue
        minutes += when {
            record.shift == Shift.SICK || record.shift == Shift.VACATION ->
                record.workHours * MINUTES_PER_HOUR
            record.shift == Shift.OFF && record.isHoliday -> defaultHours * MINUTES_PER_HOUR
            else -> 0
        }
    }
    if (records.isNotEmpty()) {
        minutes += unrecordedHolidayFundMinutes(year, month, records.map { it.day }.toSet(), defaultHours)
    }
    return minutes
}

private fun unrecordedHolidayFundMinutes(
    year: Int,
    month: Int,
    recordedDays: Set<Int>,
    defaultHours: Int
): Int {
    if (year <= 0 || month !in 1..12) return 0
    val daysInMonth = YearMonth.of(year, month).lengthOfMonth()
    var minutes = 0
    for (day in 1..daysInMonth) {
        if (day in recordedDays) continue
        if (!isSerbianHoliday(year, month, day)) continue
        if (!Weekday.from(LocalDate.of(year, month, day)).isWorkday) continue
        minutes += defaultHours * MINUTES_PER_HOUR
    }
    return minutes
}
