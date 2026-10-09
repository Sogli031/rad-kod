package com.rad.shifttracker.ui

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.rad.shifttracker.data.backup.GoogleDriveBackup
import com.rad.shifttracker.godisnji.VacationBalanceSummary
import com.rad.shifttracker.krv.BloodDonationReminderScheduleStatus
import com.rad.shifttracker.krv.BloodDonationReminderScheduler
import com.rad.shifttracker.obracun.MonthlyFinalPaymentBreakdown
import com.rad.shifttracker.obracun.MonthlyNetPayBreakdown
import com.rad.shifttracker.obracun.MonthlyOvertimeNetImpact
import com.rad.shifttracker.obracun.MonthlyPayDetails
import com.rad.shifttracker.obracun.MonthlyReceipt
import com.rad.shifttracker.repo.ShiftRepository
import com.rad.shifttracker.repo.getMonthlyFinalPaymentBreakdown
import com.rad.shifttracker.repo.getMonthlyOvertimeNetImpact
import com.rad.shifttracker.repo.getMonthlyPayDetails
import com.rad.shifttracker.repo.getMonthlyReceipt
import com.rad.shifttracker.repo.getNetPayBreakdown
import com.rad.shifttracker.repo.getSickLeaveNetDifference
import com.rad.shifttracker.repo.getSummary
import com.rad.shifttracker.repo.getVacationBalanceSummary
import com.rad.shifttracker.repo.mainPaySalaryOnly
import com.rad.shifttracker.smene.MonthKey
import com.rad.shifttracker.obracun.MonthSummary
import com.rad.shifttracker.repo.currentStatus

private const val KEY_YEAR = "calendar_year"
private const val KEY_MONTH = "calendar_month"
private const val MIN_MIDNIGHT_DELAY_MS = 60_000L

/**
 * Sve što glavni ekran računa nad podacima za izabrani mesec. Jedan objekat umesto ~20 upita u
 * kompoziciji; računa se na pozadinskoj niti i stiže kao tok.
 */
internal data class MainMonthState(
    val key: MainStateKey,
    val summary: MonthSummary,
    val payBreakdown: MonthlyNetPayBreakdown,
    val monthlyReceipt: MonthlyReceipt,
    val nextMonthReceipt: MonthlyReceipt,
    val mainPaySalaryOnly: Boolean,
    val monthlyFinalPaymentBreakdown: MonthlyFinalPaymentBreakdown,
    val nextMonthFinalPaymentBreakdown: MonthlyFinalPaymentBreakdown,
    val overtimeNetImpact: MonthlyOvertimeNetImpact,
    /** Bolovanje izabranog meseca − plata da su ti dani odrađeni (neto). */
    val sickLeaveNetDifference: Double,
    val payDetails: MonthlyPayDetails,
    val selectedMonthPayDetails: MonthlyPayDetails,
    val bloodDonationReminderStatus: BloodDonationReminderScheduleStatus,
    val vacationSummary: VacationBalanceSummary
)

/** Ulaz od kog zavisi [MainMonthState]: izmena podataka, izabrani mesec i današnji dan. */
internal data class MainStateKey(val tick: Int, val month: MonthKey, val today: LocalDate)

/**
 * Stanje glavnog ekrana: izabrani mesec (preživljava ubijanje procesa), današnji dan i izvedeni
 * podaci meseca. Računanje ne blokira glavnu nit, ni prvo; do prvog rezultata nove vrednosti se
 * vidi prethodno stanje. Svi upisi iz ekrana i dijaloga idu kroz njega ([DataActions]).
 */
internal class MainViewModel(
    private val repo: ShiftRepository,
    private val appContext: Context,
    private val saved: SavedStateHandle,
    initialToday: LocalDate = LocalDate.now(),
    backup: GoogleDriveBackup? = null
) : ViewModel(), DataActions by RepoDataActions(repo, backup, appContext) {
    private val tick = MutableStateFlow(0)
    private val todayFlow = MutableStateFlow(initialToday)
    val today: StateFlow<LocalDate> = todayFlow.asStateFlow()

    private val monthFlow = MutableStateFlow(
        MonthKey(saved[KEY_YEAR] ?: initialToday.year, saved[KEY_MONTH] ?: initialToday.monthValue)
    )
    val month: StateFlow<MonthKey> = monthFlow.asStateFlow()

    // Null dok se prvi put ne izračuna (van glavne niti); ekran do tada crta samo pozadinu.
    private val stateFlow = MutableStateFlow<MainMonthState?>(null)
    val state: StateFlow<MainMonthState?> = stateFlow.asStateFlow()

    private val changeToken = repo.onChange { tick.value += 1 }

    init {
        viewModelScope.launch {
            combine(tick, monthFlow, todayFlow) { t, m, d -> MainStateKey(t, m, d) }.collectLatest { key ->
                if (key == stateFlow.value?.key) return@collectLatest
                stateFlow.value = withContext(Dispatchers.Default) { compute(key) }
            }
        }
        viewModelScope.launch {
            while (true) {
                val now = LocalDate.now()
                todayFlow.value = now
                val untilMidnight = Duration.between(LocalDateTime.now(), now.plusDays(1).atStartOfDay()).toMillis()
                delay(untilMidnight.coerceAtLeast(MIN_MIDNIGHT_DELAY_MS))
            }
        }
    }

    fun showPreviousMonth() = shift(-1)

    fun showNextMonth() = shift(1)

    private fun shift(months: Long) {
        val target = YearMonth.of(monthFlow.value.year, monthFlow.value.month).plusMonths(months)
        saved[KEY_YEAR] = target.year
        saved[KEY_MONTH] = target.monthValue
        monthFlow.value = MonthKey(target.year, target.monthValue)
    }

    override fun onCleared() {
        repo.removeOnChange(changeToken)
    }

    private fun compute(key: MainStateKey): MainMonthState {
        val year = key.month.year
        val month = key.month.month
        val next = YearMonth.of(year, month).plusMonths(1)
        val monthlyReceipt = repo.getMonthlyReceipt(year, month)
        val payroll = monthlyReceipt.payrollMonth
        val selectedAsOf = vacationCardAsOfDate(year, month, key.today)
        return MainMonthState(
            key = key,
            summary = repo.getSummary(year, month),
            payBreakdown = repo.getNetPayBreakdown(year, month),
            monthlyReceipt = monthlyReceipt,
            nextMonthReceipt = repo.getMonthlyReceipt(next.year, next.monthValue),
            mainPaySalaryOnly = repo.mainPaySalaryOnly,
            monthlyFinalPaymentBreakdown = repo.getMonthlyFinalPaymentBreakdown(year, month),
            nextMonthFinalPaymentBreakdown = repo.getMonthlyFinalPaymentBreakdown(next.year, next.monthValue),
            overtimeNetImpact = repo.getMonthlyOvertimeNetImpact(year, month),
            sickLeaveNetDifference = repo.getSickLeaveNetDifference(year, month),
            payDetails = repo.getMonthlyPayDetails(payroll.year, payroll.month),
            selectedMonthPayDetails = repo.getMonthlyPayDetails(year, month),
            bloodDonationReminderStatus = BloodDonationReminderScheduler.currentStatus(appContext, repo),
            vacationSummary = repo.getVacationBalanceSummary(year, selectedAsOf)
        )
    }
}
