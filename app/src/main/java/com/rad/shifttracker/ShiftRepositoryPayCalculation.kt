package com.rad.shifttracker

import com.rad.shifttracker.ShiftRepository.Companion.MINUTES_PER_HOUR

fun ShiftRepository.getCompBalanceAtMonthStart(year: Int, month: Int): Int = synchronized(lock) {
    compBalanceCalculator.hoursAtMonthStart(year, month)
}

fun ShiftRepository.getSummary(year: Int, month: Int): MonthSummary = synchronized(lock) {
    val monthKey = MonthKey(year, month)
    payState.summaries[monthKey]?.let { return it }

    val monthRecords = recordStore.getMonthRecords(monthKey)
    val summary = calculateMonthSummaryUnsafe(year, month, monthRecords)
    payState.summaries[monthKey] = summary
    payState.dirtyMonths.remove(monthKey)
    summary
}

fun ShiftRepository.calculateBasePayFromNet(
    year: Int,
    month: Int,
    netPay: Double,
    actualFoodDeduction: Double? = null
): Double? = synchronized(lock) {
    if (!netPay.isFinite() || netPay < 0) return null
    if (actualFoodDeduction != null && (!actualFoodDeduction.isFinite() || actualFoodDeduction < 0)) {
        return null
    }
    val monthKey = MonthKey(year, month)
    val monthRecords = recordStore.getMonthRecords(monthKey)
    val summary = payQueries.calculateMonthSummaryUnsafe(
        year = year,
        month = month,
        monthRecords = monthRecords,
        grossHourlyRate = 1.0
    )
    val coefficient = summary.totalWithMinuli
    if (coefficient <= 0.0) return null
    netPay / coefficient
}

fun ShiftRepository.calculateDailyPay(record: DayRecord): Double = synchronized(lock) {
    payQueries.calculateDailyPayUnsafe(record)
}

fun ShiftRepository.calculateEstimatedDailyGrossPay(record: DayRecord): Double = synchronized(lock) {
    val workGross = calculateDailyGrossWorkPayWithSeniorityUnsafe(record)
    if (workGross <= 0.0) return@synchronized 0.0
    workGross + calculateDailyGrossAdditionShareUnsafe(record, monthRecordsWithPreviewUnsafe(record))
}

fun ShiftRepository.calculateEstimatedDailyWorkGrossPay(record: DayRecord): Double = synchronized(lock) {
    calculateDailyGrossWorkPayWithSeniorityUnsafe(record)
}

fun ShiftRepository.calculateEstimatedDailyNetPay(record: DayRecord): Double = synchronized(lock) {
    val grossDailyPayWithSeniority = calculateDailyGrossPayWithSeniorityAndAdditionsUnsafe(record)
    if (grossDailyPayWithSeniority <= 0.0) return@synchronized 0.0

    val netBreakdown = calculateNetPayBreakdownWithPreviewUnsafe(
        record = record,
        includeFoodAllowance = true,
        includeGrossAdditions = true
    )
    if (netBreakdown.grossTotal <= 0.0) return@synchronized 0.0

    grossDailyPayWithSeniority * (netBreakdown.displayPay / netBreakdown.grossTotal)
}

fun ShiftRepository.calculateEstimatedDailyWorkNetPay(record: DayRecord): Double = synchronized(lock) {
    val grossDailyWorkPayWithSeniority = calculateDailyGrossWorkPayWithSeniorityUnsafe(record)
    val netBreakdown = calculateNetPayBreakdownWithPreviewUnsafe(
        record = record,
        includeFoodAllowance = false,
        includeGrossAdditions = false
    )
    val paidWorkNet = if (grossDailyWorkPayWithSeniority > 0.0 && netBreakdown.grossTotal > 0.0) {
        grossDailyWorkPayWithSeniority * marginalNetFactorUnsafe(netBreakdown)
    } else {
        0.0
    }
    paidWorkNet + calculateCompensatoryAdditionalNetEffectUnsafe(
        record = record,
        netHourlyRate = marginalNetHourlyRateUnsafe(record.year, record.month, netBreakdown)
    )
}

/**
 * Marginalni neto faktor (1 − doprinosi% − porez%) za dodatne/prekovremene sate.
 * Fiksna poreska olakšica (neoporezivi iznos) već je iskorišćena osnovnom platom,
 * pa dodatni sati nose pun marginalni porez. Time se dnevna procena i "Razlika" u
 * DayEdit popup-u poklapaju sa karticom "Prekovremeno", koja računa istu marginalnu
 * razliku (mesec sa − mesec bez tih sati), umesto da se prosečnim odnosom neto/bruto
 * olakšica pogrešno razmaže i na prekovremene sate.
 */
internal fun ShiftRepository.marginalNetFactorUnsafe(netBreakdown: MonthlyNetPayBreakdown): Double {
    val grossTotal = netBreakdown.grossTotal
    if (grossTotal <= 0.0) return 0.0
    val contributionRate = netBreakdown.employeeContributions / grossTotal
    val taxRate = if (netBreakdown.taxableBase > 0.0) {
        netBreakdown.tax / netBreakdown.taxableBase
    } else {
        0.0
    }
    return (1.0 - contributionRate - taxRate).coerceAtLeast(0.0)
}

