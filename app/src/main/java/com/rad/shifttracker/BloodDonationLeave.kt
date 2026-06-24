package com.rad.shifttracker

import java.time.LocalDate

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

internal fun calculateNextBloodDonationLeaveDate(firstDay: LocalDate): LocalDate {
    val secondDay = firstDay.plusDays(1)
    return secondDay.plusMonths(3).plusDays(1)
}

internal fun bloodDonationLeaveLastUsageText(firstDay: LocalDate?): String {
    val dates = firstDay?.let(::bloodDonationLeaveDates)
    return if (dates == null) {
        "Poslednje korišćenje dana: nije uneto."
    } else {
        "Poslednje korišćenje dana: ${dates[0].formatBloodDonationDate()} i ${dates[1].formatBloodDonationDate()}"
    }
}

internal fun bloodDonationLeaveNextUsageText(firstDay: LocalDate?): String {
    val nextDate = firstDay?.let(::calculateNextBloodDonationLeaveDate)
    return "Možeš ponovo da iskoristiš dane: ${nextDate?.formatBloodDonationDate() ?: "nije izračunato."}"
}
