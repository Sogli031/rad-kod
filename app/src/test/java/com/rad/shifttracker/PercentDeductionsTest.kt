package com.rad.shifttracker

import java.math.BigDecimal
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.rad.shifttracker.listici.ParsedPayslip
import com.rad.shifttracker.listici.PercentDeduction
import com.rad.shifttracker.listici.finalPaymentDeductions
import com.rad.shifttracker.listici.percentDeductionsForMonth
import com.rad.shifttracker.obracun.roundMoneyHalfUp
import com.rad.shifttracker.repo.ShiftRepository
import com.rad.shifttracker.repo.exportJson
import com.rad.shifttracker.repo.getNetPayBreakdown
import com.rad.shifttracker.repo.getSavedPayslip
import com.rad.shifttracker.repo.importFromJson
import com.rad.shifttracker.repo.savePayslip
import com.rad.shifttracker.smene.MonthKey
import com.rad.shifttracker.smene.Shift
import com.rad.shifttracker.listici.FinalPaymentDeduction
import com.rad.shifttracker.repo.getMonthlyFinalPaymentBreakdown
import com.rad.shifttracker.repo.getMonthlyReceipt

/** Obustave u % zarade (sindikat, fond...) samo sa listića, svaka u svom mesecu. */
class PercentDeductionsTest {
    // Redovi sa listića 06, 07 i 08/2026 (obustava 1,50 % od cele neto zarade meseca).
    private val june = payslipWithDeductions(6, "T06261 Topli obrok 0.00 0 5,335.00", "000014 NEZ.SIND.RAD.JUGOSLAVIJE 1.50 % 1,798.04")
    private val july = payslipWithDeductions(7, "T07261 Topli obrok 0.00 0 6,030.00", "000014 NEZ.SIND.RAD.JUGOSLAVIJE 1.50 % 1,849.92")
    private val august = payslipWithDeductions(8, "T08261 Topli obrok 0.00 0 7,783.60", "000014 NEZ.SIND.RAD.JUGOSLAVIJE 1.50 % 2,415.07")
    private val expected = mapOf(6 to 1_798.04, 7 to 1_849.92, 8 to 2_415.07)

    private fun repoWith(vararg slips: ParsedPayslip): ShiftRepository {
        val repo = testRepository()
        for (month in 6..10) repo.set(2026, month, 1, Shift.FIRST, 8, holidayOverride = false)
        slips.forEach { repo.savePayslip(it, withPayBase = false) }
        return repo
    }

    @Test
    fun everyMonthKeepsItsOwnPercentAndAmountInAnyImportOrder() {
        for (order in listOf(listOf(june, july, august), listOf(august, july, june))) {
            val repo = repoWith(*order.toTypedArray())
            for ((month, amount) in expected) {
                val breakdown = repo.getNetPayBreakdown(2026, month)
                assertEquals("$month/2026", amount, breakdown.unionDuesNet, 0.001)
            }
        }
    }

    @Test
    fun finalPaymentShowsThePercentDeductionOfItsSalaryMonthUnderDeductions() {
        val repo = repoWith(august, june, july)

        for ((month, amount) in expected) {
            val payment = repo.getMonthlyFinalPaymentBreakdown(2026, month + 1)
            val salaryNet = repo.getMonthlyReceipt(2026, month + 1).salaryNetPay
            assertEquals(listOf(FinalPaymentDeduction("NEZ.SIND.RAD.JUGOSLAVIJE", amount)), payment.deductions)
            // Neto pre obustava; ostatak za isplatu je isti kao pre (obustava se ne skida dvaput).
            assertEquals(salaryNet + amount, payment.netPayBeforeAdvance, 0.001)
            assertEquals(salaryNet, payment.finalRemainingNetPay, 0.001)
        }
    }

    @Test
    fun percentIsReadFromThePayslipNotHardcoded() {
        val slip = payslipWithDeductions(8, "000010 FOND SOLIDARNOSTI BAKAR I DOO 1.00 % 1,518.62")
        val result = percentDeductionsForMonth(MonthKey(2026, 8), listOf(slip))!!.rows.single()

        assertEquals(0, BigDecimal("1.00").compareTo(result.percent))
        assertEquals(1_518.62, result.amountNet(999_999.0), 0.001)
    }

