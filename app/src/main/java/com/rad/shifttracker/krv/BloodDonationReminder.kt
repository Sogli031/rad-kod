package com.rad.shifttracker.krv

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.DateTimeException
import java.time.format.DateTimeFormatter

internal const val BLOOD_DONATION_OPTION_LABEL = "Davanje krvi"
internal const val BLOOD_DONATION_HISTORY_EMPTY_TEXT = "Još nema upisanih davanja krvi."

private val BLOOD_DONATION_REMINDER_TIME: LocalTime = LocalTime.of(9, 0)
private val BLOOD_DONATION_DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy.")
private val BLOOD_DONATION_DATE_TIME_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("dd.MM.yyyy 'u' HH:mm")

data class BloodDonationReminderScheduleStatus(
    val lastDonationDate: LocalDate? = null,
    val donationCount: Int = 0,
    val nextDonationDate: LocalDate? = null,
    val nextReminderTime: LocalDateTime? = null,
    val notificationPermissionGranted: Boolean = true
)

sealed class BloodDonationReminderAlarmPlan {
    data object Cancel : BloodDonationReminderAlarmPlan()
    data class Schedule(val reminderTime: LocalDateTime) : BloodDonationReminderAlarmPlan()
}

fun calculateNextBloodDonationDate(lastDonationDate: LocalDate): LocalDate {
    return lastDonationDate.plusMonths(3)
}

fun planBloodDonationReminderAlarm(
    lastDonationDate: LocalDate?,
    now: LocalDateTime
): BloodDonationReminderAlarmPlan {
    val nextDonationDate = lastDonationDate?.let(::calculateNextBloodDonationDate)
        ?: return BloodDonationReminderAlarmPlan.Cancel
    val firstReminder = LocalDateTime.of(nextDonationDate, BLOOD_DONATION_REMINDER_TIME)
    val nextReminder = when {
        now.isBefore(firstReminder) -> firstReminder
        now.toLocalTime().isBefore(BLOOD_DONATION_REMINDER_TIME) ->
            LocalDateTime.of(now.toLocalDate(), BLOOD_DONATION_REMINDER_TIME)
        else -> LocalDateTime.of(now.toLocalDate().plusDays(1), BLOOD_DONATION_REMINDER_TIME)
    }
    return BloodDonationReminderAlarmPlan.Schedule(nextReminder)
}

internal fun bloodDonationLastDateText(lastDonationDate: LocalDate?): String {
    return "Datum poslednjeg davanja: ${lastDonationDate?.formatBloodDonationDate() ?: "nije unet."}"
}

internal fun bloodDonationNextDateText(lastDonationDate: LocalDate?): String {
    val nextDate = lastDonationDate?.let(::calculateNextBloodDonationDate)
    return "Možeš sledeći put: ${nextDate?.formatBloodDonationDate() ?: "nije izračunat."}"
}

internal fun bloodDonationCountText(count: Int): String {
    return "Broj davanja: ${count.coerceAtLeast(0)}"
}

internal fun bloodDonationHistoryDateText(date: LocalDate): String {
    return date.formatBloodDonationDate()
}

internal fun bloodDonationDeleteConfirmationText(date: LocalDate): String {
    return "Obrisati davanje krvi za ${date.formatBloodDonationDate()}?"
}

internal fun validateBloodDonationLeaveStartDate(
    firstDay: LocalDate?,
    previousLeaveStartDate: LocalDate?
): String? {
    if (firstDay == null) {
        return "Unesi ispravan prvi dan odsustva."
    }
    val nextAllowedDate = previousLeaveStartDate?.let(::calculateNextBloodDonationLeaveDate)
    return if (nextAllowedDate != null && firstDay.isBefore(nextAllowedDate)) {
        "Nemaš pravo da koristiš dane pre ${nextAllowedDate.formatBloodDonationDate()}"
    } else {
        null
    }
}

internal fun bloodDonationReminderStatusText(status: BloodDonationReminderScheduleStatus): String {
    if (status.lastDonationDate == null) {
        return "Unesi datum poslednjeg davanja krvi."
    }
    return buildString {
        val nextReminder = status.nextReminderTime
        if (nextReminder != null) {
            append("Podsetnik: jednom dnevno od ")
            append(nextReminder.format(BLOOD_DONATION_DATE_TIME_FORMAT))
            append('.')
        } else {
            append("Podsetnik nije zakazan.")
        }
        if (!status.notificationPermissionGranted) {
            append('\n')
            append("Dozvola za obaveštenja nije uključena.")
        }
    }
}

internal fun parseBloodDonationDateInSelectedMonth(
    dayInput: String,
    selectedYear: Int,
    selectedMonth: Int
): LocalDate? {
    val day = dayInput.toIntOrNull()
    return try {
        if (day == null) null else LocalDate.of(selectedYear, selectedMonth, day)
    } catch (_: DateTimeException) {
        null
    }
}

internal fun LocalDate.formatBloodDonationDate(): String = format(BLOOD_DONATION_DATE_FORMAT)
