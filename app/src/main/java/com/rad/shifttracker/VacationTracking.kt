package com.rad.shifttracker

import java.time.DayOfWeek
import java.time.LocalDate

data class VacationYearBalance(
    val year: Int,
    val trackingStartDate: LocalDate,
    val carryoverDaysInitial: Double,
    val currentYearDaysTotal: Double,
    val currentYearDaysUsedBeforeAvailability: Double,
    val currentYearAvailableFrom: LocalDate,
    val manualBaseline: Boolean
)

data class VacationBalanceSummary(
    val year: Int,
    val carryoverRemaining: Double,
    val carryoverExpiresOn: LocalDate,
    val currentYearTotal: Double,
    val currentYearUsed: Double,
    val currentYearRemaining: Double,
    val availableNow: Double,
    val availableFromJuly: Double,
    val warnings: List<String>,
    val carryoverInitial: Double = 0.0,
    val carryoverUsed: Double = 0.0,
    val carryoverExpired: Double = 0.0,
    val currentYearAvailableFrom: LocalDate = currentYearVacationAvailableFrom(year),
    val currentYearExpiresOn: LocalDate = currentYearVacationAvailableFrom(year + 1)
)

data class VacationRangePreview(
    val startDate: LocalDate,
    val endDate: LocalDate,
    val countedDates: List<LocalDate>,
    val skippedWeekendDays: Int,
    val skippedHolidayDays: Int,
    val skippedExistingSickDays: Int,
    val warnings: List<String>,
    val requestValidation: VacationRequestValidation = VacationRequestValidation(
        blockedDates = emptyList(),
        message = null
    )
) {
    val countedDays: Int
        get() = countedDates.size
}

data class VacationRequestValidation(
    val blockedDates: List<LocalDate>,
    val message: String?
) {
    val isAllowed: Boolean
        get() = blockedDates.isEmpty()
}

data class AnnualLeaveCalculationInput(
    val totalSeniorityYears: Double = 0.0,
    val yearsAtEmployer: Double = 0.0,
    val hasIncreasedRiskOrBenefitedSeniority: Boolean = false,
    val worksNightShiftSystem: Boolean = false,
    val worksWeekendShiftSystem: Boolean = false,
    val requiresAtLeastThirdDegree: Boolean = false,
    val isWorkInvalidFromWorkInjuryOrDisease: Boolean = false,
    val isWarMilitaryInvalid: Boolean = false,
    val isSingleParentOfChildUnder14: Boolean = false,
    val hasThreeOrMoreChildrenUnder14: Boolean = false
)

data class AnnualLeaveBreakdown(
    val baseDays: Int,
    val seniorityDays: Int,
    val workingConditionsDays: Int,
    val nightWorkDays: Int,
    val weekendWorkDays: Int,
    val educationDays: Int,
    val socialDays: Int,
    val totalDays: Int
)

object VacationWarnings {
    const val MAIN_PART_MISSING = "Glavni deo odmora od najmanje 10 radnih dana još nije evidentiran."
    const val NEW_VACATION_BEFORE_JULY = "Ovo troši novi odmor pre 01.07. Proveri da li je odobreno."
    const val SHORT_PART_EXCEPTION = "Kratak deo pre glavnog odmora. Dozvoljeno je 1-5 dana ako je odobreno."
    const val CARRYOVER_VALID_UNTIL_JUNE = "Preneti odmor važi najkasnije do 30.06."
    const val CARRYOVER_EXPIRED = "Preneti odmor je istekao."
    const val RANGE_HAS_SKIPPED_DAYS = "Ovaj opseg sadrži dane koji se ne računaju u godišnji."
    const val VACATION_EXCEEDED = "Fond godišnjeg je prekoračen."
}

fun currentYearVacationAvailableFrom(year: Int): LocalDate = LocalDate.of(year, 7, 1)

fun carryoverExpiresOn(year: Int): LocalDate = LocalDate.of(year, 6, 30)

