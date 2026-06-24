package com.rad.shifttracker

import android.content.Context
import android.util.Log
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
import com.rad.shifttracker.data.repository.RollingPayBaseMonthSimulator
import com.rad.shifttracker.data.repository.ShiftRecordStore
import com.rad.shifttracker.data.storage.LocalShiftStorage
import com.rad.shifttracker.data.storage.ShiftRepositoryJsonCodec
import com.rad.shifttracker.data.storage.ShiftRepositorySnapshot
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth

data class BatchRecord(
    val year: Int,
    val month: Int,
    val day: Int,
    val shift: Shift,
    val hours: Int,
    val sickPayRate: Double? = null,
    val balanceExempt: Boolean = false,
    val source: DayRecordSource = DayRecordSource.MANUAL
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

enum class AdvancePaymentNotificationImportDecision {
    IMPORTED,
    SKIPPED_EXISTING_ADVANCE,
    SKIPPED_INVALID
}

data class AdvancePaymentNotificationImportResult(
    val decision: AdvancePaymentNotificationImportDecision,
    val month: MonthKey,
    val amount: Double
)

@Suppress("TooManyFunctions", "LargeClass")
class ShiftRepository(private val config: AppConfig = CONFIG) {
    // Thread safety: All access to mutable repository state must be synchronized on `lock`
    private val lock = Any()
    private val recordStore = ShiftRecordStore(::invalidateSummaryUnsafe)
    private val payState = RepositoryPayState(config)
    private val compBalanceCalculator = RepositoryCompBalanceCalculator(payState, recordStore)
    private val holidayRecords = RepositoryHolidayRecords(recordStore)
    private val paySettingsUpdater = RepositoryPaySettingsUpdater(payState, recordStore)
    private val bulkOperations = RepositoryBulkOperations(
        config = config,
        payState = payState,
        recordStore = recordStore,
        invalidateSummaryAndRollingDependents = ::invalidateSummaryAndRollingDependentsUnsafe
    )
    private val monthlyAdjustments = RepositoryMonthlyAdjustments(
        payState = payState,
        invalidateSummaryAndRollingDependents = ::invalidateSummaryAndRollingDependentsUnsafe
    )
    private val vacationTracker = RepositoryVacationTracker(recordStore, ::calculateYearsOfServiceUnsafe)
    private val payQueries = RepositoryPayQueries(
        config = config,
        payState = payState,
        recordStore = recordStore,
        getCompBalanceAtMonthStartUnsafe = compBalanceCalculator::hoursAtMonthStart,
        getCompBalanceMinutesAtMonthStartUnsafe = compBalanceCalculator::minutesAtMonthStart,
        getCompBalanceAtMonthStartReadOnly = compBalanceCalculator::hoursAtMonthStartReadOnly,
        getCompBalanceMinutesAtMonthStartReadOnly = compBalanceCalculator::minutesAtMonthStartReadOnly,
        calculateYearsOfServiceUnsafe = ::calculateYearsOfServiceUnsafe
    )
    private val absenceSimulator = RepositoryAbsenceSimulator(
        config = config,
        recordStore = recordStore,
        payQueries = payQueries
    )
    private var foodReceiptReminderEnabledState = false
    private var lastBloodDonationDateState: LocalDate? = null
    private var bloodDonationCountState = 0
    private var bloodDonationHistoryState: List<LocalDate> = emptyList()
    private var bloodDonationLeaveStates: List<BloodDonationLeaveState> = emptyList()
    private val jsonCodec = ShiftRepositoryJsonCodec(config)
    private val localStorage = LocalShiftStorage()

    private val changeNotifier = ChangeNotificationManager(TAG)

    companion object {
        private const val TAG = "ShiftRepository"

        // Granice za validaciju unosa
        private const val MAX_FOOD_PER_DAY = 10_000.0
        private const val MAX_TRANSPORT_PER_DAY = 10_000.0
        private const val MAX_PERCENT = 100.0
        private const val MAX_TAX_RELIEF = 10_000_000.0
        private const val MIN_COMP_BALANCE = 0
        private const val MAX_COMP_BALANCE = 1000
        private const val MINUTES_PER_HOUR = 60
        private const val MAX_COMP_BALANCE_MINUTES = MAX_COMP_BALANCE * MINUTES_PER_HOUR
        private const val HISTORY_RETENTION_LOOKBACK_MONTHS = 24L
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

    private fun notifyChange() = changeNotifier.notifyChange()

    private fun buildSnapshotUnsafe(): ShiftRepositorySnapshot {
        return ShiftRepositorySnapshot(
            payCoefficient = payState.payCoefficient,
            sickPayBaseHourly = payState.sickPayBaseHourly,
            defaultPayBaseSelectionMode = payState.defaultPayBaseSelectionMode,
            hourlyValueChanges = payState.hourlyValueChanges.toMap(),
            foodPerDay = payState.foodPerDay,
            transportDailyAmount = payState.transportDailyAmount,
            creditDeductionEnabled = payState.creditDeductionEnabled,
            monthlyCreditDeductionAmount = payState.monthlyCreditDeductionAmount,
            unionMember = payState.unionMember,
            unionDuesRate = payState.unionDuesRate,
            foodReceiptReminderEnabled = foodReceiptReminderEnabledState,
            lastBloodDonationDate = lastBloodDonationDateState,
            bloodDonationCount = bloodDonationCountState,
            bloodDonationHistory = bloodDonationHistoryState,
            bloodDonationLeaves = bloodDonationLeaveStates,
            initialCompBalance = payState.initialCompBalance,
            initialCompBalanceMinutes = payState.initialCompBalanceMinutes,
            minuliRadStartYear = payState.minuliRadStartYear,
            minuliRadStartMonth = payState.minuliRadStartMonth,
            seniorityInputMode = payState.seniorityInputMode,
            minuliRadBenefitEnabled = payState.minuliRadBenefitEnabled,
            minuliRadIncreaseFactor = payState.minuliRadIncreaseFactor,
            minuliRadLastPayslipYear = payState.minuliRadLastPayslipYear,
            minuliRadLastPayslipMonth = payState.minuliRadLastPayslipMonth,
            minuliRadPayslipPercent = payState.minuliRadPayslipPercent,
            brigadeType = payState.brigadeType,
            initialVacationBalance = vacationTracker.snapshotInitialVacationBalance(),
            vacationYearBalances = vacationTracker.snapshotVacationYearBalances(),
            employeeContributionsPercent = payState.employeeContributionsPercent,
            taxRelief = payState.taxRelief,
            taxPercent = payState.taxPercent,
            difficultWorkConditionsPercent = payState.difficultWorkConditionsPercent,
            monthlyPayBases = payState.monthlyPayBases.toMap(),
            monthlyGrossAdditions = payState.monthlyGrossAdditions.toMap(),
            monthlyNetAdjustments = payState.monthlyNetAdjustments.toMap(),
            knownPayBaseAnchor = payState.knownPayBaseAnchor,
            annualLeaveCalculationInput = vacationTracker.snapshotAnnualLeaveCalculationInput(),
            annualLeaveLastCalculatedDays = vacationTracker.snapshotAnnualLeaveLastCalculatedDays(),
            records = recordStore.allRecords.toList()
        )
    }

    fun pruneIrrelevantHistory(): Boolean {
        val changed = synchronized(lock) {
            pruneIrrelevantHistoryUnsafe()
        }
        if (changed) {
            notifyChange()
        }
        return changed
    }

    private fun pruneIrrelevantHistoryUnsafe(): Boolean {
        val plan = historyRetentionPlanUnsafe() ?: return false

        var changed = false
        val protectedPayBaseMonths = materializePayBaseAnchorsUnsafe(plan.cutoff, plan.retainedMonths)
        changed = protectedPayBaseMonths.changed || changed
        changed = resetInitialCompBalanceForRetentionUnsafe(plan) || changed
        changed = removeOldRecordsUnsafe(plan.cutoff) || changed
        changed = pruneHourlyValueChangesUnsafe(plan.cutoff, plan.hourlyValueAtCutoff) || changed
        changed = pruneMonthScopedDataUnsafe(plan.cutoff, protectedPayBaseMonths.months) || changed
        changed = pruneVacationYearBalancesUnsafe() || changed

        if (changed) {
            invalidateRetentionCachesUnsafe()
        }
        return changed
    }

    private data class HistoryRetentionPlan(
        val cutoff: MonthKey,
        val retainedMonths: List<MonthKey>,
        val compBalanceAtCutoff: Int,
        val hourlyValueAtCutoff: Double
    )

    private data class RetentionPayBaseAnchors(
        val months: Set<MonthKey>,
        val changed: Boolean
    )

    private fun historyRetentionPlanUnsafe(): HistoryRetentionPlan? {
        val recordMonths = recordStore.getAllMonthsSorted()
        val latestMonth = recordMonths.maxOrNull() ?: return null
        val cutoff = latestMonth.minusMonthsForRetention(HISTORY_RETENTION_LOOKBACK_MONTHS)
        if (!hasHistoryRetentionWorkUnsafe(cutoff, recordMonths)) return null

        val retainedMonths = recordMonths.filter { it >= cutoff }
        if (retainedMonths.isEmpty()) return null

        return HistoryRetentionPlan(
            cutoff = cutoff,
            retainedMonths = retainedMonths,
            compBalanceAtCutoff = compBalanceCalculator.minutesAtMonthStart(cutoff.year, cutoff.month),
            hourlyValueAtCutoff = hourlyValueUnsafe(cutoff.year, cutoff.month)
        )
    }

    private fun hasHistoryRetentionWorkUnsafe(cutoff: MonthKey, recordMonths: List<MonthKey>): Boolean {
        return recordMonths.any { it < cutoff } ||
            payState.monthlyGrossAdditions.keys.any { it < cutoff } ||
            payState.monthlyNetAdjustments.keys.any { it < cutoff } ||
            payState.monthlyPayBases.keys.any { it < cutoff } ||
            payState.hourlyValueChanges.keys.any { it < cutoff }
    }

    private fun materializePayBaseAnchorsUnsafe(
        cutoff: MonthKey,
        retainedMonths: List<MonthKey>
    ): RetentionPayBaseAnchors {
        var changed = false
        val sourceMonths = retainedMonths
            .map { month -> previousMonthKey(month.year, month.month) ?: month }
            .filter { sourceMonth -> shouldMaterializePayBaseAnchorUnsafe(sourceMonth, cutoff) }
            .toSet()

        for (sourceMonth in sourceMonths.sorted()) {
            val resolved = getResolvedPayBasesUnsafe(sourceMonth.year, sourceMonth.month)
            val anchor = MonthlyPayBases(
                year = sourceMonth.year,
                month = sourceMonth.month,
                vacationHourlyBase = resolved.vacationHourlyBase.value,
                paidHolidayHourlyBase = resolved.paidHolidayHourlyBase.value,
                sickHourlyBase = resolved.sickHourlyBase.value,
                selectionMode = PayBaseSelectionMode.MANUAL
            )
            if (payState.monthlyPayBases[sourceMonth] != anchor) {
                payState.monthlyPayBases[sourceMonth] = anchor
                changed = true
            }
        }

        return RetentionPayBaseAnchors(sourceMonths, changed)
    }

    private fun shouldMaterializePayBaseAnchorUnsafe(sourceMonth: MonthKey, cutoff: MonthKey): Boolean {
        if (sourceMonth < cutoff) return true
        val selectionMode = effectivePayBaseSelectionMode(
            payState.monthlyPayBases[sourceMonth],
            payState.defaultPayBaseSelectionMode
        )
        return when (selectionMode) {
            PayBaseSelectionMode.CALCULATED_PREVIOUS_12_MONTHS -> {
                previousTwelveMonthKeys(sourceMonth.year, sourceMonth.month).any { it < cutoff }
            }
            PayBaseSelectionMode.KNOWN_BASE -> {
                val anchor = payState.knownPayBaseAnchor ?: return false
                anchor.month < cutoff && anchor.month <= sourceMonth
            }
            PayBaseSelectionMode.MANUAL,
            PayBaseSelectionMode.DEFAULT_VALUES -> false
        }
    }

    private fun resetInitialCompBalanceForRetentionUnsafe(plan: HistoryRetentionPlan): Boolean {
        if (payState.initialCompBalanceMinutes == plan.compBalanceAtCutoff) return false
        payState.initialCompBalanceMinutes = plan.compBalanceAtCutoff
        payState.initialCompBalance = plan.compBalanceAtCutoff / MINUTES_PER_HOUR
        return true
    }

    private fun removeOldRecordsUnsafe(cutoff: MonthKey): Boolean {
        val oldRecordKeys = recordStore.allRecords
            .filter { record -> record.monthKey < cutoff }
            .map { record -> record.key }
        var changed = false
        for (key in oldRecordKeys) {
            changed = recordStore.delete(key.year, key.month, key.day) || changed
        }
        return changed
    }

    private fun pruneHourlyValueChangesUnsafe(cutoff: MonthKey, hourlyValueAtCutoff: Double): Boolean {
        val oldKeys = payState.hourlyValueChanges.keys.filter { it < cutoff }
        var changed = false
        for (key in oldKeys) {
            payState.hourlyValueChanges.remove(key)
            changed = true
        }
        if (oldKeys.isNotEmpty() && hourlyValueAtCutoff.isFinite() && hourlyValueAtCutoff > 0.0) {
            val existing = payState.hourlyValueChanges[cutoff]
            if (existing == null || existing != hourlyValueAtCutoff) {
                payState.hourlyValueChanges[cutoff] = hourlyValueAtCutoff
                changed = true
            }
        }
        return changed
    }

    private fun pruneMonthScopedDataUnsafe(cutoff: MonthKey, protectedPayBaseMonths: Set<MonthKey>): Boolean {
        var changed = false
        changed = removeMonthKeysBefore(payState.monthlyGrossAdditions, cutoff) || changed
        changed = removeMonthKeysBefore(payState.monthlyNetAdjustments, cutoff) || changed
        changed = removeMonthKeysBefore(payState.monthlyPayBases, cutoff, protectedPayBaseMonths) || changed
        return changed
    }

    private fun pruneVacationYearBalancesUnsafe(): Boolean {
        val retainedYears = recordStore.getAllMonthsSorted().map { it.year }.toSet()
        return retainedYears.isNotEmpty() && vacationTracker.retainVacationYearBalances(retainedYears)
    }

    private fun invalidateRetentionCachesUnsafe() {
        payState.invalidateSummariesRollingAndCompBalance()
        payState.dirtyMonths.clear()
        payState.dirtyMonths.addAll(recordStore.getAllMonthsSorted())
        payState.dirtyMonths.addAll(payState.monthlyPayBases.keys)
    }

    private fun <T> removeMonthKeysBefore(
        values: MutableMap<MonthKey, T>,
        cutoff: MonthKey,
        protectedKeys: Set<MonthKey> = emptySet()
    ): Boolean {
        val oldKeys = values.keys.filter { key -> key < cutoff && key !in protectedKeys }
        for (key in oldKeys) {
            values.remove(key)
        }
        return oldKeys.isNotEmpty()
    }

    fun exportJson(): String {
        val snapshot = synchronized(lock) {
            pruneIrrelevantHistoryUnsafe()
            buildSnapshotUnsafe()
        }
        return jsonCodec.encode(snapshot)
    }

    fun importFromJson(jsonText: String): Boolean {
        val snapshot = try {
            jsonCodec.decode(jsonText)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse import JSON", e)
            null
        } ?: return false

        synchronized(lock) {
            recordStore.clear()
            payState.monthlyPayBases.clear()
            payState.monthlyGrossAdditions.clear()
            payState.monthlyNetAdjustments.clear()
            payState.dirtyMonths.clear()
            payState.invalidateSummariesRollingAndCompBalance()
            payState.cachedSummaryCalc = null
            payState.cachedSummaryCalcFoodPerDay = Double.NaN
            payState.cachedSummaryCalcSickPayBaseHourly = Double.NaN
            payState.cachedSummaryCalcDifficultWorkConditionsPercent = Double.NaN

            payState.payCoefficient = if (snapshot.payCoefficient.isFinite() && snapshot.payCoefficient > 0.0) {
                snapshot.payCoefficient.coerceAtMost(MAX_BASE_PAY)
            } else {
                config.pay.defaultCoefficient
            }
            payState.hourlyValueChanges.clear()
            payState.hourlyValueChanges.putAll(snapshot.hourlyValueChanges)
            payState.sickPayBaseHourly = if (snapshot.sickPayBaseHourly.isFinite()) {
                snapshot.sickPayBaseHourly.coerceIn(0.0, MAX_BASE_PAY)
            } else {
                config.pay.sickPayBaseHourly
            }
            payState.defaultPayBaseSelectionMode = snapshot.defaultPayBaseSelectionMode.toGlobalPayBaseSelectionMode()
            payState.foodPerDay = if (snapshot.foodPerDay.isFinite()) {
                snapshot.foodPerDay.coerceIn(0.0, MAX_FOOD_PER_DAY)
            } else {
                config.pay.foodPerDay
            }
            payState.transportDailyAmount = if (snapshot.transportDailyAmount.isFinite()) {
                snapshot.transportDailyAmount.coerceIn(0.0, MAX_TRANSPORT_PER_DAY)
            } else {
                config.pay.transportPerWorkDay
            }
            payState.creditDeductionEnabled = snapshot.creditDeductionEnabled
            payState.monthlyCreditDeductionAmount = snapshot.monthlyCreditDeductionAmount.sanitizeMonthlyNetAdjustment()
            payState.unionMember = snapshot.unionMember
            payState.unionDuesRate = snapshot.unionDuesRate.sanitizeUnionDuesRate()
            foodReceiptReminderEnabledState = snapshot.foodReceiptReminderEnabled
            bloodDonationHistoryState = normalizeBloodDonationHistory(snapshot.bloodDonationHistory)
            lastBloodDonationDateState = bloodDonationHistoryState.firstOrNull() ?: snapshot.lastBloodDonationDate
            bloodDonationCountState = snapshot.bloodDonationCount.coerceAtLeast(bloodDonationHistoryState.size)
            bloodDonationLeaveStates = normalizeBloodDonationLeaveStates(snapshot.bloodDonationLeaves)
            payState.employeeContributionsPercent = if (snapshot.employeeContributionsPercent.isFinite()) {
                snapshot.employeeContributionsPercent.coerceIn(0.0, MAX_PERCENT)
            } else {
                config.pay.defaultEmployeeContributionsPercent
            }
            payState.taxRelief = if (snapshot.taxRelief.isFinite()) {
                snapshot.taxRelief.coerceIn(0.0, MAX_TAX_RELIEF)
            } else {
                config.pay.defaultTaxRelief
            }
            payState.taxPercent = if (snapshot.taxPercent.isFinite()) {
                snapshot.taxPercent.coerceIn(0.0, MAX_PERCENT)
            } else {
                config.pay.defaultTaxPercent
            }
            payState.difficultWorkConditionsPercent = if (snapshot.difficultWorkConditionsPercent.isFinite()) {
                snapshot.difficultWorkConditionsPercent.coerceIn(0.0, MAX_PERCENT)
            } else {
                config.pay.difficultWorkConditionsPercent
            }
            payState.initialCompBalanceMinutes = snapshot.initialCompBalanceMinutes
                .coerceIn(MIN_COMP_BALANCE, MAX_COMP_BALANCE_MINUTES)
            payState.initialCompBalance = payState.initialCompBalanceMinutes / MINUTES_PER_HOUR
            payState.minuliRadStartYear = snapshot.minuliRadStartYear
            payState.minuliRadStartMonth = snapshot.minuliRadStartMonth.coerceIn(1, 12)
            payState.seniorityInputMode = snapshot.seniorityInputMode
            payState.minuliRadBenefitEnabled = snapshot.minuliRadBenefitEnabled
            payState.minuliRadIncreaseFactor = snapshot.minuliRadIncreaseFactor
            payState.minuliRadLastPayslipYear = snapshot.minuliRadLastPayslipYear.coerceAtLeast(0)
            payState.minuliRadLastPayslipMonth = snapshot.minuliRadLastPayslipMonth.coerceIn(1, 12)
            payState.minuliRadPayslipPercent = snapshot.minuliRadPayslipPercent
                .takeIf { it.isFinite() }
                ?.coerceIn(0.0, MAX_PERCENT)
                ?: 0.0
            payState.brigadeType = snapshot.brigadeType
            vacationTracker.restore(
                initialVacationBalance = snapshot.initialVacationBalance,
                vacationYearBalances = snapshot.vacationYearBalances,
                annualLeaveCalculationInput = snapshot.annualLeaveCalculationInput,
                annualLeaveLastCalculatedDays = snapshot.annualLeaveLastCalculatedDays
            )
            payState.monthlyPayBases.putAll(snapshot.monthlyPayBases)
            payState.monthlyGrossAdditions.putAll(snapshot.monthlyGrossAdditions)
            payState.monthlyNetAdjustments.putAll(snapshot.monthlyNetAdjustments)
            payState.knownPayBaseAnchor = snapshot.knownPayBaseAnchor
            if (
                payState.defaultPayBaseSelectionMode == PayBaseSelectionMode.KNOWN_BASE &&
                payState.knownPayBaseAnchor == null
            ) {
                payState.defaultPayBaseSelectionMode = PayBaseSelectionMode.CALCULATED_PREVIOUS_12_MONTHS
            }

            for (record in snapshot.records) {
                recordStore.set(record)
            }
            pruneIrrelevantHistoryUnsafe()
        }
        notifyChange()
        return true
    }

    private fun invalidateSummaryUnsafe(monthKey: MonthKey) {
        invalidateSummaryAndRollingDependentsUnsafe(monthKey)
        val nextMonth = nextCalendarMonthKey(monthKey)
        payState.summaries.remove(nextMonth)
        payState.dirtyMonths.add(nextMonth)
        // Invalidate comp balance cache for all FUTURE months (> not >=)
        // because the starting balance of month X only depends on records in months BEFORE X
        // So when a record in month X changes, only months X+1 and beyond are affected
        payState.compBalanceCache.entries.removeIf { it.key > monthKey }
    }

    private fun invalidateSummaryAndRollingDependentsUnsafe(changedMonth: MonthKey) {
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
        regularShortageMinutes: Int = 0
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
                regularShortageMinutes = regularShortageMinutes
            )
            recordStore.set(record)
        }

        notifyChange()
        return true
    }

    private fun isWeekendOff(year: Int, month: Int, day: Int, shift: Shift): Boolean {
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
            bulkOperations.deleteMonth(MonthKey(year, month))
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

    fun getCompBalanceAtMonthStart(year: Int, month: Int): Int = synchronized(lock) {
        compBalanceCalculator.hoursAtMonthStart(year, month)
    }

    fun getSummary(year: Int, month: Int): MonthSummary = synchronized(lock) {
        val monthKey = MonthKey(year, month)
        payState.summaries[monthKey]?.let { return it }

        val monthRecords = recordStore.getMonthRecords(monthKey)
        val summary = calculateMonthSummaryUnsafe(year, month, monthRecords)
        payState.summaries[monthKey] = summary
        payState.dirtyMonths.remove(monthKey)
        summary
    }

    fun calculateBasePayFromNet(
        year: Int,
        month: Int,
        netPay: Double,
        actualFoodDeduction: Double? = null
    ): Double? = synchronized(lock) {
        if (!netPay.isFinite() || netPay < 0) return null
        if (actualFoodDeduction != null && (!actualFoodDeduction.isFinite() || actualFoodDeduction < 0)) {
            return null
        }
        val monthKey = MonthKey(year, month)
        val monthRecords = recordStore.getMonthRecords(monthKey)
        val summary = payQueries.calculateMonthSummaryUnsafe(
            year = year,
            month = month,
            monthRecords = monthRecords,
            grossHourlyRate = 1.0
        )
        val coefficient = summary.totalWithMinuli
        if (coefficient <= 0.0) return null
        netPay / coefficient
    }

    fun getCurrentCompBalance(year: Int, month: Int): Int {
        return getSummary(year, month).compTimeEnding
    }

    fun calculateDailyPay(record: DayRecord): Double = synchronized(lock) {
        payQueries.calculateDailyPayUnsafe(record)
    }

    fun calculateEstimatedDailyGrossPay(record: DayRecord): Double = synchronized(lock) {
        val workGross = calculateDailyGrossWorkPayWithSeniorityUnsafe(record)
        if (workGross <= 0.0) return@synchronized 0.0
        workGross + calculateDailyGrossAdditionShareUnsafe(record, monthRecordsWithPreviewUnsafe(record))
    }

    fun calculateEstimatedDailyWorkGrossPay(record: DayRecord): Double = synchronized(lock) {
        calculateDailyGrossWorkPayWithSeniorityUnsafe(record)
    }

    fun calculateEstimatedDailyNetPay(record: DayRecord): Double = synchronized(lock) {
        val grossDailyPayWithSeniority = calculateDailyGrossPayWithSeniorityAndAdditionsUnsafe(record)
        if (grossDailyPayWithSeniority <= 0.0) return@synchronized 0.0

        val netBreakdown = calculateNetPayBreakdownWithPreviewUnsafe(
            record = record,
            includeFoodAllowance = true,
            includeGrossAdditions = true
        )
        if (netBreakdown.grossTotal <= 0.0) return@synchronized 0.0

        grossDailyPayWithSeniority * (netBreakdown.displayPay / netBreakdown.grossTotal)
    }

    fun calculateEstimatedDailyWorkNetPay(record: DayRecord): Double = synchronized(lock) {
        val grossDailyWorkPayWithSeniority = calculateDailyGrossWorkPayWithSeniorityUnsafe(record)
        val netBreakdown = calculateNetPayBreakdownWithPreviewUnsafe(
            record = record,
            includeFoodAllowance = false,
            includeGrossAdditions = false
        )
        val paidWorkNet = if (grossDailyWorkPayWithSeniority > 0.0 && netBreakdown.grossTotal > 0.0) {
            grossDailyWorkPayWithSeniority * marginalNetFactorUnsafe(netBreakdown)
        } else {
            0.0
        }
        paidWorkNet + calculateCompensatoryAdditionalNetEffectUnsafe(
            record = record,
            netHourlyRate = marginalNetHourlyRateUnsafe(record.year, record.month, netBreakdown)
        )
    }

    /**
     * Marginalni neto faktor (1 − doprinosi% − porez%) za dodatne/prekovremene sate.
     * Fiksna poreska olakšica (neoporezivi iznos) već je iskorišćena osnovnom platom,
     * pa dodatni sati nose pun marginalni porez. Time se dnevna procena i "Razlika" u
     * DayEdit popup-u poklapaju sa karticom "Prekovremeno", koja računa istu marginalnu
     * razliku (mesec sa − mesec bez tih sati), umesto da se prosečnim odnosom neto/bruto
     * olakšica pogrešno razmaže i na prekovremene sate.
     */
    private fun marginalNetFactorUnsafe(netBreakdown: MonthlyNetPayBreakdown): Double {
        val grossTotal = netBreakdown.grossTotal
        if (grossTotal <= 0.0) return 0.0
        val contributionRate = netBreakdown.employeeContributions / grossTotal
        val taxRate = if (netBreakdown.taxableBase > 0.0) {
            netBreakdown.tax / netBreakdown.taxableBase
        } else {
            0.0
        }
        return (1.0 - contributionRate - taxRate).coerceAtLeast(0.0)
    }

    /**
     * Marginalna neto satnica (bruto satnica × marginalni faktor) za "Za sate" / manjak sati,
     * da vraćeni i izgubljeni sati nose istu marginalnu vrednost kao prekovremeni rad,
     * a ne prosečnu informativnu satnicu sa razmazanom poreskom olakšicom.
     */
    private fun marginalNetHourlyRateUnsafe(
        year: Int,
        month: Int,
        netBreakdown: MonthlyNetPayBreakdown
    ): Double {
        return grossHourlyRateUnsafe(year, month) * marginalNetFactorUnsafe(netBreakdown)
    }

    private fun calculateCompensatoryAdditionalNetEffectUnsafe(
        record: DayRecord,
        netHourlyRate: Double
    ): Double {
        val compensatoryMinutes = record.compensatoryAdditionalWorkMinutes
        if (compensatoryMinutes <= 0 || netHourlyRate <= 0.0) return 0.0

        val balanceBeforeDay = compBalanceMinutesBeforeRecordUnsafe(record)
        if (balanceBeforeDay >= 0) return 0.0

        val effectiveMinutes = compensatoryMinutes.coerceAtMost(-balanceBeforeDay)
        return effectiveMinutes.toDouble() / MINUTES_PER_HOUR * netHourlyRate
    }

    private fun compBalanceMinutesBeforeRecordUnsafe(record: DayRecord): Int {
        var balance = compBalanceCalculator.minutesAtMonthStart(record.year, record.month)
        val priorMonthRecords = recordStore.getMonthRecords(record.monthKey)
            .asSequence()
            .filter { it.key != record.key && it.day < record.day }
            .sortedBy { it.day }

        for (priorRecord in priorMonthRecords) {
            balance += priorRecord.netCompChangeMinutesFor(payState.brigadeType)
        }
        return balance
    }

    private fun calculateDailyGrossPayWithSeniorityAndAdditionsUnsafe(record: DayRecord): Double {
        val workGross = calculateDailyGrossWorkPayWithSeniorityUnsafe(record)
        if (workGross <= 0.0) return 0.0
        return workGross + calculateDailyGrossAdditionShareUnsafe(record, monthRecordsWithPreviewUnsafe(record))
    }

    private fun calculateDailyGrossWorkPayWithSeniorityUnsafe(record: DayRecord): Double {
        val grossHourlyRate = grossHourlyRateUnsafe(record.year, record.month)
        val grossDailyPay = payQueries.calculateDailyPayUnsafe(record, grossHourlyRate)
        val dailySeniorityBonus = payQueries.calculateDailySeniorityBonusUnsafe(record, grossHourlyRate)
        return grossDailyPay + dailySeniorityBonus
    }

    private fun calculateDailyGrossAdditionShareUnsafe(record: DayRecord, monthRecords: List<DayRecord>): Double {
        val grossHourlyRate = grossHourlyRateUnsafe(record.year, record.month)
        val breakdown = payQueries.calculateMonthlyNetPayBreakdownUnsafe(
            year = record.year,
            month = record.month,
            summary = calculateMonthSummaryUnsafe(record.year, record.month, monthRecords),
            grossHourlyRate = grossHourlyRate
        )
        val additions = breakdown.grossAdditionsTotal
        if (additions <= 0.0) return 0.0
        val paidDays = countPaidRecordsForMonthlyAdditionsUnsafe(monthRecords)
        if (paidDays <= 0) return 0.0
        return additions / paidDays
    }

    private fun countPaidRecordsForMonthlyAdditionsUnsafe(monthRecords: List<DayRecord>): Int {
        return monthRecords.count { record -> calculateDailyGrossWorkPayWithSeniorityUnsafe(record) > 0.0 }
    }

    private fun calculateNetPayBreakdownWithPreviewUnsafe(
        record: DayRecord,
        includeFoodAllowance: Boolean,
        includeGrossAdditions: Boolean
    ): MonthlyNetPayBreakdown {
        val monthRecords = monthRecordsWithPreviewUnsafe(record)
        return payQueries.calculateNetPayBreakdownWithPreviewUnsafe(
            record = record,
            monthRecords = monthRecords,
            includeFoodAllowance = includeFoodAllowance,
            includeGrossAdditions = includeGrossAdditions
        )
    }

    private fun calculateMonthSummaryUnsafe(year: Int, month: Int, monthRecords: List<DayRecord>): MonthSummary {
        return payQueries.calculateMonthSummaryUnsafe(year, month, monthRecords)
    }

    private fun monthRecordsWithPreviewUnsafe(record: DayRecord): List<DayRecord> {
        val monthRecords = recordStore.getMonthRecords(record.monthKey)
            .filterNot { saved -> saved.key == record.key }
            .toMutableList()

        if (!isWeekendOff(record.year, record.month, record.day, record.shift)) {
            monthRecords.add(record)
        }
        return monthRecords
    }

    var payCoefficientValue: Double
        get() = synchronized(lock) { payState.payCoefficient }
        set(value) {
            synchronized(lock) {
                if (!value.isFinite() || value <= 0.0) {
                    Log.w(TAG, "Odbijena nevažeća vrednost koeficijenta: $value")
                    return
                }
                paySettingsUpdater.setPayCoefficient(value.coerceAtMost(MAX_BASE_PAY))
            }
            notifyChange()
        }

    fun getHourlyValueForMonth(year: Int, month: Int): Double = synchronized(lock) {
        hourlyValueUnsafe(year, month)
    }

    fun getGrossHourlyRateForMonth(year: Int, month: Int): Double = synchronized(lock) {
        grossHourlyRateUnsafe(year, month)
    }

    fun setHourlyValueChange(year: Int, month: Int, value: Double) {
        if (year <= 0 || month !in 1..12 || !value.isFinite() || value <= 0.0) return
        synchronized(lock) {
            paySettingsUpdater.setHourlyValueChange(MonthKey(year, month), value.coerceAtMost(MAX_BASE_PAY))
        }
        notifyChange()
    }

    fun removeHourlyValueChange(year: Int, month: Int) {
        if (year <= 0 || month !in 1..12) return
        val removed = synchronized(lock) {
            paySettingsUpdater.removeHourlyValueChange(MonthKey(year, month))
        }
        if (removed) notifyChange()
    }

    fun getHourlyValueChanges(): List<HourlyValueChange> = synchronized(lock) {
        payState.hourlyValueChanges
            .toSortedMap()
            .map { (key, value) -> HourlyValueChange(key.year, key.month, value) }
    }

    private fun grossHourlyRateUnsafe(year: Int, month: Int): Double {
        return payQueries.grossHourlyRateUnsafe(year, month)
    }

    private fun hourlyValueUnsafe(year: Int, month: Int): Double {
        return payQueries.hourlyValueUnsafe(year, month)
    }

    private fun withHourlyValueUnsafe(
        year: Int,
        month: Int,
        breakdown: MonthlyNetPayBreakdown
    ): MonthlyNetPayBreakdown {
        return payQueries.withHourlyValueUnsafe(year, month, breakdown)
    }

    // Legacy-compatible accessor: source of truth is month-based pay coefficient + hourly value.
    // Kept for legacy callers that expect a single base-gross pay value.
    var grossHourlyRateValue: Double
        get() {
            val today = LocalDate.now()
            return getGrossHourlyRateForMonth(today.year, today.monthValue)
        }
        set(value) {
            val today = LocalDate.now()
            val hourlyValue = getHourlyValueForMonth(today.year, today.monthValue)
            payCoefficientValue = if (hourlyValue > 0.0) {
                value / hourlyValue
            } else {
                config.pay.defaultCoefficient
            }
        }

    // Legacy-compatible alias kept only as a compatibility shim for callers still using basePayValue.
    var basePayValue: Double
        get() = grossHourlyRateValue
        set(value) {
            grossHourlyRateValue = value
        }

    var sickPayBaseHourlyValue: Double
        get() = synchronized(lock) { payState.sickPayBaseHourly }
        set(value) {
            synchronized(lock) {
                if (!value.isFinite() || value < 0.0) {
                    Log.w(TAG, "Odbijena nevažeća osnovica bolovanja: $value")
                    return
                }
                paySettingsUpdater.setSickPayBaseHourly(value.coerceAtMost(MAX_BASE_PAY))
            }
            notifyChange()
        }

    var defaultPayBaseSelectionModeValue: PayBaseSelectionMode
        get() = synchronized(lock) { payState.defaultPayBaseSelectionMode }
        set(value) {
            val currentManualBase = synchronized(lock) { payState.sickPayBaseHourly }
            setDefaultPayBaseSelection(value, currentManualBase)
        }

    fun setDefaultPayBaseSelection(
        selectionMode: PayBaseSelectionMode,
        manualHourlyBase: Double? = null
    ) {
        val globalMode = selectionMode.toGlobalPayBaseSelectionMode()
        synchronized(lock) {
            val previousMonthlyBaseKeys = payState.monthlyPayBases.keys.toList()
            if (globalMode == PayBaseSelectionMode.MANUAL) {
                val base = manualHourlyBase
                if (base == null || !base.isFinite() || base <= 0.0) {
                    Log.w(TAG, "Odbijena nevažeća globalna osnovica naknada: $manualHourlyBase")
                    return
                }
                payState.sickPayBaseHourly = base.coerceAtMost(MAX_BASE_PAY)
            }
            if (globalMode == PayBaseSelectionMode.KNOWN_BASE && payState.knownPayBaseAnchor == null) {
                Log.w(TAG, "Odbijen režim poznate osnovice bez sidra.")
                return
            }
            payState.defaultPayBaseSelectionMode = globalMode
            payState.monthlyPayBases.clear()
            payState.invalidateSummariesAndRolling()
            payState.dirtyMonths.addAll(recordStore.getAllMonthsSorted())
            payState.dirtyMonths.addAll(previousMonthlyBaseKeys)
        }
        notifyChange()
    }

    fun setKnownPayBaseAnchor(year: Int, month: Int, grossHourlyBase: Double) {
        if (year <= 0 || month !in 1..12 || !grossHourlyBase.isFinite() || grossHourlyBase <= 0.0) return
        synchronized(lock) {
            paySettingsUpdater.setKnownPayBaseAnchor(
                KnownPayBaseAnchor(
                    month = MonthKey(year, month),
                    grossHourlyBase = grossHourlyBase.coerceAtMost(MAX_BASE_PAY)
                )
            )
        }
        notifyChange()
    }

    fun getKnownPayBaseAnchor(): KnownPayBaseAnchor? = synchronized(lock) {
        payState.knownPayBaseAnchor
    }

    fun setMonthlyPayBases(year: Int, month: Int, bases: MonthlyPayBases) {
        if (year <= 0 || month !in 1..12) return
        synchronized(lock) {
            val key = MonthKey(year, month)
            val sanitized = MonthlyPayBases(
                year = year,
                month = month,
                vacationHourlyBase = bases.vacationHourlyBase.sanitizePayBaseOrNull(),
                paidHolidayHourlyBase = bases.paidHolidayHourlyBase.sanitizePayBaseOrNull(),
                sickHourlyBase = bases.sickHourlyBase.sanitizePayBaseOrNull(),
                selectionMode = bases.selectionMode
            )
            if (
                sanitized.vacationHourlyBase == null &&
                sanitized.paidHolidayHourlyBase == null &&
                sanitized.sickHourlyBase == null &&
                sanitized.selectionMode == null
            ) {
                payState.monthlyPayBases.remove(key)
            } else {
                payState.monthlyPayBases[key] = sanitized
            }
            payState.invalidateSummariesAndRolling()
        }
        notifyChange()
    }

    fun getMonthlyPayBases(year: Int, month: Int): MonthlyPayBases? = synchronized(lock) {
        payState.monthlyPayBases[MonthKey(year, month)]
    }

    private fun PayBaseSelectionMode.toGlobalPayBaseSelectionMode(): PayBaseSelectionMode {
        return when (this) {
            PayBaseSelectionMode.MANUAL -> PayBaseSelectionMode.MANUAL
            PayBaseSelectionMode.KNOWN_BASE -> PayBaseSelectionMode.KNOWN_BASE
            PayBaseSelectionMode.CALCULATED_PREVIOUS_12_MONTHS,
            PayBaseSelectionMode.DEFAULT_VALUES -> PayBaseSelectionMode.CALCULATED_PREVIOUS_12_MONTHS
        }
    }

    fun setMonthlyGrossAdditions(year: Int, month: Int, additions: MonthlyGrossAdditions) {
        if (year <= 0 || month !in 1..12) return
        synchronized(lock) {
            monthlyAdjustments.setMonthlyGrossAdditions(year, month, additions)
        }
        notifyChange()
    }

    fun getMonthlyGrossAdditions(year: Int, month: Int): MonthlyGrossAdditions = synchronized(lock) {
        monthlyAdjustments.getMonthlyGrossAdditions(year, month)
    }

    fun setMonthlyAdvancePaymentNet(year: Int, month: Int, amount: Double) {
        if (year <= 0 || month !in 1..12) return
        synchronized(lock) {
            monthlyAdjustments.setMonthlyAdvancePaymentNet(year, month, amount)
        }
        notifyChange()
    }

    fun recordAdvancePaymentNotificationImport(
        candidate: RadAdvanceImportCandidate
    ): AdvancePaymentNotificationImportResult {
        val decision = synchronized(lock) {
            val year = candidate.month.year
            val month = candidate.month.month
            if (year <= 0 || month !in 1..12 || candidate.amount <= 0.0 || !candidate.amount.isFinite()) {
                return@synchronized AdvancePaymentNotificationImportDecision.SKIPPED_INVALID
            }
            val current = monthlyAdjustments.getMonthlyNetAdjustments(year, month)
            if (current.advancePaymentNet > 0.0) {
                return@synchronized AdvancePaymentNotificationImportDecision.SKIPPED_EXISTING_ADVANCE
            }
            monthlyAdjustments.setMonthlyAdvancePaymentNet(year, month, candidate.amount)
            AdvancePaymentNotificationImportDecision.IMPORTED
        }
        if (decision == AdvancePaymentNotificationImportDecision.IMPORTED) {
            notifyChange()
        }
        return AdvancePaymentNotificationImportResult(
            decision = decision,
            month = candidate.month,
            amount = candidate.amount
        )
    }

    fun getMonthlyNetAdjustments(year: Int, month: Int): MonthlyNetAdjustments = synchronized(lock) {
        monthlyAdjustments.getMonthlyNetAdjustments(year, month)
    }

    fun setMonthlyActualFoodSpentNet(year: Int, month: Int, amount: Double?) {
        if (year <= 0 || month !in 1..12) return
        if (amount != null && (!amount.isFinite() || amount < 0.0)) return
        synchronized(lock) {
            monthlyAdjustments.setMonthlyActualFoodSpentNet(year, month, amount)
        }
        notifyChange()
    }

    fun getMonthlyFoodSpendingBreakdown(year: Int, month: Int): MonthlyFoodSpendingBreakdown = synchronized(lock) {
        getMonthlyFoodSpendingBreakdownUnsafe(MonthKey(year, month))
    }

    fun getResolvedPayBases(year: Int, month: Int): ResolvedPayBases = synchronized(lock) {
        getResolvedPayBasesUnsafe(year, month)
    }

    fun getRollingPayBaseCalculation(year: Int, month: Int): RollingPayBaseCalculation? = synchronized(lock) {
        getRollingPayBaseCalculationUnsafe(year, month)
    }

    fun getPayBaseSourceMonthForWorkMonth(year: Int, month: Int): MonthKey = synchronized(lock) {
        payQueries.getPayBaseResolutionMonthForWorkMonthUnsafe(year, month)
    }

    fun getResolvedPayBasesForWorkMonth(year: Int, month: Int): ResolvedPayBases = synchronized(lock) {
        payQueries.getResolvedPayBasesForWorkMonthUnsafe(year, month)
    }

    fun getRollingPayBaseCalculationForWorkMonth(year: Int, month: Int): RollingPayBaseCalculation? = synchronized(lock) {
        payQueries.getResolvedPayBasesForWorkMonthUnsafe(year, month)
            .sickHourlyBase
            .calculation
    }

    private fun getResolvedPayBasesUnsafe(year: Int, month: Int): ResolvedPayBases {
        return payQueries.getResolvedPayBasesUnsafe(year, month)
    }

    private fun getRollingPayBaseCalculationUnsafe(year: Int, month: Int): RollingPayBaseCalculation? {
        return payQueries.getRollingPayBaseCalculationUnsafe(year, month)
    }

    var employeeContributionsPercentValue: Double
        get() = synchronized(lock) { payState.employeeContributionsPercent }
        set(value) {
            synchronized(lock) {
                if (!value.isFinite() || value < 0.0) {
                    Log.w(TAG, "Odbijena nevažeća vrednost doprinosa: $value")
                    return
                }
                paySettingsUpdater.setEmployeeContributionsPercent(value.coerceAtMost(MAX_PERCENT))
            }
            notifyChange()
        }

    var taxReliefValue: Double
        get() = synchronized(lock) { payState.taxRelief }
        set(value) {
            synchronized(lock) {
                if (!value.isFinite() || value < 0.0) {
                    Log.w(TAG, "Odbijena nevažeća poreska olakšica: $value")
                    return
                }
                paySettingsUpdater.setTaxRelief(value.coerceAtMost(MAX_TAX_RELIEF))
            }
            notifyChange()
        }

    var taxPercentValue: Double
        get() = synchronized(lock) { payState.taxPercent }
        set(value) {
            synchronized(lock) {
                if (!value.isFinite() || value < 0.0) {
                    Log.w(TAG, "Odbijena nevažeća vrednost poreza: $value")
                    return
                }
                paySettingsUpdater.setTaxPercent(value.coerceAtMost(MAX_PERCENT))
            }
            notifyChange()
        }

    var difficultWorkConditionsPercentValue: Double
        get() = synchronized(lock) { payState.difficultWorkConditionsPercent }
        set(value) {
            synchronized(lock) {
                if (!value.isFinite() || value < 0.0) {
                    Log.w(TAG, "Odbijena nevažeća vrednost otežanih uslova rada: $value")
                    return
                }
                paySettingsUpdater.setDifficultWorkConditionsPercent(value.coerceAtMost(MAX_PERCENT))
            }
            notifyChange()
        }

    fun getNetPayBreakdown(year: Int, month: Int): MonthlyNetPayBreakdown = synchronized(lock) {
        payQueries.calculateMonthlyNetPayBreakdownUnsafe(
            year = year,
            month = month,
            summary = getSummary(year, month),
            includeHourlyValue = true
        )
    }

    fun getMonthlyOvertimeNetImpact(year: Int, month: Int): MonthlyOvertimeNetImpact = synchronized(lock) {
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
    private fun calculatePaidOvertimeNetDifferenceUnsafe(
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

    private fun calculateCompTimeRecoveryNetEffectUnsafe(monthRecords: List<DayRecord>): Double {
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

    private fun calculateDeficitNetLossUnsafe(monthRecords: List<DayRecord>): Double {
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

    private fun DayRecord.deficitReplacementRecord(
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

    private fun DayRecord.inferDeficitReplacementShift(monthRecords: List<DayRecord>): Shift {
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

    private fun calculateMonthlyWorkOnlyNetPayUnsafe(year: Int, month: Int, summary: MonthSummary): Double {
        val workOnlySummary = summary.copy(foodAllowance = 0.0)
        return payQueries.calculateMonthlyNetPayBreakdownUnsafe(
            year = year,
            month = month,
            summary = workOnlySummary,
            grossAdditions = MonthlyGrossAdditions(year, month),
            includeRegress = false
        ).displayPay
    }

    fun getMonthlyReceipt(year: Int, month: Int): MonthlyReceipt = synchronized(lock) {
        payQueries.calculateMonthlyReceiptUnsafe(year, month)
    }

    fun getMonthlyFinalPaymentBreakdown(year: Int, month: Int): MonthlyFinalPaymentBreakdown = synchronized(lock) {
        val key = MonthKey(year, month)
        val receipt = payQueries.calculateMonthlyReceiptUnsafe(year, month)
        val adjustments = payState.monthlyNetAdjustments[key] ?: MonthlyNetAdjustments(year, month)
        val foodOverageDeduction = calculateFoodOverageDeductionUnsafe(receipt)
        calculateMonthlyFinalPaymentBreakdown(
            month = key,
            isNetPayAvailable = receipt.isSalaryAvailable,
            netPayBeforeAdvance = receipt.salaryNetPay,
            advancePaymentNet = adjustments.advancePaymentNet,
            loanDeductionNet = currentCreditDeductionSettingsUnsafe().activeDeductionNet,
            foodOverageDeductionNet = foodOverageDeduction,
            remainingRegressNetPay = receipt.regressNetPay
        )
    }

    private fun currentCreditDeductionSettingsUnsafe(): CreditDeductionSettings {
        return CreditDeductionSettings(
            creditDeductionEnabled = payState.creditDeductionEnabled,
            monthlyCreditDeductionAmount = payState.monthlyCreditDeductionAmount
        ).sanitized()
    }

    private fun currentUnionDuesSettingsUnsafe(): UnionDuesSettings {
        return UnionDuesSettings(
            unionMember = payState.unionMember,
            unionDuesRate = payState.unionDuesRate
        ).sanitized()
    }

    private fun calculateFoodOverageDeductionUnsafe(receipt: MonthlyReceipt): Double {
        if (!receipt.isSalaryAvailable) return 0.0
        return getMonthlyFoodSpendingBreakdownUnsafe(receipt.payrollMonth).foodOverageNet
    }

    private fun getMonthlyFoodSpendingBreakdownUnsafe(month: MonthKey): MonthlyFoodSpendingBreakdown {
        val actualFoodSpent = payState.monthlyGrossAdditions[month]
            ?.mealSpentNet
            .sanitizeMonthlyNetOverrideOrNull()
        val foodAllowance = getSummary(month.year, month.month).foodAllowance
        return calculateMonthlyFoodSpendingBreakdown(
            month = month,
            foodAllowanceNet = foodAllowance,
            actualFoodSpentNet = actualFoodSpent
        )
    }

    fun getMonthlyPayDetails(year: Int, month: Int): MonthlyPayDetails = synchronized(lock) {
        val monthKey = MonthKey(year, month)
        val monthRecords = recordStore.getMonthRecords(monthKey)
        val summary = getSummary(year, month)
        payQueries.calculateMonthlyPayDetailsUnsafe(year, month, monthRecords, summary)
    }

    fun getAnnualNetPayAverages(year: Int): AnnualNetPayAverages = synchronized(lock) {
        payQueries.getAnnualNetPayAverages(year)
    }

    fun getRecentNetPayAverage(year: Int, month: Int, asOfDate: LocalDate): RecentNetPayAverage = synchronized(lock) {
        payQueries.getRecentNetPayAverage(year, month, asOfDate)
    }

    fun previewAbsenceRangeNetImpact(
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

    fun getPreviewNetPayBreakdown(
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

    fun previewPayCoefficientNetDifference(
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

    var foodPerDayValue: Double
        get() = synchronized(lock) { payState.foodPerDay }
        set(value) {
            synchronized(lock) {
                if (!value.isFinite()) {
                    Log.w(TAG, "Odbijena nevažeća payState.foodPerDay vrednost: $value")
                    return
                }
                if (value < 0.0) {
                    Log.w(TAG, "Odbijena negativna payState.foodPerDay vrednost: $value")
                    return
                }
                paySettingsUpdater.setFoodPerDay(value.coerceAtMost(MAX_FOOD_PER_DAY))
            }
            notifyChange()
        }

    var transportDailyAmountValue: Double
        get() = synchronized(lock) { payState.transportDailyAmount }
        set(value) {
            synchronized(lock) {
                if (!value.isFinite()) {
                    Log.w(TAG, "Odbijena nevažeća vrednost prevoza: $value")
                    return
                }
                if (value < 0.0) {
                    Log.w(TAG, "Odbijena negativna vrednost prevoza: $value")
                    return
                }
                paySettingsUpdater.setTransportDailyAmount(value.coerceAtMost(MAX_TRANSPORT_PER_DAY))
            }
            notifyChange()
        }

    var creditDeductionEnabledValue: Boolean
        get() = synchronized(lock) { payState.creditDeductionEnabled }
        set(value) {
            val changed = synchronized(lock) {
                if (payState.creditDeductionEnabled == value) {
                    false
                } else {
                    payState.creditDeductionEnabled = value
                    true
                }
            }
            if (changed) {
                notifyChange()
            }
        }

    var monthlyCreditDeductionAmountValue: Double
        get() = synchronized(lock) { payState.monthlyCreditDeductionAmount }
        set(value) {
            val sanitized = value.sanitizeMonthlyNetAdjustment()
            val changed = synchronized(lock) {
                if (payState.monthlyCreditDeductionAmount == sanitized) {
                    false
                } else {
                    payState.monthlyCreditDeductionAmount = sanitized
                    true
                }
            }
            if (changed) {
                notifyChange()
            }
        }

    fun setCreditDeductionSettings(enabled: Boolean, monthlyAmount: Double) {
        val sanitizedAmount = monthlyAmount.sanitizeMonthlyNetAdjustment()
        val changed = synchronized(lock) {
            val changed = payState.creditDeductionEnabled != enabled ||
                payState.monthlyCreditDeductionAmount != sanitizedAmount
            if (changed) {
                payState.creditDeductionEnabled = enabled
                payState.monthlyCreditDeductionAmount = sanitizedAmount
            }
            changed
        }
        if (changed) {
            notifyChange()
        }
    }

    fun getCreditDeductionSettings(): CreditDeductionSettings = synchronized(lock) {
        currentCreditDeductionSettingsUnsafe()
    }

    fun setUnionDuesSettings(unionMember: Boolean, unionDuesRate: Double) {
        val sanitizedRate = unionDuesRate.sanitizeUnionDuesRate()
        val changed = synchronized(lock) {
            val changed = payState.unionMember != unionMember ||
                payState.unionDuesRate != sanitizedRate
            if (changed) {
                payState.unionMember = unionMember
                payState.unionDuesRate = sanitizedRate
            }
            changed
        }
        if (changed) {
            notifyChange()
        }
    }

    fun getUnionDuesSettings(): UnionDuesSettings = synchronized(lock) {
        currentUnionDuesSettingsUnsafe()
    }

    var foodReceiptReminderEnabled: Boolean
        get() = synchronized(lock) { foodReceiptReminderEnabledState }
        set(value) {
            val changed = synchronized(lock) {
                if (foodReceiptReminderEnabledState == value) {
                    false
                } else {
                    foodReceiptReminderEnabledState = value
                    true
                }
            }
            if (changed) {
                notifyChange()
            }
        }

    var lastBloodDonationDate: LocalDate?
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

    val bloodDonationCount: Int
        get() = synchronized(lock) { bloodDonationCountState.coerceAtLeast(bloodDonationHistoryState.size) }

    val bloodDonationHistory: List<LocalDate>
        get() = synchronized(lock) { bloodDonationHistoryState.toList() }

    fun recordBloodDonation(date: LocalDate) {
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

    fun deleteBloodDonation(date: LocalDate): Boolean {
        val changed = synchronized(lock) {
            if (date !in bloodDonationHistoryState) {
                false
            } else {
                bloodDonationHistoryState = bloodDonationHistoryState.filterNot { it == date }
                lastBloodDonationDateState = bloodDonationHistoryState.firstOrNull()
                bloodDonationCountState = bloodDonationHistoryState.size
                true
            }
        }
        if (changed) {
            notifyChange()
        }
        return changed
    }

    private fun normalizeBloodDonationHistory(dates: Collection<LocalDate>): List<LocalDate> {
        return dates.distinct().sortedDescending()
    }

    private fun normalizeBloodDonationLeaveStates(
        states: Collection<BloodDonationLeaveState>
    ): List<BloodDonationLeaveState> {
        return states.sortedByDescending { it.firstDay }.distinctBy { it.firstDay }
    }

    val bloodDonationLeaveStartDate: LocalDate?
        get() = synchronized(lock) { bloodDonationLeaveStates.firstOrNull()?.firstDay }

    fun applyBloodDonationLeave(firstDay: LocalDate): Boolean {
        synchronized(lock) {
            clearBloodDonationLeaveEntryForDateUnsafe(firstDay)
            val backups = bloodDonationLeaveDates(firstDay).map { date ->
                BloodDonationLeaveBackup(
                    date = date,
                    record = recordStore.get(date.year, date.monthValue, date.dayOfMonth)
                )
            }
            for (date in bloodDonationLeaveDates(firstDay)) {
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

    fun clearBloodDonationLeave(): Boolean {
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

    fun clearBloodDonationLeaveForDate(date: LocalDate): Boolean {
        val changed = synchronized(lock) {
            clearBloodDonationLeaveEntryForDateUnsafe(date)
        }
        if (changed) {
            notifyChange()
        }
        return changed
    }

    private fun clearBloodDonationLeaveEntryForDateUnsafe(date: LocalDate): Boolean {
        val state = bloodDonationLeaveStates.firstOrNull { date in bloodDonationLeaveDates(it.firstDay) } ?: return false
        restoreBloodDonationLeaveUnsafe(state)
        bloodDonationLeaveStates = bloodDonationLeaveStates.filterNot { it.firstDay == state.firstDay }
        return true
    }

    private fun restoreBloodDonationLeaveUnsafe(state: BloodDonationLeaveState) {
        for (backup in state.previousRecords) {
            val record = backup.record
            if (record == null) {
                recordStore.delete(backup.date.year, backup.date.monthValue, backup.date.dayOfMonth)
            } else {
                recordStore.set(record)
            }
        }
    }

    private fun bloodDonationLeaveRecord(date: LocalDate): DayRecord {
        return DayRecord(
            year = date.year,
            month = date.monthValue,
            day = date.dayOfMonth,
            shift = Shift.SICK,
            hours = config.pay.defaultHours,
            sickPayRate = 1.0,
            source = DayRecordSource.BLOOD_DONATION_LEAVE
        )
    }

    var initialCompBalanceValue: Int
        get() = synchronized(lock) { payState.initialCompBalance }
        set(value) {
            synchronized(lock) {
                val hours = value.coerceIn(MIN_COMP_BALANCE, MAX_COMP_BALANCE)
                payState.initialCompBalance = hours
                payState.initialCompBalanceMinutes = hours * MINUTES_PER_HOUR
                payState.invalidateSummariesRollingAndCompBalance()
            }
            notifyChange()
        }

    var initialCompBalanceMinutesValue: Int
        get() = synchronized(lock) { payState.initialCompBalanceMinutes }
        set(value) {
            synchronized(lock) {
                val minutes = value.coerceIn(MIN_COMP_BALANCE, MAX_COMP_BALANCE_MINUTES)
                payState.initialCompBalanceMinutes = minutes
                payState.initialCompBalance = minutes / MINUTES_PER_HOUR
                payState.invalidateSummariesRollingAndCompBalance()
            }
            notifyChange()
        }

    /**
     * Automatski računa godine staža na osnovu payState.minuliRadStartYear.
     * Povećava se u mesecu početka rada.
     */
    private fun calculateYearsOfServiceUnsafe(): Int {
        if (payState.minuliRadStartYear <= 0) return 0
        val now = LocalDate.now()
        return calculateYearsOfServiceUnsafe(now.year, now.monthValue)
    }

    private fun calculateYearsOfServiceUnsafe(year: Int, month: Int): Int {
        return when (payState.seniorityInputMode) {
            SeniorityInputMode.KNOWN_START -> calculateKnownStartYearsOfServiceUnsafe(year, month)
            SeniorityInputMode.PAYSLIP_PERCENT -> calculatePayslipAnchoredSeniorityYears(
                lastPayslipYear = payState.minuliRadLastPayslipYear,
                lastPayslipMonth = payState.minuliRadLastPayslipMonth,
                payslipPercent = payState.minuliRadPayslipPercent,
                targetYear = year,
                targetMonth = month,
                increaseFactor = payState.minuliRadIncreaseFactor,
                ratePerYear = config.pay.minuliRadPerYear
            )
        }
    }

    private fun calculateKnownStartYearsOfServiceUnsafe(year: Int, month: Int): Int {
        if (payState.minuliRadStartYear <= 0) return 0
        val increaseFactor = if (payState.minuliRadBenefitEnabled) {
            payState.minuliRadIncreaseFactor
        } else {
            SeniorityIncreaseFactor.NONE
        }
        return calculateKnownStartSeniorityPreview(
            startYear = payState.minuliRadStartYear,
            startMonth = payState.minuliRadStartMonth,
            targetYear = year,
            targetMonth = month,
            increaseFactor = increaseFactor,
            ratePerYear = config.pay.minuliRadPerYear
        ).fullAccountingYears
    }

    val yearsOfServiceValue: Int
        get() = synchronized(lock) { calculateYearsOfServiceUnsafe() }

    var seniorityInputModeValue: SeniorityInputMode
        get() = synchronized(lock) { payState.seniorityInputMode }
        set(value) {
            synchronized(lock) {
                payState.seniorityInputMode = value
                payState.invalidateSummariesAndRolling()
            }
            notifyChange()
        }

    var minuliRadStartYearValue: Int
        get() = synchronized(lock) { payState.minuliRadStartYear }
        set(value) {
            synchronized(lock) {
                payState.minuliRadStartYear = value.coerceAtLeast(0)
                payState.invalidateSummariesAndRolling()
            }
            notifyChange()
        }

    var minuliRadStartMonthValue: Int
        get() = synchronized(lock) { payState.minuliRadStartMonth }
        set(value) {
            synchronized(lock) {
                payState.minuliRadStartMonth = value.coerceIn(1, 12)
                payState.invalidateSummariesAndRolling()
            }
            notifyChange()
        }

    var minuliRadBenefitEnabledValue: Boolean
        get() = synchronized(lock) { payState.minuliRadBenefitEnabled }
        set(value) {
            synchronized(lock) {
                payState.minuliRadBenefitEnabled = value
                payState.invalidateSummariesAndRolling()
            }
            notifyChange()
        }

    var minuliRadIncreaseFactorValue: SeniorityIncreaseFactor
        get() = synchronized(lock) { payState.minuliRadIncreaseFactor }
        set(value) {
            synchronized(lock) {
                payState.minuliRadIncreaseFactor = value
                payState.invalidateSummariesAndRolling()
            }
            notifyChange()
        }

    var minuliRadLastPayslipYearValue: Int
        get() = synchronized(lock) { payState.minuliRadLastPayslipYear }
        set(value) {
            synchronized(lock) {
                payState.minuliRadLastPayslipYear = value.coerceAtLeast(0)
                payState.invalidateSummariesAndRolling()
            }
            notifyChange()
        }

    var minuliRadLastPayslipMonthValue: Int
        get() = synchronized(lock) { payState.minuliRadLastPayslipMonth }
        set(value) {
            synchronized(lock) {
                payState.minuliRadLastPayslipMonth = value.coerceIn(1, 12)
                payState.invalidateSummariesAndRolling()
            }
            notifyChange()
        }

    var minuliRadPayslipPercentValue: Double
        get() = synchronized(lock) { payState.minuliRadPayslipPercent }
        set(value) {
            synchronized(lock) {
                payState.minuliRadPayslipPercent = value
                    .takeIf { it.isFinite() }
                    ?.coerceIn(0.0, MAX_PERCENT)
                    ?: 0.0
                payState.invalidateSummariesAndRolling()
            }
            notifyChange()
        }

    var brigadeTypeValue: BrigadeType
        get() = synchronized(lock) { payState.brigadeType }
        set(value) {
            synchronized(lock) {
                if (payState.brigadeType != value) {
                    payState.brigadeType = value
                    payState.invalidateSummariesRollingAndCompBalance()
                } else {
                    return
                }
            }
            notifyChange()
        }

    fun getScheduledShiftForDate(date: LocalDate): Shift? = synchronized(lock) {
        RollingPayBaseMonthSimulator(config, payState, recordStore).simulateShiftForDate(date)
    }

    var initialVacationBalanceValue: Double
        get() = synchronized(lock) { vacationTracker.getInitialVacationBalance() }
        set(value) {
            synchronized(lock) {
                vacationTracker.setInitialVacationBalance(value)
            }
            notifyChange()
        }

    fun getAnnualLeaveCalculationInput(year: Int): AnnualLeaveCalculationInput = synchronized(lock) {
        vacationTracker.getAnnualLeaveCalculationInput(year)
    }

    fun getAnnualLeaveLastCalculatedDays(): Int = synchronized(lock) {
        vacationTracker.getAnnualLeaveLastCalculatedDays()
    }

    fun setAnnualLeaveCalculation(input: AnnualLeaveCalculationInput, lastCalculatedDays: Int) {
        val changed = synchronized(lock) {
            vacationTracker.setAnnualLeaveCalculation(input, lastCalculatedDays)
        }
        if (!changed) return
        notifyChange()
    }

    fun setVacationYearBalance(balance: VacationYearBalance) {
        val changed = synchronized(lock) {
            vacationTracker.setVacationYearBalance(balance)
        }
        if (!changed) return
        notifyChange()
    }

    fun getVacationYearBalance(year: Int): VacationYearBalance? = synchronized(lock) {
        vacationTracker.getVacationYearBalance(year)
    }

    fun getVacationBalanceSummary(year: Int, today: LocalDate = LocalDate.now()): VacationBalanceSummary =
        synchronized(lock) {
            vacationTracker.getVacationBalanceSummary(year, today)
        }

    fun getVacationDaysUsed(): Int = synchronized(lock) {
        vacationTracker.getVacationDaysUsed()
    }

    fun getVacationDaysRemaining(): Double = synchronized(lock) {
        vacationTracker.getVacationDaysRemaining()
    }

    fun isVacationExceeded(): Boolean = synchronized(lock) {
        vacationTracker.isVacationExceeded()
    }

    // =========================================================================
    // LOCAL STORAGE
    // =========================================================================

    fun saveToLocal(context: Context): Boolean {
        val saved = localStorage.save(context, exportJson())
        if (saved) {
            Log.d(TAG, "Saved to local storage")
        } else {
            Log.e(TAG, "Failed to save to local storage")
        }
        return saved
    }

    fun loadFromLocal(context: Context): Boolean {
        val json = localStorage.load(context)
        if (json == null) {
            Log.d(TAG, "No local backup file found")
            return false
        }
        if (json.isBlank()) {
            Log.d(TAG, "Local backup file is empty")
            return false
        }
        val imported = importFromJson(json)
        if (imported) {
            Log.d(TAG, "Loaded from local storage")
        } else {
            Log.e(TAG, "Failed to import local backup data")
        }
        return imported
    }

    fun hasLocalData(context: Context): Boolean {
        return localStorage.hasData(context)
    }
}

private fun nextCalendarMonthKey(monthKey: MonthKey): MonthKey {
    val nextMonth = YearMonth.of(monthKey.year, monthKey.month).plusMonths(1)
    return MonthKey(nextMonth.year, nextMonth.monthValue)
}

private fun MonthKey.minusMonthsForRetention(months: Long): MonthKey {
    val result = YearMonth.of(year, month).minusMonths(months)
    return MonthKey(result.year, result.monthValue)
}



