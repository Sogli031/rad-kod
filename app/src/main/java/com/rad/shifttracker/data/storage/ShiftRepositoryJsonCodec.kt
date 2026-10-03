package com.rad.shifttracker.data.storage

import android.util.Log
import com.rad.shifttracker.AnnualLeaveCalculationInput
import com.rad.shifttracker.AppConfig
import com.rad.shifttracker.BrigadeType
import com.rad.shifttracker.DayRecord
import com.rad.shifttracker.MAX_BASE_PAY
import com.rad.shifttracker.MonthKey
import com.rad.shifttracker.MonthlyGrossAdditions
import com.rad.shifttracker.MonthlyNetAdjustments
import com.rad.shifttracker.MonthlyPayBases
import com.rad.shifttracker.PayBaseSelectionMode
import com.rad.shifttracker.SeniorityIncreaseFactor
import com.rad.shifttracker.SeniorityInputMode
import com.rad.shifttracker.VacationYearBalance
import com.rad.shifttracker.calculateAnnualLeaveDays
import com.rad.shifttracker.coefficientFromLegacyGrossHourlyRate
import com.rad.shifttracker.currentYearVacationAvailableFrom
import com.rad.shifttracker.sanitizeAnnualLeaveYears
import com.rad.shifttracker.sanitizeMonthlyGrossAddition
import com.rad.shifttracker.sanitizeMonthlyGrossAdditions
import com.rad.shifttracker.sanitizeMonthlyGrossOverrideOrNull
import com.rad.shifttracker.sanitizeMonthlyGrossPenalty
import com.rad.shifttracker.sanitizeMonthlySignedAdjustment
import com.rad.shifttracker.sanitizeMonthlyNetAdjustment
import com.rad.shifttracker.sanitizePayBaseOrNull
import com.rad.shifttracker.toMealMoney
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.time.Clock
import java.time.LocalDate
import java.time.YearMonth
import java.util.Locale
import java.util.TreeMap

private const val TAG = "ShiftRepositoryJsonCodec"
private const val MEAL_GROSS_KEY = "meal_gross"
private const val REGRESS_GROSS_KEY = "regress_gross"
private const val BONUS_GROSS_KEY = "bonus_gross"
private const val MEAL_SPENT_NET_KEY = "meal_spent_net"
private const val MEAL_ENTITLEMENT_NET_OVERRIDE_KEY = "meal_entitlement_net_override"
private const val MEAL_SETTLEMENT_ADJUSTMENT_NET_KEY = "meal_settlement_adjustment_net"
private const val BONUS_NET_KEY = "bonus_net"
private const val ABSENCE_PENALTY_GROSS_KEY = "absence_penalty_gross"

