package com.rad.shifttracker.repo

import com.rad.shifttracker.data.repository.ChangeNotificationManager
import com.rad.shifttracker.data.repository.RepositoryAbsenceSimulator
import com.rad.shifttracker.data.repository.RepositoryBulkOperations
import com.rad.shifttracker.data.repository.RepositoryCompBalanceCalculator
import com.rad.shifttracker.data.repository.RepositoryHolidayRecords
import com.rad.shifttracker.data.repository.RepositoryMonthlyAdjustments
import com.rad.shifttracker.data.repository.RepositoryPayQueries
import com.rad.shifttracker.data.repository.RepositoryPaySettingsUpdater
import com.rad.shifttracker.data.repository.RepositoryPayState
import com.rad.shifttracker.data.repository.RepositoryVacationTracker
import com.rad.shifttracker.data.repository.ShiftRecordStore
import com.rad.shifttracker.data.storage.LocalDataStore
import com.rad.shifttracker.data.storage.LocalShiftStorage
import com.rad.shifttracker.data.storage.ShiftRepositoryJsonCodec
import java.time.Clock
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import com.rad.shifttracker.AppConfig
import com.rad.shifttracker.CONFIG
import com.rad.shifttracker.godisnji.VacationRequestValidation
import com.rad.shifttracker.godisnji.validateVacationRequestAvailability
import com.rad.shifttracker.krv.BloodDonationLeaveState
import com.rad.shifttracker.listici.ParsedPayslip
import com.rad.shifttracker.listici.percentDeductionsForMonth
import com.rad.shifttracker.obracun.PayBaseSelectionMode
import com.rad.shifttracker.smene.BrigadeType
import com.rad.shifttracker.smene.DayRecord
import com.rad.shifttracker.smene.DayRecordSource
import com.rad.shifttracker.smene.HolidayEntry
import com.rad.shifttracker.smene.MonthKey
import com.rad.shifttracker.smene.OvertimeType
import com.rad.shifttracker.smene.Shift
import com.rad.shifttracker.smene.Weekday

data class BatchRecord(
    val year: Int,
    val month: Int,
    val day: Int,
    val shift: Shift,
    val hours: Int,
    val sickPayRate: Double? = null,
    val balanceExempt: Boolean = false,
    val source: DayRecordSource = DayRecordSource.MANUAL,
    /** Praznik koji je korisnik sam uključio (true) ili isključio (false) za taj datum. */
    val holidayOverride: Boolean? = null,
    val holidayWorkOverride: Boolean? = null
)

data class AbsenceRangeNetImpact(
    val currentNetPay: Double,
    val previewNetPay: Double,
    val netDifference: Double,
    val affectedDays: Int,
    val absenceHours: Int,
    val replacedWorkHours: Int,
    val replacedNightHours: Int
)

