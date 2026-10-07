package com.rad.shifttracker.repo

import com.rad.shifttracker.repo.ShiftRepository.Companion.MINUTES_PER_HOUR
import java.time.DayOfWeek
import java.time.LocalDate
import com.rad.shifttracker.obracun.FinalPaymentDeduction
import com.rad.shifttracker.listici.ParsedPayslip
import com.rad.shifttracker.listici.finalPaymentDeductions
import com.rad.shifttracker.listici.payslipFromJsonOrNull
import com.rad.shifttracker.listici.percentDeductionsForMonth
import com.rad.shifttracker.obracun.AdvancePaymentPrediction
import com.rad.shifttracker.obracun.AnnualNetPayAverages
import com.rad.shifttracker.obracun.MonthlyFinalPaymentBreakdown
import com.rad.shifttracker.obracun.MonthlyFoodSpendingBreakdown
import com.rad.shifttracker.obracun.MonthlyGrossAdditions
import com.rad.shifttracker.obracun.MonthlyNetAdjustments
import com.rad.shifttracker.obracun.MonthlyNetPayBreakdown
import com.rad.shifttracker.obracun.MonthlyOvertimeNetImpact
import com.rad.shifttracker.obracun.MonthlyPayDetails
import com.rad.shifttracker.obracun.MonthlyReceipt
import com.rad.shifttracker.obracun.RecentNetPayAverage
import com.rad.shifttracker.obracun.calculateAdvancePaymentPrediction
import com.rad.shifttracker.obracun.calculateMonthlyFinalPaymentBreakdown
import com.rad.shifttracker.obracun.calculateMonthlyFoodSpendingBreakdown
import com.rad.shifttracker.obracun.previousMonthKey
import com.rad.shifttracker.obracun.unavailableAdvancePaymentPrediction
import com.rad.shifttracker.smene.DayRecord
import com.rad.shifttracker.smene.MonthKey
import com.rad.shifttracker.obracun.MonthSummary
import com.rad.shifttracker.smene.Shift

fun ShiftRepository.getNetPayBreakdown(year: Int, month: Int): MonthlyNetPayBreakdown = synchronized(lock) {
    payQueries.calculateMonthlyNetPayBreakdownUnsafe(
        year = year,
        month = month,
        summary = getSummary(year, month),
        includeHourlyValue = true
    )
}

fun ShiftRepository.getMonthlyOvertimeNetImpact(year: Int, month: Int): MonthlyOvertimeNetImpact = synchronized(lock) {
    val monthKey = MonthKey(year, month)
    val monthRecords = recordStore.getMonthRecords(monthKey)
    val summary = getSummary(year, month)
    val currentNetPay = calculateMonthlyWorkOnlyNetPayUnsafe(year, month, summary)
    val compTimeRecoveryNetEffect = calculateCompTimeRecoveryNetEffectUnsafe(monthRecords)
    val deficitNetLoss = calculateDeficitNetLossUnsafe(monthRecords)
    val paidOvertimeNetDifference = calculatePaidOvertimeNetDifferenceUnsafe(year, month, monthRecords, summary)
    val withoutOvertimeNetPay = (currentNetPay - paidOvertimeNetDifference).coerceAtLeast(0.0)
    MonthlyOvertimeNetImpact(
        year = year,
        month = month,
        overtimeHours = summary.overtimeHours,
        currentNetPay = currentNetPay,
        withoutOvertimeNetPay = withoutOvertimeNetPay,
        paidOvertimeNetDifference = paidOvertimeNetDifference,
        compTimeRecoveryNetEffect = compTimeRecoveryNetEffect,
        deficitNetLoss = deficitNetLoss,
        netDifference = paidOvertimeNetDifference + compTimeRecoveryNetEffect - deficitNetLoss
    )
}

