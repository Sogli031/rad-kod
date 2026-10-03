package com.rad.shifttracker

import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MainViewModelTest {
    private val today = LocalDate.of(2026, 10, 3)

    private fun viewModel(repo: ShiftRepository, saved: SavedStateHandle = SavedStateHandle()) =
        MainViewModel(repo, ApplicationProvider.getApplicationContext(), saved, today)

    @Test
    fun `pocetno stanje je izabrani mesec sa podacima tog meseca`() {
        val repo = testRepository()
        val state = viewModel(repo).state.value

        assertEquals(MonthKey(2026, 10), state.key.month)
        assertEquals(repo.getSummary(2026, 10), state.summary)
        // Sati u dijalogu idu po izabranom mesecu, a obračun po mesecu isplate.
        assertEquals(repo.getMonthlyPayDetails(2026, 10), state.selectedMonthPayDetails)
        val payroll = state.monthlyReceipt.payrollMonth
        assertEquals(repo.getMonthlyPayDetails(payroll.year, payroll.month), state.payDetails)
        assertEquals(repo.getSummary(payroll.year, payroll.month), state.payrollSummary)
        assertEquals(repo.getMonthlyOvertimeNetImpact(2026, 10), state.overtimeNetImpact)
    }

    @Test
    fun `prelazak meseca odmah menja izabrani mesec i pamti ga za ubijanje procesa`() {
        val saved = SavedStateHandle()
        val vm = viewModel(testRepository(), saved)

        vm.showPreviousMonth()
        assertEquals(MonthKey(2026, 9), vm.month.value)
        repeat(4) { vm.showNextMonth() }
        assertEquals(MonthKey(2027, 1), vm.month.value)

        // Novi ViewModel posle ubijanja procesa čita isti mesec iz SavedStateHandle-a.
        assertEquals(MonthKey(2027, 1), viewModel(testRepository(), saved).month.value)
    }
}