// Kodek preslikava celu šemu zapisa jedan-na-jedan (po jedna funkcija po polju); deljenje bi samo razvuklo šemu.
@Suppress("LargeClass", "TooManyFunctions")
internal class ShiftRepositoryJsonCodec(
    private val config: AppConfig,
    private val clock: Clock = Clock.systemDefaultZone(),
    private val migrations: SchemaMigrations = SchemaMigrations()
) {

    fun encode(snapshot: ShiftRepositorySnapshot): String {
        return JsonWriter.write(buildDataMap(snapshot))
    }

    /** Kao [decodeChecked], ali odbijen JSON baca [SnapshotRejectedException]. */
    fun decode(jsonText: String): ShiftRepositorySnapshot {
        return when (val result = decodeChecked(jsonText)) {
            is SnapshotDecodeResult.Decoded -> result.snapshot
            is SnapshotDecodeResult.Rejected -> throw SnapshotRejectedException(result.reason)
        }
    }

    /**
     * Čita JSON tek pošto proveri da liči na podatke ove aplikacije: objekat, razumljiva verzija sheme
     * i bar jedan ključ koji aplikacija piše. Inače bi `{"x":1}` dao „prazan“ snapshot sa podrazumevanim
     * vrednostima koji bi pri vraćanju obrisao sve.
     */
    fun decodeChecked(jsonText: String): SnapshotDecodeResult {
        val json = runCatching { JSONObject(jsonText) }.getOrNull()
            ?: return SnapshotDecodeResult.Rejected(SnapshotRejection.UNREADABLE)
        val version = readSchemaVersion(json)
            ?: return SnapshotDecodeResult.Rejected(SnapshotRejection.INVALID_SCHEMA_VERSION)
        if (version > migrations.currentVersion) {
            return SnapshotDecodeResult.Rejected(SnapshotRejection.NEWER_SCHEMA)
        }
        if (!hasRecognizedData(json)) {
            return SnapshotDecodeResult.Rejected(SnapshotRejection.NO_RECOGNIZED_DATA)
        }
        migrations.migrate(json, version.toInt())
        val snapshot = parseJson(json).copy(
            savedPayslips = SavedPayslipsJsonCodec.parse(json),
            deletedPayslipMonths = parseDeletedMonths(json.optJSONArray(DELETED_PAYSLIP_MONTHS_KEY))
        )
        return SnapshotDecodeResult.Decoded(snapshot, version.toInt())
    }

    private fun hasRecognizedData(json: JSONObject): Boolean {
        val keys = json.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            if (key in RECOGNIZED_KEYS) return true
            val year = key.toIntOrNull()
            if (year != null && year in 1900..2200 && json.optJSONObject(key) != null) return true
        }
        return false
    }

    private fun buildDataMap(snapshot: ShiftRepositorySnapshot): TreeMap<String, Any> {
        val data = TreeMap<String, Any>()
        data[SCHEMA_VERSION_KEY] = migrations.currentVersion
        data[PAY_COEFFICIENT_KEY] = snapshot.payCoefficient
        if (snapshot.hourlyValueChanges.isNotEmpty()) {
            data[HOURLY_VALUE_CHANGES_KEY] = buildHourlyValueChangesMapUnsafe(snapshot)
        }
        if (snapshot.foodPerDayChanges.isNotEmpty()) {
            data[FOOD_PER_DAY_CHANGES_KEY] = buildFoodPerDayChangesMapUnsafe(snapshot)
        }
        RegressNetChangesJsonCodec.write(snapshot.regressNetChanges, data)
        data[SICK_PAY_BASE_HOURLY_KEY] = snapshot.sickPayBaseHourly
        data[DEFAULT_PAY_BASE_SELECTION_MODE_KEY] = snapshot.defaultPayBaseSelectionMode.name
        KnownPayBaseAnchorsJsonCodec.write(snapshot.knownPayBaseAnchors, data)
        SavedPayslipsJsonCodec.write(snapshot.savedPayslips, data)
        data[EMPLOYEE_CONTRIBUTIONS_PERCENT_KEY] = snapshot.employeeContributionsPercent
        data[TAX_RELIEF_KEY] = snapshot.taxRelief
        data[TAX_PERCENT_KEY] = snapshot.taxPercent
        data[DIFFICULT_WORK_CONDITIONS_PERCENT_KEY] = snapshot.difficultWorkConditionsPercent
        data[FOOD_PER_DAY_KEY] = snapshot.foodPerDay
        data[TRANSPORT_DAILY_AMOUNT_KEY] = snapshot.transportDailyAmount
        data[AUTO_ADVANCE_IMPORT_ENABLED_KEY] = snapshot.autoAdvanceImportEnabled
        data[NOTIFICATION_ACCESS_PROMPT_SUPPRESSED_KEY] = snapshot.notificationAccessPromptSuppressed
        data[MAIN_PAY_INCLUDES_TRANSPORT_KEY] = snapshot.mainPayIncludesTransport
        snapshot.lastBloodDonationDate?.let { date ->
            data[BLOOD_DONATION_LAST_DATE_KEY] = date.toString()
        }
        data[BLOOD_DONATION_COUNT_KEY] = snapshot.bloodDonationCount
        if (snapshot.bloodDonationHistory.isNotEmpty()) {
            data[BLOOD_DONATION_HISTORY_KEY] = snapshot.bloodDonationHistory.map { it.toString() }
        }
        if (snapshot.bloodDonationLeaves.isNotEmpty()) {
            data[BLOOD_DONATION_LEAVE_KEY] = BloodDonationLeaveJsonCodec.build(snapshot.bloodDonationLeaves)
        }
        if (snapshot.deletedMonths.isNotEmpty()) {
            data[DELETED_MONTHS_KEY] = snapshot.deletedMonths
                .sorted()
                .map { String.format(Locale.ROOT, "%04d-%02d", it.year, it.month) }
        }
        if (snapshot.deletedHourlyValueMonths.isNotEmpty()) {
            data[DELETED_HOURLY_VALUE_MONTHS_KEY] = snapshot.deletedHourlyValueMonths
                .sorted()
                .map { String.format(Locale.ROOT, "%04d-%02d", it.year, it.month) }
        }
        if (snapshot.deletedPayslipMonths.isNotEmpty()) {
            data[DELETED_PAYSLIP_MONTHS_KEY] = snapshot.deletedPayslipMonths
                .sorted()
                .map { String.format(Locale.ROOT, "%04d-%02d", it.year, it.month) }
        }
        data[INITIAL_COMP_BALANCE_KEY] = snapshot.initialCompBalance
        data[INITIAL_COMP_BALANCE_MINUTES_KEY] = snapshot.initialCompBalanceMinutes
        data[MINULI_RAD_START_YEAR_KEY] = snapshot.minuliRadStartYear
        data[MINULI_RAD_START_MONTH_KEY] = snapshot.minuliRadStartMonth
        data[SENIORITY_INPUT_MODE_KEY] = snapshot.seniorityInputMode.jsonValue
        data[MINULI_RAD_BENEFIT_ENABLED_KEY] = snapshot.minuliRadBenefitEnabled
        data[MINULI_RAD_INCREASE_FACTOR_KEY] = snapshot.minuliRadIncreaseFactor.jsonValue
        data[MINULI_RAD_LAST_PAYSLIP_YEAR_KEY] = snapshot.minuliRadLastPayslipYear
        data[MINULI_RAD_LAST_PAYSLIP_MONTH_KEY] = snapshot.minuliRadLastPayslipMonth
        data[MINULI_RAD_PAYSLIP_PERCENT_KEY] = snapshot.minuliRadPayslipPercent
        data[BRIGADE_TYPE_KEY] = snapshot.brigadeType.value
        data[INITIAL_VACATION_BALANCE_KEY] = snapshot.initialVacationBalance
        if (snapshot.vacationYearBalances.isNotEmpty()) {
            data[VACATION_YEAR_BALANCES_KEY] = buildVacationYearBalancesMapUnsafe(snapshot)
        }
        if (snapshot.annualLeaveCalculationInput != null || snapshot.annualLeaveLastCalculatedDays > 0) {
            data[ANNUAL_LEAVE_CALCULATION_KEY] = buildAnnualLeaveCalculationMapUnsafe(snapshot)
        }

        if (snapshot.monthlyPayBases.isNotEmpty()) {
            data[MONTHLY_PAY_BASES_KEY] = buildMonthlyPayBasesMapUnsafe(snapshot)
        }
        if (snapshot.monthlyGrossAdditions.isNotEmpty()) {
            data[MONTHLY_GROSS_ADDITIONS_KEY] = buildMonthlyGrossAdditionsMapUnsafe(snapshot)
        }
        if (snapshot.monthlyNetAdjustments.isNotEmpty()) {
            data[MONTHLY_NET_ADJUSTMENTS_KEY] = buildMonthlyNetAdjustmentsMapUnsafe(snapshot)
        }

        for (record in snapshot.records) {
            val yearKey = record.year.toString()
            // Locale.ROOT: ključevi moraju imati ASCII cifre i na npr. arapskom jeziku sistema
            val monthKey = String.format(Locale.ROOT, "%02d", record.month)
            val dayKey = String.format(Locale.ROOT, "%02d", record.day)

            @Suppress("UNCHECKED_CAST")
            val yearMap = data.getOrPut(yearKey) { TreeMap<String, Any>() } as TreeMap<String, Any>
            @Suppress("UNCHECKED_CAST")
            val monthMap = yearMap.getOrPut(monthKey) { TreeMap<String, Any>() } as TreeMap<String, Any>
            monthMap[dayKey] = record.toMap()
        }

        return data
    }

    // Existing schema migration parser keeps old backup compatibility in one place.
    @Suppress("CyclomaticComplexMethod")
    private fun parseJson(json: JSONObject): ShiftRepositorySnapshot {
        val parsedSickPayBaseHourly = json.optDouble(SICK_PAY_BASE_HOURLY_KEY, config.pay.sickPayBaseHourly)
        val parsedDefaultPayBaseSelectionMode = json.optString(DEFAULT_PAY_BASE_SELECTION_MODE_KEY, "")
            .let { raw ->
                if (raw.isEmpty()) {
                    null
                } else {
                    val parsed = runCatching { PayBaseSelectionMode.valueOf(raw) }.getOrNull()
                    if (parsed == null) {
                        Log.w(TAG, "Nepoznata vrednost za $DEFAULT_PAY_BASE_SELECTION_MODE_KEY: '$raw'.")
                    }
                    parsed
                }
            }
            ?: PayBaseSelectionMode.CALCULATED_PREVIOUS_12_MONTHS
        val parsedKnownPayBaseAnchors = KnownPayBaseAnchorsJsonCodec.parse(json)
        val parsedEmployeeContributionsPercent = json.optDouble(
            EMPLOYEE_CONTRIBUTIONS_PERCENT_KEY,
            config.pay.defaultEmployeeContributionsPercent
        )
        val parsedTaxRelief = json.optDouble(TAX_RELIEF_KEY, config.pay.defaultTaxRelief)
        val parsedTaxPercent = json.optDouble(TAX_PERCENT_KEY, config.pay.defaultTaxPercent)
        val parsedDifficultWorkConditionsPercent = json.optDouble(
            DIFFICULT_WORK_CONDITIONS_PERCENT_KEY,
            config.pay.difficultWorkConditionsPercent
        )
        val parsedFoodPerDay = json.optDouble(FOOD_PER_DAY_KEY, config.pay.foodPerDay)
        val parsedTransportDailyAmount = json.optDouble(
            TRANSPORT_DAILY_AMOUNT_KEY,
            config.pay.transportPerWorkDay
        )
        // „unionMember“ i „unionDuesRate“ iz starijih backup-a se ne čitaju: obustave u % su samo sa listića.
        val parsedAutoAdvanceImport = parseAutoAdvanceImportFlags(json)
        val parsedLastBloodDonationDate = json.optLocalDateOrNull(BLOOD_DONATION_LAST_DATE_KEY)
        val parsedBloodDonationHistory = parseBloodDonationHistory(
            root = json.optJSONArray(BLOOD_DONATION_HISTORY_KEY),
            fallbackLastDate = parsedLastBloodDonationDate
        )
        val parsedBloodDonationCount = parseBloodDonationCount(json, parsedBloodDonationHistory)
        val parsedBloodDonationLeaves = BloodDonationLeaveJsonCodec.parse(json.optJSONObject(BLOOD_DONATION_LEAVE_KEY))
        val parsedInitialComp = json.optInt(INITIAL_COMP_BALANCE_KEY, 0)
        val parsedInitialCompMinutes = parseInitialCompBalanceMinutes(json, parsedInitialComp)
        val parsedStartYear = if (json.has(MINULI_RAD_START_YEAR_KEY)) {
            json.optInt(MINULI_RAD_START_YEAR_KEY, 0)
        } else {
            val oldYears = json.optInt(YEARS_OF_SERVICE_KEY, 0)
            if (oldYears > 0) {
                val now = LocalDate.now(clock)
                if (now.monthValue >= 2) now.year - oldYears else now.year - 1 - oldYears
            } else {
                0
            }
        }
        val parsedStartMonth = json.optInt(MINULI_RAD_START_MONTH_KEY, 2).coerceIn(1, 12)
        val parsedSeniorityInputMode = SeniorityInputMode.fromJson(json.optString(SENIORITY_INPUT_MODE_KEY, ""))
        val parsedMinuliRadBenefitEnabled = json.optBoolean(MINULI_RAD_BENEFIT_ENABLED_KEY, false)
        val parsedMinuliRadIncreaseFactor = SeniorityIncreaseFactor.fromJson(
            json.optString(MINULI_RAD_INCREASE_FACTOR_KEY, "")
        )
        val parsedLastPayslipYear = json.optInt(MINULI_RAD_LAST_PAYSLIP_YEAR_KEY, 0).coerceAtLeast(0)
        val parsedLastPayslipMonth = json.optInt(MINULI_RAD_LAST_PAYSLIP_MONTH_KEY, 1).coerceIn(1, 12)
        val parsedPayslipPercent = json.optDouble(MINULI_RAD_PAYSLIP_PERCENT_KEY, 0.0)
            .takeIf { it.isFinite() }
            ?.coerceIn(0.0, 100.0)
            ?: 0.0
        val parsedBrigade = BrigadeType.fromValue(
            json.optString(BRIGADE_TYPE_KEY, BrigadeType.TROBRIGADA.value)
        )
        val parsedVacationBalance = json.optDouble(INITIAL_VACATION_BALANCE_KEY, 0.0)
        val parsedVacationYearBalances = parseVacationYearBalances(json.optJSONObject(VACATION_YEAR_BALANCES_KEY))
        val parsedAnnualLeaveCalculation = parseAnnualLeaveCalculation(
            json.optJSONObject(ANNUAL_LEAVE_CALCULATION_KEY)
        )
        val parsedMonthlyPayBases = parseMonthlyPayBases(json.optJSONObject(MONTHLY_PAY_BASES_KEY))
        val parsedMonthlyGrossAdditions = parseMonthlyGrossAdditions(
            json.optJSONObject(MONTHLY_GROSS_ADDITIONS_KEY)
        )
        val parsedMonthlyNetAdjustments = parseMonthlyNetAdjustments(json.optJSONObject(MONTHLY_NET_ADJUSTMENTS_KEY))
        val parsedHourlyValueChanges = parseHourlyValueChanges(json.optJSONObject(HOURLY_VALUE_CHANGES_KEY))
        val parsedFoodPerDayChanges = parseFoodPerDayChanges(json.optJSONObject(FOOD_PER_DAY_CHANGES_KEY))

        val parsedRecords = parseDayRecords(json)
        val representativeImportMonth = parsedRecords
            .minWithOrNull(compareBy<DayRecord> { it.year }.thenBy { it.month })
            ?.let { MonthKey(it.year, it.month) }
            ?: MonthKey(2026, 1)
        val parsedCoefficient = parseCoefficient(json, representativeImportMonth)

        return ShiftRepositorySnapshot(
            payCoefficient = parsedCoefficient,
            foodPerDay = parsedFoodPerDay,
            transportDailyAmount = parsedTransportDailyAmount,
            autoAdvanceImportEnabled = parsedAutoAdvanceImport.enabled,
            notificationAccessPromptSuppressed = parsedAutoAdvanceImport.promptSuppressed,
            mainPayIncludesTransport = json.optBoolean(MAIN_PAY_INCLUDES_TRANSPORT_KEY, true),
            lastBloodDonationDate = parsedBloodDonationHistory.firstOrNull() ?: parsedLastBloodDonationDate,
            bloodDonationCount = parsedBloodDonationCount,
            bloodDonationHistory = parsedBloodDonationHistory,
            bloodDonationLeaves = parsedBloodDonationLeaves,
            initialCompBalance = parsedInitialComp,
            initialCompBalanceMinutes = parsedInitialCompMinutes,
            minuliRadStartYear = parsedStartYear,
            minuliRadStartMonth = parsedStartMonth,
            seniorityInputMode = parsedSeniorityInputMode,
            minuliRadBenefitEnabled = parsedMinuliRadBenefitEnabled,
            minuliRadIncreaseFactor = parsedMinuliRadIncreaseFactor,
            minuliRadLastPayslipYear = parsedLastPayslipYear,
            minuliRadLastPayslipMonth = parsedLastPayslipMonth,
            minuliRadPayslipPercent = parsedPayslipPercent,
            brigadeType = parsedBrigade,
            initialVacationBalance = parsedVacationBalance,
            sickPayBaseHourly = parsedSickPayBaseHourly,
            defaultPayBaseSelectionMode = parsedDefaultPayBaseSelectionMode,
            hourlyValueChanges = parsedHourlyValueChanges,
            foodPerDayChanges = parsedFoodPerDayChanges,
            regressNetChanges = RegressNetChangesJsonCodec.read(json),
            employeeContributionsPercent = parsedEmployeeContributionsPercent,
            taxRelief = parsedTaxRelief,
            taxPercent = parsedTaxPercent,
            difficultWorkConditionsPercent = parsedDifficultWorkConditionsPercent,
            vacationYearBalances = parsedVacationYearBalances,
            annualLeaveCalculationInput = parsedAnnualLeaveCalculation.input,
            annualLeaveLastCalculatedDays = parsedAnnualLeaveCalculation.lastCalculatedDays,
            monthlyPayBases = parsedMonthlyPayBases,
            monthlyGrossAdditions = parsedMonthlyGrossAdditions,
            monthlyNetAdjustments = parsedMonthlyNetAdjustments,
            knownPayBaseAnchors = parsedKnownPayBaseAnchors,
            records = parsedRecords,
            deletedMonths = parseDeletedMonths(json.optJSONArray(DELETED_MONTHS_KEY)),
            deletedHourlyValueMonths = parseDeletedMonths(json.optJSONArray(DELETED_HOURLY_VALUE_MONTHS_KEY))
        )
    }

    private fun parseCoefficient(json: JSONObject, representativeImportMonth: MonthKey): Double = when {
        json.has(PAY_COEFFICIENT_KEY) -> json.optDouble(PAY_COEFFICIENT_KEY, config.pay.defaultCoefficient)
        json.has(GROSS_HOURLY_RATE_KEY) -> coefficientFromLegacyGrossHourlyRate(
            json.optDouble(GROSS_HOURLY_RATE_KEY, config.pay.defaultBasePay),
            representativeImportMonth
        )
        else -> config.pay.defaultCoefficient
    }

    // Stari bekapi nemaju ove ključeve — auto akontacija ostaje uključena kao i pre.
    private fun parseAutoAdvanceImportFlags(json: JSONObject) = ParsedAutoAdvanceImportFlags(
        enabled = json.optBoolean(AUTO_ADVANCE_IMPORT_ENABLED_KEY, true),
        promptSuppressed = json.optBoolean(NOTIFICATION_ACCESS_PROMPT_SUPPRESSED_KEY, false)
    )

    private fun parseDeletedMonths(root: JSONArray?): List<MonthKey> {
        if (root == null) return emptyList()
        val months = mutableListOf<MonthKey>()
        for (index in 0 until root.length()) {
            val raw = root.optString(index) ?: continue
            val parts = raw.split("-")
            val year = parts.getOrNull(0)?.toIntOrNull() ?: continue
            val month = parts.getOrNull(1)?.toIntOrNull() ?: continue
            if (year in 1900..2200 && month in 1..12) {
                months += MonthKey(year, month)
            }
        }
        return months.distinct()
    }

    private fun parseDayRecords(json: JSONObject): List<DayRecord> {
        val parsedRecords = mutableListOf<DayRecord>()
        val keys = json.keys()
        while (keys.hasNext()) {
            parsedRecords.addAll(parseYearDayRecords(json, keys.next()))
        }
        return parsedRecords
    }

    private fun parseYearDayRecords(json: JSONObject, yearKey: String): List<DayRecord> {
        if (yearKey in METADATA_KEYS) return emptyList()

        val year = yearKey.toIntOrNull() ?: return emptyList()
        if (year < 1900 || year > 2200) {
            Log.w(TAG, "Preskočen nevažeći ključ godine pri uvozu: '$yearKey' (van 1900–2200).")
            return emptyList()
        }

        val monthsObj = json.optJSONObject(yearKey) ?: return emptyList()
        val parsedRecords = mutableListOf<DayRecord>()
        val monthKeys = monthsObj.keys()
        while (monthKeys.hasNext()) {
            parsedRecords.addAll(parseMonthDayRecords(year, monthsObj, monthKeys.next()))
        }
        return parsedRecords
    }

    private fun parseBloodDonationHistory(
        root: JSONArray?,
        fallbackLastDate: LocalDate?
    ): List<LocalDate> {
        val dates = mutableListOf<LocalDate>()
        if (root != null) {
            for (index in 0 until root.length()) {
                runCatching { LocalDate.parse(root.optString(index)) }.getOrNull()?.let(dates::add)
            }
        }
        fallbackLastDate?.let(dates::add)
        return dates.distinct().sortedDescending()
    }

    private fun parseBloodDonationCount(json: JSONObject, history: List<LocalDate>): Int {
        if (!json.has(BLOOD_DONATION_COUNT_KEY)) {
            return history.size
        }
        return json.optInt(BLOOD_DONATION_COUNT_KEY, history.size).coerceAtLeast(history.size)
    }

    private fun parseMonthDayRecords(year: Int, monthsObj: JSONObject, monthKey: String): List<DayRecord> {
        val month = monthKey.toIntOrNull() ?: return emptyList()
        if (month < 1 || month > 12) {
            Log.w(TAG, "Preskočen nevažeći mesec pri uvozu: '$monthKey' za godinu $year.")
            return emptyList()
        }

        val daysObj = monthsObj.optJSONObject(monthKey) ?: return emptyList()
        val dayKeys = daysObj.keys()
        val maxDays = YearMonth.of(year, month).lengthOfMonth()
        val parsedRecords = mutableListOf<DayRecord>()
        while (dayKeys.hasNext()) {
            val dayKey = dayKeys.next()
            val day = dayKey.toIntOrNull() ?: continue
            if (day < 1 || day > maxDays) {
                Log.w(TAG, "Preskočen nevažeći dan pri uvozu: $year-$month-$dayKey (van 1..$maxDays).")
                continue
            }
            val recData = daysObj.optJSONObject(dayKey) ?: continue
            parsedRecords.add(DayRecord.fromJson(year, month, day, recData))
        }
        return parsedRecords
    }

    private fun buildVacationYearBalancesMapUnsafe(snapshot: ShiftRepositorySnapshot): TreeMap<String, Any> {
        val root = TreeMap<String, Any>()
        for ((year, balance) in snapshot.vacationYearBalances.toSortedMap()) {
            val data = TreeMap<String, Any>()
            data[VACATION_TRACKING_START_DATE_KEY] = balance.trackingStartDate.toString()
            data[VACATION_CARRYOVER_DAYS_INITIAL_KEY] = balance.carryoverDaysInitial
            data[VACATION_CURRENT_YEAR_DAYS_TOTAL_KEY] = balance.currentYearDaysTotal
            data[VACATION_CURRENT_YEAR_USED_BEFORE_AVAILABILITY_KEY] =
                balance.currentYearDaysUsedBeforeAvailability
            data[VACATION_CURRENT_YEAR_AVAILABLE_FROM_KEY] = balance.currentYearAvailableFrom.toString()
            data[VACATION_MANUAL_BASELINE_KEY] = balance.manualBaseline
            if (balance.carryoverFrozen) data[VACATION_CARRYOVER_FROZEN_KEY] = true
            root[year.toString()] = data
        }
        return root
    }

    private fun parseVacationYearBalances(root: JSONObject?): Map<Int, VacationYearBalance> {
        if (root == null) return emptyMap()
        val parsed = mutableMapOf<Int, VacationYearBalance>()
        val yearKeys = root.keys()
        while (yearKeys.hasNext()) {
            val yearKey = yearKeys.next()
            val year = yearKey.toIntOrNull() ?: continue
            if (year < 1900 || year > 2200) continue
            val data = root.optJSONObject(yearKey) ?: continue
            val trackingStart = data.optLocalDateOrNull(VACATION_TRACKING_START_DATE_KEY) ?: continue
            val availableFrom = data.optLocalDateOrNull(VACATION_CURRENT_YEAR_AVAILABLE_FROM_KEY)
                ?: currentYearVacationAvailableFrom(year)
            parsed[year] = VacationYearBalance(
                year = year,
                trackingStartDate = trackingStart,
                carryoverDaysInitial = data.optDouble(VACATION_CARRYOVER_DAYS_INITIAL_KEY, 0.0).coerceAtLeast(0.0),
                currentYearDaysTotal = data.optDouble(VACATION_CURRENT_YEAR_DAYS_TOTAL_KEY, 0.0).coerceAtLeast(0.0),
                currentYearDaysUsedBeforeAvailability = data
                    .optDouble(VACATION_CURRENT_YEAR_USED_BEFORE_AVAILABILITY_KEY, 0.0)
                    .coerceAtLeast(0.0),
                currentYearAvailableFrom = availableFrom,
                manualBaseline = data.optBoolean(VACATION_MANUAL_BASELINE_KEY, true),
                carryoverFrozen = data.optBoolean(VACATION_CARRYOVER_FROZEN_KEY, false)
            )
        }
        return parsed
    }

    private fun buildAnnualLeaveCalculationMapUnsafe(snapshot: ShiftRepositorySnapshot): TreeMap<String, Any> {
        val data = TreeMap<String, Any>()
        snapshot.annualLeaveCalculationInput?.let { input ->
            data[ANNUAL_LEAVE_TOTAL_SENIORITY_YEARS_KEY] = input.totalSeniorityYears
            data[ANNUAL_LEAVE_YEARS_AT_EMPLOYER_KEY] = input.yearsAtEmployer
            data[ANNUAL_LEAVE_INCREASED_RISK_KEY] = input.hasIncreasedRiskOrBenefitedSeniority
            data[ANNUAL_LEAVE_NIGHT_WORK_KEY] = input.worksNightShiftSystem
            data[ANNUAL_LEAVE_WEEKEND_WORK_KEY] = input.worksWeekendShiftSystem
            data[ANNUAL_LEAVE_THIRD_DEGREE_KEY] = input.requiresAtLeastThirdDegree
            data[ANNUAL_LEAVE_WORK_INVALID_KEY] = input.isWorkInvalidFromWorkInjuryOrDisease
            data[ANNUAL_LEAVE_WAR_INVALID_KEY] = input.isWarMilitaryInvalid
            data[ANNUAL_LEAVE_SINGLE_PARENT_KEY] = input.isSingleParentOfChildUnder14
            data[ANNUAL_LEAVE_THREE_CHILDREN_KEY] = input.hasThreeOrMoreChildrenUnder14
        }
        data[ANNUAL_LEAVE_LAST_CALCULATED_DAYS_KEY] = snapshot.annualLeaveLastCalculatedDays
        return data
    }

    private fun parseAnnualLeaveCalculation(root: JSONObject?): ParsedAnnualLeaveCalculation {
        if (root == null) return ParsedAnnualLeaveCalculation(input = null, lastCalculatedDays = 0)
        val input = AnnualLeaveCalculationInput(
            totalSeniorityYears = root.optDouble(ANNUAL_LEAVE_TOTAL_SENIORITY_YEARS_KEY, 0.0)
                .sanitizeAnnualLeaveYears(),
            yearsAtEmployer = root.optDouble(ANNUAL_LEAVE_YEARS_AT_EMPLOYER_KEY, 0.0)
                .sanitizeAnnualLeaveYears(),
            hasIncreasedRiskOrBenefitedSeniority = root.optBoolean(ANNUAL_LEAVE_INCREASED_RISK_KEY, false),
            worksNightShiftSystem = root.optBoolean(ANNUAL_LEAVE_NIGHT_WORK_KEY, false),
            worksWeekendShiftSystem = root.optBoolean(ANNUAL_LEAVE_WEEKEND_WORK_KEY, false),
            requiresAtLeastThirdDegree = root.optBoolean(ANNUAL_LEAVE_THIRD_DEGREE_KEY, false),
            isWorkInvalidFromWorkInjuryOrDisease = root.optBoolean(ANNUAL_LEAVE_WORK_INVALID_KEY, false),
            isWarMilitaryInvalid = root.optBoolean(ANNUAL_LEAVE_WAR_INVALID_KEY, false),
            isSingleParentOfChildUnder14 = root.optBoolean(ANNUAL_LEAVE_SINGLE_PARENT_KEY, false),
            hasThreeOrMoreChildrenUnder14 = root.optBoolean(ANNUAL_LEAVE_THREE_CHILDREN_KEY, false)
        )
        return ParsedAnnualLeaveCalculation(
            input = input,
            lastCalculatedDays = root.optInt(
                ANNUAL_LEAVE_LAST_CALCULATED_DAYS_KEY,
                calculateAnnualLeaveDays(input).totalDays
            ).coerceAtLeast(0)
        )
    }

    private fun parseInitialCompBalanceMinutes(json: JSONObject, initialCompBalance: Int): Int {
        return if (json.has(INITIAL_COMP_BALANCE_MINUTES_KEY)) {
            json.optInt(INITIAL_COMP_BALANCE_MINUTES_KEY, initialCompBalance * 60)
        } else {
            initialCompBalance * 60
        }
    }

    private fun buildMonthlyPayBasesMapUnsafe(snapshot: ShiftRepositorySnapshot): TreeMap<String, Any> {
        val root = TreeMap<String, TreeMap<String, Any>>()
        for ((key, bases) in snapshot.monthlyPayBases.toSortedMap()) {
            val yearMap = root.getOrPut(key.year.toString()) { TreeMap() }
            val monthData = TreeMap<String, Any>()
            bases.selectionMode?.let { monthData[SELECTION_MODE_KEY] = it.name }
            bases.vacationHourlyBase?.let { monthData[VACATION_HOURLY_BASE_KEY] = it }
            bases.paidHolidayHourlyBase?.let { monthData[PAID_HOLIDAY_HOURLY_BASE_KEY] = it }
            bases.sickHourlyBase?.let { monthData[SICK_HOURLY_BASE_KEY] = it }
            if (monthData.isNotEmpty()) {
                yearMap[String.format(Locale.ROOT, "%02d", key.month)] = monthData
            }
        }
        return TreeMap(root)
    }

    private fun parseMonthlyPayBases(root: JSONObject?): Map<MonthKey, MonthlyPayBases> {
        if (root == null) return emptyMap()
        val parsed = mutableMapOf<MonthKey, MonthlyPayBases>()
        forEachMonthlyPayBaseObject(root) { year, month, data ->
            parseMonthlyPayBase(year, month, data)?.let { bases ->
                parsed[MonthKey(year, month)] = bases
            }
        }
        return parsed
    }

    private fun forEachMonthlyPayBaseObject(root: JSONObject, block: (Int, Int, JSONObject) -> Unit) {
        val yearKeys = root.keys()
        while (yearKeys.hasNext()) {
            val yearKey = yearKeys.next()
            val year = yearKey.toIntOrNull() ?: continue
            if (year < 1900 || year > 2200) continue
            val monthsObj = root.optJSONObject(yearKey) ?: continue
            val monthKeys = monthsObj.keys()
            while (monthKeys.hasNext()) {
                val monthKey = monthKeys.next()
                val month = monthKey.toIntOrNull() ?: continue
                if (month !in 1..12) continue
                val data = monthsObj.optJSONObject(monthKey) ?: continue
                block(year, month, data)
            }
        }
    }

    private fun parseMonthlyPayBase(year: Int, month: Int, data: JSONObject): MonthlyPayBases? {
        val selectionMode = data.optString(SELECTION_MODE_KEY, "")
            .takeIf { it.isNotBlank() }
            ?.let { runCatching { PayBaseSelectionMode.valueOf(it) }.getOrNull() }
        val bases = MonthlyPayBases(
            year = year,
            month = month,
            vacationHourlyBase = data.optPositiveFiniteDoubleOrNull(VACATION_HOURLY_BASE_KEY),
            paidHolidayHourlyBase = data.optPositiveFiniteDoubleOrNull(PAID_HOLIDAY_HOURLY_BASE_KEY),
            sickHourlyBase = data.optPositiveFiniteDoubleOrNull(SICK_HOURLY_BASE_KEY),
            selectionMode = selectionMode
        )
        return bases.takeIf { it.hasAnyConfiguredPayBase() }
    }

    private fun MonthlyPayBases.hasAnyConfiguredPayBase(): Boolean {
        return vacationHourlyBase != null ||
            paidHolidayHourlyBase != null ||
            sickHourlyBase != null ||
            selectionMode != null
    }

    private fun buildMonthlyNetAdjustmentsMapUnsafe(snapshot: ShiftRepositorySnapshot): TreeMap<String, Any> {
        val root = TreeMap<String, TreeMap<String, Any>>()
        for ((key, adjustments) in snapshot.monthlyNetAdjustments.toSortedMap()) {
            val sanitized = adjustments.sanitizedFor(key.year, key.month)
            if (!sanitized.hasAny) continue
            val yearMap = root.getOrPut(key.year.toString()) { TreeMap() }
            yearMap[String.format(Locale.ROOT, "%02d", key.month)] = TreeMap<String, Any>().apply {
                put(ADVANCE_PAYMENT_NET_KEY, sanitized.advancePaymentNet)
            }
        }
        return TreeMap(root)
    }

    private fun parseMonthlyNetAdjustments(root: JSONObject?): Map<MonthKey, MonthlyNetAdjustments> {
        if (root == null) return emptyMap()
        val parsed = mutableMapOf<MonthKey, MonthlyNetAdjustments>()
        val yearKeys = root.keys()
        while (yearKeys.hasNext()) {
            val yearKey = yearKeys.next()
            val year = yearKey.toIntOrNull() ?: continue
            if (year < 1900 || year > 2200) continue
            val monthsObj = root.optJSONObject(yearKey) ?: continue
            val monthKeys = monthsObj.keys()
            while (monthKeys.hasNext()) {
                val monthKey = monthKeys.next()
                val month = monthKey.toIntOrNull() ?: continue
                if (month !in 1..12) continue
                val data = monthsObj.optJSONObject(monthKey) ?: continue
                val adjustments = MonthlyNetAdjustments(
                    year = year,
                    month = month,
                    advancePaymentNet = data.optMonthlyNetAdjustment(ADVANCE_PAYMENT_NET_KEY)
                ).sanitizedFor(year, month)
                if (adjustments.hasAny) {
                    parsed[MonthKey(year, month)] = adjustments
                }
            }
        }
        return parsed
    }

    private fun buildHourlyValueChangesMapUnsafe(snapshot: ShiftRepositorySnapshot): TreeMap<String, Any> {
        val root = TreeMap<String, TreeMap<String, Any>>()
        for ((key, value) in snapshot.hourlyValueChanges.toSortedMap()) {
            val yearMap = root.getOrPut(key.year.toString()) { TreeMap() }
            yearMap[String.format(Locale.ROOT, "%02d", key.month)] = TreeMap<String, Any>().apply {
                put(HOURLY_VALUE_KEY, value)
            }
        }
        return TreeMap(root)
    }

    private fun parseHourlyValueChanges(root: JSONObject?): Map<MonthKey, Double> {
        if (root == null) return emptyMap()
        val parsed = mutableMapOf<MonthKey, Double>()
        val yearKeys = root.keys()
        while (yearKeys.hasNext()) {
            val yearKey = yearKeys.next()
            val year = yearKey.toIntOrNull() ?: continue
            if (year < 1900 || year > 2200) continue
            val monthsObj = root.optJSONObject(yearKey) ?: continue
            val monthKeys = monthsObj.keys()
            while (monthKeys.hasNext()) {
                val monthKey = monthKeys.next()
                val month = monthKey.toIntOrNull() ?: continue
                if (month !in 1..12) continue
                val value = monthsObj.optJSONObject(monthKey)
                    ?.optDouble(HOURLY_VALUE_KEY, Double.NaN)
                    ?: continue
                if (value.isFinite() && value > 0.0) {
                    parsed[MonthKey(year, month)] = value.coerceAtMost(MAX_BASE_PAY)
                }
            }
        }
        return parsed
    }

    private fun buildFoodPerDayChangesMapUnsafe(snapshot: ShiftRepositorySnapshot): TreeMap<String, Any> {
        val root = TreeMap<String, TreeMap<String, Any>>()
        for ((key, value) in snapshot.foodPerDayChanges.toSortedMap()) {
            val yearMap = root.getOrPut(key.year.toString()) { TreeMap() }
            yearMap[String.format(Locale.ROOT, "%02d", key.month)] = TreeMap<String, Any>().apply {
                put(FOOD_PER_DAY_VALUE_KEY, value)
            }
        }
        return TreeMap(root)
    }

    private fun parseFoodPerDayChanges(root: JSONObject?): Map<MonthKey, Double> {
        if (root == null) return emptyMap()
        val parsed = mutableMapOf<MonthKey, Double>()
        val yearKeys = root.keys()
        while (yearKeys.hasNext()) {
            val yearKey = yearKeys.next()
            val year = yearKey.toIntOrNull() ?: continue
            if (year < 1900 || year > 2200) continue
            val monthsObj = root.optJSONObject(yearKey) ?: continue
            val monthKeys = monthsObj.keys()
            while (monthKeys.hasNext()) {
                val monthKey = monthKeys.next()
                val month = monthKey.toIntOrNull() ?: continue
                if (month !in 1..12) continue
                val value = monthsObj.optJSONObject(monthKey)
                    ?.optDouble(FOOD_PER_DAY_VALUE_KEY, Double.NaN)
                    ?: continue
                if (value.isFinite() && value >= 0.0) {
                    parsed[MonthKey(year, month)] = value.coerceAtMost(10_000.0)
                }
            }
        }
        return parsed
    }

    private data class ParsedAutoAdvanceImportFlags(
        val enabled: Boolean,
        val promptSuppressed: Boolean
    )

    private data class ParsedAnnualLeaveCalculation(
        val input: AnnualLeaveCalculationInput?,
        val lastCalculatedDays: Int
    )

    private companion object {
        const val BASE_PAY_KEY = "config_base_pay_hourly"
        const val GROSS_HOURLY_RATE_KEY = "config_gross_hourly_rate"
        const val PAY_COEFFICIENT_KEY = "pay_coefficient"
        const val HOURLY_VALUE_CHANGES_KEY = "hourly_value_changes"
        const val HOURLY_VALUE_KEY = "hourly_value"
        const val FOOD_PER_DAY_CHANGES_KEY = "food_per_day_changes"
        const val FOOD_PER_DAY_VALUE_KEY = "food_per_day"
        const val SICK_PAY_BASE_HOURLY_KEY = "config_sick_pay_base_hourly"
        const val DEFAULT_PAY_BASE_SELECTION_MODE_KEY = "default_pay_base_selection_mode"
        const val EMPLOYEE_CONTRIBUTIONS_PERCENT_KEY = "config_employee_contributions_percent"
        const val TAX_RELIEF_KEY = "config_tax_relief"
        const val TAX_PERCENT_KEY = "config_tax_percent"
        const val DIFFICULT_WORK_CONDITIONS_PERCENT_KEY = "config_difficult_work_conditions_percent"
        const val INITIAL_COMP_BALANCE_KEY = "initial_comp_balance"
        const val INITIAL_COMP_BALANCE_MINUTES_KEY = "initial_comp_balance_minutes"
        const val YEARS_OF_SERVICE_KEY = "years_of_service"
        const val MINULI_RAD_START_YEAR_KEY = "minuli_rad_start_year"
        const val MINULI_RAD_START_MONTH_KEY = "minuli_rad_start_month"
        const val SENIORITY_INPUT_MODE_KEY = "seniority_input_mode"
        const val MINULI_RAD_BENEFIT_ENABLED_KEY = "minuli_rad_benefit_enabled"
        const val MINULI_RAD_INCREASE_FACTOR_KEY = "minuli_rad_increase_factor"
        const val MINULI_RAD_LAST_PAYSLIP_YEAR_KEY = "minuli_rad_last_payslip_year"
        const val MINULI_RAD_LAST_PAYSLIP_MONTH_KEY = "minuli_rad_last_payslip_month"
        const val MINULI_RAD_PAYSLIP_PERCENT_KEY = "minuli_rad_payslip_percent"
        const val BRIGADE_TYPE_KEY = "brigade_type"
        const val FOOD_PER_DAY_KEY = "config_food_per_day"
        const val TRANSPORT_DAILY_AMOUNT_KEY = "config_transport_per_work_day"
        // Ručni kredit je ukinut (obustave idu sa listića); ključevi ostaju da se stari backup ne čita kao dan.
        const val CREDIT_DEDUCTION_ENABLED_KEY = "creditDeductionEnabled"
        const val MONTHLY_CREDIT_DEDUCTION_AMOUNT_KEY = "monthlyCreditDeductionAmount"
        // Ukinuto „Član sindikata“ (obustave u % idu sa listića): stari backup ih ima, ne čitaju se.
        const val UNION_MEMBER_KEY = "unionMember"
        const val UNION_DUES_RATE_KEY = "unionDuesRate"
        /** Ukinut podsetnik za račun hrane: ključ se više ne piše, ali stari JSON ga još ima. */
        const val FOOD_RECEIPT_REMINDER_ENABLED_KEY = "food_receipt_reminder_enabled"
        const val AUTO_ADVANCE_IMPORT_ENABLED_KEY = "auto_advance_import_enabled"
        const val NOTIFICATION_ACCESS_PROMPT_SUPPRESSED_KEY = "notification_access_prompt_suppressed"
        /** Stari bekapi nemaju ovaj ključ — prikaz ispod kalendara ostaje sa prevozom kao i do sada. */
        const val MAIN_PAY_INCLUDES_TRANSPORT_KEY = "main_pay_includes_transport"
        const val BLOOD_DONATION_LAST_DATE_KEY = "blood_donation_last_date"
        const val BLOOD_DONATION_COUNT_KEY = "blood_donation_count"
        const val BLOOD_DONATION_HISTORY_KEY = "blood_donation_history"
        const val BLOOD_DONATION_LEAVE_KEY = "blood_donation_leave"
        const val DELETED_MONTHS_KEY = "deleted_months"
        const val DELETED_HOURLY_VALUE_MONTHS_KEY = "deleted_hourly_value_months"
        const val DELETED_PAYSLIP_MONTHS_KEY = "deleted_payslip_months"
        const val INITIAL_VACATION_BALANCE_KEY = "initial_vacation_balance"
        const val VACATION_YEAR_BALANCES_KEY = "vacation_year_balances"
        const val ANNUAL_LEAVE_CALCULATION_KEY = "annual_leave_calculation"
        const val MONTHLY_PAY_BASES_KEY = "monthly_pay_bases"
        const val MONTHLY_GROSS_ADDITIONS_KEY = "monthly_gross_additions"
        const val MONTHLY_NET_ADJUSTMENTS_KEY = "monthly_net_adjustments"
        const val VACATION_TRACKING_START_DATE_KEY = "tracking_start_date"
        const val VACATION_CARRYOVER_DAYS_INITIAL_KEY = "carryover_days_initial"
        const val VACATION_CURRENT_YEAR_DAYS_TOTAL_KEY = "current_year_days_total"
        const val VACATION_CURRENT_YEAR_USED_BEFORE_AVAILABILITY_KEY =
            "current_year_days_used_before_availability"
        const val VACATION_CURRENT_YEAR_AVAILABLE_FROM_KEY = "current_year_available_from"
        const val VACATION_MANUAL_BASELINE_KEY = "manual_baseline"
        const val VACATION_CARRYOVER_FROZEN_KEY = "carryover_frozen"
        const val ANNUAL_LEAVE_TOTAL_SENIORITY_YEARS_KEY = "total_seniority_years"
        const val ANNUAL_LEAVE_YEARS_AT_EMPLOYER_KEY = "years_at_employer"
        const val ANNUAL_LEAVE_INCREASED_RISK_KEY = "increased_risk_or_benefited_seniority"
        const val ANNUAL_LEAVE_NIGHT_WORK_KEY = "night_work_shift_system"
        const val ANNUAL_LEAVE_WEEKEND_WORK_KEY = "weekend_work_shift_system"
        const val ANNUAL_LEAVE_THIRD_DEGREE_KEY = "requires_at_least_third_degree"
        const val ANNUAL_LEAVE_WORK_INVALID_KEY = "work_invalid_from_work_injury_or_disease"
        const val ANNUAL_LEAVE_WAR_INVALID_KEY = "war_military_invalid"
        const val ANNUAL_LEAVE_SINGLE_PARENT_KEY = "single_parent_child_under_14"
        const val ANNUAL_LEAVE_THREE_CHILDREN_KEY = "three_or_more_children_under_14"
        const val ANNUAL_LEAVE_LAST_CALCULATED_DAYS_KEY = "last_calculated_days"
        const val VACATION_HOURLY_BASE_KEY = "vacation_hourly_base"
        const val PAID_HOLIDAY_HOURLY_BASE_KEY = "paid_holiday_hourly_base"
        const val SICK_HOURLY_BASE_KEY = "sick_hourly_base"
        const val SELECTION_MODE_KEY = "selection_mode"
        const val ADVANCE_PAYMENT_NET_KEY = "advance_payment_net"
        val METADATA_KEYS = setOf(
            BASE_PAY_KEY,
            INITIAL_COMP_BALANCE_KEY,
            YEARS_OF_SERVICE_KEY,
            MINULI_RAD_START_YEAR_KEY,
            MINULI_RAD_START_MONTH_KEY,
            SENIORITY_INPUT_MODE_KEY,
            MINULI_RAD_BENEFIT_ENABLED_KEY,
            MINULI_RAD_INCREASE_FACTOR_KEY,
            MINULI_RAD_LAST_PAYSLIP_YEAR_KEY,
            MINULI_RAD_LAST_PAYSLIP_MONTH_KEY,
            MINULI_RAD_PAYSLIP_PERCENT_KEY,
            BRIGADE_TYPE_KEY,
            FOOD_PER_DAY_KEY,
            TRANSPORT_DAILY_AMOUNT_KEY,
            CREDIT_DEDUCTION_ENABLED_KEY,
            MONTHLY_CREDIT_DEDUCTION_AMOUNT_KEY,
            UNION_MEMBER_KEY,
            UNION_DUES_RATE_KEY,
            FOOD_RECEIPT_REMINDER_ENABLED_KEY,
            AUTO_ADVANCE_IMPORT_ENABLED_KEY,
            NOTIFICATION_ACCESS_PROMPT_SUPPRESSED_KEY,
            BLOOD_DONATION_LAST_DATE_KEY,
            BLOOD_DONATION_COUNT_KEY,
            BLOOD_DONATION_LEAVE_KEY,
            DELETED_MONTHS_KEY,
            DELETED_HOURLY_VALUE_MONTHS_KEY,
            DELETED_PAYSLIP_MONTHS_KEY,
            INITIAL_VACATION_BALANCE_KEY,
            VACATION_YEAR_BALANCES_KEY,
            ANNUAL_LEAVE_CALCULATION_KEY,
            MONTHLY_PAY_BASES_KEY,
            MONTHLY_GROSS_ADDITIONS_KEY,
            MONTHLY_NET_ADJUSTMENTS_KEY,
            PAY_COEFFICIENT_KEY,
            HOURLY_VALUE_CHANGES_KEY,
            RegressNetChangesJsonCodec.KEY,
            GROSS_HOURLY_RATE_KEY,
            SICK_PAY_BASE_HOURLY_KEY,
            DEFAULT_PAY_BASE_SELECTION_MODE_KEY,
            KnownPayBaseAnchorsJsonCodec.LEGACY_MONTH_KEY,
            KnownPayBaseAnchorsJsonCodec.LEGACY_GROSS_HOURLY_KEY,
            KnownPayBaseAnchorsJsonCodec.ANCHORS_KEY,
            EMPLOYEE_CONTRIBUTIONS_PERCENT_KEY,
            TAX_RELIEF_KEY,
            TAX_PERCENT_KEY,
            DIFFICULT_WORK_CONDITIONS_PERCENT_KEY,
            "comp_time_balance"
        )

        /** Svi ključevi koje aplikacija piše ili je ikad pisala (osim godina sa zapisima i verzije sheme). */
        val RECOGNIZED_KEYS = METADATA_KEYS + setOf(
            FOOD_PER_DAY_CHANGES_KEY,
            MAIN_PAY_INCLUDES_TRANSPORT_KEY,
            BLOOD_DONATION_HISTORY_KEY,
            SavedPayslipsJsonCodec.KEY
        )
    }
}