// Neto efekat plaćenog prekovremenog = pun bruto prekovremenih minuta × marginalni neto faktor.
// Minuli rad se ne dodaje jer prekovremeni minuti ne ulaze u njegovu osnovicu.
// Marginalni faktor se koristi jer fiksna poreska olakšica otpada u razlici meseca sa i bez prekovremenog.
// Računa se direktno iz minutno preciznog prekovremenog, bez rekonstrukcije zapisa (koja je sekla na cele sate).
internal fun ShiftRepository.calculatePaidOvertimeNetDifferenceUnsafe(
    year: Int,
    month: Int,
    monthRecords: List<DayRecord>,
    summary: MonthSummary
): Double {
    if (monthRecords.isEmpty()) return 0.0
    val details = payQueries.calculateMonthlyPayDetailsUnsafe(year, month, monthRecords, summary)
    if (details.overtimeMinutes <= 0 || details.grossOvertimeFull <= 0.0) return 0.0
    val netBreakdown = payQueries.calculateMonthlyNetPayBreakdownUnsafe(
        year = year,
        month = month,
        summary = summary,
        grossAdditions = MonthlyGrossAdditions(year, month),
        includeRegress = false
    )
    val marginalNetFactor = marginalNetFactorUnsafe(netBreakdown)
    if (marginalNetFactor <= 0.0) return 0.0
    return (details.grossOvertimeFull * marginalNetFactor).coerceAtLeast(0.0)
}

internal fun ShiftRepository.calculateCompTimeRecoveryNetEffectUnsafe(monthRecords: List<DayRecord>): Double {
    val firstRecord = monthRecords.firstOrNull() ?: return 0.0
    val netBreakdown = payQueries.calculateMonthlyNetPayBreakdownUnsafe(
        year = firstRecord.year,
        month = firstRecord.month,
        summary = getSummary(firstRecord.year, firstRecord.month),
        grossAdditions = MonthlyGrossAdditions(firstRecord.year, firstRecord.month),
        includeRegress = false
    )
    val netHourlyRate = marginalNetHourlyRateUnsafe(firstRecord.year, firstRecord.month, netBreakdown)
    if (netHourlyRate <= 0.0) return 0.0

    var balance = compBalanceCalculator.minutesAtMonthStart(firstRecord.year, firstRecord.month)
    var effect = 0.0
    for (record in monthRecords.sortedBy { it.day }) {
        if (balance < 0 && record.compensatoryAdditionalWorkMinutes > 0) {
            val effectiveMinutes = record.compensatoryAdditionalWorkMinutes.coerceAtMost(-balance)
            effect += effectiveMinutes.toDouble() / MINUTES_PER_HOUR * netHourlyRate
        }
        balance += record.netCompChangeMinutesFor(payState.brigadeType)
    }
    return effect
}

internal fun ShiftRepository.calculateDeficitNetLossUnsafe(monthRecords: List<DayRecord>): Double {
    return monthRecords.sumOf { record ->
        val deficitMinutes = record.hoursDeficitMinutesFor(payState.brigadeType)
        if (deficitMinutes <= 0) return@sumOf 0.0
        val replacementNet = calculateEstimatedDailyWorkNetPay(
            record.deficitReplacementRecord(deficitMinutes, monthRecords)
        )
        if (deficitMinutes < MINUTES_PER_HOUR) {
            replacementNet * deficitMinutes.toDouble() / MINUTES_PER_HOUR
        } else {
            replacementNet
        }
    }
}

internal fun ShiftRepository.calculateMonthlyWorkOnlyNetPayUnsafe(year: Int, month: Int, summary: MonthSummary): Double {
    val workOnlySummary = summary.copy(foodAllowance = 0.0, mealEntitlementCalculation = null)
    return payQueries.calculateMonthlyNetPayBreakdownUnsafe(
        year = year,
        month = month,
        summary = workOnlySummary,
        grossAdditions = MonthlyGrossAdditions(year, month),
        includeRegress = false
    ).displayPay
}

