package com.rad.shifttracker.ui

import android.content.Context
import com.rad.shifttracker.BuildConfig
import com.rad.shifttracker.data.backup.BackupOutcome
import com.rad.shifttracker.data.backup.GoogleDriveBackup
import com.rad.shifttracker.godisnji.AnnualLeaveCalculationInput
import com.rad.shifttracker.godisnji.VacationYearBalance
import com.rad.shifttracker.listici.ParsedPayslip
import com.rad.shifttracker.listici.PayslipParseResult
import com.rad.shifttracker.listici.PayslipSnapshotStore
import com.rad.shifttracker.obracun.SeniorityIncreaseFactor
import com.rad.shifttracker.obracun.SeniorityInputMode
import com.rad.shifttracker.repo.ShiftRepository
import com.rad.shifttracker.repo.applyBloodDonationLeave
import com.rad.shifttracker.repo.applySickRange
import com.rad.shifttracker.repo.applyVacationRange
import com.rad.shifttracker.repo.buildCloudBackup
import com.rad.shifttracker.repo.clearBloodDonationLeaveForDate
import com.rad.shifttracker.repo.deleteBloodDonation
import com.rad.shifttracker.repo.deletePayslip
import com.rad.shifttracker.repo.importFromJson
import com.rad.shifttracker.repo.initialCompBalanceMinutesValue
import com.rad.shifttracker.repo.minuliRadBenefitEnabledValue
import com.rad.shifttracker.repo.minuliRadIncreaseFactorValue
import com.rad.shifttracker.repo.minuliRadLastPayslipMonthValue
import com.rad.shifttracker.repo.minuliRadLastPayslipYearValue
import com.rad.shifttracker.repo.minuliRadPayslipPercentValue
import com.rad.shifttracker.repo.minuliRadStartMonthValue
import com.rad.shifttracker.repo.minuliRadStartYearValue
import com.rad.shifttracker.repo.AUTO_FILL_MONTHS_AHEAD
import com.rad.shifttracker.repo.AutoFillPatternStore
import com.rad.shifttracker.repo.populateMonthsAhead
import com.rad.shifttracker.repo.recordBloodDonation
import com.rad.shifttracker.repo.savePayslip
import com.rad.shifttracker.repo.seniorityInputModeValue
import com.rad.shifttracker.repo.setAnnualLeaveCalculation
import com.rad.shifttracker.repo.setFoodPerDayFromMonth
import com.rad.shifttracker.repo.setMonthlyAdvancePaymentNet
import com.rad.shifttracker.repo.setVacationYearBalance
import com.rad.shifttracker.smene.DayRecord
import com.rad.shifttracker.smene.MonthKey
import com.rad.shifttracker.smene.ShiftFillSettings
import com.rad.shifttracker.smene.ShiftScheduler
import com.rad.shifttracker.uvoz.FillAllResult
import com.rad.shifttracker.uvoz.GmailImportRunner
import com.rad.shifttracker.uvoz.MonthFillResult
import com.rad.shifttracker.uvoz.PayslipChange
import com.rad.shifttracker.uvoz.applyMonthFill
import com.rad.shifttracker.uvoz.applyPayslipChanges
import com.rad.shifttracker.uvoz.fillAllMonths
import com.rad.shifttracker.uvoz.importManualPayslip
import com.rad.shifttracker.uvoz.importManualPayslips
import java.time.LocalDate

/**
 * Svi upisi koje ekran i dijalozi rade nad podacima. Dijalozi repozitorijum samo čitaju, a menjaju
 * ga isključivo preko ovoga (implementira [MainViewModel]); strukturni test čuva to pravilo.
 */
internal interface DataActions : DayActions, SettingsActions, PayslipActions

/** Upisi dana u kalendaru: smene, odsustva, davanje krvi i brisanje. */
internal interface DayActions {
    fun saveDay(record: DayRecord)
    fun applySickRange(scheduler: ShiftScheduler, year: Int, month: Int, startDay: Int, endDay: Int, sickPayRate: Double?)
    fun applyVacationRange(scheduler: ShiftScheduler, year: Int, month: Int, startDay: Int, endDay: Int)
    /** „Popuni smene“: presnimi raspored od danas i drži ga popunjenim unapred (kontinualno). */
    fun populateAutoFill(scheduler: ShiftScheduler, today: LocalDate, settings: ShiftFillSettings)
    fun deleteMonth(year: Int, month: Int)
    fun deleteAllData()
    fun recordBloodDonation(date: LocalDate)
    fun deleteBloodDonation(date: LocalDate): Boolean
    fun applyBloodDonationLeave(firstDay: LocalDate)
    fun clearBloodDonationLeaveForDate(date: LocalDate)
}

