package com.rad.shifttracker

import com.rad.shifttracker.data.storage.ShiftRepositorySnapshot
import java.math.BigDecimal
import java.time.LocalDate
import kotlin.random.Random

/**
 * Slučajni snapshot-ovi za svojstvene testove koda. Svaka vrednost je unutar opsega koji dekoder
 * ne sme da menja (novac na 2 decimale, meseci 1–12), pa je encode→decode jednakost tačna.
 * Seed je uvek zadat u testu: neuspeh se uvek ponavlja.
 */
internal fun randomSnapshot(random: Random): ShiftRepositorySnapshot {
    fun positive() = random.nextInt(1, 10_000_000) / 100.0
    fun nonNegative() = random.nextInt(0, 10_000_000) / 100.0
    fun percent() = random.nextInt(0, 10_001) / 100.0
    fun month() = MonthKey(2024 + random.nextInt(5), 1 + random.nextInt(12))
    fun date() = LocalDate.of(2024, 1, 1).plusDays(random.nextLong(0, 1_700))
    fun <T> pick(values: List<T>): T = values[random.nextInt(values.size)]
    fun <T> maybe(value: () -> T): T? = if (random.nextBoolean()) value() else null
    fun <T> randomMap(count: Int, key: () -> MonthKey, value: (MonthKey) -> T): Map<MonthKey, T> =
        buildMap { repeat(count) { key().let { put(it, value(it)) } } }

    val history = List(random.nextInt(0, 5)) { date() }.distinct().sortedDescending()
    val annualInput = maybe {
        AnnualLeaveCalculationInput(
            totalSeniorityYears = random.nextInt(0, 400) / 10.0,
            yearsAtEmployer = random.nextInt(0, 400) / 10.0,
            hasIncreasedRiskOrBenefitedSeniority = random.nextBoolean(),
            worksNightShiftSystem = random.nextBoolean(),
            worksWeekendShiftSystem = random.nextBoolean(),
            requiresAtLeastThirdDegree = random.nextBoolean(),
            isWorkInvalidFromWorkInjuryOrDisease = random.nextBoolean(),
            isWarMilitaryInvalid = random.nextBoolean(),
            isSingleParentOfChildUnder14 = random.nextBoolean(),
            hasThreeOrMoreChildrenUnder14 = random.nextBoolean()
        )
    }
    return ShiftRepositorySnapshot(
        payCoefficient = random.nextInt(100, 500) / 100.0,
        sickPayBaseHourly = positive(),
        defaultPayBaseSelectionMode = pick(PayBaseSelectionMode.entries),
        hourlyValueChanges = randomMap(random.nextInt(0, 4), ::month) { positive() },
        foodPerDay = random.nextInt(0, 100_000) / 100.0,
        transportDailyAmount = random.nextInt(0, 100_000) / 100.0,
        autoAdvanceImportEnabled = random.nextBoolean(),
        notificationAccessPromptSuppressed = random.nextBoolean(),
        lastBloodDonationDate = history.firstOrNull(),
        bloodDonationCount = history.size + random.nextInt(0, 4),
        bloodDonationHistory = history,
        bloodDonationLeaves = emptyList(),
        initialCompBalance = random.nextInt(0, 1_000),
        initialCompBalanceMinutes = random.nextInt(0, 60_000),
        minuliRadStartYear = if (random.nextBoolean()) 0 else 1960 + random.nextInt(66),
        minuliRadStartMonth = 1 + random.nextInt(12),
        seniorityInputMode = pick(SeniorityInputMode.entries),
        minuliRadBenefitEnabled = random.nextBoolean(),
        minuliRadIncreaseFactor = pick(SeniorityIncreaseFactor.entries),
        minuliRadLastPayslipYear = if (random.nextBoolean()) 0 else 2020 + random.nextInt(7),
        minuliRadLastPayslipMonth = 1 + random.nextInt(12),
        minuliRadPayslipPercent = percent(),
        brigadeType = pick(BrigadeType.entries),
        initialVacationBalance = random.nextInt(0, 60) / 2.0,
        vacationYearBalances = (2024..2027).filter { random.nextBoolean() }.associateWith { randomVacationBalance(random, it) },
        employeeContributionsPercent = percent(),
        taxRelief = nonNegative(),
        taxPercent = percent(),
        difficultWorkConditionsPercent = percent(),
        monthlyPayBases = randomMap(random.nextInt(0, 4), ::month) { randomPayBases(random, it) },
        monthlyGrossAdditions = randomMap(random.nextInt(0, 4), ::month) { randomGrossAdditions(random, it) },
        monthlyNetAdjustments = randomMap(random.nextInt(0, 4), ::month) { MonthlyNetAdjustments(it.year, it.month, positive()) },
        knownPayBaseAnchors = randomMap(random.nextInt(0, 4), ::month) { positive() },
        annualLeaveCalculationInput = annualInput,
        // Sa ulazom null dekoder pravi podrazumevani ulaz, pa bez ulaza nema ni sačuvanih dana.
        annualLeaveLastCalculatedDays = if (annualInput == null) 0 else random.nextInt(0, 40),
        records = randomRecords(random),
        foodPerDayChanges = randomMap(random.nextInt(0, 4), ::month) { random.nextInt(0, 1_000_000) / 100.0 },
        regressNetChanges = randomMap(random.nextInt(0, 3), ::month) { positive() },
        deletedMonths = List(random.nextInt(0, 4)) { month() }.distinct().sorted(),
        deletedHourlyValueMonths = List(random.nextInt(0, 4)) { month() }.distinct().sorted(),
        mainPayIncludesTransport = random.nextBoolean(),
        savedPayslips = randomPayslips(random),
        deletedPayslipMonths = List(random.nextInt(0, 4)) { month() }.distinct().sorted()
    )
}