fun ShiftRepository.getMonthlyReceipt(year: Int, month: Int): MonthlyReceipt = synchronized(lock) {
    payQueries.calculateMonthlyReceiptUnsafe(year, month)
}

fun ShiftRepository.getMonthlyFinalPaymentBreakdown(year: Int, month: Int): MonthlyFinalPaymentBreakdown = synchronized(lock) {
    val key = MonthKey(year, month)
    val receipt = payQueries.calculateMonthlyReceiptUnsafe(year, month)
    val adjustments = payState.monthlyNetAdjustments[key] ?: MonthlyNetAdjustments(year, month)
    // Obustave u % su već skinute sa neto plate meseca; ovde se prikazuju među obustavama.
    val percentDeductions = if (receipt.isSalaryAvailable) percentDeductionsFromSalaryUnsafe(receipt.salaryMonth) else emptyList()
    calculateMonthlyFinalPaymentBreakdown(
        month = key,
        isNetPayAvailable = receipt.isSalaryAvailable,
        netPayBeforeAdvance = receipt.salaryNetPay + percentDeductions.sumOf { it.amountNet },
        advancePaymentNet = adjustments.advancePaymentNet,
        deductions = if (receipt.isSalaryAvailable) {
            finalPaymentDeductionsUnsafe(receipt.salaryMonth) + percentDeductions
        } else {
            emptyList()
        },
        remainingRegressNetPay = receipt.regressNetPay
    )
}

fun ShiftRepository.getAdvancePaymentPrediction(year: Int, month: Int): AdvancePaymentPrediction = synchronized(lock) {
    val paymentMonth = MonthKey(year, month)
    val previousMonth = previousMonthKey(year, month)
        ?: return@synchronized unavailableAdvancePaymentPrediction(paymentMonth)
    // U prethodnom mesecu su legle akontacija i konačna isplata za mesec pre njega: kad je ta lista
    // sačuvana, uzima se šta je stvarno isplaćeno (i za mesece čije smene nisu popunjene).
    val salaryMonth = previousMonthKey(previousMonth.year, previousMonth.month)
    val payslip = salaryMonth?.let { getSavedPayslip(it.year, it.month) }
    val payslipPaid = payslip?.paidNet
    val payslipAdvance = payslip?.let(::payslipAdvanceOnlyNet)
    if (payslipAdvance != null && payslipPaid != null) {
        // Akontacija upisana iz obračuna akontacije je tačan iznos; lista je rezerva.
        val recordedAdvance = payState.monthlyNetAdjustments[previousMonth]?.advancePaymentNet ?: 0.0
        return@synchronized calculateAdvancePaymentPrediction(
            paymentMonth = paymentMonth,
            previousPaymentMonth = previousMonth,
            isPreviousMonthNetPayAvailable = true,
            previousMonthAdvanceNet = if (recordedAdvance > 0.0) recordedAdvance else payslipAdvance,
            previousMonthFinalNet = payslipPaid.toDouble()
        )
    }
    val previousBreakdown = getMonthlyFinalPaymentBreakdown(previousMonth.year, previousMonth.month)
    calculateAdvancePaymentPrediction(
        paymentMonth = paymentMonth,
        previousPaymentMonth = previousMonth,
        isPreviousMonthNetPayAvailable = previousBreakdown.isNetPayAvailable,
        previousMonthAdvanceNet = previousBreakdown.advancePaymentNet,
        previousMonthFinalNet = previousBreakdown.finalRemainingNetPay
    )
}

/**
 * Akontacija sa liste: kolona „ISPLAĆENO“ sadrži sve isplaćeno pre konačne, pa i regres isplaćen
 * posebno krajem meseca (08/2026: 58.942 = akontacija 38.942 + regres 20.000); regres se oduzima.
 */
internal fun ShiftRepository.payslipAdvanceNet(payslip: ParsedPayslip): Double? = synchronized(lock) { payslipAdvanceOnlyNet(payslip) }

