package com.rad.shifttracker

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth

private const val PAYROLL_SLIP_WORK_WEEK_DAYS = 5
private const val PAYROLL_SLIP_MINUTES_PER_HOUR = 60
private val PAYROLL_SLIP_COMPARISON_LABELS = listOf(
    "Rad u režiji",
    "Noćni rad",
    "Prekovremeni rad",
    "Godišnji",
    "Bolovanje",
    "Državni praznik",
    "Rad u režiji na DP",
    "Rad nedeljom",
    "Rad na praznik",
    "Izostanci"
)

class PayrollSlipReconstructionService {
    fun inferThreeBrigadeReference(repo: ShiftRepository, target: MonthKey): ShiftFillSettings? {
        for (month in repo.getAllMonthsSorted().filter { it > target && it.isValidMonthKey() }) {
            val recordsByDate = repo.getMonthRecords(month.year, month.month).associateBy { it.date }
            val yearMonth = YearMonth.of(month.year, month.month)

            for (day in 1..yearMonth.lengthOfMonth()) {
                val date = LocalDate.of(month.year, month.month, day)
                if (date.dayOfWeek != DayOfWeek.MONDAY) continue

                val shift = inferReliableWeekShift(date, recordsByDate) ?: continue
                return ShiftFillSettings(
                    mode = ShiftFillMode.THREE_BRIGADE,
                    anchorDate = date,
                    selectedShift = shift
                )
            }
        }

        return null
    }

    fun reconstruct(repo: ShiftRepository, input: PayrollSlipInput): PayrollSlipReconstructionResult {
        val warnings = mutableListOf<PayrollSlipWarning>()
        val validationErrors = input.validationErrors()
        val hasValidInput = validationErrors.isEmpty()
        val reference = if (hasValidInput) inferThreeBrigadeReference(repo, input.month) else null
        warnings += unsupportedMinuteWarnings(input)

        if (hasValidInput && reference == null) {
            warnings += PayrollSlipWarning(
                PayrollSlipWarningType.NO_REFERENCE_WEEK,
                "Nije pronađena pouzdana referentna sedmica za trobrigadu."
            )
        }
        if (input.month.isValidMonthKey() && repo.getMonthRecords(input.month.year, input.month.month).isNotEmpty()) {
            warnings += PayrollSlipWarning(
                PayrollSlipWarningType.EXISTING_MONTH_WILL_BE_REPLACED,
                "Primena predloga će zameniti postojeće unose za izabrani mesec."
            )
        }
        if (hasValidInput) {
            warnings += validateFundTargets(input)
        }

        val records = if (hasValidInput) {
            generateTargetDrafts(input)
        } else {
            emptyList()
        }
        if (records.isNotEmpty()) {
            warnings += PayrollSlipWarning(
                PayrollSlipWarningType.APPROXIMATE_DATES,
                "Datumi su približni jer platni listić sadrži zbirne sate, ne dnevni raspored."
            )
        }

        val additions = input.toMonthlyGrossAdditions()
        val comparison = compare(repo, input, records, additions)
        if (!comparison.allHoursExact) {
            warnings += PayrollSlipWarning(
                PayrollSlipWarningType.TARGET_MISMATCH,
                "Predlog se ne poklapa potpuno sa svim satima sa listića."
            )
        }
        return PayrollSlipReconstructionResult(
            input = input,
            records = records,
            additions = additions,
            comparison = comparison,
            warnings = warnings
        )
    }