@Suppress("DEPRECATION")
private fun buildMonthlyGrossAdditionsMapUnsafe(snapshot: ShiftRepositorySnapshot): TreeMap<String, Any> {
    val root = TreeMap<String, TreeMap<String, Any>>()
    for ((key, additions) in snapshot.monthlyGrossAdditions.toSortedMap()) {
        val sanitized = additions.sanitizeMonthlyGrossAdditions(key.year, key.month)
        if (!sanitized.hasAny) continue
        val yearMap = root.getOrPut(key.year.toString()) { TreeMap() }
        val monthData = TreeMap<String, Any>()
        sanitized.mealGross?.let { monthData[MEAL_GROSS_KEY] = it }
        if (sanitized.regressGross > 0.0) monthData[REGRESS_GROSS_KEY] = sanitized.regressGross
        if (sanitized.bonusGross > 0.0) monthData[BONUS_GROSS_KEY] = sanitized.bonusGross
        sanitized.mealSpentNet?.let { monthData[MEAL_SPENT_NET_KEY] = it }
        sanitized.mealEntitlementNetOverride?.let { monthData[MEAL_ENTITLEMENT_NET_OVERRIDE_KEY] = it }
        if (sanitized.bonusNet > 0.0) monthData[BONUS_NET_KEY] = sanitized.bonusNet
        if (sanitized.absencePenaltyGross.signum() > 0) {
            monthData[ABSENCE_PENALTY_GROSS_KEY] = sanitized.absencePenaltyGross
        }
        monthData.putIfNonZero(MEAL_SETTLEMENT_ADJUSTMENT_NET_KEY, sanitized.mealSettlementAdjustmentNet)
        if (monthData.isNotEmpty()) {
            yearMap[String.format(Locale.ROOT, "%02d", key.month)] = monthData
        }
    }
    return TreeMap(root)
}

