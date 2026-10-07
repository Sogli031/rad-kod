package com.rad.shifttracker.repo

import android.content.Context
import java.time.LocalDate
import com.rad.shifttracker.krv.BloodDonationLeaveBackup
import com.rad.shifttracker.krv.BloodDonationReminderScheduleStatus
import com.rad.shifttracker.krv.BloodDonationReminderScheduler
import com.rad.shifttracker.krv.BloodDonationLeaveState
import com.rad.shifttracker.krv.BloodDonationLeaveUsage
import com.rad.shifttracker.krv.bloodDonationLeaveDates
import com.rad.shifttracker.krv.bloodDonationLeaveWorkdayDates
import com.rad.shifttracker.krv.latestPaidLeavePair
import com.rad.shifttracker.smene.DayRecord
import com.rad.shifttracker.smene.DayRecordSource
import com.rad.shifttracker.smene.Shift
import com.rad.shifttracker.krv.lastBloodDonationLeaveUsage

var ShiftRepository.lastBloodDonationDate: LocalDate?
    get() = synchronized(lock) { lastBloodDonationDateState }
    set(value) {
        val changed = synchronized(lock) {
            val newHistory = value?.let { normalizeBloodDonationHistory(listOf(it)) } ?: emptyList()
            val newCount = if (value == null) 0 else bloodDonationCountState.coerceAtLeast(1)
            if (
                lastBloodDonationDateState == value &&
                bloodDonationHistoryState == newHistory &&
                bloodDonationCountState == newCount
            ) {
                false
            } else {
                lastBloodDonationDateState = value
                bloodDonationHistoryState = newHistory
                bloodDonationCountState = newCount
                true
            }
        }
        if (changed) {
            notifyChange()
        }
    }

val ShiftRepository.bloodDonationCount: Int
    get() = synchronized(lock) { bloodDonationCountState.coerceAtLeast(bloodDonationHistoryState.size) }

val ShiftRepository.bloodDonationHistory: List<LocalDate>
    get() = synchronized(lock) { bloodDonationHistoryState.toList() }

fun ShiftRepository.recordBloodDonation(date: LocalDate) {
    val changed = synchronized(lock) {
        val newHistory = normalizeBloodDonationHistory(bloodDonationHistoryState + date)
        val dateAdded = newHistory.size != bloodDonationHistoryState.size
        val newCount = when {
            dateAdded -> (bloodDonationCountState + 1).coerceAtLeast(newHistory.size)
            bloodDonationCountState == 0 -> 1.coerceAtLeast(newHistory.size)
            else -> bloodDonationCountState.coerceAtLeast(newHistory.size)
        }
        val newLastDate = newHistory.firstOrNull()
        if (
            bloodDonationHistoryState == newHistory &&
            lastBloodDonationDateState == newLastDate &&
            bloodDonationCountState == newCount
        ) {
            false
        } else {
            bloodDonationHistoryState = newHistory
            lastBloodDonationDateState = newLastDate
            bloodDonationCountState = newCount
            true
        }
    }
    if (changed) {
        notifyChange()
    }
}

fun ShiftRepository.deleteBloodDonation(date: LocalDate): Boolean {
    val changed = synchronized(lock) {
        if (date !in bloodDonationHistoryState) {
            false
        } else {
            bloodDonationHistoryState = bloodDonationHistoryState.filterNot { it == date }
            lastBloodDonationDateState = bloodDonationHistoryState.firstOrNull()
            // Ručno unet ukupan brojač (veći od praćene istorije) se ne resetuje
            // na history.size — samo se umanji za obrisano davanje.
            bloodDonationCountState = (bloodDonationCountState - 1).coerceAtLeast(bloodDonationHistoryState.size)
            true
        }
    }
    if (changed) {
        notifyChange()
    }
    return changed
}

internal fun ShiftRepository.normalizeBloodDonationHistory(dates: Collection<LocalDate>): List<LocalDate> {
    return dates.distinct().sortedDescending()
}

internal fun ShiftRepository.normalizeBloodDonationLeaveStates(
    states: Collection<BloodDonationLeaveState>
): List<BloodDonationLeaveState> {
    return states.sortedByDescending { it.firstDay }.distinctBy { it.firstDay }
}

