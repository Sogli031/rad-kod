package com.rad.shifttracker.krv

import java.time.DayOfWeek
import java.time.LocalDate
import com.rad.shifttracker.smene.DayRecord

internal const val BLOOD_DONATION_BOTTOM_BAR_LABEL = "Krv"

internal data class BloodDonationLeaveBackup(
    val date: LocalDate,
    val record: DayRecord?
)

internal data class BloodDonationLeaveState(
    val firstDay: LocalDate,
    val previousRecords: List<BloodDonationLeaveBackup>
)

internal fun bloodDonationLeaveDates(firstDay: LocalDate): List<LocalDate> {
    return listOf(firstDay, firstDay.plusDays(1))
}

/**
 * Datumi para na koje se upisuje plaćeno odsustvo — vikend se preskače,
 * isto kao kod bolovanja i godišnjeg (applySickRange/previewVacationRange).
 * Bez ovoga odsustvo od petka pravi subotu kao plaćen SICK dan 100%.
 */
internal fun bloodDonationLeaveWorkdayDates(firstDay: LocalDate): List<LocalDate> {
    return bloodDonationLeaveDates(firstDay).filter { it.dayOfWeek.value <= DayOfWeek.FRIDAY.value }
}

/** Dva dana odsustva za davanje krvi; ponovo se mogu koristiti posle 3 meseca od drugog dana. */
internal data class BloodDonationLeaveUsage(val first: LocalDate, val second: LocalDate) {
    val nextAllowed: LocalDate get() = second.plusMonths(3).plusDays(1)
}

internal fun calculateNextBloodDonationLeaveDate(firstDay: LocalDate): LocalDate =
    BloodDonationLeaveUsage(firstDay, firstDay.plusDays(1)).nextAllowed

/** Sledeći radni dan (petak → ponedeljak). */
private fun nextWorkday(date: LocalDate): LocalDate {
    var next = date.plusDays(1)
    while (next.dayOfWeek.value > DayOfWeek.FRIDAY.value) next = next.plusDays(1)
    return next
}

/**
 * Najnoviji par plaćenog odsustva u kalendaru: dva dana zaredom (petak i ponedeljak se računaju
 * kao zaredom, vikend se preskače), ili null ako takvog para nema.
 */
internal fun latestPaidLeavePair(paidLeaveDates: Collection<LocalDate>): BloodDonationLeaveUsage? {
    val dates = java.util.TreeSet(paidLeaveDates)
    return dates.descendingSet().firstNotNullOfOrNull { second ->
        dates.lower(second)?.takeIf { nextWorkday(it) == second }?.let { BloodDonationLeaveUsage(it, second) }
    }
}

/** Poslednje korišćenje: noviji od para u kalendaru i ručno upisanog odsustva. */
internal fun lastBloodDonationLeaveUsage(
    calendarPair: BloodDonationLeaveUsage?,
    manualFirstDay: LocalDate?
): BloodDonationLeaveUsage? {
    val manual = manualFirstDay?.let { BloodDonationLeaveUsage(it, it.plusDays(1)) }
    return listOfNotNull(calendarPair, manual).maxByOrNull { it.second }
}

internal fun bloodDonationLeaveLastUsageText(firstDay: LocalDate?): String =
    bloodDonationLeaveUsageText(firstDay?.let { BloodDonationLeaveUsage(it, it.plusDays(1)) })

internal fun bloodDonationLeaveNextUsageText(firstDay: LocalDate?): String =
    bloodDonationLeaveNextText(firstDay?.let { BloodDonationLeaveUsage(it, it.plusDays(1)) })

internal fun bloodDonationLeaveUsageText(usage: BloodDonationLeaveUsage?): String =
    if (usage == null) {
        "Poslednje korišćenje dana: nije uneto."
    } else {
        "Poslednje korišćenje dana: ${usage.first.formatBloodDonationDate()} i ${usage.second.formatBloodDonationDate()}"
    }

internal fun bloodDonationLeaveNextText(usage: BloodDonationLeaveUsage?): String =
    "Možeš ponovo da iskoristiš dane: ${usage?.nextAllowed?.formatBloodDonationDate() ?: "nije izračunato."}"
