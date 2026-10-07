package com.rad.shifttracker.obracun

import kotlin.math.max
import kotlin.math.roundToInt
import com.rad.shifttracker.osnova.PayConfig
import com.rad.shifttracker.smene.BrigadeType
import com.rad.shifttracker.smene.DayRecord
import com.rad.shifttracker.smene.Shift

private const val MINUTES_PER_HOUR = 60

/** Mesečni detalji plate (sati i bruto po stavkama listića), sabrani iz dnevnih zapisa. */
internal class PayCalculatorMonthlyDetails(
    private val calculator: PayCalculator,
    private val config: PayConfig
) {
    fun calculate(
        records: List<DayRecord>,
        summary: MonthSummary,
        baseRate: Double,
        netBreakdown: MonthlyNetPayBreakdown,
        brigadeType: BrigadeType,
        payBases: ResolvedPayBases
    ): MonthlyPayDetails {
        val details = MutablePayDetails()
        val recordedDays = records.map { it.day }.toSet()
        val overtimeByDay = calculator.calculateMonthlyOvertimeMinutesByDay(
            records = records,
            year = summary.year,
            month = summary.month,
            brigadeType = brigadeType
        )

        for (record in records) {
            accumulateRecord(
                record = record,
                baseRate = baseRate,
                brigadeType = brigadeType,
                payBases = payBases,
                details = details,
                monthlyOvertimeMinutes = calculator.monthlyOvertimeMinutesForRecord(record, brigadeType, overtimeByDay)
            )
        }

        if (brigadeType == BrigadeType.CETVOROBRIGADA) {
            details.applyFourBrigadeFund(summary.fundTopUpMinutes, clockHolidayWorkMinutes(records), baseRate)
        }

        val unrecordedHolidayPay = if (records.isNotEmpty()) {
            calculator.calculateUnrecordedHolidayPay(
                summary.year,
                summary.month,
                recordedDays,
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

        val baseComponents = calculator.calculateSeniorityBreakdown(
            baseHourlyGross = baseRate,
            regularWorkedBaseMinutes = details.regularWorkedBaseMinutes,
            publicHolidayWorkedBaseMinutes = details.publicHolidayWorkedBaseMinutes,
            overtimeBaseMinutes = details.overtimeBaseMinutes,
            completedYearsOfService = summary.completedYearsOfService
        )
        return details.toMonthlyPayDetails(baseComponents, netBreakdown, payBases)
    }

    private fun accumulateRecord(
        record: DayRecord,
        baseRate: Double,
        brigadeType: BrigadeType,
        payBases: ResolvedPayBases,
        details: MutablePayDetails,
        monthlyOvertimeMinutes: Int?
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

        val segments = classifiedWorkSegments(record, brigadeType, monthlyOvertimeMinutes, config.defaultHours)
        for (segment in segments) {
            accumulateWorkSegment(
                segment = segment,
                publicHolidayBaseMinutes = segment.publicHolidayBaseMinutes(record, brigadeType),
                baseRate = baseRate,
                details = details
            )
        }
    }

    private fun accumulateWorkSegment(
        segment: ClassifiedWorkSegment,
        publicHolidayBaseMinutes: Int,
        baseRate: Double,
        details: MutablePayDetails
    ) {
        val minutes = segment.minutes
        val isOvertime = segment.isOvertime
        if (minutes <= 0) return
        val safeSundayMinutes = segment.sundayMinutes.coerceIn(0, minutes)
        val safeNightMinutes = segment.nightMinutes.coerceIn(0, minutes)
        val safeHolidayMinutes = segment.holidayMinutes.coerceIn(0, minutes)
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
            config.difficultWorkConditionsRate()
        )
        details.grossDifficultWorkConditions += difficultBonus
        if (isOvertime) overtimeSegmentGross += difficultBonus

        details.grossOvertimeFull += overtimeSegmentGross
    }
}

internal class MutablePayDetails {
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

private fun MutablePayDetails.toMonthlyPayDetails(
    baseComponents: SeniorityBreakdown,
    netBreakdown: MonthlyNetPayBreakdown,
    payBases: ResolvedPayBases
): MonthlyPayDetails {
    val grossWorkBeforeSeniority = grossTotalBeforeSeniority
    val grossBeforeSeniority = (
        grossWorkBeforeSeniority +
            netBreakdown.grossAdditionsTotal -
            netBreakdown.absencePenaltyGross.toDouble()
        ).coerceAtLeast(0.0)
    val netBeforeSeniority = max(0.0, netBreakdown.netPayBeforeDeductions - netBreakdown.netSeniorityBonus)

    return MonthlyPayDetails(
        totalPaidHours = totalPaidMinutes / MINUTES_PER_HOUR,
        paidWorkHours = basePaidHours,
        nightHours = nightHours,
        overtimeHours = overtimeHours,
        sundayHours = sundayHours,
        sickHours = sickHours,
        paidLeaveHours = paidLeaveHours,
        vacationHours = vacationHours,
        paidHolidayHours = paidHolidayHours,
        coveredDeficitHours = coveredDeficitHours,
        uncoveredDeficitHours = uncoveredDeficitHours,
        grossBaseRegular = grossBaseRegular,
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
        grossNightPremium = grossNightPremium,
        grossOvertimePremium = grossOvertimePremium,
        grossSundayPremium = grossSundayPremium,
        grossHolidayPremium = grossHolidayPremium,
        grossDifficultWorkConditions = grossDifficultWorkConditions,
        grossSickPay = grossSickPay,
        grossPaidLeave = grossPaidLeave,
        grossVacationPay = grossVacationPay,
        grossPaidHoliday = grossPaidHoliday,
        grossCoveredDeficitPay = grossCoveredDeficitPay,
        grossUncoveredDeficitLoss = grossUncoveredDeficitLoss,
        grossOvertimeFull = grossOvertimeFull,
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
        netUncoveredDeficitLoss = uncoveredDeficitMinutes.toDouble() / MINUTES_PER_HOUR *
            netBreakdown.informativeNetHourlyBase,
        netTotal = netBreakdown.displayPay,
        totalPaidMinutes = totalPaidMinutes,
        paidWorkMinutes = basePaidMinutes,
        nightMinutes = nightMinutes,
        overtimeMinutes = overtimeMinutes,
        sundayMinutes = sundayMinutes,
        sickMinutes = sickMinutes,
        paidLeaveMinutes = paidLeaveMinutes,
        vacationMinutes = vacationMinutes,
        paidHolidayMinutes = paidHolidayMinutes,
        coveredDeficitMinutes = coveredDeficitMinutes,
        uncoveredDeficitMinutes = uncoveredDeficitMinutes
    )
}
