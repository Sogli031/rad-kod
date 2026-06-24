package com.rad.shifttracker

import org.junit.Assert.assertTrue
import org.junit.Test

class PayrollSlipReconstructionSourceTest {
    @Test
    fun `payroll slip service delegates net preview to repository instead of default calculator`() {
        val serviceSource = sourceText("PayrollSlipReconstructionService.kt")

        assertTrue(!serviceSource.contains("private val calculator: PayCalculator = PayCalculator()"))
        assertTrue(!serviceSource.contains("calculator.calculateMonthSummary("))
        assertTrue(serviceSource.contains("repo.calculatePayrollSlipPreviewNetBeforeDeductions("))
    }

    private fun sourceText(fileName: String): String {
        val file = listOf(
            java.io.File("src/main/java/com/rad/shifttracker/$fileName"),
            java.io.File("app/src/main/java/com/rad/shifttracker/$fileName")
        ).firstOrNull { it.exists() }
        return requireNotNull(file) { "Missing source file $fileName" }.readText()
    }
}