internal fun ShiftRepository.payslipAdvanceOnlyNet(payslip: ParsedPayslip): Double? {
    val paidBefore = payslip.advanceNet?.toDouble() ?: return null
    val regress = if (payslip.extras.any { it.label == "Regres" }) payState.regressNetForMonth(payslip.month) else 0.0
    return (paidBefore - regress).coerceAtLeast(0.0)
}

internal fun ShiftRepository.finalPaymentDeductionsUnsafe(salaryMonth: MonthKey): List<FinalPaymentDeduction> =
    finalPaymentDeductions(salaryMonth, savedPayslipsUnsafe())

/** Obustave u % meseca rada, pod nazivom sa liste, istim iznosom koji je skinut sa neto plate. */
internal fun ShiftRepository.percentDeductionsFromSalaryUnsafe(salaryMonth: MonthKey): List<FinalPaymentDeduction> {
    val percentDeductions = percentDeductionsForMonth(salaryMonth, savedPayslipsUnsafe()) ?: return emptyList()
    val breakdown = payQueries.calculateAnnualMonthNetPayUnsafe(
        year = salaryMonth.year,
        month = salaryMonth.month,
        monthRecords = recordStore.getMonthRecords(salaryMonth),
        includeRegress = false
    )
    if (breakdown.unionDuesNet <= 0.0) return emptyList()
    return percentDeductions.deductions(breakdown.netPayBeforeDeductions).filter { it.amountNet > 0.0 }
}

/** Svi sačuvani listići, svaki pod svojim mesecem; oštećen ili pod tuđim mesecom se preskače. */
internal fun ShiftRepository.savedPayslipsUnsafe(): List<ParsedPayslip> {
    parsedPayslipCache.keys.retainAll(savedPayslipJsonByMonth.keys)
    return savedPayslipJsonByMonth.mapNotNull { (key, json) ->
        val cached = parsedPayslipCache[key]?.takeIf { it.first == json }
        val payslip = if (cached != null) {
            cached.second
        } else {
            payslipFromJsonOrNull(json)?.takeIf { it.month == key }.also { parsedPayslipCache[key] = json to it }
        }
        payslip
    }
}

internal fun ShiftRepository.getMonthlyFoodSpendingBreakdownUnsafe(month: MonthKey): MonthlyFoodSpendingBreakdown {
    val additions = payState.monthlyGrossAdditions[month]
    val foodAllowance = getSummary(month.year, month.month).foodAllowance
    // Ručni unos potrošnje za hranu je ukinut: pojedeno = pripada (vidi MonthlyNetPayBreakdownCalculator).
    return calculateMonthlyFoodSpendingBreakdown(
        month = month,
        foodAllowanceNet = foodAllowance,
        manualFoodSpentNet = null,
        confirmedEntitlementNetOverride = additions?.mealEntitlementNetOverride?.toDouble()
    )
}

fun ShiftRepository.getMonthlyPayDetails(year: Int, month: Int): MonthlyPayDetails = synchronized(lock) {
    val monthKey = MonthKey(year, month)
    val monthRecords = recordStore.getMonthRecords(monthKey)
    val summary = getSummary(year, month)
    payQueries.calculateMonthlyPayDetailsUnsafe(year, month, monthRecords, summary)
}

fun ShiftRepository.getAnnualNetPayAverages(year: Int): AnnualNetPayAverages = synchronized(lock) {
    payQueries.getAnnualNetPayAverages(year)
}

fun ShiftRepository.getRecentNetPayAverage(year: Int, month: Int, asOfDate: LocalDate): RecentNetPayAverage = synchronized(lock) {
    payQueries.getRecentNetPayAverage(year, month, asOfDate)
}

