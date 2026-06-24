package com.rad.shifttracker

import com.rad.shifttracker.data.storage.JsonWriter
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.util.Locale

private const val MINUTES_PER_HOUR = 60

internal fun monthlyWorkAndPayExportFileName(year: Int, month: Int): String {
    return String.format(Locale.US, "rad_obracun_%04d_%02d.json", year, month)
}

fun ShiftRepository.exportSelectedMonthWorkAndPayJson(
    selectedYear: Int,
    selectedMonth: Int,
    appName: String = "Rad",
    appVersion: String? = null,
    exportTimestamp: String = Instant.now().toString()
): String {
    return JsonWriter.write(
        buildMonthlyWorkAndPayExportMap(
            repo = this,
            selectedYear = selectedYear,
            selectedMonth = selectedMonth,
            appName = appName,
            appVersion = appVersion,
            exportTimestamp = exportTimestamp
        )
    )
}

internal fun buildMonthlyWorkAndPayExportMap(
    repo: ShiftRepository,
    selectedYear: Int,
    selectedMonth: Int,
    appName: String,
    appVersion: String?,
    exportTimestamp: String
): Map<String, Any?> {
    val target = MonthKey(selectedYear, selectedMonth)
    val records = repo.getMonthRecords(selectedYear, selectedMonth)
    val recordsByDay = records.associateBy { it.day }
    val summary = repo.getSummary(selectedYear, selectedMonth)
    val payBreakdown = repo.getNetPayBreakdown(selectedYear, selectedMonth)
    val payDetails = repo.getMonthlyPayDetails(selectedYear, selectedMonth)
    val dailyCategories = DailyPayCategoryBreakdownCalculator(
        payCalculator = exportPayCalculator(repo),
        difficultConditionsPercent = repo.difficultWorkConditionsPercentValue
    ).calculateForMonth(records, selectedYear, selectedMonth, repo.brigadeTypeValue)
    val monthWorkEntries = buildMonthWorkEntries(target, recordsByDay, dailyCategories)
    val grossAdditionsBreakdown = grossAdditionsBreakdownMap(
        payBreakdown = payBreakdown,
        contributionsPercent = repo.employeeContributionsPercentValue,
        taxPercent = repo.taxPercentValue
    )
    val netDeductionsBreakdown = netDeductionsBreakdownMap(payBreakdown)
    return linkedMapOf(
        "exportInfo" to exportInfoMap(appName, appVersion, exportTimestamp, target),
        "monthWorkEntries" to monthWorkEntries,
        "dailyCalculationBreakdown" to monthWorkEntries.map { it["dailyCalculationBreakdown"] },
        "monthlySummary" to monthlySummaryMap(summary, payBreakdown, payDetails),
        "exportedMonthAuditStatus" to exportedMonthAuditStatusMap(target, summary),
        "grossAdditionsBreakdown" to grossAdditionsBreakdown,
        "netDeductionsBreakdown" to netDeductionsBreakdown,
        "auditFormulas" to auditFormulasMap(repo, target),
        "auditChecks" to auditChecksMap(
            target = target,
            payBreakdown = payBreakdown,
            payDetails = payDetails,
            grossAdditionsBreakdown = grossAdditionsBreakdown,
            netDeductionsBreakdown = netDeductionsBreakdown
        ),
        "settingsSnapshot" to settingsSnapshotMap(repo, target),
        "rawCalculationDebug" to rawCalculationDebugMap(repo, target, summary, payBreakdown, payDetails)
    )
}

private fun exportPayCalculator(repo: ShiftRepository): PayCalculator {
    return PayCalculator(
        CONFIG.pay.copy(
            foodPerDay = repo.foodPerDayValue,
            sickPayBaseHourly = repo.sickPayBaseHourlyValue,
            difficultWorkConditionsPercent = repo.difficultWorkConditionsPercentValue
        )
    )
}