fun formatVacationDate(date: LocalDate): String {
    val day = date.dayOfMonth.toString().padStart(2, '0')
    val month = date.monthValue.toString().padStart(2, '0')
    return "$day.$month.${date.year}."
}

fun isVacationWorkday(date: LocalDate): Boolean = date.dayOfWeek.value <= DayOfWeek.FRIDAY.value

fun validateVacationRequestAvailability(
    requestedDates: List<LocalDate>,
    existingVacationDates: Set<LocalDate>,
    carryoverRemainingBeforeDate: (LocalDate) -> Double,
    currentYearAvailableFromForDate: (LocalDate) -> LocalDate = { currentYearVacationAvailableFrom(it.year) }
): VacationRequestValidation {
    val blockedDates = mutableListOf<LocalDate>()
    val carryoverConsumedByRequest = mutableMapOf<Int, Double>()
    val uniqueDates = requestedDates.distinct().sorted()

    for (date in uniqueDates) {
        if (date in existingVacationDates) continue
        val availableFrom = currentYearAvailableFromForDate(date)
        if (!date.isBefore(availableFrom)) continue

        val alreadyConsumed = carryoverConsumedByRequest[date.year] ?: 0.0
        val carryoverRemaining = carryoverRemainingBeforeDate(date).coerceAtLeast(0.0) - alreadyConsumed
        if (carryoverRemaining >= 1.0) {
            carryoverConsumedByRequest[date.year] = alreadyConsumed + 1.0
        } else {
            blockedDates.add(date)
        }
    }

    return VacationRequestValidation(
        blockedDates = blockedDates,
        message = vacationRequestBlockedMessage(blockedDates)
    )
}

private fun vacationRequestBlockedMessage(blockedDates: List<LocalDate>): String? {
    val firstBlockedDate = blockedDates.firstOrNull() ?: return null
    val extraDatesText = if (blockedDates.size > 1) {
        " i još ${blockedDates.size - 1} dana"
    } else {
        ""
    }
    val availableFrom = currentYearVacationAvailableFrom(firstBlockedDate.year)
    return "Ne možeš da upišeš godišnji ${formatVacationDate(firstBlockedDate)}$extraDatesText " +
        "jer nemaš starog odmora. Novi odmor može da se koristi od ${formatVacationDate(availableFrom)}"
}

fun calculateAnnualLeaveDays(input: AnnualLeaveCalculationInput): AnnualLeaveBreakdown {
    val safeTotalSeniorityYears = input.totalSeniorityYears.coerceAtLeast(0.0)
    val safeYearsAtEmployer = input.yearsAtEmployer.coerceAtLeast(0.0)
    val baseDays = 20
    val standardSeniorityDays = when {
        safeTotalSeniorityYears < 5.0 -> 0
        safeTotalSeniorityYears <= 15.0 -> 1
        safeTotalSeniorityYears <= 30.0 -> 2
        else -> 3
    }
    val seniorityDays = if (safeYearsAtEmployer >= 35.0) {
        maxOf(standardSeniorityDays, 4)
    } else {
        standardSeniorityDays
    }
    val workingConditionsDays = if (input.hasIncreasedRiskOrBenefitedSeniority) 2 else 1
    val nightWorkDays = if (input.worksNightShiftSystem) 1 else 0
    val weekendWorkDays = if (input.worksWeekendShiftSystem) 1 else 0
    val educationDays = if (input.requiresAtLeastThirdDegree) 1 else 0
    val socialDays =
        // KU tumačenje je trenutno sabiranje osnova; ako se potvrdi drugačije,
        // ovo se lako menja u max(+2) za bilo koji aktivan socijalni uslov.
        listOf(
            input.isWorkInvalidFromWorkInjuryOrDisease,
            input.isWarMilitaryInvalid,
            input.isSingleParentOfChildUnder14,
            input.hasThreeOrMoreChildrenUnder14
        ).count { it } * 2
    val totalDays = (
        baseDays +
            seniorityDays +
            workingConditionsDays +
            nightWorkDays +
            weekendWorkDays +
            educationDays +
            socialDays
        ).coerceAtLeast(0)

    return AnnualLeaveBreakdown(
        baseDays = baseDays,
        seniorityDays = seniorityDays,
        workingConditionsDays = workingConditionsDays,
        nightWorkDays = nightWorkDays,
        weekendWorkDays = weekendWorkDays,
        educationDays = educationDays,
        socialDays = socialDays,
        totalDays = totalDays
    )
}