    private fun unsupportedMinuteWarnings(input: PayrollSlipInput): List<PayrollSlipWarning> {
        val warnings = mutableListOf<PayrollSlipWarning>()
        if (input.vacationHours > 0.0 && input.vacationHours % 1.0 != 0.0) {
            warnings += PayrollSlipWarning(
                PayrollSlipWarningType.UNSUPPORTED_ABSENCE_MINUTES,
                "Godišnji i bolovanje mogu da se raspodele samo na cele sate."
            )
        } else if (input.sickHours > 0.0 && input.sickHours % 1.0 != 0.0) {
            warnings += PayrollSlipWarning(
                PayrollSlipWarningType.UNSUPPORTED_ABSENCE_MINUTES,
                "Godišnji i bolovanje mogu da se raspodele samo na cele sate."
            )
        }
        if (input.absenceHours > 0.0 && slipHoursToMinutes(input.absenceHours) == null) {
            warnings += PayrollSlipWarning(
                PayrollSlipWarningType.UNSUPPORTED_ABSENCE_MINUTES,
                "Izostanci moraju biti u koraku od 15 minuta."
            )
        }
        return warnings
    }

    private fun validateFundTargets(input: PayrollSlipInput): List<PayrollSlipWarning> {
        if (!input.month.isValidMonthKey()) return emptyList()

        val regularMinutes = slipHoursToMinutes(input.regularWorkHours) ?: return emptyList()
        val regularHolidayMinutes = slipHoursToMinutes(input.regularHolidayWorkHours) ?: return emptyList()
        val vacationMinutes = slipHoursToMinutes(input.vacationHours) ?: return emptyList()
        val sickMinutes = slipHoursToMinutes(input.sickHours) ?: return emptyList()
        val holidayMinutes = slipHoursToMinutes(input.holidayHours) ?: return emptyList()
        val absenceMinutes = slipHoursToMinutes(input.absenceHours) ?: return emptyList()

        val warnings = mutableListOf<PayrollSlipWarning>()
        val coveredFundMinutes = regularMinutes + regularHolidayMinutes + vacationMinutes +
            sickMinutes + holidayMinutes + absenceMinutes
        val monthlyFundMinutes = calculateMonthlyFundHours(
            input.month.year,
            input.month.month,
            CONFIG.pay.defaultHours
        ) * PAYROLL_SLIP_MINUTES_PER_HOUR
        if (monthlyFundMinutes > 0 && coveredFundMinutes != monthlyFundMinutes) {
            warnings += PayrollSlipWarning(
                PayrollSlipWarningType.INFEASIBLE_TARGET,
                "Fond pokriven listićem je ${formatHoursAndMinutes(coveredFundMinutes)}, " +
                    "a fond meseca je ${formatHoursAndMinutes(monthlyFundMinutes)}."
            )
        }

        val holidayFundMinutes = calculateCalendarHolidayFundMinutes(input.month)
        if (holidayMinutes + regularHolidayMinutes != holidayFundMinutes) {
            warnings += PayrollSlipWarning(
                PayrollSlipWarningType.INFEASIBLE_TARGET,
                "Državni praznik i Rad u režiji na DP zajedno pokrivaju " +
                    "${formatHoursAndMinutes(holidayMinutes + regularHolidayMinutes)}, " +
                    "a kalendar za mesec ima ${formatHoursAndMinutes(holidayFundMinutes)}."
            )
        }
        return warnings
    }

    private fun inferReliableWeekShift(
        monday: LocalDate,
        recordsByDate: Map<LocalDate, DayRecord>
    ): Shift? {
        val weekRecords = (0 until PAYROLL_SLIP_WORK_WEEK_DAYS).map { offset ->
            recordsByDate[monday.plusDays(offset.toLong())] ?: return null
        }
        val shift = weekRecords.first().shift
        if (shift !in Shift.workShifts()) return null

        return shift.takeIf {
            weekRecords.all { record ->
                record.shift == shift &&
                    record.hours == CONFIG.pay.defaultHours &&
                    record.shift2 == null &&
                    record.hours2 == 0
            }
        }
    }

    private fun generateTargetDrafts(input: PayrollSlipInput): List<PayrollSlipDayDraft> {
        if (!input.month.isValidMonthKey()) return emptyList()

        val allocation = PayrollSlipAllocation(input)
        allocation.allocateHolidayFundRows()
        allocation.allocatePaidAbsence(Shift.VACATION, slipHoursToMinutes(input.vacationHours) ?: 0)
        allocation.allocatePaidAbsence(Shift.SICK, slipHoursToMinutes(input.sickHours) ?: 0)
        allocation.allocateSundayWork()
        allocation.allocateNightWork()
        allocation.allocateRegularWorkAndAbsence()
        allocation.allocateWeekdayOvertime()
        return allocation.drafts()
    }