private fun exportInfoMap(
    appName: String,
    appVersion: String?,
    exportTimestamp: String,
    target: MonthKey
): Map<String, Any?> {
    val label = monthYearText(target)
    return linkedMapOf(
        "appName" to appName,
        "appVersion" to appVersion,
        "exportTimestamp" to exportTimestamp,
        "selectedYear" to target.year,
        "selectedMonth" to target.month,
        "monthLabel" to label,
        "exportTargetYear" to target.year,
        "exportTargetMonth" to target.month,
        "exportTargetMonthLabel" to label
    )
}

private fun buildMonthWorkEntries(
    target: MonthKey,
    recordsByDay: Map<Int, DayRecord>,
    dailyCategories: Map<DayKey, DailyPayCategoryBreakdown>
): List<Map<String, Any?>> {
    val yearMonth = YearMonth.of(target.year, target.month)
    return (1..yearMonth.lengthOfMonth()).map { day ->
        val date = LocalDate.of(target.year, target.month, day)
        val record = recordsByDay[day]
        val categories = dailyCategories.getValue(DayKey(target.year, target.month, day))
        monthWorkDayMap(date, record, categories)
    }
}

private fun monthWorkDayMap(
    date: LocalDate,
    record: DayRecord?,
    categories: DailyPayCategoryBreakdown
): Map<String, Any?> {
    val dayFlags = dayFlagMap(date, record)
    val enteredMinutes = record?.totalWorkMinutes ?: 0
    val plannedMinutes = plannedShiftMinutes(record)
    return linkedMapOf(
        "date" to date.toString(),
        "dayOfWeek" to WEEKDAY_NAMES[Weekday.from(date).ordinal],
        "isWorkingDay" to dayFlags["isWorkingDay"],
        "isWeekend" to dayFlags["isWeekend"],
        "isSaturday" to dayFlags["isSaturday"],
        "isSunday" to dayFlags["isSunday"],
        "isPublicHoliday" to dayFlags["isPublicHoliday"],
        "publicHolidayName" to dayFlags["publicHolidayName"],
        "hasEntry" to (record != null),
        "shiftType" to record?.shift?.name,
        "shiftName" to record?.shift?.fullName,
        "plannedStart" to null,
        "plannedEnd" to null,
        "actualStart" to null,
        "actualEnd" to null,
        "plannedShiftHours" to hoursPart(plannedMinutes),
        "plannedShiftMinutes" to minutesPart(plannedMinutes),
        "plannedShiftTotalMinutes" to plannedMinutes,
        "enteredHours" to hoursPart(enteredMinutes),
        "enteredMinutes" to minutesPart(enteredMinutes),
        "enteredTotalMinutes" to enteredMinutes,
        "totalMinutes" to enteredMinutes,
        "regularHours" to hoursPart(categories.regularWorkMinutes),
        "regularMinutes" to minutesPart(categories.regularWorkMinutes),
        "regularTotalMinutes" to categories.regularWorkMinutes,
        "absenceMinutes" to categories.absenceMinutes,
        "nightWorkMinutes" to categories.nightWorkMinutes,
        "overtimeMinutes" to categories.overtimeMinutes,
        "sundayWorkMinutes" to categories.sundayWorkMinutes,
        "holidayWorkMinutes" to categories.holidayWorkMinutes,
        "difficultConditionsMinutes" to categories.difficultConditionsMinutes,
        "sickLeaveMinutes" to categories.sickLeaveMinutes,
        "vacationMinutes" to categories.vacationMinutes,
        "paidHolidayMinutes" to categories.paidHolidayMinutes,
        "note" to null,
        "overtimeType" to record?.overtimeType?.name,
        "weekendOvertimeType" to record?.weekendOvertimeType?.name,
        "compHoursUsed" to (record?.compHoursUsed ?: 0),
        "compTimeEarnedMinutes" to (record?.compTimeEarnedMinutes ?: 0),
        "holidayWorkOverride" to record?.holidayWorkOverride,
        "holidayOverride" to record?.holidayOverride,
        "balanceExempt" to (record?.balanceExempt ?: false),
        "sickPayRate" to record?.sickPayRate,
        "regularShortageMinutes" to (record?.regularShortageMinutes ?: 0),
        "rawRecord" to record?.toMap(),
        "dailyCalculationBreakdown" to dailyCalculationBreakdownMap(date, record, categories, plannedMinutes)
    )
}

