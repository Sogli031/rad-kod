package com.rad.shifttracker

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import java.time.YearMonth
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
import com.rad.shifttracker.ui.dialozi.PayDetailsSections
import com.rad.shifttracker.repo.getMonthlyFinalPaymentBreakdown

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
    fun `isplata ispod pokazuje tabelu obracuna plate meseca obracuna`() {
        val repo = repoWithMonth()

        rule.setContent {
            RadTheme {
                androidx.compose.foundation.layout.Column {
                    PayDetailsSections(
                        monthlyReceipt = repo.getMonthlyReceipt(YEAR, MONTH + 1),
                        monthlyFinalPaymentBreakdown = repo.getMonthlyFinalPaymentBreakdown(YEAR, MONTH + 1),
                        payDetails = repo.getMonthlyPayDetails(YEAR, MONTH),
                        repo = repo
                    )
                }
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
