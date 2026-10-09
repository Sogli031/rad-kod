package com.rad.shifttracker

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import com.rad.shifttracker.repo.ShiftRepository
import com.rad.shifttracker.repo.getMonthlyPayDetails
import com.rad.shifttracker.repo.getMonthlyReceipt
import com.rad.shifttracker.smene.Shift
import com.rad.shifttracker.ui.RadTheme
import com.rad.shifttracker.ui.SalaryCard
import com.rad.shifttracker.ui.dialozi.PayDetailsDialog
import com.rad.shifttracker.repo.getMonthlyFinalPaymentBreakdown
import com.rad.shifttracker.ui.salaryCardLabel

/**
 * Ponašanje kartice plate i dijaloga sati kroz Compose UI test (zamena za testove koji su čitali
 * izvorni tekst): šta korisnik vidi i da dodir otvara dijalog.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class DashboardBehaviorTest {

    @get:Rule
    val rule = createComposeRule()

    private fun repoWithMonth(): ShiftRepository = testRepository().apply {
        for (day in 1..YearMonth.of(YEAR, MONTH).lengthOfMonth()) {
            val shift = if (day % 7 in 0..1) Shift.OFF else Shift.FIRST
            set(YEAR, MONTH, day, shift, if (shift == Shift.OFF) 0 else 8)
        }
    }

    @Test
    fun `kartica plate prikazuje iznos sledeceg meseca i dodir je otvara`() {
        val repo = repoWithMonth()
        val receipt = repo.getMonthlyReceipt(YEAR, MONTH + 1)
        var clicks = 0

        rule.setContent {
            RadTheme { SalaryCard(month = MONTH, nextMonthReceipt = receipt, onClick = { clicks++ }) }
        }

        rule.onNodeWithText(salaryCardLabel(MONTH)).assertIsDisplayed().performClick()
        assertEquals(1, clicks)
    }

    @Test
    fun `isplata ispod pokazuje tabelu obracuna plate meseca obracuna`() {
        val repo = repoWithMonth()

        rule.setContent {
            RadTheme {
                PayDetailsDialog(
                    monthlyReceipt = repo.getMonthlyReceipt(YEAR, MONTH + 1),
                    monthlyFinalPaymentBreakdown = repo.getMonthlyFinalPaymentBreakdown(YEAR, MONTH + 1),
                    payDetails = repo.getMonthlyPayDetails(YEAR, MONTH),
                    repo = repo,
                    onDismiss = {}
                )
            }
        }

        rule.waitUntil(timeoutMillis = 5_000) { rule.onAllNodesWithText("Isplata", ignoreCase = true).fetchSemanticsNodes().isNotEmpty() }
        for (section in listOf("Isplata", "Obračun plate za Oktobar", "Rad", "= Neto plata")) {
            assertTrue("nedostaje sekcija $section", rule.onAllNodesWithText(section, ignoreCase = true).fetchSemanticsNodes().isNotEmpty())
        }
        // Tabela obračuna ima kolone Sati / Iznos.
        for (header in listOf("Sati", "Iznos")) {
            assertTrue("nedostaje kolona $header", rule.onAllNodesWithText(header, ignoreCase = true).fetchSemanticsNodes().isNotEmpty())
        }
        assertTrue(rule.onAllNodesWithText("Ostalo u obračunu", ignoreCase = true).fetchSemanticsNodes().isEmpty())
    }

    private companion object {
        const val YEAR = 2026
        const val MONTH = 10
    }
}
