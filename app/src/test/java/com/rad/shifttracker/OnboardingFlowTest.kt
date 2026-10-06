package com.rad.shifttracker

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.rad.shifttracker.repo.getLatestSavedPayslipMonth
import com.rad.shifttracker.repo.getMonthlyPayDetails
import com.rad.shifttracker.repo.getNetPayBreakdown
import com.rad.shifttracker.repo.payCoefficientValue

class OnboardingFlowTest {

    private fun source(name: String): String = mainSource(name)

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
    fun `app works with default pay settings before any payslip`() {
        val repo = testRepository()
        assertTrue(repo.payCoefficientValue > 0.0)
        assertTrue(repo.getLatestSavedPayslipMonth() == null)
        repo.getNetPayBreakdown(2026, 9)
        repo.getMonthlyPayDetails(2026, 9)
    }
}
