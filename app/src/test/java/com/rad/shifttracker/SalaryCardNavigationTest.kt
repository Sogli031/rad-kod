package com.rad.shifttracker

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class SalaryCardNavigationTest {
    @Test
    fun `salary card opens the selected month pay summary`() {
        val dashboardSource = sourceText("ShiftDashboardCards.kt")
        val screenSource = sourceText("ShiftTrackerScreen.kt")
        val dialogsSource = sourceText("MainDialogs.kt")
        val salaryCardSource = dashboardSource
            .substringAfter("internal fun SalaryCard")
            .substringBefore("\n}\n")

        assertTrue(salaryCardSource.contains("onClick: () -> Unit"))
        assertTrue(salaryCardSource.contains("onClick = onClick"))
        assertTrue(salaryCardSource.contains("amount = nextMonthReceipt.totalReceivedThisMonth"))
        assertTrue(!salaryCardSource.contains("amount = nextMonthReceipt.salaryNetPay"))
        assertTrue(screenSource.contains("onClick = { dialogState = DialogState.SalaryDetails }"))
        assertTrue(dialogsSource.contains("DialogState.SalaryDetails ->"))
        assertTrue(dialogsSource.contains("monthlyReceipt = data.salaryDetailsReceipt"))
        assertTrue(dialogsSource.contains("summary = data.workHoursSummary"))
        assertTrue(dialogsSource.contains("payBreakdown = data.workHoursPayBreakdown"))
        assertTrue(dialogsSource.contains("payDetails = data.workHoursPayDetails"))
    }

    private fun sourceText(fileName: String): String {
        val candidates = listOf(
            File("src/main/java/com/rad/shifttracker/$fileName"),
            File("app/src/main/java/com/rad/shifttracker/$fileName")
        )
        return candidates.firstOrNull(File::exists)?.readText()
            ?: error("Ne mogu da pronađem $fileName")
    }
}