/** Podešavanja obračuna, praznika i godišnjeg. */
internal interface SettingsActions {
    fun setMonthlyAdvancePaymentNet(year: Int, month: Int, amount: Double)
    fun setFoodPerDayFromMonth(year: Int, month: Int, value: Double)
    fun setHolidayActive(year: Int, month: Int, day: Int, isActive: Boolean)
    fun setInitialCompBalanceMinutes(minutes: Int)
    fun saveSeniority(settings: SenioritySettings)
    fun setVacationYearBalance(balance: VacationYearBalance)
    fun setAnnualLeaveCalculation(input: AnnualLeaveCalculationInput, lastCalculatedDays: Int)
}

/** Listići (ručno, iz foldera i Gmail-a), popuna meseca po listiću i vraćanje iz oblaka. */
internal interface PayslipActions {
    fun savePayslip(payslip: ParsedPayslip)
    /** Izmene podešavanja sa listića, pa sam listić. */
    fun savePayslipWithChanges(payslip: ParsedPayslip, changes: List<PayslipChange>)
    /** Listić meseca i stari „poslednji listić“ za isti mesec; podešavanja upisana sa listića ostaju. */
    fun deletePayslip(month: MonthKey)
    fun applyMonthFill(result: MonthFillResult.Success)
    /** Dugo traje (solver po mesecu): zove se van glavne niti. */
    fun fillAllMonths(months: List<MonthKey>): FillAllResult
    /** Dugo traje: zove se van glavne niti. Vraća poruku i da li je greška. */
    fun importManualPayslip(result: PayslipParseResult): Pair<String, Boolean>
    /** Više lista odjednom (izbor više PDF-ova); van glavne niti. */
    fun importManualPayslips(results: List<PayslipParseResult>): Pair<String, Boolean>
    fun startGmailImport()
    /** Vraćanje iz oblaka uz sigurnosnu kopiju; vraća poruku za korisnika. */
    suspend fun restoreBackup(fetch: suspend () -> String?, messages: RestoreMessages): String
}

/** Podešavanja minulog rada iz dijaloga; upisuju se samo polja izabranog načina unosa. */
internal data class SenioritySettings(
    val mode: SeniorityInputMode,
    val increaseFactor: SeniorityIncreaseFactor,
    val startYear: Int,
    val startMonth: Int,
    val benefitEnabled: Boolean,
    val lastPayslipYear: Int,
    val lastPayslipMonth: Int,
    val payslipPercent: Double
)

