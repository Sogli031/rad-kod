package com.rad.shifttracker

import org.junit.Assert.assertTrue
import org.junit.Test

class WorkHoursDialogTextTest {
    @Test
    fun `work hours card opens dedicated dialog and pay details no longer owns hours section`() {
        val dashboardSource = sourceText("ShiftDashboardCards.kt")
        val screenSource = sourceText("ShiftTrackerScreen.kt")
        val mainDialogsSource = sourceText("MainDialogs.kt")
        val payDetailsSource = sourceText("PayDetailsDialog.kt")
        val workHoursSource = sourceText("WorkHoursDialog.kt", required = false)

        assertTrue(dashboardSource.contains("onWorkHoursClick"))
        assertTrue(dashboardSource.contains("WORK_HOURS_CARD_LABEL"))
        assertTrue(dashboardSource.contains("onClick = onWorkHoursClick"))
        assertTrue(screenSource.contains("DialogState.WorkHours"))
        assertTrue(mainDialogsSource.contains("WorkHoursDialog("))
        assertTrue(!payDetailsSource.contains("title = \"Sati\""))
        assertTrue(workHoursSource.contains("PopupDialog("))
        assertTrue(workHoursSource.contains("title = WORK_HOURS_DIALOG_TITLE"))
        assertTrue(workHoursSource.contains("\"Obični sati\""))
        assertTrue(workHoursSource.contains("\"Manjak po danima (saldo)\""))
        assertTrue(workHoursSource.contains("\"Izostanci po fondu (lista)\""))
        assertTrue(workHoursSource.contains("payDetails.fundAbsenceMinutes(payBreakdown.monthlyFundHours)"))
        assertTrue(workHoursSource.contains("\"Godišnji odmor\""))
    }

    @Test
    fun `work hours dialog uses selected month while pay details keeps payroll month`() {
        val screenSource = sourceText("ShiftTrackerScreen.kt")
        val mainDialogsSource = sourceText("MainDialogs.kt")

        // Izvor podataka (izabrani mesec za sate, mesec isplate za obračun) proverava MainViewModelTest.
        assertTrue(screenSource.contains("workHoursSummary = summary"))
        assertTrue(screenSource.contains("workHoursPayBreakdown = payBreakdown"))
        assertTrue(screenSource.contains("workHoursPayDetails = selectedMonthPayDetails"))
        assertTrue(screenSource.contains("workHoursOvertimeNetImpact = selectedMonthOvertimeNetImpact"))
        assertTrue(screenSource.contains("workHoursMonth = MonthKey(currentYear, currentMonth)"))
        assertTrue(mainDialogsSource.contains("summary = data.workHoursSummary"))
        assertTrue(mainDialogsSource.contains("payBreakdown = data.workHoursPayBreakdown"))
        assertTrue(mainDialogsSource.contains("month = data.workHoursMonth"))
        assertTrue(mainDialogsSource.contains("payDetails = data.workHoursPayDetails"))
        assertTrue(mainDialogsSource.contains("deficitNetLoss = data.workHoursOvertimeNetImpact.deficitNetLoss"))

        val workHoursSource = sourceText("WorkHoursDialog.kt")
        assertTrue(workHoursSource.contains("month: MonthKey"))
        assertTrue(!workHoursSource.contains("monthlyReceipt: MonthlyReceipt"))
        assertTrue(!workHoursSource.contains("payrollMonthTitleText(monthlyReceipt)"))
    }

    @Test
    fun `work hours hides zero money add on rows`() {
        val workHoursSource = sourceText("WorkHoursDialog.kt")

        assertTrue(workHoursSource.contains("if (gross <= 0.0) return"))
        assertTrue(!workHoursSource.contains("if (gross <= 0.0 && hours <= 0) return"))
    }

    @Test
    fun `work hours add ons and absences use amount table without premium percentages`() {
        val workHoursSource = sourceText("WorkHoursDialog.kt")

        assertTrue(workHoursSource.contains("WorkHoursSection(\"Pregled\""))
        assertTrue(workHoursSource.contains("WorkHoursTableSection(\"Dodaci\""))
        assertTrue(workHoursSource.contains("WorkHoursTableSection(\"Izostanci i naknade\""))
        assertTrue(workHoursSource.contains("\"Tip\""))
        assertTrue(workHoursSource.contains("\"Sati\""))
        assertTrue(workHoursSource.contains("\"Iznos\""))
        assertTrue(workHoursSource.contains("label = \"Treća smena\""))
        assertTrue(!workHoursSource.contains("Treća smena / noćni rad"))
        assertTrue(!workHoursSource.contains("percentText("))
        assertTrue(!workHoursSource.contains("neto"))
    }

    @Test
    fun `work hours dialog formats payroll detail minutes without truncating partial hours`() {
        val workHoursSource = sourceText("WorkHoursDialog.kt")

        assertTrue(workHoursSource.contains("formatPayDetailMinutes(payDetails.paidWorkMinutes)"))
        assertTrue(workHoursSource.contains("minutes = payDetails.nightMinutes"))
        assertTrue(workHoursSource.contains("formatPayDetailMinutes(minutes)"))
    }

    @Test
    fun `work hours deductions use monthly net breakdown without duplicate food overage`() {
        val screenSource = sourceText("ShiftTrackerScreen.kt")
        val mainDialogsSource = sourceText("MainDialogs.kt")
        val workHoursSource = sourceText("WorkHoursDialog.kt")

        assertTrue(!screenSource.contains("selectedMonthFoodOverageDeductionNet"))
        assertTrue(!screenSource.contains("workHoursFoodOverageDeductionNet"))

        assertTrue(screenSource.contains("monthlyFinalPaymentBreakdown = monthlyFinalPaymentBreakdown"))
        assertTrue(mainDialogsSource.contains("monthlyFinalPaymentBreakdown = data.monthlyFinalPaymentBreakdown"))
        assertTrue(mainDialogsSource.contains("PayDetailsDialog("))

        assertTrue(!mainDialogsSource.contains("workHoursFoodOverageDeductionNet"))
        assertTrue(!workHoursSource.contains("foodOverageDeductionNet: Double"))
        assertTrue(workHoursSource.contains("val deductionsTotal = payBreakdown.netDeductionsTotal"))
    }

    private fun sourceText(fileName: String, required: Boolean = true): String {
        val file = listOf(
            java.io.File("src/main/java/com/rad/shifttracker/$fileName"),
            java.io.File("app/src/main/java/com/rad/shifttracker/$fileName")
        ).firstOrNull { it.exists() }

        if (file == null && !required) return ""
        return requireNotNull(file) { "Missing source file $fileName" }.readText()
    }
}