fun calculateVacationBalanceSummary(
    balance: VacationYearBalance,
    vacationDates: List<LocalDate>,
    today: LocalDate
): VacationBalanceSummary {
    val availableFrom = balance.currentYearAvailableFrom
    val carryoverExpiry = carryoverExpiresOn(balance.year)
    val carryoverInitial = balance.carryoverDaysInitial.coerceAtLeast(0.0)
    val countedDates = vacationDates
        .asSequence()
        .filter { carryoverInitial > 0.0 || !it.isBefore(balance.trackingStartDate) }
        .filter { it.year == balance.year }
        .filter { !it.isAfter(today) }
        .distinct()
        .sorted()
        .toList()

    var carryoverRemaining = carryoverInitial
    var carryoverUsed = 0.0
    var currentYearUsed = balance.currentYearDaysUsedBeforeAvailability.coerceAtLeast(0.0)

    for (date in countedDates) {
        if (!date.isAfter(carryoverExpiry) && carryoverRemaining > 0.0) {
            val consumed = minOf(1.0, carryoverRemaining)
            carryoverRemaining = (carryoverRemaining - consumed).coerceAtLeast(0.0)
            carryoverUsed += consumed
        } else {
            currentYearUsed += 1.0
        }
    }

    val carryoverExpired = today.isAfter(carryoverExpiry)
    val expiredCarryoverDays = if (carryoverExpired) carryoverRemaining else 0.0
    val effectiveCarryoverRemaining = if (carryoverExpired) 0.0 else carryoverRemaining
    val currentYearTotal = balance.currentYearDaysTotal.coerceAtLeast(0.0)
    val currentYearRemaining = (currentYearTotal - currentYearUsed).coerceAtLeast(0.0)
    val availableNow = if (today.isBefore(availableFrom)) {
        effectiveCarryoverRemaining
    } else {
        currentYearRemaining
    }
    val availableFromJuly = if (today.isBefore(availableFrom)) currentYearRemaining else 0.0

    val warnings = buildList {
        if (!carryoverExpired && balance.carryoverDaysInitial > 0.0) add(VacationWarnings.CARRYOVER_VALID_UNTIL_JUNE)
        if (carryoverExpired && carryoverRemaining > 0.0) add(VacationWarnings.CARRYOVER_EXPIRED)
        if (today.isBefore(availableFrom) && currentYearUsed > 0.0) add(VacationWarnings.NEW_VACATION_BEFORE_JULY)
        if (currentYearUsed > currentYearTotal) add(VacationWarnings.VACATION_EXCEEDED)
    }

    return VacationBalanceSummary(
        year = balance.year,
        carryoverRemaining = effectiveCarryoverRemaining,
        carryoverExpiresOn = carryoverExpiry,
        currentYearTotal = currentYearTotal,
        currentYearUsed = currentYearUsed,
        currentYearRemaining = currentYearRemaining,
        availableNow = availableNow,
        availableFromJuly = availableFromJuly,
        warnings = warnings,
        carryoverInitial = carryoverInitial,
        carryoverUsed = carryoverUsed,
        carryoverExpired = expiredCarryoverDays,
        currentYearAvailableFrom = availableFrom,
        currentYearExpiresOn = currentYearVacationAvailableFrom(balance.year + 1)
    )
}
