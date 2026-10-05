package com.rad.shifttracker

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
    val payrollSummary: MonthSummary,
    val payrollPayBreakdown: MonthlyNetPayBreakdown,
    val payDetails: MonthlyPayDetails,
    val selectedMonthPayDetails: MonthlyPayDetails,
    val annualNetPayAverages: AnnualNetPayAverages,
    val recentNetPayAverage: RecentNetPayAverage,
    val selectedMonthAnnualNetPayAverages: AnnualNetPayAverages,
    val selectedMonthRecentNetPayAverage: RecentNetPayAverage,
    val bloodDonationReminderStatus: BloodDonationReminderScheduleStatus,
    val vacationSummary: VacationBalanceSummary
)

/** Ulaz od kog zavisi [MainMonthState]: izmena podataka, izabrani mesec i današnji dan. */
internal data class MainStateKey(val tick: Int, val month: MonthKey, val today: LocalDate)

/**
 * Stanje glavnog ekrana: izabrani mesec (preživljava ubijanje procesa), današnji dan i izvedeni
 * podaci meseca. Računanje ne blokira glavnu nit, ni prvo; do prvog rezultata nove vrednosti se
 * vidi prethodno stanje.
 */
internal class MainViewModel(
    private val repo: ShiftRepository,
    private val appContext: Context,
    private val saved: SavedStateHandle,
    initialToday: LocalDate = LocalDate.now()
) : ViewModel() {
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
        val payrollAsOf = vacationCardAsOfDate(payroll.year, payroll.month, key.today)
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
            payrollSummary = repo.getSummary(payroll.year, payroll.month),
            payrollPayBreakdown = repo.getNetPayBreakdown(payroll.year, payroll.month),
            payDetails = repo.getMonthlyPayDetails(payroll.year, payroll.month),
            selectedMonthPayDetails = repo.getMonthlyPayDetails(year, month),
            annualNetPayAverages = repo.getAnnualNetPayAverages(payroll.year),
            recentNetPayAverage = repo.getRecentNetPayAverage(payroll.year, payroll.month, payrollAsOf),
            selectedMonthAnnualNetPayAverages = repo.getAnnualNetPayAverages(year),
            selectedMonthRecentNetPayAverage = repo.getRecentNetPayAverage(year, month, selectedAsOf),
            bloodDonationReminderStatus = BloodDonationReminderScheduler.currentStatus(appContext, repo),
            vacationSummary = repo.getVacationBalanceSummary(year, selectedAsOf)
        )
    }
}