private fun dailyCalculationBreakdownMap(
    date: LocalDate,
    record: DayRecord?,
    categories: DailyPayCategoryBreakdown,
    plannedMinutes: Int
): Map<String, Any?> {
    val dayFlags = dayFlagMap(date, record)
    val enteredMinutes = record?.totalWorkMinutes ?: 0
    return linkedMapOf(
        "date" to date.toString(),
        "isWeekend" to dayFlags["isWeekend"],
        "isSaturday" to dayFlags["isSaturday"],
        "isSunday" to dayFlags["isSunday"],
        "isPublicHoliday" to dayFlags["isPublicHoliday"],
        "publicHolidayName" to dayFlags["publicHolidayName"],
        "isWorkingDay" to dayFlags["isWorkingDay"],
        "hasEntry" to (record != null),
        "enteredShift" to record?.shift?.fullName,
        "enteredHours" to hoursPart(enteredMinutes),
        "enteredMinutes" to minutesPart(enteredMinutes),
        "enteredTotalMinutes" to enteredMinutes,
        "plannedShiftHours" to hoursPart(plannedMinutes),
        "plannedShiftMinutes" to minutesPart(plannedMinutes),
        "plannedShiftTotalMinutes" to plannedMinutes,
        "workedRegularHours" to hoursPart(categories.regularWorkMinutes),
        "workedRegularMinutes" to minutesPart(categories.regularWorkMinutes),
        "workedRegularTotalMinutes" to categories.regularWorkMinutes,
        "absenceHours" to hoursPart(categories.absenceMinutes),
        "absenceMinutes" to categories.absenceMinutes,
        "earlyLeaveMinutes" to earlyLeaveMinutes(record, categories),
        "nightWorkHours" to hoursPart(categories.nightWorkMinutes),
        "nightWorkMinutes" to minutesPart(categories.nightWorkMinutes),
        "nightWorkTotalMinutes" to categories.nightWorkMinutes,
        "overtimeHours" to hoursPart(categories.overtimeMinutes),
        "overtimeMinutes" to categories.overtimeMinutes,
        "sundayWorkHours" to hoursPart(categories.sundayWorkMinutes),
        "sundayWorkMinutes" to categories.sundayWorkMinutes,
        "holidayWorkHours" to hoursPart(categories.holidayWorkMinutes),
        "holidayWorkMinutes" to categories.holidayWorkMinutes,
        "difficultConditionsHours" to hoursPart(categories.difficultConditionsMinutes),
        "difficultConditionsMinutes" to categories.difficultConditionsMinutes,
        "sickLeaveHours" to hoursPart(categories.sickLeaveMinutes),
        "vacationHours" to hoursPart(categories.vacationMinutes),
        "paidHolidayHours" to hoursPart(categories.paidHolidayMinutes),
        "paidHolidayMinutes" to categories.paidHolidayMinutes,
        "countsIntoWorkBalance" to categories.countsIntoWorkBalance,
        "workBalanceDeltaMinutes" to categories.workBalanceDeltaMinutes,
        "payCategoriesApplied" to categories.payCategoriesApplied
    )
}

private fun dayFlagMap(date: LocalDate, record: DayRecord?): Map<String, Any?> {
    val weekday = Weekday.from(date)
    val holiday = record?.isHoliday ?: isSerbianHoliday(date.year, date.monthValue, date.dayOfMonth)
    val holidayName = record?.holidayName ?: getHolidayName(date.year, date.monthValue, date.dayOfMonth)
    return linkedMapOf(
        "isWorkingDay" to weekday.isWorkday,
        "isWeekend" to weekday.isWeekend,
        "isSaturday" to (weekday == Weekday.SATURDAY),
        "isSunday" to (weekday == Weekday.SUNDAY),
        "isPublicHoliday" to holiday,
        "publicHolidayName" to holidayName
    )
}