    @Test
    fun payslipWithoutPercentRowMeansZeroForThatMonth() {
        val repo = repoWith(june, payslipWithDeductions(7, "000001 OZ \"DUNAV\" UŽICE 150.00"), august)

        assertEquals(1_798.04, repo.getNetPayBreakdown(2026, 6).unionDuesNet, 0.001)
        assertEquals(0.0, repo.getNetPayBreakdown(2026, 7).unionDuesNet, 0.001)
        assertEquals(2_415.07, repo.getNetPayBreakdown(2026, 8).unionDuesNet, 0.001)
    }

    @Test
    fun monthWithoutPayslipIsPercentOfTheWholeMonthlyNet() {
        // „Zarade i naknade zarada bez poreza i dop.“ iz kolone UKUPNO (sa akontacijom).
        val rate = PercentDeduction("SINDIKAT", BigDecimal("1.50"), amountFromPayslip = null)
        assertEquals(1_798.04, rate.amountNet(119_869.27), 0.001)
        assertEquals(1_849.92, rate.amountNet(123_327.70), 0.001)
        assertEquals(2_415.07, rate.amountNet(161_004.63), 0.001)

        val repo = repoWith(june, july, august)
        val september = repo.getNetPayBreakdown(2026, 9)
        assertTrue(september.netPayBeforeDeductions > 0.0)
        assertEquals(roundMoneyHalfUp(september.netPayBeforeDeductions * 0.015), september.unionDuesNet, 0.001)
        // Pre prve liste nema odakle da se zna procenat.
        assertNull(percentDeductionsForMonth(MonthKey(2026, 5), listOf(june, july, august)))
    }

    @Test
    fun amountFromThePayslipWinsOverTheCalculatedOne() {
        val fromSlip = PercentDeduction("SINDIKAT", BigDecimal("1.50"), amountFromPayslip = BigDecimal("1798.04"))
        assertEquals(1_798.04, fromSlip.amountNet(500_000.0), 0.001)
    }

    @Test
    fun percentRowsAreNotRepeatedInTheFinalPaymentDeductions() {
        val slip = payslipWithDeductions(8, "000001 OZ \"DUNAV\" UŽICE 150.00", "000014 NEZ.SIND.RAD.JUGOSLAVIJE 1.50 % 2,415.07")

        assertEquals(listOf("OZ \"DUNAV\" UŽICE"), finalPaymentDeductions(MonthKey(2026, 8), listOf(slip)).map { it.name })
        assertEquals(listOf("OZ \"DUNAV\" UŽICE"), finalPaymentDeductions(MonthKey(2026, 9), listOf(slip)).map { it.name })
    }

    @Test
    fun mealDeductionStaysWithItsOwnMonth() {
        val repo = repoWith(august, june, july)

        assertEquals(0, BigDecimal("5335.00").compareTo(repo.getSavedPayslip(2026, 6)!!.mealDeduction))
        assertEquals(0, BigDecimal("6030.00").compareTo(repo.getSavedPayslip(2026, 7)!!.mealDeduction))
        assertEquals(0, BigDecimal("7783.60").compareTo(repo.getSavedPayslip(2026, 8)!!.mealDeduction))
    }

    @Test
    fun oldUnionSettingFromBackupIsIgnored() {
        val repo = repoWith()
        val json = JSONObject(repo.exportJson()).put("unionMember", true).put("unionDuesRate", 0.02)

        val restored = testRepository()
        assertTrue(restored.importFromJson(json.toString()))

        assertEquals(0.0, restored.getNetPayBreakdown(2026, 6).unionDuesNet, 0.0001)
        assertFalse(JSONObject(restored.exportJson()).has("unionMember"))
        assertFalse(JSONObject(restored.exportJson()).has("unionDuesRate"))
    }
}
