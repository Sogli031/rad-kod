package com.rad.shifttracker

import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import com.rad.shifttracker.obracun.SeniorityIncreaseFactor
import com.rad.shifttracker.obracun.SeniorityInputMode
import com.rad.shifttracker.repo.ShiftRepository
import com.rad.shifttracker.repo.minuliRadBenefitEnabledValue
import com.rad.shifttracker.repo.minuliRadLastPayslipYearValue
import com.rad.shifttracker.repo.minuliRadPayslipPercentValue
import com.rad.shifttracker.repo.minuliRadStartYearValue
import com.rad.shifttracker.repo.seniorityInputModeValue
import com.rad.shifttracker.smene.DayRecord
import com.rad.shifttracker.smene.Shift
import com.rad.shifttracker.ui.MainViewModel
import com.rad.shifttracker.ui.SenioritySettings

/** UI repozitorijum samo čita; svaki upis ide kroz MainViewModel (DataActions). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UiWritesThroughViewModelTest {
    @get:Rule
    val mainDispatcher = MainDispatcherRule()

    private val writePatterns = listOf(
        Regex("""\brepo\.(set|save|delete|clear|apply|record|import|populate|update|add)\w*\s*\("""),
        Regex("""\brepo\.\w+\s*=[^=]"""),
        Regex("""\b(applyMonthFill|fillAllMonths|applyPayslipChanges|importManualPayslip|applySickRange|applyVacationRange|populateMonthsAhead)\(\s*repo\b"""),
        Regex("""GmailImportRunner\.start\(""")
    )

    @Test
    fun `ui sloj ne menja repo mimo DataActions`() {
        val uiRoot = File(mainSourceRoot(), "ui")
        val violations = uiRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.name != "DataActions.kt" }
            .flatMap { file ->
                file.readLines().withIndex()
                    .filter { (_, line) -> writePatterns.any { it.containsMatchIn(line) } }
                    .map { (index, line) -> "${file.name}:${index + 1}: ${line.trim()}" }
            }
            .toList()
        assertEquals(emptyList<String>(), violations)
    }

    @Test
    fun `zavisnosti dolaze iz AppContainer-a`() {
        val application = mainSource("RadApplication.kt")
        assertFalse(application.contains("shiftRepository"))
        assertTrue(application.contains("fun container(): AppContainer"))
        val activity = mainSource("MainActivity.kt")
        assertTrue(activity.contains("app.container()"))
    }

    @Test
    fun `upis kroz view model stize u repo i osvezava stanje`() {
        val repo = ShiftRepository()
        val viewModel = MainViewModel(repo, ApplicationProvider.getApplicationContext(), SavedStateHandle(), LocalDate.of(2026, 10, 3))
        var changes = 0
        repo.onChange { changes++ }

        viewModel.saveDay(DayRecord(year = 2026, month = 10, day = 5, shift = Shift.FIRST, hours = 8))

        assertEquals(Shift.FIRST, repo.get(2026, 10, 5)?.shift)
        assertTrue(changes > 0)
    }

    @Test
    fun `minuli rad upisuje samo polja izabranog nacina`() {
        val repo = ShiftRepository()
        val viewModel = MainViewModel(repo, ApplicationProvider.getApplicationContext(), SavedStateHandle(), LocalDate.of(2026, 10, 3))
        val settings = SenioritySettings(
            mode = SeniorityInputMode.PAYSLIP_PERCENT,
            increaseFactor = SeniorityIncreaseFactor.entries.first(),
            startYear = 2001,
            startMonth = 3,
            benefitEnabled = true,
            lastPayslipYear = 2026,
            lastPayslipMonth = 8,
            payslipPercent = 9.5
        )

        viewModel.saveSeniority(settings)

        assertEquals(SeniorityInputMode.PAYSLIP_PERCENT, repo.seniorityInputModeValue)
        assertEquals(2026, repo.minuliRadLastPayslipYearValue)
        assertEquals(9.5, repo.minuliRadPayslipPercentValue, 1e-9)
        assertFalse(repo.minuliRadBenefitEnabledValue)
        assertEquals(0, repo.minuliRadStartYearValue)
    }
}
