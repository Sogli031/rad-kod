package com.rad.shifttracker

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.github.takahirom.roborazzi.captureScreenRoboImage
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import com.rad.shifttracker.listici.FillDay
import com.rad.shifttracker.listici.FillDayKind
import com.rad.shifttracker.listici.PayslipCheckRow
import com.rad.shifttracker.smene.MonthKey
import com.rad.shifttracker.smene.Shift
import com.rad.shifttracker.ui.RadTheme
import com.rad.shifttracker.ui.dialozi.PayslipFillReviewDialog
import com.rad.shifttracker.uvoz.FillSelection
import com.rad.shifttracker.uvoz.MonthFillResult
import com.rad.shifttracker.uvoz.PayslipFillProposal
import com.rad.shifttracker.uvoz.PayslipReconciliation
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class PayslipFillReviewTest {
    @get:Rule val rule = createComposeRule()

    @Test fun lightReview() = review(dark = false, font = 1.0f)
    @Test fun darkReview() = review(dark = true, font = 1.0f)
    @Test fun largeFontReviewKeepsConfirmationVisible() = review(dark = true, font = 2.0f)

    private fun review(dark: Boolean, font: Float) {
        RuntimeEnvironment.setQualifiers("w360dp-h780dp-${if (dark) "night" else "notnight"}-xxhdpi")
        RuntimeEnvironment.setFontScale(font)
        var writes = 0
        var dismissals = 0
        val proposal = sampleProposal()
        rule.setContent {
            RadTheme {
                PayslipFillReviewDialog(listOf(proposal), emptyList(), { writes++ }, { dismissals++ })
            }
        }
        rule.mainClock.advanceTimeBy(1_000L)
        rule.waitForIdle()
        rule.onNodeWithText("Procenjeni datumi").assertIsDisplayed()
        rule.onNodeWithText("Upiši").assertIsDisplayed()
        assertEquals(0, writes)
        captureScreenRoboImage("src/test/snapshots/payslip_review_${if (dark) "dark" else "light"}_f${(font * 100).toInt()}.png")
        rule.onNodeWithText("Otkaži").performClick()
        assertEquals(0, writes)
        assertEquals(1, dismissals)
    }

    private fun sampleProposal(): PayslipFillProposal {
        val month = MonthKey(2026, 10)
        val days = (1..31).map { day ->
            FillDay(LocalDate.of(2026, 10, day), if (day in 12..16) FillDayKind.VACATION else FillDayKind.WORK,
                if (day in 12..16) null else Shift.FIRST, 8)
        }
        val reconciliation = PayslipReconciliation(month, true, listOf(
            PayslipCheckRow("Noćni rad", "40 h", "39 h", false, isHours = true)
        ))
        return PayslipFillProposal(MonthFillResult.Success(month, days, reconciliation, 3), FillSelection.AI, 12, "test")
    }
}