    private fun compare(
        repo: ShiftRepository,
        input: PayrollSlipInput,
        drafts: List<PayrollSlipDayDraft>,
        additions: MonthlyGrossAdditions
    ): PayrollSlipComparison {
        val records = drafts.map { it.toDayRecord() }
        val netBeforeDeductions = if (input.month.isValidMonthKey()) {
            calculateNetBeforeDeductions(repo, input.month, records, additions)
        } else {
            0.0
        }

        return PayrollSlipComparison(
            rows = buildComparisonRows(input, records),
            calculatedNetBeforeDeductions = netBeforeDeductions
        )
    }

    private fun buildComparisonRows(
        input: PayrollSlipInput,
        records: List<DayRecord>
    ): List<PayrollSlipComparisonRow> {
        val comparisonCalculator = PayCalculator()
        val calculatedByLabel = mapOf(
            "Rad u režiji" to records
                .filter { it.shift in Shift.workShifts() && !it.workedOnHoliday }
                .sumOf { it.effectiveRegularWorkMinutes },
            "Noćni rad" to records.sumOf { comparisonCalculator.getNightHours(it) * PAYROLL_SLIP_MINUTES_PER_HOUR },
            "Prekovremeni rad" to records.sumOf {
                comparisonCalculator.getOvertimeHours(it, BrigadeType.TROBRIGADA) * PAYROLL_SLIP_MINUTES_PER_HOUR
            },
            "Godišnji" to records
                .filter { it.shift == Shift.VACATION }
                .sumOf { it.effectiveRegularWorkMinutes },
            "Bolovanje" to records
                .filter { it.shift == Shift.SICK }
                .sumOf { it.effectiveRegularWorkMinutes },
            "Državni praznik" to records
                .filter { it.isHoliday && !it.workedOnHoliday }
                .sumOf { defaultDayMinutes() },
            "Rad u režiji na DP" to records
                .filter { it.workedOnHoliday }
                .sumOf { it.effectiveRegularWorkMinutes },
            "Rad nedeljom" to records
                .filter { it.weekday == Weekday.SUNDAY }
                .sumOf { it.totalWorkMinutes },
            "Rad na praznik" to records
                .filter { it.workedOnHoliday }
                .sumOf { it.totalWorkMinutes },
            "Izostanci" to records
                .filter { it.shift in Shift.workShifts() }
                .sumOf { (defaultDayMinutes() - it.effectiveRegularWorkMinutes).coerceAtLeast(0) }
        )
        val targetByLabel = mapOf(
            "Rad u režiji" to (slipHoursToMinutes(input.regularWorkHours) ?: 0),
            "Noćni rad" to (slipHoursToMinutes(input.nightHours) ?: 0),
            "Prekovremeni rad" to (slipHoursToMinutes(input.overtimeHours) ?: 0),
            "Godišnji" to (slipHoursToMinutes(input.vacationHours) ?: 0),
            "Bolovanje" to (slipHoursToMinutes(input.sickHours) ?: 0),
            "Državni praznik" to (slipHoursToMinutes(input.holidayHours) ?: 0),
            "Rad u režiji na DP" to (slipHoursToMinutes(input.regularHolidayWorkHours) ?: 0),
            "Rad nedeljom" to (slipHoursToMinutes(input.sundayHours) ?: 0),
            "Rad na praznik" to (slipHoursToMinutes(input.holidayWorkHours) ?: 0),
            "Izostanci" to (slipHoursToMinutes(input.absenceHours) ?: 0)
        )
        return PAYROLL_SLIP_COMPARISON_LABELS.map { label ->
            PayrollSlipComparisonRow(
                label = label,
                targetMinutes = targetByLabel.getValue(label),
                calculatedMinutes = calculatedByLabel.getValue(label)
            )
        }
    }

