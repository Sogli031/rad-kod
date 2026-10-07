package com.rad.shifttracker.repo

import android.util.Log
import com.rad.shifttracker.osnova.AppConfig
import com.rad.shifttracker.smene.BrigadeType
import com.rad.shifttracker.smene.DayRecord
import com.rad.shifttracker.smene.MonthKey
import com.rad.shifttracker.obracun.PayBaseSelectionMode
import com.rad.shifttracker.obracun.SeniorityIncreaseFactor
import com.rad.shifttracker.obracun.SeniorityInputMode
import com.rad.shifttracker.obracun.coefficientFromLegacyGrossHourlyRate
import org.json.JSONArray
import org.json.JSONObject
import java.time.Clock
import java.time.LocalDate
import java.time.YearMonth
import java.util.Locale
import java.util.TreeMap
import com.rad.shifttracker.data.storage.BloodDonationLeaveJsonCodec
import com.rad.shifttracker.data.storage.JsonWriter
import com.rad.shifttracker.data.storage.KnownPayBaseAnchorsJsonCodec
import com.rad.shifttracker.data.storage.RegressNetChangesJsonCodec
import com.rad.shifttracker.data.storage.SCHEMA_VERSION_KEY
import com.rad.shifttracker.data.storage.SavedPayslipsJsonCodec
import com.rad.shifttracker.data.storage.SchemaMigrations
import com.rad.shifttracker.data.storage.ShiftRepositorySnapshot
import com.rad.shifttracker.data.storage.SnapshotDecodeResult
import com.rad.shifttracker.data.storage.SnapshotRejectedException
import com.rad.shifttracker.data.storage.SnapshotRejection
import com.rad.shifttracker.data.storage.readSchemaVersion