private fun plannedShiftMinutes(record: DayRecord?): Int {
    return if (record?.shift in Shift.workShifts() || record?.shift in Shift.paidNonWork()) {
        CONFIG.pay.defaultHours * MINUTES_PER_HOUR
    } else {
        0
    }
}

private fun earlyLeaveMinutes(record: DayRecord?, categories: DailyPayCategoryBreakdown): Int {
    return if (record?.shift in Shift.workShifts()) categories.absenceMinutes else 0
}

private fun monthlySummaryMap(
    summary: MonthSummary,
    payBreakdown: MonthlyNetPayBreakdown,
    payDetails: MonthlyPayDetails
): Map<String, Any?> {
    return linkedMapOf(
        "year" to summary.year,
        "month" to summary.month,
        "monthLabel" to summary.displayName,
        "monthlyFundHours" to payBreakdown.monthlyFundHours,
        "workHours" to summary.workHours,
        "workMinutes" to summary.workMinutes,
        "overtimeHours" to summary.overtimeHours,
        "sickHours" to summary.sickHours,
        "vacationHours" to summary.vacationHours,
        "sundayHours" to summary.sundayHours,
        "nightHours" to summary.nightHours,
        "workDays" to summary.workDays,
        "doubleShiftDays" to summary.doubleShiftDays,
        "foodAllowance" to summary.foodAllowance,
        "compTimeEarned" to summary.compTimeEarned,
        "compTimeDeficit" to summary.compTimeDeficit,
        "compTimeUsed" to summary.compTimeUsed,
        "compTimeNet" to summary.compTimeNet,
        "compTimeStarting" to summary.compTimeStarting,
        "compTimeEnding" to summary.compTimeEnding,
        "compTimeEarnedMinutes" to summary.compTimeEarnedMinutes,
        "compTimeDeficitMinutes" to summary.compTimeDeficitMinutes,
        "compTimeUsedMinutes" to summary.compTimeUsedMinutes,
        "compTimeNetMinutes" to summary.compTimeNetMinutes,
        "compTimeStartingMinutes" to summary.compTimeStartingMinutes,
        "compTimeEndingMinutes" to summary.compTimeEndingMinutes,
        "yearsOfService" to summary.yearsOfService,
        "seniorityEligibleWorkMinutes" to summary.seniorityEligibleWorkMinutes,
        "grossBaseBeforeSeniority" to payBreakdown.grossBaseBeforeSeniority,
        "grossSeniorityBonus" to payBreakdown.grossSeniorityBonus,
        "grossAdditionsTotal" to payBreakdown.grossAdditionsTotal,
        "grossTotal" to payBreakdown.grossTotal,
        "employeeContributions" to payBreakdown.employeeContributions,
        "taxRelief" to payBreakdown.taxRelief,
        "taxableBase" to payBreakdown.taxableBase,
        "tax" to payBreakdown.tax,
        "netPayBeforeDeductions" to payBreakdown.netPayBeforeDeductions,
        "netSeniorityBonus" to payBreakdown.netSeniorityBonus,
        "mealAllowanceNet" to payBreakdown.mealAllowanceNet,
        "mealSpentNet" to payBreakdown.mealSpentNet,
        "netDeductionsTotal" to payBreakdown.netDeductionsTotal,
        "displayPay" to payBreakdown.displayPay,
        "payDetails" to monthlyPayDetailsMap(payDetails)
    )
}