    private fun calculateNetBeforeDeductions(
        repo: ShiftRepository,
        month: MonthKey,
        records: List<DayRecord>,
        additions: MonthlyGrossAdditions
    ): Double {
        return repo.calculatePayrollSlipPreviewNetBeforeDeductions(
            month = month,
            records = records,
            additions = additions
        )
    }
}

private class PayrollSlipAllocation(private val input: PayrollSlipInput) {
    private val yearMonth: YearMonth = YearMonth.of(input.month.year, input.month.month)
    private val recordsByDay = linkedMapOf<Int, PayrollSlipDayDraft>()
    private val holidayWorkdays = daysMatching { date ->
        Weekday.from(date).isWorkday && isSerbianHoliday(date.year, date.monthValue, date.dayOfMonth)
    }.toMutableList()
    private val nonHolidayWeekdays = daysMatching { date ->
        Weekday.from(date).isWorkday && !isSerbianHoliday(date.year, date.monthValue, date.dayOfMonth)
    }.toMutableList()
    private val nonHolidaySundays = daysMatching { date ->
        Weekday.from(date) == Weekday.SUNDAY && !isSerbianHoliday(date.year, date.monthValue, date.dayOfMonth)
    }.toMutableList()

    private var regularMinutes = slipHoursToMinutes(input.regularWorkHours) ?: 0
    private var nightMinutes = slipHoursToMinutes(input.nightHours) ?: 0
    private var overtimeMinutes = slipHoursToMinutes(input.overtimeHours) ?: 0
    private var sundayMinutes = slipHoursToMinutes(input.sundayHours) ?: 0
    private var absenceMinutes = slipHoursToMinutes(input.absenceHours) ?: 0

    fun allocateHolidayFundRows() {
        var holidayWorkMinutes = slipHoursToMinutes(input.regularHolidayWorkHours) ?: 0
        while (holidayWorkMinutes > 0 && holidayWorkdays.isNotEmpty()) {
            val day = holidayWorkdays.removeAt(0)
            val minutes = holidayWorkMinutes.coerceAtMost(defaultDayMinutes())
            recordsByDay[day] = workDraft(day, Shift.FIRST, minutes, holidayWorkOverride = true)
            holidayWorkMinutes -= minutes
        }

        var holidayMinutes = slipHoursToMinutes(input.holidayHours) ?: 0
        while (holidayMinutes > 0 && holidayWorkdays.isNotEmpty()) {
            val day = holidayWorkdays.removeAt(0)
            recordsByDay[day] = PayrollSlipDayDraft(
                year = input.month.year,
                month = input.month.month,
                day = day,
                shift = Shift.OFF,
                hours = 0
            )
            holidayMinutes -= defaultDayMinutes()
        }
    }

    fun allocatePaidAbsence(shift: Shift, targetMinutes: Int) {
        var remaining = targetMinutes
        while (remaining > 0 && nonHolidayWeekdays.isNotEmpty()) {
            val day = nonHolidayWeekdays.removeAt(0)
            val minutes = remaining.coerceAtMost(defaultDayMinutes())
            recordsByDay[day] = workDraft(day, shift, minutes)
            remaining -= minutes
        }
    }

    fun allocateSundayWork() {
        while (sundayMinutes > 0 && nonHolidaySundays.isNotEmpty()) {
            val day = nonHolidaySundays.removeAt(0)
            val minutes = sundayMinutes.coerceAtMost(defaultDayMinutes())
            recordsByDay[day] = workDraft(day, Shift.FIRST, minutes)
            sundayMinutes -= minutes
            regularMinutes -= minutes
            overtimeMinutes -= minutes
        }
    }

    fun allocateNightWork() {
        while (nightMinutes > 0 && nonHolidayWeekdays.isNotEmpty()) {
            val day = nonHolidayWeekdays.removeAt(0)
            val minutes = nightMinutes.coerceAtMost(defaultDayMinutes())
            recordsByDay[day] = workDraft(day, Shift.THIRD, minutes)
            nightMinutes -= minutes
            regularMinutes -= minutes
            absenceMinutes -= (defaultDayMinutes() - minutes).coerceAtLeast(0)
        }
    }