private const val TAG = "ShiftRepositoryJsonCodec"

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
            data[HOURLY_VALUE_CHANGES_KEY] = buildHourlyValueChangesMap(snapshot.hourlyValueChanges)
        }
        if (snapshot.foodPerDayChanges.isNotEmpty()) {
            data[FOOD_PER_DAY_CHANGES_KEY] = buildFoodPerDayChangesMap(snapshot.foodPerDayChanges)
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
        data[NOTIFICATION_ACCESS_PROMPT_SUPPRESSED_KEY] = snapshot.notificationAccessPromptSuppressed
        data[MAIN_PAY_SALARY_ONLY_KEY] = snapshot.mainPaySalaryOnly
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
            data[VACATION_YEAR_BALANCES_KEY] = buildVacationYearBalancesMap(snapshot.vacationYearBalances)
        }
        if (snapshot.annualLeaveCalculationInput != null || snapshot.annualLeaveLastCalculatedDays > 0) {
            data[ANNUAL_LEAVE_CALCULATION_KEY] = buildAnnualLeaveCalculationMap(
                snapshot.annualLeaveCalculationInput,
                snapshot.annualLeaveLastCalculatedDays
            )
        }

        if (snapshot.monthlyPayBases.isNotEmpty()) {
            data[MONTHLY_PAY_BASES_KEY] = buildMonthlyPayBasesMap(snapshot.monthlyPayBases)
        }
        if (snapshot.monthlyGrossAdditions.isNotEmpty()) {
            data[MONTHLY_GROSS_ADDITIONS_KEY] = buildMonthlyGrossAdditionsMap(snapshot.monthlyGrossAdditions)
        }
        if (snapshot.monthlyNetAdjustments.isNotEmpty()) {
            data[MONTHLY_NET_ADJUSTMENTS_KEY] = buildMonthlyNetAdjustmentsMap(snapshot.monthlyNetAdjustments)
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

    private fun parseJson(json: JSONObject): ShiftRepositorySnapshot {
        val parsedSickPayBaseHourly = json.optDouble(SICK_PAY_BASE_HOURLY_KEY, config.pay.sickPayBaseHourly)
        val parsedDefaultPayBaseSelectionMode = parseDefaultPayBaseSelectionMode(json)
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
        val parsedStartYear = parseMinuliRadStartYear(json)
        val parsedStartMonth = json.optInt(MINULI_RAD_START_MONTH_KEY, 2).coerceIn(1, 12)
        val parsedSeniorityInputMode = SeniorityInputMode.fromJson(json.optString(SENIORITY_INPUT_MODE_KEY, ""))
        val parsedMinuliRadBenefitEnabled = json.optBoolean(MINULI_RAD_BENEFIT_ENABLED_KEY, false)
        val parsedMinuliRadIncreaseFactor = SeniorityIncreaseFactor.fromJson(
            json.optString(MINULI_RAD_INCREASE_FACTOR_KEY, "")
        )
        val parsedLastPayslipYear = json.optInt(MINULI_RAD_LAST_PAYSLIP_YEAR_KEY, 0).coerceAtLeast(0)
        val parsedLastPayslipMonth = json.optInt(MINULI_RAD_LAST_PAYSLIP_MONTH_KEY, 1).coerceIn(1, 12)
        val parsedPayslipPercent = parsePayslipPercent(json)
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
            notificationAccessPromptSuppressed = parsedAutoAdvanceImport.promptSuppressed,
            mainPaySalaryOnly = json.optBoolean(MAIN_PAY_SALARY_ONLY_KEY, true),
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

    private fun parseDefaultPayBaseSelectionMode(json: JSONObject): PayBaseSelectionMode {
        val raw = json.optString(DEFAULT_PAY_BASE_SELECTION_MODE_KEY, "")
        if (raw.isEmpty()) return PayBaseSelectionMode.CALCULATED_PREVIOUS_12_MONTHS
        val parsed = runCatching { PayBaseSelectionMode.valueOf(raw) }.getOrNull()
        if (parsed == null) {
            Log.w(TAG, "Nepoznata vrednost za $DEFAULT_PAY_BASE_SELECTION_MODE_KEY: '$raw'.")
        }
        return parsed ?: PayBaseSelectionMode.CALCULATED_PREVIOUS_12_MONTHS
    }

    /** Stari backup ima samo broj godina staža; početak se procenjuje od današnjeg datuma. */
    private fun parseMinuliRadStartYear(json: JSONObject): Int {
        if (json.has(MINULI_RAD_START_YEAR_KEY)) return json.optInt(MINULI_RAD_START_YEAR_KEY, 0)
        val oldYears = json.optInt(YEARS_OF_SERVICE_KEY, 0)
        if (oldYears <= 0) return 0
        val now = LocalDate.now(clock)
        return if (now.monthValue >= 2) now.year - oldYears else now.year - 1 - oldYears
    }

    private fun parsePayslipPercent(json: JSONObject): Double {
        return json.optDouble(MINULI_RAD_PAYSLIP_PERCENT_KEY, 0.0)
            .takeIf { it.isFinite() }
            ?.coerceIn(0.0, 100.0)
            ?: 0.0
    }

    private fun parseCoefficient(json: JSONObject, representativeImportMonth: MonthKey): Double = when {
        json.has(PAY_COEFFICIENT_KEY) -> json.optDouble(PAY_COEFFICIENT_KEY, config.pay.defaultCoefficient)
        json.has(GROSS_HOURLY_RATE_KEY) -> coefficientFromLegacyGrossHourlyRate(
            json.optDouble(GROSS_HOURLY_RATE_KEY, config.pay.defaultBasePay),
            representativeImportMonth
        )
        else -> config.pay.defaultCoefficient
    }

    // Auto akontacija je uvek uključena; stari ključ AUTO_ADVANCE_IMPORT_ENABLED_KEY se ignoriše.
    private fun parseAutoAdvanceImportFlags(json: JSONObject) = ParsedAutoAdvanceImportFlags(
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

    private fun parseInitialCompBalanceMinutes(json: JSONObject, initialCompBalance: Int): Int {
        return if (json.has(INITIAL_COMP_BALANCE_MINUTES_KEY)) {
            json.optInt(INITIAL_COMP_BALANCE_MINUTES_KEY, initialCompBalance * 60)
        } else {
            initialCompBalance * 60
        }
    }

    private data class ParsedAutoAdvanceImportFlags(
        val promptSuppressed: Boolean
    )

    private companion object {
        const val BASE_PAY_KEY = "config_base_pay_hourly"
        const val GROSS_HOURLY_RATE_KEY = "config_gross_hourly_rate"
        const val PAY_COEFFICIENT_KEY = "pay_coefficient"
        const val HOURLY_VALUE_CHANGES_KEY = "hourly_value_changes"
        const val FOOD_PER_DAY_CHANGES_KEY = "food_per_day_changes"
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
        /** Bez ključa (stari bekapi) prikaz ispod kalendara je samo plata za rad, bez regresa i prevoza. */
        const val MAIN_PAY_SALARY_ONLY_KEY = "main_pay_salary_only"
        /** Ukinut prekidač „Prevoz u iznosu za isplatu“: ključ se više ne čita ni ne piše. */
        const val LEGACY_MAIN_PAY_INCLUDES_TRANSPORT_KEY = "main_pay_includes_transport"
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
            MAIN_PAY_SALARY_ONLY_KEY,
            LEGACY_MAIN_PAY_INCLUDES_TRANSPORT_KEY,
            BLOOD_DONATION_HISTORY_KEY,
            SavedPayslipsJsonCodec.KEY
        )
    }
}
