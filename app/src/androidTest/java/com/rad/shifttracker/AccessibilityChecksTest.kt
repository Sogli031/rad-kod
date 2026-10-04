package com.rad.shifttracker

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.tryPerformAccessibilityChecks
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.rad.shifttracker.data.backup.GoogleDriveBackupProvider
import java.time.LocalDate
import java.time.YearMonth
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Accessibility Test Framework (isti kao Play predlaunch izveštaj) nad pravim ekranima, na telefonu:
 * dodirne površine, opisi, kontrast teksta, dupli opisi. Svaka greška ATF-a obara test.
 * Pokretanje: `./gradlew :app:connectedUiTestAndroidTest` (aplikacija com.rad.shifttracker.uitest,
 * prava instalacija se ne dira).
 */
@RunWith(AndroidJUnit4::class)
class AccessibilityChecksTest {

    @get:Rule
    val rule = createComposeRule()

    private val today = LocalDate.now()

    @Before
    fun setUp() {
        rule.enableAccessibilityChecks()
    }

    @Test
    fun glavniEkran() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as android.app.Application
        val backup = GoogleDriveBackupProvider.get(app)
        checkAllWindows(waitForDescription = "Sledeći mesec") { RadApp(monthRepository(), backup) }
    }

    @Test
    fun dijalogDana() = checkAllWindows {
        val repo = monthRepository()
        RadTheme {
            DayEditDialog(
                year = today.year,
                month = today.monthValue,
                day = 9,
                record = repo.get(today.year, today.monthValue, 9),
                repo = repo,
                onDismiss = {}
            )
        }
    }

    @Test
    fun opcije() = checkAllWindows {
        RadTheme {
            OptionsDialog(
                repo = monthRepository(),
                onCloudClick = {},
                onMonthlyExportClick = {},
                onDebugExportClick = {},
                onHolidaysClick = {},
                onDismiss = {}
            )
        }
    }

    private fun checkAllWindows(waitForDescription: String? = null, content: @Composable () -> Unit) {
        rule.setContent(content)
        // Glavni ekran se crta tek kad stigne prvi obračun meseca (pozadinska nit).
        waitForDescription?.let { description ->
            rule.waitUntil(WAIT_MS) { rule.onAllNodesWithContentDescription(description).fetchSemanticsNodes().isNotEmpty() }
        }
        rule.mainClock.advanceTimeBy(SETTLE_MS)
        rule.waitForIdle()
        // Dijalog je poseban prozor: proverava se svaki koren.
        val roots = rule.onAllNodes(isRoot())
        repeat(roots.fetchSemanticsNodes().size) { index -> roots[index].tryPerformAccessibilityChecks() }
    }

    /** Tekući mesec sa smenama, slobodnim danima, godišnjim, bolovanjem i prekovremenim. */
    private fun monthRepository(): ShiftRepository = ShiftRepository().apply {
        val year = today.year
        val month = today.monthValue
        val pattern = listOf(Shift.FIRST, Shift.FIRST, Shift.SECOND, Shift.SECOND, Shift.THIRD, Shift.THIRD, Shift.OFF, Shift.OFF)
        for (day in 1..YearMonth.of(year, month).lengthOfMonth()) {
            val shift = when (day) {
                in 20..22 -> Shift.VACATION
                27 -> Shift.SICK
                else -> pattern[(day - 1) % pattern.size]
            }
            val hours = if (shift == Shift.OFF) 0 else 8
            if (day == 9) {
                set(year, month, day, shift, hours, shift2 = Shift.SECOND, hours2 = 4)
            } else {
                set(year, month, day, shift, hours)
            }
        }
    }

    private companion object {
        const val SETTLE_MS = 1_500L
        const val WAIT_MS = 10_000L
    }
}