    fun allocateRegularWorkAndAbsence() {
        while ((regularMinutes > 0 || absenceMinutes > 0) && nonHolidayWeekdays.isNotEmpty()) {
            val day = nonHolidayWeekdays.removeAt(0)
            val absenceForDay = absenceMinutes.coerceIn(0, defaultDayMinutes())
            val preferredPaidMinutes = defaultDayMinutes() - absenceForDay
            val paidMinutes = when {
                regularMinutes <= 0 -> 0
                preferredPaidMinutes <= 0 -> 0
                else -> preferredPaidMinutes.coerceAtMost(regularMinutes)
            }
            recordsByDay[day] = workDraft(day, Shift.FIRST, paidMinutes)
            regularMinutes -= paidMinutes
            absenceMinutes -= (defaultDayMinutes() - paidMinutes).coerceAtLeast(0)
        }
    }

    fun allocateWeekdayOvertime() {
        var remainingHours = (overtimeMinutes.coerceAtLeast(0)) / PAYROLL_SLIP_MINUTES_PER_HOUR
        if (remainingHours <= 0) return

        val eligibleDays = recordsByDay.keys.sorted().filter { day ->
            val draft = recordsByDay.getValue(day)
            draft.shift in Shift.workShifts() && draft.hours > 0 &&
                Weekday.from(LocalDate.of(input.month.year, input.month.month, day)).isWorkday
        }
        for (day in eligibleDays) {
            if (remainingHours <= 0) break
            val draft = recordsByDay.getValue(day)
            val addHours = remainingHours.coerceAtMost(CONFIG.pay.defaultHours)
            recordsByDay[day] = draft.copy(
                shift2 = defaultOvertimeShiftAfter(draft.shift),
                hours2 = addHours,
                overtimeType = OvertimeType.PAID
            )
            remainingHours -= addHours
        }
    }

    fun drafts(): List<PayrollSlipDayDraft> {
        return recordsByDay.values.sortedWith(
            compareBy<PayrollSlipDayDraft> { it.year }.thenBy { it.month }.thenBy { it.day }
        )
    }

    private fun daysMatching(predicate: (LocalDate) -> Boolean): List<Int> {
        return (1..yearMonth.lengthOfMonth()).filter { day ->
            predicate(LocalDate.of(input.month.year, input.month.month, day))
        }
    }

    private fun workDraft(
        day: Int,
        shift: Shift,
        minutes: Int,
        holidayWorkOverride: Boolean? = null
    ): PayrollSlipDayDraft {
        val coercedMinutes = minutes.coerceIn(0, defaultDayMinutes())
        val hours = coercedMinutes / PAYROLL_SLIP_MINUTES_PER_HOUR
        val shortageMinutes = coercedMinutes % PAYROLL_SLIP_MINUTES_PER_HOUR
        return PayrollSlipDayDraft(
            year = input.month.year,
            month = input.month.month,
            day = day,
            shift = shift,
            hours = hours,
            holidayWorkOverride = holidayWorkOverride,
            regularShortageMinutes = shortageMinutes
        )
    }
}

private fun defaultDayMinutes(): Int {
    return CONFIG.pay.defaultHours * PAYROLL_SLIP_MINUTES_PER_HOUR
}

private fun calculateCalendarHolidayFundMinutes(month: MonthKey): Int {
    val yearMonth = YearMonth.of(month.year, month.month)
    return (1..yearMonth.lengthOfMonth()).sumOf { day ->
        val date = LocalDate.of(month.year, month.month, day)
        val holidayWorkday = Weekday.from(date).isWorkday && isSerbianHoliday(month.year, month.month, day)
        if (holidayWorkday) defaultDayMinutes() else 0
    }
}

private fun MonthKey.isValidMonthKey(): Boolean {
    return year > 0 && month in 1..12
}