fun ShiftRepository.previewAbsenceRangeNetImpact(
    year: Int,
    month: Int,
    startDay: Int,
    endDay: Int,
    absenceShift: Shift,
    sickPayRate: Double?
): AbsenceRangeNetImpact = synchronized(lock) {
    absenceSimulator.previewAbsenceRangeNetImpact(
        year = year,
        month = month,
        startDay = startDay,
        endDay = endDay,
        absenceShift = absenceShift,
        sickPayRate = sickPayRate
    )
}

fun ShiftRepository.getPreviewNetPayBreakdown(
    year: Int,
    month: Int,
    grossHourlyRate: Double,
    contributionsPercent: Double,
    taxRelief: Double,
    taxPercent: Double
): MonthlyNetPayBreakdown = synchronized(lock) {
    val monthKey = MonthKey(year, month)
    val monthRecords = recordStore.getMonthRecords(monthKey)
    payQueries.calculatePreviewNetPayBreakdownUnsafe(
        year = year,
        month = month,
        monthRecords = monthRecords,
        grossHourlyRate = grossHourlyRate,
        contributionsPercent = contributionsPercent,
        taxRelief = taxRelief,
        taxPercent = taxPercent
    )
}

fun ShiftRepository.previewPayCoefficientNetDifference(
    year: Int,
    month: Int,
    coefficient: Double,
    hourlyValue: Double
): Double = synchronized(lock) {
    if (
        year <= 0 ||
        month !in 1..12 ||
        !coefficient.isFinite() ||
        coefficient <= 0.0 ||
        !hourlyValue.isFinite() ||
        hourlyValue <= 0.0
    ) {
        return@synchronized 0.0
    }
    val monthKey = MonthKey(year, month)
    val monthRecords = recordStore.getMonthRecords(monthKey)
    val currentSummary = calculateMonthSummaryUnsafe(year, month, monthRecords)
    val currentNetPay = payQueries.calculateMonthlyNetPayBreakdownUnsafe(
        year = year,
        month = month,
        summary = currentSummary
    ).displayPay
    val previewNetPay = payQueries.calculatePreviewNetPayBreakdownUnsafe(
        year = year,
        month = month,
        monthRecords = monthRecords,
        grossHourlyRate = coefficient * hourlyValue,
        contributionsPercent = payState.employeeContributionsPercent,
        taxRelief = payState.taxRelief,
        taxPercent = payState.taxPercent
    ).displayPay
    previewNetPay - currentNetPay
}

internal fun DayRecord.deficitReplacementRecord(
    deficitMinutes: Int,
    monthRecords: List<DayRecord>
): DayRecord {
    val wholeHours = deficitMinutes / MINUTES_PER_HOUR
    val extraMinutes = deficitMinutes % MINUTES_PER_HOUR
    val replacementHours = wholeHours.coerceAtLeast(1)
    val replacementShortageMinutes = if (wholeHours > 0) extraMinutes else 0
    val replacementShift = inferDeficitReplacementShift(monthRecords)
    val replacementHolidayWork = holidayWorkOverride ?: if (isHoliday) true else null
    return DayRecord(
        year = year,
        month = month,
        day = day,
        shift = replacementShift,
        hours = replacementHours,
        holidayWorkOverride = replacementHolidayWork,
        holidayOverride = holidayOverride,
        regularShortageMinutes = replacementShortageMinutes
    )
}

internal fun DayRecord.inferDeficitReplacementShift(monthRecords: List<DayRecord>): Shift {
    if (shift in Shift.workShifts()) return shift
    if (shift != Shift.OFF) return Shift.FIRST

    val weekStart = date.minusDays((date.dayOfWeek.value - DayOfWeek.MONDAY.value).toLong())
    val weekEnd = weekStart.plusDays(4)
    val populatedShifts = monthRecords.asSequence()
        .filter { it.key != key }
        .filter { !it.date.isBefore(weekStart) && !it.date.isAfter(weekEnd) }
        .filter { it.shift in Shift.workShifts() }
        .map { it.shift }
        .distinct()
        .toList()
    return if (populatedShifts.size == 1) populatedShifts.single() else Shift.FIRST
}