class ShiftRepository(
    internal val config: AppConfig = CONFIG,
    /** Izvor „danas“; testovi ga fiksiraju (ponoć, promena godine). */
    internal val clock: Clock = Clock.systemDefaultZone()
) {
    // Thread safety: All access to mutable repository state must be synchronized on `lock`
    internal val lock = Any()
    internal val recordStore = ShiftRecordStore(::invalidateSummaryUnsafe)
    internal val payState = RepositoryPayState(config)
    internal val compBalanceCalculator = RepositoryCompBalanceCalculator(payState, recordStore)
    internal val holidayRecords = RepositoryHolidayRecords(recordStore)
    internal val paySettingsUpdater = RepositoryPaySettingsUpdater(payState, recordStore)
    internal val bulkOperations = RepositoryBulkOperations(
        config = config,
        payState = payState,
        recordStore = recordStore,
        invalidateSummaryAndRollingDependents = ::invalidateSummaryAndRollingDependentsUnsafe
    )
    internal val monthlyAdjustments = RepositoryMonthlyAdjustments(
        payState = payState,
        invalidateSummaryAndRollingDependents = ::invalidateSummaryAndRollingDependentsUnsafe
    )
    internal val vacationTracker = RepositoryVacationTracker(
        recordStore,
        ::calculateYearsOfServiceUnsafe,
        ::payslipVacationDaysByMonthUnsafe,
        clock
    )
    internal val payQueries = RepositoryPayQueries(
        config = config,
        payState = payState,
        recordStore = recordStore,
        getCompBalanceAtMonthStartUnsafe = compBalanceCalculator::hoursAtMonthStart,
        getCompBalanceMinutesAtMonthStartUnsafe = compBalanceCalculator::minutesAtMonthStart,
        calculateYearsOfServiceUnsafe = ::calculateYearsOfServiceUnsafe,
        percentDeductionsForMonthUnsafe = { month -> percentDeductionsForMonth(month, savedPayslipsUnsafe()) }
    )
    internal val absenceSimulator = RepositoryAbsenceSimulator(
        config = config,
        recordStore = recordStore,
        payQueries = payQueries
    )
    // Nova instalacija: čitanje akontacije iz obaveštenja je isključeno dok ga korisnik ne uključi.
    internal var notificationAccessPromptSuppressedState = false
    internal var mainPaySalaryOnlyState = true
    internal var lastBloodDonationDateState: LocalDate? = null
    internal var bloodDonationCountState = 0
    internal var bloodDonationHistoryState: List<LocalDate> = emptyList()
    internal var bloodDonationLeaveStates: List<BloodDonationLeaveState> = emptyList()

    // Meseci koje je korisnik izričito obrisao — čuva se u snapshot-u da ih
    // merge sa cloud arhivom ne bi vaskrsao (vidi CloudArchiveMerger).
    internal var deletedMonthTombstones: MutableSet<MonthKey> = linkedSetOf()

    // Izričito obrisane satnice — isti razlog kao gore, samo za hourly_value_changes.
    internal var deletedHourlyValueTombstones: MutableSet<MonthKey> = linkedSetOf()

    // Izričito obrisani listići — oblak spaja listiće kao uniju, pa bi se bez ovoga vratili.
    internal var deletedPayslipTombstones: MutableSet<MonthKey> = linkedSetOf()

    // Platni listići po mesecu (JSON listića) za „Uporedi“; ne orezuju se kao zapisi.
    internal val savedPayslipJsonByMonth: MutableMap<MonthKey, String> = mutableMapOf()

    // Dani godišnjeg po listiću, uz JSON iz kog su izračunati (da se listić ne parsira pri svakom crtanju).
    internal val payslipVacationDaysCache: MutableMap<MonthKey, Pair<String, Double>> = mutableMapOf()

    // Pročitani listići uz JSON iz kog su nastali — obustave u % se traže pri svakom obračunu neta.
    internal val parsedPayslipCache: MutableMap<MonthKey, Pair<String, ParsedPayslip?>> = mutableMapOf()
    internal val jsonCodec = ShiftRepositoryJsonCodec(config, clock)

    /** Zamenljivo u testovima; produkcija piše u `filesDir`. */
    internal var localStorage: LocalDataStore = LocalShiftStorage()

    /** Lokalni fajl je pisala novija verzija aplikacije: ne sme se pregaziti starijom. */
    @Volatile
    internal var localWriteBlockedBySchema = false

    internal val changeNotifier = ChangeNotificationManager(TAG)

    companion object {
        internal const val TAG = "ShiftRepository"

        // Granice za validaciju unosa
        internal const val MAX_FOOD_PER_DAY = 10_000.0
        internal const val MAX_TRANSPORT_PER_DAY = 10_000.0
        internal const val MAX_PERCENT = 100.0
        internal const val MAX_TAX_RELIEF = 10_000_000.0
        internal const val MIN_COMP_BALANCE = 0
        internal const val MAX_COMP_BALANCE = 1000
        internal const val MINUTES_PER_HOUR = 60
        internal const val MAX_COMP_BALANCE_MINUTES = MAX_COMP_BALANCE * MINUTES_PER_HOUR
        internal const val NIGHT_WORK_PAYSLIP_CODE = 20
        internal const val ADVANCE_SAME_AMOUNT_TOLERANCE = 0.005
    }

    /**
     * Register a callback to be called when data changes.
     * @return A token that can be used to remove the callback later.
     */
    fun onChange(callback: () -> Unit): Int = changeNotifier.register(callback)

    /**
     * Remove a previously registered change callback.
     * @param token The token returned from onChange()
     */
    fun removeOnChange(token: Int) = changeNotifier.unregister(token)

    /**
     * Kept for older callers. Callback ownership belongs to returned tokens,
     * so application-level callbacks such as autosave must not be cleared here.
     */
    fun close() = Unit

    internal fun notifyChange() = changeNotifier.notifyChange()

    /** Tekući mesec za granicu brisanja istorije; testovi ga pomeraju. */
    internal var retentionClock: () -> YearMonth = { YearMonth.now(clock) }

    internal fun invalidateSummaryUnsafe(monthKey: MonthKey) {
        invalidateSummaryAndRollingDependentsUnsafe(monthKey)
        val nextMonth = nextCalendarMonthKey(monthKey)
        payState.summaries.remove(nextMonth)
        payState.dirtyMonths.add(nextMonth)
        // Invalidate comp balance cache for all FUTURE months (> not >=)
        // because the starting balance of month X only depends on records in months BEFORE X
        // So when a record in month X changes, only months X+1 and beyond are affected
        payState.compBalanceCache.entries.removeIf { it.key > monthKey }
    }

    internal fun invalidateSummaryAndRollingDependentsUnsafe(changedMonth: MonthKey) {
        payState.summaries.remove(changedMonth)
        payState.rollingPayBaseCache.clear()
        payState.dirtyMonths.add(changedMonth)
        if (payState.defaultPayBaseSelectionMode in setOf(
                PayBaseSelectionMode.CALCULATED_PREVIOUS_12_MONTHS,
                PayBaseSelectionMode.KNOWN_BASE
            )
        ) {
            val futureSummaryKeys = payState.summaries.keys.filter { it > changedMonth }
            for (key in futureSummaryKeys) {
                payState.summaries.remove(key)
                payState.dirtyMonths.add(key)
            }
        }
        for ((key, bases) in payState.monthlyPayBases) {
            if (
                bases.selectionMode in setOf(
                    PayBaseSelectionMode.CALCULATED_PREVIOUS_12_MONTHS,
                    PayBaseSelectionMode.KNOWN_BASE
                ) &&
                key > changedMonth
            ) {
                payState.summaries.remove(key)
                payState.dirtyMonths.add(key)
            }
        }
    }

    fun get(year: Int, month: Int, day: Int): DayRecord? = synchronized(lock) {
        recordStore.get(year, month, day)
    }

    fun set(
        year: Int,
        month: Int,
        day: Int,
        shift: Shift,
        hours: Int,
        shift2: Shift? = null,
        hours2: Int = 0,
        overtimeType: OvertimeType = OvertimeType.PAID,
        weekendOvertimeType: OvertimeType = OvertimeType.PAID,
        compHoursUsed: Int = 0,
        holidayWorkOverride: Boolean? = null,
        holidayOverride: Boolean? = null,
        sickPayRate: Double? = null,
        balanceExempt: Boolean = false,
        regularShortageMinutes: Int = 0,
        source: DayRecordSource = DayRecordSource.MANUAL
    ): Boolean {
        synchronized(lock) {
            val targetDate = LocalDate.of(year, month, day)
            val clearedBloodDonationLeave = clearBloodDonationLeaveEntryForDateUnsafe(targetDate)
            if (shift == Shift.OFF && clearedBloodDonationLeave) {
                return@synchronized
            }
            recordStore.delete(year, month, day)

            if (isWeekendOff(year, month, day, shift)) {
                return@synchronized
            }

            val finalSickRate = if (shift != Shift.SICK) {
                null
            } else {
                sickPayRate ?: config.pay.sickPayRate
            }
            val finalBalanceExempt = shift == Shift.OFF && balanceExempt

            val record = DayRecord(
                year = year,
                month = month,
                day = day,
                shift = shift,
                hours = hours,
                shift2 = shift2,
                hours2 = hours2,
                overtimeType = overtimeType,
                weekendOvertimeType = weekendOvertimeType,
                compHoursUsed = compHoursUsed,
                holidayWorkOverride = holidayWorkOverride,
                holidayOverride = holidayOverride,
                balanceExempt = finalBalanceExempt,
                sickPayRate = finalSickRate,
                regularShortageMinutes = regularShortageMinutes,
                source = source
            )
            recordStore.set(record)
        }

        notifyChange()
        return true
    }

    internal fun isWeekendOff(year: Int, month: Int, day: Int, shift: Shift): Boolean {
        val date = LocalDate.of(year, month, day)
        if (shift != Shift.OFF || Weekday.from(date).isWorkday) return false
        return payState.brigadeType != BrigadeType.CETVOROBRIGADA || date.dayOfWeek == DayOfWeek.SUNDAY
    }

    fun delete(year: Int, month: Int, day: Int): Boolean {
        val changed = synchronized(lock) {
            if (clearBloodDonationLeaveEntryForDateUnsafe(LocalDate.of(year, month, day))) return@synchronized true
            if (!recordStore.delete(year, month, day)) return false
            true
        }
        if (!changed) return false
        notifyChange()
        return true
    }

    fun deleteMonth(year: Int, month: Int): Int {
        val outcome = synchronized(lock) {
            val result = bulkOperations.deleteMonth(MonthKey(year, month))
            if (result.recordsDeletedCount > 0) {
                // Zapamti izričito brisanje da ga merge sa cloud arhivom
                // ne bi vaskrsao (posebno za najstariji mesec).
                deletedMonthTombstones += MonthKey(year, month)
            }
            result
        }
        if (outcome.shouldNotify) {
            notifyChange()
        }
        return outcome.recordsDeletedCount
    }

    fun deleteAutoFilledHolidayRecordsBefore(cutoffMonth: MonthKey): Int {
        val deleted = synchronized(lock) {
            holidayRecords.deleteAutoFilledHolidayRecordsBefore(cutoffMonth)
        }
        if (deleted > 0) {
            notifyChange()
        }
        return deleted
    }

    /**
     * Deletes month-scoped user entries while preserving global app settings.
     */
    fun deleteAllData(): Int {
        val outcome = synchronized(lock) {
            bulkOperations.deleteAllData()
        }
        if (outcome.shouldNotify) {
            notifyChange()
        }
        return outcome.recordsDeletedCount
    }

    fun setBatch(batchRecords: List<BatchRecord>) {
        synchronized(lock) {
            bulkOperations.setBatch(batchRecords)
        }
        notifyChange()
    }

    fun validateVacationRequest(dates: List<LocalDate>): VacationRequestValidation = synchronized(lock) {
        val uniqueDates = dates.distinct().sorted()
        val existingVacationDates = uniqueDates
            .filter { date ->
                recordStore.get(date.year, date.monthValue, date.dayOfMonth)?.shift == Shift.VACATION
            }
            .toSet()
        validateVacationRequestAvailability(
            requestedDates = uniqueDates,
            existingVacationDates = existingVacationDates,
            carryoverRemainingBeforeDate = { date ->
                vacationTracker.getVacationBalanceSummary(date.year, date.minusDays(1)).carryoverRemaining
            },
            currentYearAvailableFromForDate = { date ->
                vacationTracker.getVacationBalanceSummary(date.year, date.minusDays(1)).currentYearAvailableFrom
            }
        )
    }

    fun getMonthRecords(year: Int, month: Int): List<DayRecord> = synchronized(lock) {
        recordStore.getMonthRecords(year, month)
    }

    fun getAllMonthsSorted(): List<MonthKey> = synchronized(lock) {
        recordStore.getAllMonthsSorted()
    }

    fun getHolidayEntries(year: Int): List<HolidayEntry> = synchronized(lock) {
        holidayRecords.getHolidayEntries(year)
    }

    fun setHolidayActive(year: Int, month: Int, day: Int, isActive: Boolean): Boolean {
        val changed = synchronized(lock) {
            holidayRecords.setHolidayActive(year, month, day, isActive)
        }
        if (changed) {
            notifyChange()
        }
        return true
    }
}

private fun nextCalendarMonthKey(monthKey: MonthKey): MonthKey {
    val nextMonth = YearMonth.of(monthKey.year, monthKey.month).plusMonths(1)
    return MonthKey(nextMonth.year, nextMonth.monthValue)
}

/**
 * Lokalno se čuva ~24 meseca istorije (od najnovijeg upisanog meseca). Isto važi i za sačuvane
 * listiće, lokalno i u oblaku: listić stariji od dana sa kojima se poredi ne služi ničemu.
 */
internal const val HISTORY_RETENTION_LOOKBACK_MONTHS = 24L

/** Prva godina čiji su svi meseci posle granice brisanja (januar je unutar prozora). */
internal fun firstFullyRetainedYear(cutoff: MonthKey): Int = if (cutoff.month == 1) cutoff.year else cutoff.year + 1

internal fun MonthKey.minusMonthsForRetention(months: Long): MonthKey {
    val result = YearMonth.of(year, month).minusMonths(months)
    return MonthKey(result.year, result.monthValue)
}

