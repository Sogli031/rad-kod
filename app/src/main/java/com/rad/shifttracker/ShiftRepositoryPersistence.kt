@file:Suppress("TooManyFunctions", "LongMethod")

package com.rad.shifttracker

import android.content.Context
import android.util.Log
import com.rad.shifttracker.ShiftRepository.Companion.MAX_COMP_BALANCE_MINUTES
import com.rad.shifttracker.ShiftRepository.Companion.MAX_FOOD_PER_DAY
import com.rad.shifttracker.ShiftRepository.Companion.MAX_PERCENT
import com.rad.shifttracker.ShiftRepository.Companion.MAX_TAX_RELIEF
import com.rad.shifttracker.ShiftRepository.Companion.MAX_TRANSPORT_PER_DAY
import com.rad.shifttracker.ShiftRepository.Companion.MINUTES_PER_HOUR
import com.rad.shifttracker.ShiftRepository.Companion.MIN_COMP_BALANCE
import com.rad.shifttracker.ShiftRepository.Companion.TAG
import com.rad.shifttracker.data.backup.BackupStats
import com.rad.shifttracker.data.backup.CloudBackupContent
import com.rad.shifttracker.data.storage.CloudArchiveMerger
import com.rad.shifttracker.data.storage.ShiftRepositorySnapshot
import com.rad.shifttracker.data.storage.SnapshotDecodeResult
import com.rad.shifttracker.data.storage.SnapshotRejection