@Suppress("DEPRECATION")
private fun parseMonthlyGrossAdditions(root: JSONObject?): Map<MonthKey, MonthlyGrossAdditions> {
    if (root == null) return emptyMap()
    val parsed = mutableMapOf<MonthKey, MonthlyGrossAdditions>()
    val yearKeys = root.keys()
    while (yearKeys.hasNext()) {
        val yearKey = yearKeys.next()
        val year = yearKey.toIntOrNull() ?: continue
        if (year < 1900 || year > 2200) continue
        val monthsObj = root.optJSONObject(yearKey) ?: continue
        val monthKeys = monthsObj.keys()
        while (monthKeys.hasNext()) {
            val monthKey = monthKeys.next()
            val month = monthKey.toIntOrNull() ?: continue
            if (month !in 1..12) continue
            val data = monthsObj.optJSONObject(monthKey) ?: continue
            val additions = MonthlyGrossAdditions(
                year = year,
                month = month,
                mealGross = data.optMonthlyGrossOverrideOrNull(MEAL_GROSS_KEY),
                regressGross = data.optMonthlyGrossAddition(REGRESS_GROSS_KEY),
                bonusGross = data.optMonthlyGrossAddition(BONUS_GROSS_KEY),
                mealSpentNet = data.optMonthlyNetDeductionOrNull(MEAL_SPENT_NET_KEY),
                bonusNet = data.optMonthlyGrossAddition(BONUS_NET_KEY),
                mealSettlementAdjustmentNet = data.optMonthlySignedAdjustment(MEAL_SETTLEMENT_ADJUSTMENT_NET_KEY),
                absencePenaltyGross = data.optMonthlyGrossPenalty(ABSENCE_PENALTY_GROSS_KEY),
                mealEntitlementNetOverride = data.optMonthlyMoneyOrNull(MEAL_ENTITLEMENT_NET_OVERRIDE_KEY)
            ).sanitizeMonthlyGrossAdditions(year, month)
            if (additions.hasAny) {
                parsed[MonthKey(year, month)] = additions
            }
        }
    }
    return parsed
}