private fun settingsSnapshotMap(repo: ShiftRepository, target: MonthKey): Map<String, Any?> {
    val payBaseSourceMonth = repo.getPayBaseSourceMonthForWorkMonth(target.year, target.month)
    val resolvedPayBases = repo.getResolvedPayBasesForWorkMonth(target.year, target.month)
    return linkedMapOf(
        "grossHourlyRate" to repo.getGrossHourlyRateForMonth(target.year, target.month),
        "hourlyValue" to repo.getHourlyValueForMonth(target.year, target.month),
        "payCoefficient" to repo.payCoefficientValue,
        "hourlyValueChanges" to repo.getHourlyValueChanges().map { hourlyValueChangeMap(it) },
        "sickPayBaseHourly" to repo.sickPayBaseHourlyValue,
        "defaultPayBaseSelectionMode" to repo.defaultPayBaseSelectionModeValue.name,
        "foodPerDay" to repo.foodPerDayValue,
        "transportDailyAmount" to repo.transportDailyAmountValue,
        "employeeContributionsPercent" to repo.employeeContributionsPercentValue,
        "taxRelief" to repo.taxReliefValue,
        "taxPercent" to repo.taxPercentValue,
        "difficultWorkConditionsPercent" to repo.difficultWorkConditionsPercentValue,
        "initialCompBalance" to repo.initialCompBalanceValue,
        "initialCompBalanceMinutes" to repo.initialCompBalanceMinutesValue,
        "minuliRadStartYear" to repo.minuliRadStartYearValue,
        "minuliRadStartMonth" to repo.minuliRadStartMonthValue,
        "brigadeType" to repo.brigadeTypeValue.name,
        "initialVacationBalance" to repo.initialVacationBalanceValue,
        "payBaseSourceYear" to payBaseSourceMonth.year,
        "payBaseSourceMonth" to payBaseSourceMonth.month,
        "monthlyPayBases" to repo.getMonthlyPayBases(payBaseSourceMonth.year, payBaseSourceMonth.month)
            ?.let { monthlyPayBasesMap(it) },
        "resolvedPayBases" to resolvedPayBasesMap(resolvedPayBases),
        "knownPayBaseAnchor" to repo.getKnownPayBaseAnchor()?.let { knownPayBaseAnchorMap(it) },
        "payBaseResolutionDebug" to payBaseResolutionDebugMap(repo, target, payBaseSourceMonth, resolvedPayBases),
        "rollingPayBaseCalculation" to repo.getRollingPayBaseCalculationForWorkMonth(target.year, target.month)?.let {
            rollingPayBaseCalculationMap(it)
        },
        "monthlyGrossAdditions" to monthlyGrossAdditionsMap(repo.getMonthlyGrossAdditions(target.year, target.month)),
        "shiftSettings" to shiftSettingsMap(),
        "payRuleSettings" to payRuleSettingsMap(),
        "publicHolidaySettings" to publicHolidaySettingsMap(repo, target.year)
    )
}

private fun rawCalculationDebugMap(
    repo: ShiftRepository,
    target: MonthKey,
    summary: MonthSummary,
    payBreakdown: MonthlyNetPayBreakdown,
    payDetails: MonthlyPayDetails
): Map<String, Any?> {
    val payBaseSourceMonth = repo.getPayBaseSourceMonthForWorkMonth(target.year, target.month)
    val resolvedPayBases = repo.getResolvedPayBasesForWorkMonth(target.year, target.month)
    return linkedMapOf(
        "selectedYear" to target.year,
        "selectedMonth" to target.month,
        "grossBeforeSeniority" to payBreakdown.grossBaseBeforeSeniority,
        "seniorityBonusGross" to payBreakdown.grossSeniorityBonus,
        "grossTotal" to payBreakdown.grossTotal,
        "employeeContributions" to payBreakdown.employeeContributions,
        "taxableBase" to payBreakdown.taxableBase,
        "tax" to payBreakdown.tax,
        "netBeforeDeductions" to payBreakdown.netPayBeforeDeductions,
        "netAfterDeductions" to payBreakdown.displayPay,
        "sickPayBaseHourly" to payDetails.sickPayBaseHourly,
        "sickPayBase" to resolvedPayBaseAuditMap(resolvedPayBases.sickHourlyBase),
        "vacationPayBase" to resolvedPayBaseMap(payDetails.vacationPayBase),
        "paidHolidayPayBase" to resolvedPayBaseMap(payDetails.paidHolidayPayBase),
        "payBaseResolution" to payBaseResolutionDebugMap(
            repo,
            target,
            payBaseSourceMonth,
            resolvedPayBases
        ),
        "monthlyFundHoursUsed" to payBreakdown.monthlyFundHours,
        "grossHourlyRateUsed" to payBreakdown.grossHourlyRate,
        "hourlyValueUsed" to repo.getHourlyValueForMonth(target.year, target.month),
        "totalPaidHours" to payDetails.totalPaidHours,
        "totalPaidMinutes" to payDetails.totalPaidMinutes,
        "paidWorkHours" to payDetails.paidWorkHours,
        "paidWorkMinutes" to payDetails.paidWorkMinutes,
        "seniorityEligibleWorkMinutes" to summary.seniorityEligibleWorkMinutes,
        "summaryTotalPayBeforeSeniority" to summary.totalPay,
        "summaryTotalWithSeniority" to summary.totalWithMinuli,
        "rollingPayBaseCalculation" to repo.getRollingPayBaseCalculationForWorkMonth(target.year, target.month)?.let {
            rollingPayBaseCalculationMap(it)
        }
    )
}