/**
 * Marginalna neto satnica (bruto satnica × marginalni faktor) za "Za sate" / manjak sati,
 * da vraćeni i izgubljeni sati nose istu marginalnu vrednost kao prekovremeni rad,
 * a ne prosečnu informativnu satnicu sa razmazanom poreskom olakšicom.
 */
internal fun ShiftRepository.marginalNetHourlyRateUnsafe(
    year: Int,
    month: Int,
    netBreakdown: MonthlyNetPayBreakdown
): Double {
    return grossHourlyRateUnsafe(year, month) * marginalNetFactorUnsafe(netBreakdown)
}

internal fun ShiftRepository.calculateCompensatoryAdditionalNetEffectUnsafe(
    record: DayRecord,
    netHourlyRate: Double
): Double {
    val compensatoryMinutes = record.compensatoryAdditionalWorkMinutes
    if (compensatoryMinutes <= 0 || netHourlyRate <= 0.0) return 0.0

    val balanceBeforeDay = compBalanceMinutesBeforeRecordUnsafe(record)
    if (balanceBeforeDay >= 0) return 0.0

    val effectiveMinutes = compensatoryMinutes.coerceAtMost(-balanceBeforeDay)
    return effectiveMinutes.toDouble() / MINUTES_PER_HOUR * netHourlyRate
}

internal fun ShiftRepository.compBalanceMinutesBeforeRecordUnsafe(record: DayRecord): Int {
    var balance = compBalanceCalculator.minutesAtMonthStart(record.year, record.month)
    val priorMonthRecords = recordStore.getMonthRecords(record.monthKey)
        .asSequence()
        .filter { it.key != record.key && it.day < record.day }
        .sortedBy { it.day }

    for (priorRecord in priorMonthRecords) {
        balance += priorRecord.netCompChangeMinutesFor(payState.brigadeType)
    }
    return balance
}

internal fun ShiftRepository.calculateDailyGrossPayWithSeniorityAndAdditionsUnsafe(record: DayRecord): Double {
    val workGross = calculateDailyGrossWorkPayWithSeniorityUnsafe(record)
    if (workGross <= 0.0) return 0.0
    return workGross + calculateDailyGrossAdditionShareUnsafe(record, monthRecordsWithPreviewUnsafe(record))
}

internal fun ShiftRepository.calculateDailyGrossWorkPayWithSeniorityUnsafe(record: DayRecord): Double {
    val grossHourlyRate = grossHourlyRateUnsafe(record.year, record.month)
    val grossDailyPay = payQueries.calculateDailyPayUnsafe(record, grossHourlyRate)
    val dailySeniorityBonus = payQueries.calculateDailySeniorityBonusUnsafe(record, grossHourlyRate)
    return grossDailyPay + dailySeniorityBonus
}

internal fun ShiftRepository.calculateDailyGrossAdditionShareUnsafe(record: DayRecord, monthRecords: List<DayRecord>): Double {
    val grossHourlyRate = grossHourlyRateUnsafe(record.year, record.month)
    val breakdown = payQueries.calculateMonthlyNetPayBreakdownUnsafe(
        year = record.year,
        month = record.month,
        summary = calculateMonthSummaryUnsafe(record.year, record.month, monthRecords),
        grossHourlyRate = grossHourlyRate
    )
    val additions = breakdown.grossAdditionsTotal
    if (additions <= 0.0) return 0.0
    val paidDays = countPaidRecordsForMonthlyAdditionsUnsafe(monthRecords)
    if (paidDays <= 0) return 0.0
    return additions / paidDays
}

internal fun ShiftRepository.countPaidRecordsForMonthlyAdditionsUnsafe(monthRecords: List<DayRecord>): Int {
    return monthRecords.count { record -> calculateDailyGrossWorkPayWithSeniorityUnsafe(record) > 0.0 }
}

internal fun ShiftRepository.calculateNetPayBreakdownWithPreviewUnsafe(
    record: DayRecord,
    includeFoodAllowance: Boolean,
    includeGrossAdditions: Boolean
): MonthlyNetPayBreakdown {
    val monthRecords = monthRecordsWithPreviewUnsafe(record)
    return payQueries.calculateNetPayBreakdownWithPreviewUnsafe(
        record = record,
        monthRecords = monthRecords,
        includeFoodAllowance = includeFoodAllowance,
        includeGrossAdditions = includeGrossAdditions
    )
}

internal fun ShiftRepository.calculateMonthSummaryUnsafe(year: Int, month: Int, monthRecords: List<DayRecord>): MonthSummary {
    return payQueries.calculateMonthSummaryUnsafe(year, month, monthRecords)
}

internal fun ShiftRepository.monthRecordsWithPreviewUnsafe(record: DayRecord): List<DayRecord> {
    val monthRecords = recordStore.getMonthRecords(record.monthKey)
        .filterNot { saved -> saved.key == record.key }
        .toMutableList()

    if (!isWeekendOff(record.year, record.month, record.day, record.shift)) {
        monthRecords.add(record)
    }
    return monthRecords
}
