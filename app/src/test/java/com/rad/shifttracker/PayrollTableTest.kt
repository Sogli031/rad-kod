package com.rad.shifttracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import com.rad.shifttracker.repo.ShiftRepository
import com.rad.shifttracker.repo.getMonthlyPayDetails
import com.rad.shifttracker.repo.getMonthlyReceipt
import com.rad.shifttracker.repo.getNetPayBreakdown
import com.rad.shifttracker.repo.getSickLeaveNetDifference
import com.rad.shifttracker.repo.getVacationNetDifference
import com.rad.shifttracker.obracun.MonthlyFinalPaymentBreakdown
import com.rad.shifttracker.obracun.MonthlyReceipt
import com.rad.shifttracker.smene.MonthKey
import com.rad.shifttracker.ui.dialozi.PayrollTable
import com.rad.shifttracker.ui.dialozi.payDetailsTotalAfterDeductions
import com.rad.shifttracker.ui.dialozi.payDetailsTotalOn20
import com.rad.shifttracker.ui.dialozi.payrollTable
import com.rad.shifttracker.ui.dialozi.signedSrMoney
import com.rad.shifttracker.ui.dialozi.srMoney

class PayrollTableTest {
    private fun table(repo: ShiftRepository, year: Int = 2026, month: Int = 10): PayrollTable {
        val receipt = repo.getMonthlyReceipt(year, month + 1)
        return payrollTable(
            payDetails = repo.getMonthlyPayDetails(year, month),
            payBreakdown = repo.getNetPayBreakdown(year, month),
            netSalary = receipt.salaryNetPay,
            sickLeaveNetDifference = repo.getSickLeaveNetDifference(year, month),
            uncoveredDeficitNetLoss = 0.0,
            vacationNetDifference = repo.getVacationNetDifference(year, month)
        )
    }

    @Test
    fun `tabela obracuna se sabira tacno u neto platu, a zaokruzenje je malo`() {
        val repo = sampleMonthRepository()
        val table = table(repo)
        val rows = table.work + table.compensation + table.other

        assertEquals(table.netSalary, rows.sumOf { it.amount } + table.rounding, 0.001)
        assertTrue("zaokruženje ${table.rounding}", kotlin.math.abs(table.rounding) < table.netSalary * 0.01)
        assertEquals("Obični sati", table.work.first().label)
        // Bolovanje je stvarna naknada (pozitivno), a razlika prema radu je samo napomena.
        val sick = table.compensation.single { it.label == "Bolovanje" }
        assertTrue(sick.amount > 0.0)
        assertTrue(sick.note.orEmpty().endsWith("nego da si radio"))
        val vacation = table.compensation.single { it.label == "Godišnji odmor" }
        assertTrue(vacation.note.orEmpty().endsWith("nego da si radio"))
    }

    @Test
    fun `iznosi su u srpskom formatu`() {
        assertEquals("51.996,94", srMoney(51_996.94))
        assertEquals("164,49", srMoney(164.49))
        assertEquals("−1.545,52", signedSrMoney(-1_545.52))
        assertEquals("+164,49", signedSrMoney(164.49))
    }

    @Test
    fun `ukupno za isplatu 20 je ukupno minus akontacija`() {
        val receipt = MonthlyReceipt(
            year = 2026,
            month = 11,
            salaryMonth = MonthKey(2026, 10),
            salaryNetPay = 88_351.70,
            regressNetPay = 20_000.0
        )
        val finalPayment = MonthlyFinalPaymentBreakdown(
            month = MonthKey(2026, 11),
            isNetPayAvailable = true,
            netPayBeforeAdvance = 88_351.70,
            advancePaymentNet = 25_000.0,
            deductionsNet = 49_864.0,
            deductions = emptyList(),
            remainingRegressNetPay = 20_000.0,
            finalRemainingNetPay = 13_487.70,
            advanceExceedsCalculatedNetPay = false
        )

        assertEquals(58_487.70, payDetailsTotalAfterDeductions(receipt, finalPayment), 0.001)
        assertEquals(33_487.70, payDetailsTotalOn20(receipt, finalPayment), 0.001)
    }
}
