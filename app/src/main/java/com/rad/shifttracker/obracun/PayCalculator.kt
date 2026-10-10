package com.rad.shifttracker.obracun

import java.time.LocalDate
import java.time.YearMonth
import kotlin.math.max
import kotlin.math.min
import com.rad.shifttracker.osnova.CONFIG
import com.rad.shifttracker.osnova.PayConfig
import com.rad.shifttracker.smene.BrigadeType
import com.rad.shifttracker.smene.DayKey
import com.rad.shifttracker.smene.DayRecord
import com.rad.shifttracker.smene.MonthKey
import com.rad.shifttracker.smene.Shift
import com.rad.shifttracker.smene.Weekday
import com.rad.shifttracker.smene.isSerbianHoliday

private const val MINUTES_PER_HOUR = 60

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

    fun getSundayHoursForShift(
        weekday: Weekday,
        shift: Shift,
        shiftHours: Int
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

    fun getSundayHours(record: DayRecord): Int {
        return paidWorkSegments(record).sumOf { (shift, hours) ->
            getSundayHoursForShift(record.weekday, shift, hours)
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

    fun getNightHours(record: DayRecord): Int {
        return paidWorkSegments(record)
            .filter { (shift, _) -> shift == Shift.THIRD }
            .sumOf { (_, hours) -> hours }
    }

    private fun calculateNightPremium(shift: Shift, shiftMinutes: Int, baseRate: Double): Double {
        return nightPremiumMinutes(shift, shiftMinutes).toDouble() / MINUTES_PER_HOUR *
            baseRate * premiumBonus(config.shift3Premium)
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
            calculateDifficultWorkConditionsBonusMinutes(minutes, baseRate, config.difficultWorkConditionsRate())
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

    fun calculateDailyPay(
        record: DayRecord,
        baseRate: Double,
        brigadeType: BrigadeType,
        payBases: ResolvedPayBases = defaultPayBases(),
        monthlyOvertimeMinutes: Int? = null
    ): Double {
        nonWorkDayPay(record, payBases)?.let { return it }

        val shiftsData = paidWorkMinuteSegments(record)
        return if (isAutomaticWeekendOvertime(record, brigadeType)) {
            weekendOvertimeDayPay(record, shiftsData, baseRate)
        } else {
            regularDayPay(record, shiftsData, baseRate, brigadeType, monthlyOvertimeMinutes)
        }
    }

    /** Plata dana bez rada (slobodan dan, praznik, odsustvo, bolovanje, godišnji); null za radni dan. */
    private fun nonWorkDayPay(record: DayRecord, payBases: ResolvedPayBases): Double? {
        val isWorkday = record.weekday.isWorkday
        return when {
            record.shift == Shift.OFF ->
                if (record.isHoliday && isWorkday) config.defaultHours * payBases.paidHolidayHourlyBase.value else 0.0
            record.workHours <= 0 -> 0.0
            record.isPaidLeave ->
                if (isWorkday) record.workHours.toDouble() * payBases.vacationHourlyBase.value else 0.0
            record.shift == Shift.SICK -> if (isWorkday) {
                val sickRate = record.sickPayRate ?: config.sickPayRate
                record.workHours.toDouble() * payBases.sickHourlyBase.value * sickRate
            } else {
                0.0
            }
            record.shift == Shift.VACATION -> if (isWorkday) {
                record.workHours.toDouble() * payBases.vacationHourlyBase.value * config.vacationPayRate
            } else {
                0.0
            }
            else -> null
        }
    }

    // Dan početka nije rad na praznik (vidi isAutomaticWeekendOvertime), ali
    // treća smena posle ponoći može pasti na praznik.
    private fun weekendOvertimeDayPay(
        record: DayRecord,
        shiftsData: List<Pair<Shift, Int>>,
        baseRate: Double
    ): Double {
        var totalPay = 0.0
        for ((shift, shiftMinutes) in shiftsData) {
            val bonuses = listOf(premiumBonus(config.overtimePremium))
            val pay = calculatePayForSegmentMinutes(
                weekday = record.weekday,
                shift = shift,
                minutes = shiftMinutes,
                baseRate = baseRate,
                bonuses = bonuses,
                sundayBonus = config.sundayBonus
            )
            totalPay += withSegmentPremiums(pay, record, shift, shiftMinutes, baseRate)
        }
        return totalPay
    }

    private fun regularDayPay(
        record: DayRecord,
        shiftsData: List<Pair<Shift, Int>>,
        baseRate: Double,
        brigadeType: BrigadeType,
        monthlyOvertimeMinutes: Int?
    ): Double {
        var totalPay = 0.0
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
                weekday = record.weekday,
                shift = shift,
                regularMinutes = regularMinutes,
                overtimeMinutes = overtimeMinutes
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
            totalPay += withSegmentPremiums(pay, record, shift, shiftMinutes, baseRate)
        }
        return totalPay
    }

    /**
     * Dodaje noćni, praznični i dodatak za otežane uslove na platu segmenta.
     * 123% samo za minute stvarnog rada na praznik, po satu (treća smena deli dan u ponoć);
     * dan označen „nije rad na praznik" ga nema.
     */
    private fun withSegmentPremiums(
        segmentPay: Double,
        record: DayRecord,
        shift: Shift,
        shiftMinutes: Int,
        baseRate: Double
    ): Double {
        var pay = segmentPay
        pay += calculateNightPremium(shift, shiftMinutes, baseRate)
        pay += calculateHolidayPremiumMinutes(
            record.holidayPremiumMinutes(shift, shiftMinutes),
            baseRate,
            config.holidayBonus
        )
        pay += calculateDifficultWorkConditionsBonusMinutes(
            shiftMinutes,
            baseRate,
            config.difficultWorkConditionsRate()
        )
        return pay
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
            monthlyOvertimeMinutes = null,
            defaultHours = config.defaultHours
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
        return PayCalculatorMonthlyDetails(this, config).calculate(
            records = records,
            summary = summary,
            baseRate = baseRate,
            netBreakdown = netBreakdown,
            brigadeType = brigadeType,
            payBases = payBases
        )
    }

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
            accumulateRecordSummary(summary, record, baseRate, brigadeType, payBases, overtimeByDay)
        }

        // Dodaj platu za neupisane državne praznike samo kada mesec već ima korisnički unos.
        if (records.isNotEmpty()) {
            summary.totalPay += calculateUnrecordedHolidayPay(year, month, recordedDays, payBases)
        }

        val coveredDeficitMinutes = applyCompBalance(summary, records, baseRate, brigadeType)

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

    private fun accumulateRecordSummary(
        summary: MonthSummary,
        record: DayRecord,
        baseRate: Double,
        brigadeType: BrigadeType,
        payBases: ResolvedPayBases,
        overtimeByDay: Map<DayKey, Int>
    ) {
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
            accumulateWorkDaySummary(summary, record, baseRate, brigadeType, payBases, overtimeByDay)
        }

        summary.compTimeEarned += record.compTimeEarned
        summary.compTimeDeficit += record.hoursDeficitFor(brigadeType)
        summary.compTimeUsed += record.compHoursUsed
        summary.compTimeEarnedMinutes += record.compTimeEarnedMinutes
        summary.compTimeDeficitMinutes += record.hoursDeficitMinutesFor(brigadeType)
        summary.compTimeUsedMinutes += record.compHoursUsed * MINUTES_PER_HOUR
    }

    private fun accumulateWorkDaySummary(
        summary: MonthSummary,
        record: DayRecord,
        baseRate: Double,
        brigadeType: BrigadeType,
        payBases: ResolvedPayBases,
        overtimeByDay: Map<DayKey, Int>
    ) {
        val monthlyOvertimeMinutes = monthlyOvertimeMinutesForRecord(record, brigadeType, overtimeByDay)
        summary.workHours += record.totalHours
        summary.workMinutes += record.totalWorkMinutes
        summary.workDays += 1
        // Minuti se sabiraju pa dele na kraju: četvorobrigada ume da da 30 min prekovremenog u dva dana.
        summary.overtimeMinutes += monthlyOvertimeMinutes ?: getOvertimeMinutes(record, brigadeType)
        summary.overtimeHours = summary.overtimeMinutes / MINUTES_PER_HOUR
        summary.sundayHours += getSundayHours(record)
        summary.nightHours += getNightHours(record)
        summary.totalPay += calculateDailyPay(
            record = record,
            baseRate = baseRate,
            brigadeType = brigadeType,
            payBases = payBases,
            monthlyOvertimeMinutes = monthlyOvertimeMinutes
        )
        summary.difficultWorkConditionsBonus += calculateDailyDifficultWorkConditionsBonus(record, baseRate)
        summary.foodAllowance += getFoodAllowanceForAccountingMonth(record, MonthKey(summary.year, summary.month))
        if (record.isDoubleShift) {
            summary.doubleShiftDays += 1
        }
    }

    /**
     * Saldo sati i pokriveni/nepokriveni manjak meseca; vraća pokriveni manjak (plaćen kao redovan rad).
     *
     * Net se računa po danu (manjak i iskorišćeni sati istog dana se
     * preklapaju — vidi DayRecord.balanceDebitMinutesFor), ne iz mesečnih suma.
     * Manjak koji je odrada (ili fond četvorobrigade) već platila kao redovan
     * rad ne skida saldo i ne plaća se ponovo iz njega.
     */
    private fun applyCompBalance(
        summary: MonthSummary,
        records: List<DayRecord>,
        baseRate: Double,
        brigadeType: BrigadeType
    ): Int {
        val shortageCoveredByWorkMinutes = shortageMinutesCoveredByExtraWork(
            records = records,
            year = summary.year,
            month = summary.month,
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
        return coveredDeficitMinutes
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
                ),
                defaultHours = config.defaultHours
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

internal fun premiumBonus(multiplier: Double): Double {
    return max(0.0, multiplier - 1.0)
}

internal fun PayConfig.difficultWorkConditionsRate(): Double {
    val percent = difficultWorkConditionsPercent
    return if (percent.isFinite() && percent > 0.0) percent.coerceAtMost(100.0) / 100.0 else 0.0
}