private fun monthlyPayDetailsMap(details: MonthlyPayDetails): Map<String, Any?> {
    return linkedMapOf(
        "totalPaidHours" to details.totalPaidHours,
        "totalPaidMinutes" to details.totalPaidMinutes,
        "paidWorkHours" to details.paidWorkHours,
        "paidWorkMinutes" to details.paidWorkMinutes,
        "nightHours" to details.nightHours,
        "nightMinutes" to details.nightMinutes,
        "overtimeHours" to details.overtimeHours,
        "overtimeMinutes" to details.overtimeMinutes,
        "sundayHours" to details.sundayHours,
        "sundayMinutes" to details.sundayMinutes,
        "sickHours" to details.sickHours,
        "sickMinutes" to details.sickMinutes,
        "vacationHours" to details.vacationHours,
        "vacationMinutes" to details.vacationMinutes,
        "paidHolidayHours" to details.paidHolidayHours,
        "paidHolidayMinutes" to details.paidHolidayMinutes,
        "coveredDeficitHours" to details.coveredDeficitHours,
        "coveredDeficitMinutes" to details.coveredDeficitMinutes,
        "uncoveredDeficitHours" to details.uncoveredDeficitHours,
        "uncoveredDeficitMinutes" to details.uncoveredDeficitMinutes,
        "grossBaseRegular" to details.grossBaseRegular,
        "grossNightPremium" to details.grossNightPremium,
        "grossOvertimePremium" to details.grossOvertimePremium,
        "grossSundayPremium" to details.grossSundayPremium,
        "grossHolidayPremium" to details.grossHolidayPremium,
        "grossDifficultWorkConditions" to details.grossDifficultWorkConditions,
        "grossSickPay" to details.grossSickPay,
        "grossVacationPay" to details.grossVacationPay,
        "grossPaidHoliday" to details.grossPaidHoliday,
        "grossCoveredDeficitPay" to details.grossCoveredDeficitPay,
        "grossUncoveredDeficitLoss" to details.grossUncoveredDeficitLoss,
        "grossTotalBeforeSeniority" to details.grossTotalBeforeSeniority,
        "grossTotalWithSeniority" to details.grossTotalWithSeniority,
        "netTotalBeforeSeniority" to details.netTotalBeforeSeniority,
        "netSeniorityBonus" to details.netSeniorityBonus,
        "netUncoveredDeficitLoss" to details.netUncoveredDeficitLoss,
        "netTotal" to details.netTotal
    )
}

private fun hourlyValueChangeMap(change: HourlyValueChange): Map<String, Any?> {
    return linkedMapOf(
        "year" to change.year,
        "month" to change.month,
        "value" to change.value
    )
}

private fun monthlyPayBasesMap(bases: MonthlyPayBases): Map<String, Any?> {
    return linkedMapOf(
        "year" to bases.year,
        "month" to bases.month,
        "vacationHourlyBase" to bases.vacationHourlyBase,
        "paidHolidayHourlyBase" to bases.paidHolidayHourlyBase,
        "sickHourlyBase" to bases.sickHourlyBase,
        "selectionMode" to bases.selectionMode?.name
    )
}