private fun randomVacationBalance(random: Random, year: Int) = VacationYearBalance(
    year = year,
    trackingStartDate = LocalDate.of(year, 1 + random.nextInt(12), 1 + random.nextInt(28)),
    carryoverDaysInitial = random.nextInt(0, 40) / 2.0,
    currentYearDaysTotal = random.nextInt(0, 60) / 2.0,
    currentYearDaysUsedBeforeAvailability = random.nextInt(0, 20) / 2.0,
    currentYearAvailableFrom = LocalDate.of(year, 1 + random.nextInt(12), 1 + random.nextInt(28)),
    manualBaseline = random.nextBoolean(),
    carryoverFrozen = random.nextBoolean()
)

private fun randomPayBases(random: Random, month: MonthKey): MonthlyPayBases {
    fun base() = if (random.nextBoolean()) random.nextInt(1, 200_000) / 100.0 else null
    return MonthlyPayBases(
        year = month.year,
        month = month.month,
        vacationHourlyBase = base(),
        paidHolidayHourlyBase = base(),
        sickHourlyBase = base(),
        // Uvek barem jedna vrednost, inače se mesec ne upisuje.
        selectionMode = PayBaseSelectionMode.entries[random.nextInt(PayBaseSelectionMode.entries.size)]
    )
}

@Suppress("DEPRECATION")
private fun randomGrossAdditions(random: Random, month: MonthKey): MonthlyGrossAdditions {
    fun money() = random.nextInt(1, 5_000_000) / 100.0
    fun decimal() = BigDecimal.valueOf(random.nextInt(1, 5_000_000).toLong(), 2)
    return MonthlyGrossAdditions(
        year = month.year,
        month = month.month,
        mealGross = if (random.nextBoolean()) money() else null,
        regressGross = if (random.nextBoolean()) money() else 0.0,
        bonusGross = if (random.nextBoolean()) money() else 0.0,
        // 3620,00 je poseban slučaj sa istorijskim pravom (vidi sanitizer), pa ga generator zaobilazi.
        mealSpentNet = if (random.nextBoolean()) money().takeIf { it != 3620.0 } else null,
        bonusNet = if (random.nextBoolean()) money() else 0.0,
        mealSettlementAdjustmentNet = if (random.nextBoolean()) random.nextInt(-100_000, 100_000) / 100.0 else 0.0,
        absencePenaltyGross = if (random.nextBoolean()) decimal() else BigDecimal.ZERO,
        // Uvek barem jedna vrednost, inače se mesec ne upisuje.
        mealEntitlementNetOverride = decimal()
    )
}

private fun randomRecords(random: Random): List<DayRecord> {
    val dates = List(random.nextInt(0, 60)) { LocalDate.of(2024, 1, 1).plusDays(random.nextLong(0, 1_700)) }.distinct()
    return dates.map { date -> randomRecord(random, date) }
}

private fun randomRecord(random: Random, date: LocalDate): DayRecord {
    val shifts = Shift.entries
    val shift = shifts[random.nextInt(shifts.size)]
    val shift2 = if (random.nextInt(4) == 0) Shift.workShifts()[random.nextInt(Shift.workShifts().size)] else null
    return DayRecord(
        year = date.year,
        month = date.monthValue,
        day = date.dayOfMonth,
        shift = shift,
        hours = random.nextInt(0, 14),
        shift2 = shift2,
        hours2 = random.nextInt(0, 7),
        overtimeType = OvertimeType.entries[random.nextInt(OvertimeType.entries.size)],
        weekendOvertimeType = OvertimeType.entries[random.nextInt(OvertimeType.entries.size)],
        compHoursUsed = random.nextInt(0, 9),
        holidayWorkOverride = listOf(null, true, false)[random.nextInt(3)],
        holidayOverride = listOf(null, true, false)[random.nextInt(3)],
        balanceExempt = random.nextBoolean(),
        sickPayRate = if (random.nextBoolean()) random.nextInt(1, 101) / 100.0 else null,
        regularShortageMinutes = random.nextInt(0, 60),
        source = DayRecordSource.entries[random.nextInt(DayRecordSource.entries.size)]
    )
}

private fun randomPayslips(random: Random): Map<MonthKey, String> {
    val months = PAYSLIPS_2026.keys.filter { random.nextBoolean() }
    return months.associate { month ->
        val slip = (parsePayslip(requireNotNull(PAYSLIPS_2026[month])) as PayslipParseResult.Success).payslip
        MonthKey(2026, month) to payslipToJson(slip)
    }
}
