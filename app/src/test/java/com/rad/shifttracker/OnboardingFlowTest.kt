package com.rad.shifttracker

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OnboardingFlowTest {

    private fun source(name: String): String = listOf(
        java.io.File("src/main/java/com/rad/shifttracker/$name"),
        java.io.File("app/src/main/java/com/rad/shifttracker/$name")
    ).first { it.exists() }.readText()

    @Test
    fun `first launch opens auto fill and then gmail`() {
        val screen = source("ShiftTrackerScreen.kt")
        assertTrue(screen.contains("if (backup.isFirstLaunch()) {"))
        assertTrue(screen.contains("dialogState = DialogState.OnboardingAutoFill"))
        assertFalse(screen.contains("OnboardingDialog("))
        val dialogs = source("MainDialogs.kt")
        assertTrue(dialogs.contains("if (onboarding) data.onDialogChange(DialogState.GmailOnboarding) else onDismiss()"))
        assertTrue(dialogs.contains("if (!update) data.onFirstLaunchDone()"))
    }

    @Test
    fun `first launch does not ask for coefficient seniority or tax update`() {
        val onboarding = source("Onboarding.kt")
        assertFalse(onboarding.contains("OnboardingStep"))
        assertFalse(onboarding.contains("payCoefficientValue"))
        assertFalse(onboarding.contains("minuliRadStartYearValue"))
        assertFalse(onboarding.contains("difficultWorkConditionsPercentValue"))
        assertFalse(onboarding.contains("fetchAndApplyLatestTaxParameters"))
    }

    @Test
    fun `app works with default pay settings before any payslip`() {
        val repo = testRepository()
        assertTrue(repo.payCoefficientValue > 0.0)
        assertTrue(repo.getLatestSavedPayslipMonth() == null)
        repo.getNetPayBreakdown(2026, 9)
        repo.getMonthlyPayDetails(2026, 9)
    }
}