private fun JSONObject.optMonthlyGrossPenalty(key: String): BigDecimal {
    if (!has(key)) return BigDecimal.ZERO
    return optString(key).toBigDecimalOrNull()?.sanitizeMonthlyGrossPenalty() ?: BigDecimal.ZERO
}

private fun JSONObject.optMonthlyMoneyOrNull(key: String): BigDecimal? {
    if (!has(key) || isNull(key)) return null
    return optString(key).toBigDecimalOrNull()?.takeIf { it.signum() >= 0 }?.toMealMoney()
}

private fun JSONObject.optPositiveFiniteDoubleOrNull(key: String): Double? {
    if (!has(key)) return null
    val value = optDouble(key, Double.NaN)
    return value.sanitizePayBaseOrNull()
}

private fun JSONObject.optMonthlyGrossAddition(key: String): Double {
    if (!has(key)) return 0.0
    val value = optDouble(key, Double.NaN)
    return value.sanitizeMonthlyGrossAddition()
}

private fun MutableMap<String, Any>.putIfNonZero(key: String, value: Double) {
    if (value != 0.0) this[key] = value
}

private fun JSONObject.optMonthlyGrossOverrideOrNull(key: String): Double? {
    if (!has(key)) return null
    val value = optDouble(key, Double.NaN)
    return value.sanitizeMonthlyGrossOverrideOrNull()
}

private fun JSONObject.optMonthlyNetDeductionOrNull(key: String): Double? {
    if (!has(key)) return null
    val value = optDouble(key, Double.NaN)
    return value.sanitizeMonthlyGrossAddition()
}

private fun JSONObject.optMonthlySignedAdjustment(key: String): Double {
    if (!has(key)) return 0.0
    return optDouble(key, Double.NaN).sanitizeMonthlySignedAdjustment()
}

private fun JSONObject.optMonthlyNetAdjustment(key: String): Double {
    if (!has(key)) return 0.0
    val value = optDouble(key, Double.NaN)
    return value.sanitizeMonthlyNetAdjustment()
}

private fun JSONObject.optLocalDateOrNull(key: String): LocalDate? {
    if (!has(key)) return null
    return runCatching { LocalDate.parse(optString(key)) }.getOrNull()
}