private fun resolvedPayBasesMap(bases: ResolvedPayBases): Map<String, Any?> {
    return linkedMapOf(
        "vacationHourlyBase" to resolvedPayBaseMap(bases.vacationHourlyBase),
        "paidHolidayHourlyBase" to resolvedPayBaseMap(bases.paidHolidayHourlyBase),
        "sickHourlyBase" to resolvedPayBaseMap(bases.sickHourlyBase)
    )
}

private fun resolvedPayBaseMap(base: ResolvedPayBase): Map<String, Any?> {
    return linkedMapOf(
        "value" to base.value,
        "source" to base.source.name,
        "sourceYear" to base.sourceYear,
        "sourceMonth" to base.sourceMonth,
        "calculation" to base.calculation?.let { rollingPayBaseCalculationMap(it) }
    )
}

private fun knownPayBaseAnchorMap(anchor: KnownPayBaseAnchor): Map<String, Any?> {
    return linkedMapOf(
        "year" to anchor.month.year,
        "month" to anchor.month.month,
        "grossHourlyBase" to anchor.grossHourlyBase
    )
}

private fun rollingPayBaseCalculationMap(calculation: RollingPayBaseCalculation): Map<String, Any?> {
    return linkedMapOf(
        "hourlyBase" to calculation.hourlyBase,
        "grossTotal" to calculation.grossTotal,
        "hoursTotal" to calculation.hoursTotal,
        "months" to calculation.months.map { month ->
            linkedMapOf(
                "year" to month.year,
                "month" to month.month,
                "grossForAverage" to month.grossForAverage,
                "hoursForAverage" to month.hoursForAverage,
                "source" to month.source.name
            )
        }
    )
}

private fun monthlyGrossAdditionsMap(additions: MonthlyGrossAdditions): Map<String, Any?> {
    return linkedMapOf(
        "year" to additions.year,
        "month" to additions.month,
        "mealGross" to additions.mealGross,
        "regressGross" to additions.regressGross,
        "bonusGross" to additions.bonusGross,
        "mealSpentNet" to additions.mealSpentNet,
        "bonusNet" to additions.bonusNet
    )
}

private fun shiftSettingsMap(): List<Map<String, Any?>> {
    return Shift.values().map { shift ->
        linkedMapOf(
            "type" to shift.name,
            "value" to shift.value,
            "displayName" to shift.displayName,
            "fullName" to shift.fullName,
            "defaultPlannedMinutes" to plannedShiftMinutes(DayRecord(2000, 1, 3, shift, CONFIG.pay.defaultHours))
        )
    }
}

private fun payRuleSettingsMap(): Map<String, Any?> {
    return linkedMapOf(
        "defaultShiftHours" to CONFIG.pay.defaultHours,
        "shift3Premium" to CONFIG.pay.shift3Premium,
        "overtimePremium" to CONFIG.pay.overtimePremium,
        "sundayBonus" to CONFIG.pay.sundayBonus,
        "holidayBonus" to CONFIG.pay.holidayBonus,
        "sickPayRate" to CONFIG.pay.sickPayRate,
        "vacationPayRate" to CONFIG.pay.vacationPayRate,
        "currency" to CONFIG.pay.currency,
        "minuliRadPerYear" to CONFIG.pay.minuliRadPerYear
    )
}

private fun publicHolidaySettingsMap(repo: ShiftRepository, year: Int): Map<String, Any?> {
    return linkedMapOf(
        "source" to "embedded_and_manual",
        "year" to year,
        "holidays" to repo.getHolidayEntries(year).map { entry ->
            linkedMapOf(
                "date" to LocalDate.of(entry.year, entry.month, entry.day).toString(),
                "name" to entry.name,
                "isActive" to entry.isActive,
                "source" to entry.source.name
            )
        }
    )
}

private fun hoursPart(totalMinutes: Int): Int {
    return totalMinutes / MINUTES_PER_HOUR
}

private fun minutesPart(totalMinutes: Int): Int {
    return totalMinutes % MINUTES_PER_HOUR
}
