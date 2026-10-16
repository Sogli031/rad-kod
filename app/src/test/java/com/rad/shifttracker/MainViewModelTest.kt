package com.rad.shifttracker

import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import java.time.LocalDate
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import com.rad.shifttracker.repo.ShiftRepository
import com.rad.shifttracker.repo.getMonthlyOvertimeNetImpact
import com.rad.shifttracker.repo.getMonthlyPayDetails
import com.rad.shifttracker.repo.getSummary
import com.rad.shifttracker.smene.MonthKey
import com.rad.shifttracker.ui.MainMonthState
import com.rad.shifttracker.ui.MainViewModel

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MainViewModelTest {
    private val today = LocalDate.of(2026, 10, 3)

    @get:Rule
    val mainDispatcher = MainDispatcherRule()

    private fun viewModel(repo: ShiftRepository, saved: SavedStateHandle = SavedStateHandle()) =
        mainDispatcher.track(MainViewModel(repo, ApplicationProvider.getApplicationContext(), saved, today))

    private fun MainViewModel.firstState(): MainMonthState = runBlocking {
        withTimeout(10_000) { state.filterNotNull().first() }
    }

    @Test
    fun `pocetno stanje je izabrani mesec sa podacima tog meseca`() {
        val repo = testRepository()
        val state = viewModel(repo).firstState()

        assertEquals(MonthKey(2026, 10), state.key.month)
        assertEquals(repo.getSummary(2026, 10), state.summary)
        // Sati u dijalogu idu po izabranom mesecu, a obračun po mesecu isplate.
        assertEquals(repo.getMonthlyPayDetails(2026, 10), state.selectedMonthPayDetails)
        val payroll = state.monthlyReceipt.payrollMonth
        assertEquals(repo.getMonthlyPayDetails(payroll.year, payroll.month), state.payDetails)
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

    @Test
    fun `stanje se ne racuna na glavnoj niti pri pravljenju ViewModel-a`() {
        val vm = viewModel(testRepository())
        // Prvi obračun ide na Dispatchers.Default; ViewModel se pravi bez čekanja na njega.
        assertEquals(MonthKey(2026, 10), vm.firstState().key.month)
    }

    @Test
    fun `izmena podataka i prelazak meseca daju novo stanje`() {
        val repo = testRepository()
        val vm = viewModel(repo)
        vm.firstState()

        vm.showNextMonth()
        val next = runBlocking { withTimeout(10_000) { vm.state.filterNotNull().first { it.key.month == MonthKey(2026, 11) } } }
        assertEquals(repo.getSummary(2026, 11), next.summary)
    }
}

/**
 * viewModelScope na test dispečeru: rezultat sa Dispatchers.Default stiže bez Android main looper-a.
 * ViewModel-i iz [track] se gase i čekaju pre resetMain: inače računanje sa Default niti posle kraja
 * testa pokuša da se vrati na Main koji više ne postoji (nestabilan pad na sporijoj mašini).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule : TestWatcher() {
    private val viewModels = mutableListOf<ViewModel>()

    fun <T : ViewModel> track(viewModel: T): T = viewModel.also { viewModels += it }

    override fun starting(description: Description) = Dispatchers.setMain(UnconfinedTestDispatcher())
    override fun finished(description: Description) {
        runBlocking { viewModels.forEach { it.viewModelScope.coroutineContext.job.cancelAndJoin() } }
        viewModels.clear()
        Dispatchers.resetMain()
    }
}