/** Jedino mesto u UI sloju koje menja [repo]. */
internal class RepoDataActions(
    private val repo: ShiftRepository,
    private val backup: GoogleDriveBackup?,
    private val appContext: Context
) : DataActions {
    override fun saveDay(record: DayRecord) {
        repo.set(
            year = record.year,
            month = record.month,
            day = record.day,
            shift = record.shift,
            hours = record.hours,
            shift2 = record.shift2,
            hours2 = record.hours2,
            overtimeType = record.overtimeType,
            weekendOvertimeType = record.weekendOvertimeType,
            compHoursUsed = record.compHoursUsed,
            holidayWorkOverride = record.holidayWorkOverride,
            holidayOverride = record.holidayOverride,
            sickPayRate = record.sickPayRate,
            balanceExempt = record.balanceExempt,
            regularShortageMinutes = record.regularShortageMinutes,
            source = record.source
        )
    }

    override fun applySickRange(scheduler: ShiftScheduler, year: Int, month: Int, startDay: Int, endDay: Int, sickPayRate: Double?) {
        scheduler.applySickRange(repo, year, month, startDay, endDay, sickPayRate)
    }

    override fun applyVacationRange(scheduler: ShiftScheduler, year: Int, month: Int, startDay: Int, endDay: Int) {
        scheduler.applyVacationRange(repo, year, month, startDay, endDay)
    }

    override fun populateAutoFill(scheduler: ShiftScheduler, today: LocalDate, settings: ShiftFillSettings) {
        scheduler.populateMonthsAhead(repo, today, settings, AUTO_FILL_MONTHS_AHEAD)
        // Isti obrazac posle sam produžava raspored pri pokretanju aplikacije.
        AutoFillPatternStore.save(appContext, settings)
    }

    override fun deleteMonth(year: Int, month: Int) {
        repo.deleteMonth(year, month)
    }

    override fun deleteAllData() {
        repo.deleteAllData()
    }

    override fun recordBloodDonation(date: LocalDate) = repo.recordBloodDonation(date)

    override fun deleteBloodDonation(date: LocalDate): Boolean = repo.deleteBloodDonation(date)

    override fun applyBloodDonationLeave(firstDay: LocalDate) {
        repo.applyBloodDonationLeave(firstDay)
    }

    override fun clearBloodDonationLeaveForDate(date: LocalDate) {
        repo.clearBloodDonationLeaveForDate(date)
    }

    override fun setMonthlyAdvancePaymentNet(year: Int, month: Int, amount: Double) =
        repo.setMonthlyAdvancePaymentNet(year, month, amount)

    override fun setFoodPerDayFromMonth(year: Int, month: Int, value: Double) =
        repo.setFoodPerDayFromMonth(year, month, value)

    override fun setHolidayActive(year: Int, month: Int, day: Int, isActive: Boolean) {
        repo.setHolidayActive(year, month, day, isActive)
    }

    override fun setInitialCompBalanceMinutes(minutes: Int) {
        repo.initialCompBalanceMinutesValue = minutes
    }

    override fun saveSeniority(settings: SenioritySettings) {
        repo.seniorityInputModeValue = settings.mode
        repo.minuliRadIncreaseFactorValue = settings.increaseFactor
        when (settings.mode) {
            SeniorityInputMode.KNOWN_START -> {
                repo.minuliRadStartYearValue = settings.startYear
                repo.minuliRadStartMonthValue = settings.startMonth
                repo.minuliRadBenefitEnabledValue = settings.benefitEnabled
            }

            SeniorityInputMode.PAYSLIP_PERCENT -> {
                repo.minuliRadBenefitEnabledValue = false
                repo.minuliRadLastPayslipYearValue = settings.lastPayslipYear
                repo.minuliRadLastPayslipMonthValue = settings.lastPayslipMonth
                repo.minuliRadPayslipPercentValue = settings.payslipPercent
            }
        }
    }

    override fun setVacationYearBalance(balance: VacationYearBalance) = repo.setVacationYearBalance(balance)

    override fun setAnnualLeaveCalculation(input: AnnualLeaveCalculationInput, lastCalculatedDays: Int) =
        repo.setAnnualLeaveCalculation(input, lastCalculatedDays)

    override fun savePayslip(payslip: ParsedPayslip) = repo.savePayslip(payslip)

    override fun savePayslipWithChanges(payslip: ParsedPayslip, changes: List<PayslipChange>) {
        applyPayslipChanges(repo, changes)
        repo.savePayslip(payslip)
    }

    override fun deletePayslip(month: MonthKey) {
        repo.deletePayslip(month)
        val store = PayslipSnapshotStore(appContext)
        if (store.load()?.month == month) store.clear()
    }

    override fun applyMonthFill(result: MonthFillResult.Success) = applyMonthFill(repo, result)

    override fun fillAllMonths(months: List<MonthKey>): FillAllResult = fillAllMonths(repo, months)

    override fun importManualPayslip(result: PayslipParseResult): Pair<String, Boolean> = importManualPayslip(repo, result)

    override fun importManualPayslips(results: List<PayslipParseResult>): Pair<String, Boolean> =
        importManualPayslips(repo, results)

    override fun startGmailImport() = GmailImportRunner.start(appContext, repo)

    /**
     * Cilj se skida PRE pravljenja kopije: inače bi „najnovija verzija" bila upravo ta kopija
     * (sveže stanje spojeno sa arhivom), pa bi se na novoj instalaciji vratilo pola podataka. Ako
     * kopija ne uspe, vraćanje se prekida (UI obećava da je poništivo, pa ne sme da nastavi bez kopije).
     */
    override suspend fun restoreBackup(fetch: suspend () -> String?, messages: RestoreMessages): String {
        val backup = backup ?: return messages.failure
        val json = fetch() ?: return messages.missing
        val safetyOutcome = backup.backupVersioned(appVersion = BuildConfig.VERSION_NAME, safety = true) { existing ->
            repo.buildCloudBackup(existing)
        }
        if (safetyOutcome is BackupOutcome.Failure) {
            return "Vraćanje otkazano — sigurnosna kopija nije uspela: ${safetyOutcome.message}"
        }
        if (!repo.importFromJson(json)) return messages.failure
        // Uspešan uvoz = stanje je sada nastavak oblaka, pa auto-backup sme da krene.
        backup.markCloudAdopted()
        return messages.success
    }
}