val ShiftRepository.bloodDonationLeaveStartDate: LocalDate?
    get() = synchronized(lock) { bloodDonationLeaveStates.firstOrNull()?.firstDay }

/** Poslednje korišćenje dana za krv: najnoviji par plaćenog odsustva u kalendaru ili ručni upis. */
internal val ShiftRepository.lastBloodDonationLeaveUsage: BloodDonationLeaveUsage?
    get() = synchronized(lock) {
        val paidLeaveDates = recordStore.getAllMonthsSorted()
            .flatMap { recordStore.getMonthRecords(it) }
            .filter { it.isPaidLeave }
            .map { it.date }
        lastBloodDonationLeaveUsage(latestPaidLeavePair(paidLeaveDates), bloodDonationLeaveStates.firstOrNull()?.firstDay)
    }

fun ShiftRepository.applyBloodDonationLeave(firstDay: LocalDate): Boolean {
    synchronized(lock) {
        clearBloodDonationLeaveEntryForDateUnsafe(firstDay)
        val backups = bloodDonationLeaveWorkdayDates(firstDay).map { date ->
            BloodDonationLeaveBackup(
                date = date,
                record = recordStore.get(date.year, date.monthValue, date.dayOfMonth)
            )
        }
        for (date in bloodDonationLeaveWorkdayDates(firstDay)) {
            recordStore.set(bloodDonationLeaveRecord(date))
        }
        bloodDonationLeaveStates = normalizeBloodDonationLeaveStates(
            bloodDonationLeaveStates + BloodDonationLeaveState(
                firstDay = firstDay,
                previousRecords = backups
            )
        )
    }
    notifyChange()
    return true
}

fun ShiftRepository.clearBloodDonationLeave(): Boolean {
    val changed = synchronized(lock) {
        val state = bloodDonationLeaveStates.firstOrNull() ?: return@synchronized false
        restoreBloodDonationLeaveUnsafe(state)
        bloodDonationLeaveStates = bloodDonationLeaveStates.filterNot { it.firstDay == state.firstDay }
        true
    }
    if (changed) {
        notifyChange()
    }
    return changed
}

fun ShiftRepository.clearBloodDonationLeaveForDate(date: LocalDate): Boolean {
    val changed = synchronized(lock) {
        clearBloodDonationLeaveEntryForDateUnsafe(date)
    }
    if (changed) {
        notifyChange()
    }
    return changed
}

internal fun ShiftRepository.clearBloodDonationLeaveEntryForDateUnsafe(date: LocalDate): Boolean {
    val state = bloodDonationLeaveStates.firstOrNull { date in bloodDonationLeaveDates(it.firstDay) } ?: return false
    restoreBloodDonationLeaveUnsafe(state)
    bloodDonationLeaveStates = bloodDonationLeaveStates.filterNot { it.firstDay == state.firstDay }
    return true
}

internal fun ShiftRepository.restoreBloodDonationLeaveUnsafe(state: BloodDonationLeaveState) {
    for (backup in state.previousRecords) {
        val record = backup.record
        if (record == null) {
            recordStore.delete(backup.date.year, backup.date.monthValue, backup.date.dayOfMonth)
        } else {
            recordStore.set(record)
        }
    }
}

internal fun ShiftRepository.bloodDonationLeaveRecord(date: LocalDate): DayRecord {
    return DayRecord(
        year = date.year,
        month = date.monthValue,
        day = date.dayOfMonth,
        shift = Shift.SICK,
        hours = config.pay.defaultHours,
        source = DayRecordSource.BLOOD_DONATION_LEAVE
    )
}

/** Zakazuje podsetnik za davanje krvi po podacima iz repo-a. */
internal fun BloodDonationReminderScheduler.reschedule(context: Context, repo: ShiftRepository): BloodDonationReminderScheduleStatus =
    reschedule(context, repo.lastBloodDonationDate, repo.bloodDonationCount)

internal fun BloodDonationReminderScheduler.currentStatus(context: Context, repo: ShiftRepository): BloodDonationReminderScheduleStatus =
    currentStatus(context, repo.lastBloodDonationDate, repo.bloodDonationCount)