internal fun ShiftRepository.buildSnapshotUnsafe(): ShiftRepositorySnapshot {
    return ShiftRepositorySnapshot(
        payCoefficient = payState.payCoefficient,
        sickPayBaseHourly = payState.sickPayBaseHourly,
        defaultPayBaseSelectionMode = payState.defaultPayBaseSelectionMode,
        hourlyValueChanges = payState.hourlyValueChanges.toMap(),
        foodPerDay = payState.foodPerDay,
        foodPerDayChanges = payState.foodPerDayChanges.toMap(),
        regressNetChanges = payState.regressNetChanges.toMap(),
        transportDailyAmount = payState.transportDailyAmount,
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

fun ShiftRepository.pruneIrrelevantHistory(): Boolean {
    val changed = synchronized(lock) {
        pruneIrrelevantHistoryUnsafe()
    }
    if (changed) {
        notifyChange()
    }
    return changed
}

internal fun ShiftRepository.pruneIrrelevantHistoryUnsafe(): Boolean {
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

internal data class HistoryRetentionPlan(
    val cutoff: MonthKey,
    val retainedMonths: List<MonthKey>,
    val compBalanceAtCutoff: Int
)

internal data class RetentionPayBaseAnchors(
    val months: Set<MonthKey>,
    val changed: Boolean
)

internal fun ShiftRepository.historyRetentionPlanUnsafe(): HistoryRetentionPlan? {
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

internal fun ShiftRepository.hasHistoryRetentionWorkUnsafe(cutoff: MonthKey, recordMonths: List<MonthKey>): Boolean {
    return recordMonths.any { it < cutoff } ||
        savedPayslipJsonByMonth.keys.any { it < cutoff } ||
        vacationTracker.snapshotVacationYearBalances().keys.any { it < firstFullyRetainedYear(cutoff) } ||
        payState.monthlyGrossAdditions.keys.any { it < cutoff } ||
        payState.monthlyNetAdjustments.keys.any { it < cutoff } ||
        payState.monthlyPayBases.keys.any { it < cutoff }
}

internal fun ShiftRepository.materializePayBaseAnchorsUnsafe(
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

internal fun ShiftRepository.shouldMaterializePayBaseAnchorUnsafe(sourceMonth: MonthKey, cutoff: MonthKey): Boolean {
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

internal fun ShiftRepository.resetInitialCompBalanceForRetentionUnsafe(plan: HistoryRetentionPlan): Boolean {
    if (payState.initialCompBalanceMinutes == plan.compBalanceAtCutoff) return false
    payState.initialCompBalanceMinutes = plan.compBalanceAtCutoff
    payState.initialCompBalance = plan.compBalanceAtCutoff / MINUTES_PER_HOUR
    return true
}

internal fun ShiftRepository.removeOldRecordsUnsafe(cutoff: MonthKey): Boolean {
    val oldRecordKeys = recordStore.allRecords
        .filter { record -> record.monthKey < cutoff }
        .map { record -> record.key }
    var changed = false
    for (key in oldRecordKeys) {
        changed = recordStore.delete(key.year, key.month, key.day) || changed
    }
    return changed
}

internal fun ShiftRepository.pruneMonthScopedDataUnsafe(cutoff: MonthKey, protectedPayBaseMonths: Set<MonthKey>): Boolean {
    var changed = false
    changed = removeMonthKeysBefore(payState.monthlyGrossAdditions, cutoff) || changed
    changed = removeMonthKeysBefore(payState.monthlyNetAdjustments, cutoff) || changed
    changed = removeMonthKeysBefore(payState.monthlyPayBases, cutoff, protectedPayBaseMonths) || changed
    return changed
}

internal fun ShiftRepository.invalidateRetentionCachesUnsafe() {
    payState.invalidateSummariesRollingAndCompBalance()
    payState.dirtyMonths.clear()
    payState.dirtyMonths.addAll(recordStore.getAllMonthsSorted())
    payState.dirtyMonths.addAll(payState.monthlyPayBases.keys)
}

internal fun <T> ShiftRepository.removeMonthKeysBefore(
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

fun ShiftRepository.exportJson(): String {
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
fun ShiftRepository.exportCloudBackupJson(existingCloudJson: String?): String {
    return jsonCodec.encode(buildCloudSnapshot(existingCloudJson))
}

/**
 * Kao [exportCloudBackupJson], ali uz JSON vraća i [BackupStats] otisak
 * (broj dana/meseci/radnih sati) spojenog stanja — koristi ga verzionisani
 * backup za metapodatke verzije i detekciju anomalija.
 */
fun ShiftRepository.buildCloudBackup(existingCloudJson: String?): CloudBackupContent {
    val snapshot = buildCloudSnapshot(existingCloudJson)
    return CloudBackupContent(
        json = jsonCodec.encode(snapshot),
        stats = BackupStats.from(snapshot.records)
    )
}

internal fun ShiftRepository.buildCloudSnapshot(existingCloudJson: String?): ShiftRepositorySnapshot {
    return synchronized(lock) {
        val current = buildSnapshotUnsafe()
        val archive = existingCloudJson
            ?.takeIf { it.isNotBlank() }
            ?.let { runCatching { jsonCodec.decode(it) }.getOrNull() }
        if (archive == null) current else CloudArchiveMerger.merge(archive, current)
    }
}

fun ShiftRepository.importFromJson(jsonText: String): Boolean = tryImportFromJson(jsonText) == null

/**
 * Vraćanje stanja iz JSON-a. Sve provere su PRE brisanja: odbijeni JSON ne menja ništa.
 * @return null kad je uvezeno, inače razlog odbijanja.
 */
internal fun ShiftRepository.tryImportFromJson(jsonText: String): SnapshotRejection? {
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

internal fun ShiftRepository.decodeForImport(jsonText: String): SnapshotDecodeResult {
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

internal fun ShiftRepository.applySnapshotUnsafe(snapshot: ShiftRepositorySnapshot) {
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

// =========================================================================
// LOCAL STORAGE
// =========================================================================

fun ShiftRepository.saveToLocal(context: Context): Boolean {
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

fun ShiftRepository.loadFromLocal(context: Context): Boolean {
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

fun ShiftRepository.hasLocalData(context: Context): Boolean {
    return localStorage.hasData(context)
}
