package com.rad.shifttracker

import android.content.Context
import android.util.Log
import com.rad.shifttracker.data.backup.BackupStats
import com.rad.shifttracker.data.backup.CloudBackupContent
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
import com.rad.shifttracker.data.storage.CloudArchiveMerger
import com.rad.shifttracker.data.storage.LocalDataStore
import com.rad.shifttracker.data.storage.LocalShiftStorage
import com.rad.shifttracker.data.storage.ShiftRepositoryJsonCodec
import com.rad.shifttracker.data.storage.ShiftRepositorySnapshot
import com.rad.shifttracker.data.storage.SnapshotDecodeResult
import com.rad.shifttracker.data.storage.SnapshotRejection
import java.math.BigDecimal
import java.time.Clock
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import kotlin.math.abs

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

@Suppress("TooManyFunctions", "LargeClass")
class ShiftRepository(
    private val config: AppConfig = CONFIG,
    /** Izvor „danas“; testovi ga fiksiraju (ponoć, promena godine). */
    private val clock: Clock = Clock.systemDefaultZone()
) {
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
    private val vacationTracker = RepositoryVacationTracker(
        recordStore,
        ::calculateYearsOfServiceUnsafe,
        ::payslipVacationDaysByMonthUnsafe,
        clock
    )
    private val payQueries = RepositoryPayQueries(
        config = config,
        payState = payState,
        recordStore = recordStore,
        getCompBalanceAtMonthStartUnsafe = compBalanceCalculator::hoursAtMonthStart,
        getCompBalanceMinutesAtMonthStartUnsafe = compBalanceCalculator::minutesAtMonthStart,
        getCompBalanceAtMonthStartReadOnly = compBalanceCalculator::hoursAtMonthStartReadOnly,
        getCompBalanceMinutesAtMonthStartReadOnly = compBalanceCalculator::minutesAtMonthStartReadOnly,
        calculateYearsOfServiceUnsafe = ::calculateYearsOfServiceUnsafe,
        percentDeductionsForMonthUnsafe = { month -> percentDeductionsForMonth(month, savedPayslipsUnsafe()) }
    )
    private val absenceSimulator = RepositoryAbsenceSimulator(
        config = config,
        recordStore = recordStore,
        payQueries = payQueries
    )
    // Nova instalacija: čitanje akontacije iz obaveštenja je isključeno dok ga korisnik ne uključi.
    private var autoAdvanceImportEnabledState = false
    private var notificationAccessPromptSuppressedState = false
    private var mainPayIncludesTransportState = true
    private var lastBloodDonationDateState: LocalDate? = null
    private var bloodDonationCountState = 0
    private var bloodDonationHistoryState: List<LocalDate> = emptyList()
    private var bloodDonationLeaveStates: List<BloodDonationLeaveState> = emptyList()

    // Meseci koje je korisnik izričito obrisao — čuva se u snapshot-u da ih
    // merge sa cloud arhivom ne bi vaskrsao (vidi CloudArchiveMerger).
    private var deletedMonthTombstones: MutableSet<MonthKey> = linkedSetOf()

    // Izričito obrisane satnice — isti razlog kao gore, samo za hourly_value_changes.
    private var deletedHourlyValueTombstones: MutableSet<MonthKey> = linkedSetOf()

    // Izričito obrisani listići — oblak spaja listiće kao uniju, pa bi se bez ovoga vratili.
    private var deletedPayslipTombstones: MutableSet<MonthKey> = linkedSetOf()

    // Platni listići po mesecu (JSON listića) za „Uporedi“; ne orezuju se kao zapisi.
    private val savedPayslipJsonByMonth: MutableMap<MonthKey, String> = mutableMapOf()

    // Dani godišnjeg po listiću, uz JSON iz kog su izračunati (da se listić ne parsira pri svakom crtanju).
    private val payslipVacationDaysCache: MutableMap<MonthKey, Pair<String, Double>> = mutableMapOf()

    // Pročitani listići uz JSON iz kog su nastali — obustave u % se traže pri svakom obračunu neta.
    private val parsedPayslipCache: MutableMap<MonthKey, Pair<String, ParsedPayslip?>> = mutableMapOf()
    private val jsonCodec = ShiftRepositoryJsonCodec(config, clock)

    /** Zamenljivo u testovima; produkcija piše u `filesDir`. */
    internal var localStorage: LocalDataStore = LocalShiftStorage()

    /** Lokalni fajl je pisala novija verzija aplikacije: ne sme se pregaziti starijom. */
    @Volatile
    private var localWriteBlockedBySchema = false

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
        private const val NIGHT_WORK_PAYSLIP_CODE = 20
        private const val ADVANCE_SAME_AMOUNT_TOLERANCE = 0.005
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
            foodPerDayChanges = payState.foodPerDayChanges.toMap(),
            regressNetChanges = payState.regressNetChanges.toMap(),
            transportDailyAmount = payState.transportDailyAmount,
            autoAdvanceImportEnabled = autoAdvanceImportEnabledState,
            notificationAccessPromptSuppressed = notificationAccessPromptSuppressedState,
            mainPayIncludesTransport = mainPayIncludesTransportState,
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
            knownPayBaseAnchors = payState.knownPayBaseAnchors.toMap(),
            annualLeaveCalculationInput = vacationTracker.snapshotAnnualLeaveCalculationInput(),
            annualLeaveLastCalculatedDays = vacationTracker.snapshotAnnualLeaveLastCalculatedDays(),
            records = recordStore.allRecords.toList(),
            // Tombstone se automatski neutrališe ako mesec ponovo dobije zapise.
            deletedMonths = deletedMonthTombstones.filter { recordStore.getMonthRecords(it).isEmpty() },
            deletedHourlyValueMonths = deletedHourlyValueTombstones.filter { it !in payState.hourlyValueChanges },
            savedPayslips = savedPayslipJsonByMonth.toMap(),
            deletedPayslipMonths = deletedPayslipTombstones.filter { it !in savedPayslipJsonByMonth }
        )
    }

    /** Tekući mesec za granicu brisanja istorije; testovi ga pomeraju. */
    internal var retentionClock: () -> YearMonth = { YearMonth.now(clock) }

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
        // Pre ikakvog brisanja: prenos godišnjeg se računa dok su podaci prošle godine još celi.
        changed = vacationTracker.freezeCarryover(firstFullyRetainedYear(plan.cutoff)) || changed
        val protectedPayBaseMonths = materializePayBaseAnchorsUnsafe(plan.cutoff, plan.retainedMonths)
        changed = protectedPayBaseMonths.changed || changed
        changed = resetInitialCompBalanceForRetentionUnsafe(plan) || changed
        changed = removeOldRecordsUnsafe(plan.cutoff) || changed
        // Satnice se NAMERNO ne orezuju: to je ručno unet podatak (par brojeva),
        // a orezivanje bi ga izbrisalo iz memorije pre nego što stigne u oblak.
        changed = pruneMonthScopedDataUnsafe(plan.cutoff, protectedPayBaseMonths.months) || changed
        // Godišnji starijih godina je već obrisan u freezeCarryover (posle zamrzavanja prenosa).
        changed = savedPayslipJsonByMonth.keys.removeAll { it < plan.cutoff } || changed

        if (changed) {
            invalidateRetentionCachesUnsafe()
        }
        return changed
    }

    private data class HistoryRetentionPlan(
        val cutoff: MonthKey,
        val retainedMonths: List<MonthKey>,
        val compBalanceAtCutoff: Int
    )

    private data class RetentionPayBaseAnchors(
        val months: Set<MonthKey>,
        val changed: Boolean
    )

    private fun historyRetentionPlanUnsafe(): HistoryRetentionPlan? {
        val recordMonths = recordStore.getAllMonthsSorted()
        val latestRecorded = recordMonths.maxOrNull() ?: return null
        // Smene popunjene unapred nisu stvarni podaci: granica ide od tekućeg meseca, inače bi popuna
        // 12 meseci unapred obrisala i prave listiće i godišnji prošle godine.
        val now = retentionClock().let { MonthKey(it.year, it.monthValue) }
        val latestMonth = minOf(latestRecorded, now)
        val cutoff = latestMonth.minusMonthsForRetention(HISTORY_RETENTION_LOOKBACK_MONTHS)
        if (!hasHistoryRetentionWorkUnsafe(cutoff, recordMonths)) return null

        val retainedMonths = recordMonths.filter { it >= cutoff }
        if (retainedMonths.isEmpty()) return null

        return HistoryRetentionPlan(
            cutoff = cutoff,
            retainedMonths = retainedMonths,
            compBalanceAtCutoff = compBalanceCalculator.minutesAtMonthStart(cutoff.year, cutoff.month)
        )
    }

    private fun hasHistoryRetentionWorkUnsafe(cutoff: MonthKey, recordMonths: List<MonthKey>): Boolean {
        return recordMonths.any { it < cutoff } ||
            savedPayslipJsonByMonth.keys.any { it < cutoff } ||
            vacationTracker.snapshotVacationYearBalances().keys.any { it < firstFullyRetainedYear(cutoff) } ||
            payState.monthlyGrossAdditions.keys.any { it < cutoff } ||
            payState.monthlyNetAdjustments.keys.any { it < cutoff } ||
            payState.monthlyPayBases.keys.any { it < cutoff }
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
                val anchor = payState.knownPayBaseAnchorFor(sourceMonth) ?: return false
                anchor.month < cutoff
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

    private fun pruneMonthScopedDataUnsafe(cutoff: MonthKey, protectedPayBaseMonths: Set<MonthKey>): Boolean {
        var changed = false
        changed = removeMonthKeysBefore(payState.monthlyGrossAdditions, cutoff) || changed
        changed = removeMonthKeysBefore(payState.monthlyNetAdjustments, cutoff) || changed
        changed = removeMonthKeysBefore(payState.monthlyPayBases, cutoff, protectedPayBaseMonths) || changed
        return changed
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

    /**
     * Backup za oblak koji NE briše istoriju: spaja postojeću arhivu iz oblaka sa
     * trenutnim stanjem tako da stari (lokalno orezani) meseci ostanu sačuvani.
     * Namerno ne poziva [pruneIrrelevantHistoryUnsafe] — lokalno čuvanje i dalje
     * orezuje na ~24 meseca, ali kopija u oblaku akumulira punu istoriju.
     *
     * @param existingCloudJson sadržaj prethodnog backup-a iz oblaka, ili null/prazno
     *   ako backup još ne postoji (ili se nije mogao pročitati).
     */
    fun exportCloudBackupJson(existingCloudJson: String?): String {
        return jsonCodec.encode(buildCloudSnapshot(existingCloudJson))
    }

    /**
     * Kao [exportCloudBackupJson], ali uz JSON vraća i [BackupStats] otisak
     * (broj dana/meseci/radnih sati) spojenog stanja — koristi ga verzionisani
     * backup za metapodatke verzije i detekciju anomalija.
     */
    fun buildCloudBackup(existingCloudJson: String?): CloudBackupContent {
        val snapshot = buildCloudSnapshot(existingCloudJson)
        return CloudBackupContent(
            json = jsonCodec.encode(snapshot),
            stats = BackupStats.from(snapshot.records)
        )
    }

    private fun buildCloudSnapshot(existingCloudJson: String?): ShiftRepositorySnapshot {
        return synchronized(lock) {
            val current = buildSnapshotUnsafe()
            val archive = existingCloudJson
                ?.takeIf { it.isNotBlank() }
                ?.let { runCatching { jsonCodec.decode(it) }.getOrNull() }
            if (archive == null) current else CloudArchiveMerger.merge(archive, current)
        }
    }

    fun importFromJson(jsonText: String): Boolean = tryImportFromJson(jsonText) == null

    /**
     * Vraćanje stanja iz JSON-a. Sve provere su PRE brisanja: odbijeni JSON ne menja ništa.
     * @return null kad je uvezeno, inače razlog odbijanja.
     */
    internal fun tryImportFromJson(jsonText: String): SnapshotRejection? {
        val snapshot = when (val decoded = decodeForImport(jsonText)) {
            is SnapshotDecodeResult.Decoded -> decoded.snapshot
            is SnapshotDecodeResult.Rejected -> return decoded.reason
        }
        synchronized(lock) {
            // Pravi backup ima barem jedan zapis; prazan snapshot nad popunjenim kalendarom je skoro sigurno
            // pogrešan fajl (ili tek instalirana aplikacija), a uvoz bi tiho obrisao sve zapise.
            if (snapshot.records.isEmpty() && recordStore.allRecords.isNotEmpty()) {
                Log.w(TAG, "Uvoz odbijen: snapshot nema zapisa, a lokalno ih ima.")
                return SnapshotRejection.WOULD_ERASE_EXISTING_RECORDS
            }
            applySnapshotUnsafe(snapshot)
        }
        notifyChange()
        return null
    }

    private fun decodeForImport(jsonText: String): SnapshotDecodeResult {
        val decoded = try {
            jsonCodec.decodeChecked(jsonText)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse import JSON", e)
            SnapshotDecodeResult.Rejected(SnapshotRejection.UNREADABLE)
        }
        if (decoded is SnapshotDecodeResult.Rejected) {
            Log.w(TAG, "Uvoz odbijen: ${decoded.reason}")
        }
        return decoded
    }

    private fun applySnapshotUnsafe(snapshot: ShiftRepositorySnapshot) {
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
        payState.restoreRegressNetChanges(snapshot.regressNetChanges)
        restoreMealPriceHistoryUnsafe(snapshot)
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
        autoAdvanceImportEnabledState = snapshot.autoAdvanceImportEnabled
        notificationAccessPromptSuppressedState = snapshot.notificationAccessPromptSuppressed
        mainPayIncludesTransportState = snapshot.mainPayIncludesTransport
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
        payState.knownPayBaseAnchors.clear()
        payState.knownPayBaseAnchors.putAll(snapshot.knownPayBaseAnchors)
        savedPayslipJsonByMonth.clear()
        savedPayslipJsonByMonth.putAll(snapshot.savedPayslips)
        // Listić sačuvan bez osnovice za svoj mesec (npr. stariji „poslednji listić“): osnovica sa njega.
        for (json in savedPayslipJsonByMonth.values) {
            payslipFromJsonOrNull(json)?.let(::payslipPayBaseAnchor)?.let { anchor ->
                payState.knownPayBaseAnchors.putIfAbsent(anchor.month, anchor.grossHourlyBase)
            }
        }
        if (
            payState.defaultPayBaseSelectionMode == PayBaseSelectionMode.KNOWN_BASE &&
            payState.knownPayBaseAnchor == null
        ) {
            payState.defaultPayBaseSelectionMode = PayBaseSelectionMode.CALCULATED_PREVIOUS_12_MONTHS
        }

        deletedMonthTombstones = snapshot.deletedMonths.toMutableSet()
        deletedHourlyValueTombstones = snapshot.deletedHourlyValueMonths.toMutableSet()
        deletedPayslipTombstones = snapshot.deletedPayslipMonths.toMutableSet()

        for (record in snapshot.records) {
            recordStore.set(record)
        }
        pruneIrrelevantHistoryUnsafe()
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
            val key = MonthKey(year, month)
            paySettingsUpdater.setHourlyValueChange(key, value.coerceAtMost(MAX_BASE_PAY))
            deletedHourlyValueTombstones.remove(key)
        }
        notifyChange()
    }

    fun removeHourlyValueChange(year: Int, month: Int) {
        if (year <= 0 || month !in 1..12) return
        val removed = synchronized(lock) {
            val key = MonthKey(year, month)
            paySettingsUpdater.removeHourlyValueChange(key).also { wasRemoved ->
                if (wasRemoved) deletedHourlyValueTombstones.add(key)
            }
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
            val today = LocalDate.now(clock)
            return getGrossHourlyRateForMonth(today.year, today.monthValue)
        }
        set(value) {
            val today = LocalDate.now(clock)
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

    /** Listić sačuvan za radni mesec („Uporedi“), ili null ako ga nema. */
    internal fun getSavedPayslip(year: Int, month: Int): ParsedPayslip? {
        val key = MonthKey(year, month)
        val json = synchronized(lock) { savedPayslipJsonByMonth[key] } ?: return null
        // Listić iz oštećenog backup-a čiji mesec ne odgovara ključu ne prikazuje se pod tuđim mesecom.
        return payslipFromJsonOrNull(json)?.takeIf { it.month == key }
    }

    /**
     * Dani godišnjeg (šifra 10 / 8 h) sa sačuvanih listića za godinu. Listić bez šifre 10 daje 0,
     * a listić koji ne može da se pročita se preskače (taj mesec se broji po kalendaru).
     */
    private fun payslipVacationDaysByMonthUnsafe(year: Int): Map<Int, Double> =
        savedPayslipJsonByMonth.entries
            .filter { it.key.year == year }
            .mapNotNull { (key, json) ->
                val cached = payslipVacationDaysCache[key]?.takeIf { it.first == json }?.second
                val days = cached ?: payslipFromJsonOrNull(json)
                    ?.takeIf { it.month == key }
                    ?.let(::payslipVacationDays)
                    ?.also { payslipVacationDaysCache[key] = json to it }
                days?.let { key.month to it }
            }
            .toMap()

    /** Listić meseca je korisnik obrisao („Obriši listu“), pa se ne upisuje ponovo sam od sebe. */
    internal fun isPayslipDeleted(month: MonthKey): Boolean = synchronized(lock) { month in deletedPayslipTombstones }

    /** Mesec najnovijeg sačuvanog listića, ili null ako nijedan nije sačuvan. */
    internal fun getLatestSavedPayslipMonth(): MonthKey? = synchronized(lock) { savedPayslipJsonByMonth.keys.maxOrNull() }

    /** Meseci svih sačuvanih listića, od najstarijeg. */
    internal fun getSavedPayslipMonths(): List<MonthKey> = synchronized(lock) { savedPayslipJsonByMonth.keys.sorted() }

    /**
     * Čuva listić pod njegovim mesecem (zamenjuje raniji za isti mesec); ide i u backup.
     * Ako za taj mesec nije uneta osnovica, uzima se sa listića (GO, PO, praznik, bolovanje).
     */
    /** [withPayBase] = false: listić se samo čuva, osnovica sa njega se ne upisuje (stariji listići). */
    internal fun savePayslip(payslip: ParsedPayslip, withPayBase: Boolean = true) {
        val json = payslipToJson(payslip)
        val changed = synchronized(lock) {
            deletedPayslipTombstones.remove(payslip.month)
            savedPayslipJsonByMonth.put(payslip.month, json) != json
        }
        val anchor = payslipPayBaseAnchor(payslip)?.takeIf { withPayBase }
        if (anchor != null && getKnownPayBaseAnchors().none { it.month == anchor.month }) {
            setKnownPayBaseAnchor(anchor.month.year, anchor.month.month, anchor.grossHourlyBase)
            ensureKnownPayBaseMode(this)
        } else if (changed) {
            notifyChange()
        }
    }

    /**
     * Briše sačuvan listić meseca (i iz backup-a, preko tombstone-a). Podešavanja koja su
     * ranije upisana sa listića (koeficijent, osnovica, porez...) ostaju kakva jesu.
     */
    internal fun deletePayslip(month: MonthKey) {
        val removed = synchronized(lock) {
            (savedPayslipJsonByMonth.remove(month) != null).also { wasRemoved ->
                if (wasRemoved) {
                    deletedPayslipTombstones.add(month)
                    payslipVacationDaysCache.remove(month)
                }
            }
        }
        if (removed) notifyChange()
    }

    /** Najnovije uneto sidro; sva sidra daje [getKnownPayBaseAnchors]. */
    fun getKnownPayBaseAnchor(): KnownPayBaseAnchor? = synchronized(lock) {
        payState.knownPayBaseAnchor
    }

    fun getKnownPayBaseAnchors(): List<KnownPayBaseAnchor> = synchronized(lock) {
        payState.knownPayBaseAnchors.map { (month, base) -> KnownPayBaseAnchor(month, base) }
    }

    /** Sidro po kome se računa osnovica radnog meseca (najnovije koje nije posle njega, inače prvo kasnije). */
    fun getKnownPayBaseAnchorForWorkMonth(year: Int, month: Int): KnownPayBaseAnchor? = synchronized(lock) {
        payState.knownPayBaseAnchorFor(MonthKey(year, month))
    }

    fun removeKnownPayBaseAnchor(year: Int, month: Int) {
        val removed = synchronized(lock) {
            val removed = paySettingsUpdater.removeKnownPayBaseAnchor(MonthKey(year, month))
            if (
                removed &&
                payState.knownPayBaseAnchors.isEmpty() &&
                payState.defaultPayBaseSelectionMode == PayBaseSelectionMode.KNOWN_BASE
            ) {
                payState.defaultPayBaseSelectionMode = PayBaseSelectionMode.CALCULATED_PREVIOUS_12_MONTHS
            }
            removed
        }
        if (removed) notifyChange()
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

    fun setMonthlyAbsencePenaltyGross(year: Int, month: Int, amount: java.math.BigDecimal) {
        if (year <= 0 || month !in 1..12) return
        synchronized(lock) {
            monthlyAdjustments.setMonthlyAbsencePenaltyGross(year, month, amount)
        }
        notifyChange()
    }

    @Deprecated("Legacy backup compatibility only; meal gross is always calculated from the applied meal net.")
    @Suppress("DEPRECATION")
    fun setMonthlyMealGrossOverride(year: Int, month: Int, amount: Double?) {
        if (year <= 0 || month !in 1..12) return
        synchronized(lock) {
            monthlyAdjustments.setMonthlyMealGrossOverride(year, month, amount)
        }
        notifyChange()
    }

    @Deprecated("Legacy backup compatibility only; settlement adjustment is ignored by payroll calculation.")
    @Suppress("DEPRECATION")
    fun setMonthlyMealSettlementAdjustmentNet(year: Int, month: Int, amount: Double) {
        if (year <= 0 || month !in 1..12) return
        synchronized(lock) {
            monthlyAdjustments.setMonthlyMealSettlementAdjustmentNet(year, month, amount)
        }
        notifyChange()
    }

    fun setMonthlyAdvancePaymentNet(year: Int, month: Int, amount: Double) {
        if (year <= 0 || month !in 1..12) return
        synchronized(lock) {
            monthlyAdjustments.setMonthlyAdvancePaymentNet(year, month, amount)
        }
        notifyChange()
    }

    /**
     * Akontacija za [month] sa obračuna akontacije (iznos uplaćen na račun). Obračun je tačniji od
     * ručnog unosa, pa ga zamenjuje; true ako je iznos promenjen.
     */
    fun recordAdvancePaymentFromPayslip(month: MonthKey, amount: Double): Boolean {
        val changed = synchronized(lock) {
            if (!autoAdvanceImportEnabledState) return@synchronized false
            if (month.year <= 0 || month.month !in 1..12 || amount <= 0.0 || !amount.isFinite()) {
                return@synchronized false
            }
            val current = monthlyAdjustments.getMonthlyNetAdjustments(month.year, month.month)
            if (abs(current.advancePaymentNet - amount) < ADVANCE_SAME_AMOUNT_TOLERANCE) return@synchronized false
            monthlyAdjustments.setMonthlyAdvancePaymentNet(month.year, month.month, amount)
            true
        }
        if (changed) notifyChange()
        return changed
    }

    /** Neto regres koji važi za [month] (poslednji sa obračuna regresa, inače 20.000). */
    fun getRegressNetForMonth(month: MonthKey): Double = synchronized(lock) { payState.regressNetForMonth(month) }

    fun isRegressPaidInMonth(month: MonthKey): Boolean = synchronized(lock) { payState.regressPaidInMonth(month) }

    /**
     * Regres sa obračuna regresa za mesec [month] (iznos uplaćen na račun). Mesec se pamti uvek, pa se
     * regres računa u tom mesecu i kad nije 2/5/8/11 (npr. dve rate 20.000 u oktobru i novembru);
     * iznos važi i za kasnije mesece. True samo kad se iznos razlikuje od onog koji je za mesec važio.
     */
    fun recordRegressFromPayslip(month: MonthKey, amount: Double): Boolean {
        var amountChanged = false
        val changed = synchronized(lock) {
            if (month.year <= 0 || month.month !in 1..12 || amount <= 0.0 || !amount.isFinite()) {
                return@synchronized false
            }
            amountChanged = abs(payState.regressNetForMonth(month) - amount) >= ADVANCE_SAME_AMOUNT_TOLERANCE
            val stored = payState.regressNetChanges[month]
            if (!amountChanged && stored != null && abs(stored - amount) < ADVANCE_SAME_AMOUNT_TOLERANCE) {
                return@synchronized false
            }
            payState.regressNetChanges[month] = amount
            true
        }
        if (changed) notifyChange()
        return amountChanged
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

    fun setMonthlyActualFoodSpentNetDecimal(year: Int, month: Int, amount: BigDecimal?) {
        setMonthlyActualFoodSpentNet(year, month, amount?.toMealMoney()?.toDouble())
    }

    internal fun setMonthlyMealEntitlementNetOverride(year: Int, month: Int, amount: BigDecimal?) {
        if (year <= 0 || month !in 1..12 || amount?.signum() == -1) return
        synchronized(lock) {
            monthlyAdjustments.setMonthlyMealEntitlementNetOverride(year, month, amount?.toMealMoney())
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
        val workOnlySummary = summary.copy(foodAllowance = 0.0, mealEntitlementCalculation = null)
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
        // Obustave u % su već skinute sa neto plate meseca; ovde se prikazuju među obustavama.
        val percentDeductions = if (receipt.isSalaryAvailable) percentDeductionsFromSalaryUnsafe(receipt.salaryMonth) else emptyList()
        calculateMonthlyFinalPaymentBreakdown(
            month = key,
            isNetPayAvailable = receipt.isSalaryAvailable,
            netPayBeforeAdvance = receipt.salaryNetPay + percentDeductions.sumOf { it.amountNet },
            advancePaymentNet = adjustments.advancePaymentNet,
            deductions = if (receipt.isSalaryAvailable) {
                finalPaymentDeductionsUnsafe(receipt.salaryMonth) + percentDeductions
            } else {
                emptyList()
            },
            remainingRegressNetPay = receipt.regressNetPay
        )
    }

    fun getAdvancePaymentPrediction(year: Int, month: Int): AdvancePaymentPrediction = synchronized(lock) {
        val paymentMonth = MonthKey(year, month)
        val previousMonth = previousMonthKey(year, month)
            ?: return@synchronized unavailableAdvancePaymentPrediction(paymentMonth)
        // U prethodnom mesecu su legle akontacija i konačna isplata za mesec pre njega: kad je ta lista
        // sačuvana, uzima se šta je stvarno isplaćeno (i za mesece čije smene nisu popunjene).
        val salaryMonth = previousMonthKey(previousMonth.year, previousMonth.month)
        val payslip = salaryMonth?.let { getSavedPayslip(it.year, it.month) }
        val payslipPaid = payslip?.paidNet
        val payslipAdvance = payslip?.let(::payslipAdvanceOnlyNet)
        if (payslipAdvance != null && payslipPaid != null) {
            // Akontacija upisana iz obračuna akontacije je tačan iznos; lista je rezerva.
            val recordedAdvance = payState.monthlyNetAdjustments[previousMonth]?.advancePaymentNet ?: 0.0
            return@synchronized calculateAdvancePaymentPrediction(
                paymentMonth = paymentMonth,
                previousPaymentMonth = previousMonth,
                isPreviousMonthNetPayAvailable = true,
                previousMonthAdvanceNet = if (recordedAdvance > 0.0) recordedAdvance else payslipAdvance,
                previousMonthFinalNet = payslipPaid.toDouble()
            )
        }
        val previousBreakdown = getMonthlyFinalPaymentBreakdown(previousMonth.year, previousMonth.month)
        calculateAdvancePaymentPrediction(
            paymentMonth = paymentMonth,
            previousPaymentMonth = previousMonth,
            isPreviousMonthNetPayAvailable = previousBreakdown.isNetPayAvailable,
            previousMonthAdvanceNet = previousBreakdown.advancePaymentNet,
            previousMonthFinalNet = previousBreakdown.finalRemainingNetPay
        )
    }

    /**
     * Akontacija sa liste: kolona „ISPLAĆENO“ sadrži sve isplaćeno pre konačne, pa i regres isplaćen
     * posebno krajem meseca (08/2026: 58.942 = akontacija 38.942 + regres 20.000); regres se oduzima.
     */
    internal fun payslipAdvanceNet(payslip: ParsedPayslip): Double? = synchronized(lock) { payslipAdvanceOnlyNet(payslip) }

    private fun payslipAdvanceOnlyNet(payslip: ParsedPayslip): Double? {
        val paidBefore = payslip.advanceNet?.toDouble() ?: return null
        val regress = if (payslip.extras.any { it.label == "Regres" }) payState.regressNetForMonth(payslip.month) else 0.0
        return (paidBefore - regress).coerceAtLeast(0.0)
    }

    private fun finalPaymentDeductionsUnsafe(salaryMonth: MonthKey): List<FinalPaymentDeduction> =
        finalPaymentDeductions(salaryMonth, savedPayslipsUnsafe())

    /** Obustave u % meseca rada, pod nazivom sa liste, istim iznosom koji je skinut sa neto plate. */
    private fun percentDeductionsFromSalaryUnsafe(salaryMonth: MonthKey): List<FinalPaymentDeduction> {
        val percentDeductions = percentDeductionsForMonth(salaryMonth, savedPayslipsUnsafe()) ?: return emptyList()
        val breakdown = payQueries.calculateAnnualMonthNetPayUnsafe(
            year = salaryMonth.year,
            month = salaryMonth.month,
            monthRecords = recordStore.getMonthRecords(salaryMonth),
            includeRegress = false
        )
        if (breakdown.unionDuesNet <= 0.0) return emptyList()
        return percentDeductions.deductions(breakdown.netPayBeforeDeductions).filter { it.amountNet > 0.0 }
    }

    /** Svi sačuvani listići, svaki pod svojim mesecem; oštećen ili pod tuđim mesecom se preskače. */
    private fun savedPayslipsUnsafe(): List<ParsedPayslip> {
        parsedPayslipCache.keys.retainAll(savedPayslipJsonByMonth.keys)
        return savedPayslipJsonByMonth.mapNotNull { (key, json) ->
            val cached = parsedPayslipCache[key]?.takeIf { it.first == json }
            val payslip = if (cached != null) {
                cached.second
            } else {
                payslipFromJsonOrNull(json)?.takeIf { it.month == key }.also { parsedPayslipCache[key] = json to it }
            }
            payslip
        }
    }

    private fun getMonthlyFoodSpendingBreakdownUnsafe(month: MonthKey): MonthlyFoodSpendingBreakdown {
        val additions = payState.monthlyGrossAdditions[month]
        val foodAllowance = getSummary(month.year, month.month).foodAllowance
        // Ručni unos potrošnje za hranu je ukinut: pojedeno = pripada (vidi MonthlyNetPayBreakdownCalculator).
        return calculateMonthlyFoodSpendingBreakdown(
            month = month,
            foodAllowanceNet = foodAllowance,
            manualFoodSpentNet = null,
            confirmedEntitlementNetOverride = additions?.mealEntitlementNetOverride?.toDouble()
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

    fun getFoodPerDayForMonth(year: Int, month: Int): Double = synchronized(lock) {
        resolveFoodPerDayForMonthUnsafe(MonthKey(year, month))
    }

    fun setFoodPerDayFromMonth(year: Int, month: Int, value: Double) {
        if (year <= 0 || month !in 1..12 || !value.isFinite() || value < 0.0) return
        synchronized(lock) {
            paySettingsUpdater.setFoodPerDayFromMonth(
                MonthKey(year, month),
                value.coerceAtMost(MAX_FOOD_PER_DAY)
            )
        }
        notifyChange()
    }

    private fun resolveFoodPerDayForMonthUnsafe(month: MonthKey): Double {
        return payState.foodPerDayForMonth(month)
    }

    private fun restoreMealPriceHistoryUnsafe(snapshot: ShiftRepositorySnapshot) {
        payState.foodPerDayChanges.clear()
        payState.foodPerDayChanges.putAll(
            snapshot.foodPerDayChanges.filter { (key, value) ->
                key.year > 0 && key.month in 1..12 && value.isFinite() && value >= 0.0
            }.mapValues { (_, value) -> value.coerceAtMost(MAX_FOOD_PER_DAY) }
        )
        // Ugrađena istorija se dopunjuje samo tamo gde korisnik nema svoju cenu
        // koja već pokriva taj period: postojeće instalacije su snimljene bez
        // perioda pre 01.06.2026 (cena 274,00 sa listića 05/2026), pa bi im
        // obračun starijih meseci koristio pogrešnu cenu. Ako korisnik ima cenu
        // koja važi od ranijeg meseca, ona ostaje netaknuta.
        DEFAULT_MEAL_PRICE_PERIODS.forEach { period ->
            val key = MonthKey(period.validFrom.year, period.validFrom.monthValue)
            val coveredByUserPrice = payState.foodPerDayChanges.keys.any { it <= key }
            if (!coveredByUserPrice) {
                payState.foodPerDayChanges[key] = period.netAmountPerMeal.toDouble()
            }
        }
    }

    fun getMealPricePeriods(): List<MealPricePeriod> = synchronized(lock) {
        payState.mealPricePeriods()
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

    /**
     * Glavni prekidač za automatski upis akontacije iz NLB obaveštenja.
     * Kad je isključen, servis ne upisuje ništa i nema pitanja za pristup obaveštenjima.
     */
    /**
     * Da li kartice ispod kalendara („Septembarska plata", „Primanja u Septembru")
     * prikazuju neto platu (i regres) zajedno sa prevozom. Isključeno = samo
     * neto plata; detalji plate i dalje prikazuju prevoz kao zasebnu stavku.
     */
    var mainPayIncludesTransport: Boolean
        get() = synchronized(lock) { mainPayIncludesTransportState }
        set(value) {
            val changed = synchronized(lock) {
                if (mainPayIncludesTransportState == value) {
                    false
                } else {
                    mainPayIncludesTransportState = value
                    true
                }
            }
            if (changed) {
                notifyChange()
            }
        }

    var autoAdvanceImportEnabled: Boolean
        get() = synchronized(lock) { autoAdvanceImportEnabledState }
        set(value) {
            val changed = synchronized(lock) {
                if (autoAdvanceImportEnabledState == value) {
                    false
                } else {
                    autoAdvanceImportEnabledState = value
                    true
                }
            }
            if (changed) {
                notifyChange()
            }
        }

    var notificationAccessPromptSuppressed: Boolean
        get() = synchronized(lock) { notificationAccessPromptSuppressedState }
        set(value) {
            val changed = synchronized(lock) {
                if (notificationAccessPromptSuppressedState == value) {
                    false
                } else {
                    notificationAccessPromptSuppressedState = value
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

    /** Poslednje korišćenje dana za krv: najnoviji par plaćenog odsustva u kalendaru ili ručni upis. */
    internal val lastBloodDonationLeaveUsage: BloodDonationLeaveUsage?
        get() = synchronized(lock) {
            val paidLeaveDates = recordStore.getAllMonthsSorted()
                .flatMap { recordStore.getMonthRecords(it) }
                .filter { it.isPaidLeave }
                .map { it.date }
            lastBloodDonationLeaveUsage(latestPaidLeavePair(paidLeaveDates), bloodDonationLeaveStates.firstOrNull()?.firstDay)
        }

    fun applyBloodDonationLeave(firstDay: LocalDate): Boolean {
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
        val now = LocalDate.now(clock)
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

    /** Staž 0 god. je ispravan za novog radnika; upozorava se samo kad početak uopšte nije unet. */
    val isSeniorityConfiguredValue: Boolean
        get() = synchronized(lock) {
            when (payState.seniorityInputMode) {
                SeniorityInputMode.KNOWN_START -> payState.minuliRadStartYear > 0
                SeniorityInputMode.PAYSLIP_PERCENT -> payState.minuliRadLastPayslipYear > 0
            }
        }

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

    fun getVacationBalanceSummary(year: Int, today: LocalDate = LocalDate.now(clock)): VacationBalanceSummary =
        synchronized(lock) {
            vacationTracker.getVacationBalanceSummary(year, today)
        }

    /**
     * Pravo na godišnji za [year] po KU čl. 29. Staž na 01.01. je sa sačuvanih listića
     * („Ukupni radni staž“, a za 35 godina kod poslodavca „Radni staž za MR“), bez listića iz
     * podešavanja minulog rada. Noćni rad: četvorobrigada ili treća smena / šifra 20 u podacima;
     * vikend samo četvorobrigada. Ostalo su ručni odgovori ([setAnnualLeaveCalculation]).
     */
    internal fun getAnnualLeaveEntitlement(year: Int): AnnualLeaveEntitlement = synchronized(lock) {
        val payslips = savedPayslipsUnsafe()
        val totalPoints = payslips.mapNotNull { slip ->
            (slip.totalSeniorityYears ?: slip.seniorityYears)?.let { PayslipSeniorityPoint(slip.month, it) }
        }
        val employerPoints = payslips.mapNotNull { slip -> slip.seniorityYears?.let { PayslipSeniorityPoint(slip.month, it) } }
        val fourBrigade = payState.brigadeType == BrigadeType.CETVOROBRIGADA
        val nightWork = fourBrigade ||
            payslips.any { (it.items[NIGHT_WORK_PAYSLIP_CODE]?.hours?.signum() ?: 0) > 0 } ||
            recordStore.allRecords.any { it.shift == Shift.THIRD || it.shift2 == Shift.THIRD }
        val manual = vacationTracker.getAnnualLeaveCalculationInput(year)
        annualLeaveEntitlement(
            // „Beneficirani staž“ na listiću = uvećan rizik (KU čl. 29), bez ručnog prekidača.
            manual = if (payslips.any { it.benefitedSeniority }) {
                manual.copy(hasIncreasedRiskOrBenefitedSeniority = true)
            } else {
                manual
            },
            totalSeniority = seniorityOnJanuaryFirst(totalPoints, year),
            employerSeniority = seniorityOnJanuaryFirst(employerPoints, year),
            fallbackSeniorityYears = calculateYearsOfServiceUnsafe(year, 1),
            worksNightShifts = nightWork,
            worksWeekendShifts = fourBrigade
        )
    }

    /** Da li se „preneto iz prošle godine“ računa samo (prošla godina je praćena u aplikaciji). */
    fun isVacationCarryoverAutomatic(year: Int): Boolean = synchronized(lock) {
        vacationTracker.isCarryoverAutomatic(year)
    }

    /**
     * Završeni meseci godine (do [today]) za koje aplikacija nema ni listić ni jedan upisan dan —
     * samo za njih ima smisla „potrošeno van aplikacije“.
     */
    fun getVacationMonthsWithoutData(year: Int, today: LocalDate = LocalDate.now(clock)): List<Int> = synchronized(lock) {
        val lastCompletedMonth = when {
            year < today.year -> 12
            year == today.year -> today.monthValue - 1
            else -> 0
        }
        (1..lastCompletedMonth).filter { month ->
            MonthKey(year, month) !in savedPayslipJsonByMonth && recordStore.getMonthRecords(year, month).isEmpty()
        }
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
        if (localWriteBlockedBySchema) {
            Log.e(TAG, "Čuvanje blokirano: lokalni fajl je pisala novija verzija aplikacije")
            return false
        }
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
        // „{}" i tuđ JSON imaju odbijen uvoz (nema prepoznatih ključeva): fajl ide u karantin kao i neparsiv.
        val rejection = tryImportFromJson(json)
        val imported = rejection == null
        if (imported) {
            Log.d(TAG, "Loaded from local storage")
        } else {
            // Fajl postoji ali se ne može uvesti — sačuvaj ga u karantin
            // pre nego što ga prvi autosave pregazi praznim stanjem.
            val quarantined = localStorage.quarantineCorrupt(context)
            // Novija verzija aplikacije je pisala fajl: ne pregazi ga ni posle karantina (zaštita od downgrade-a).
            localWriteBlockedBySchema = rejection == SnapshotRejection.NEWER_SCHEMA
            Log.e(TAG, "Failed to import local backup data ($rejection, quarantined: $quarantined)")
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

/**
 * Lokalno se čuva ~24 meseca istorije (od najnovijeg upisanog meseca). Isto važi i za sačuvane
 * listiće, lokalno i u oblaku: listić stariji od dana sa kojima se poredi ne služi ničemu.
 */
internal const val HISTORY_RETENTION_LOOKBACK_MONTHS = 24L

/** Prva godina čiji su svi meseci posle granice brisanja (januar je unutar prozora). */
private fun firstFullyRetainedYear(cutoff: MonthKey): Int = if (cutoff.month == 1) cutoff.year else cutoff.year + 1

internal fun MonthKey.minusMonthsForRetention(months: Long): MonthKey {
    val result = YearMonth.of(year, month).minusMonths(months)
    return MonthKey(result.year, result.monthValue)
}



