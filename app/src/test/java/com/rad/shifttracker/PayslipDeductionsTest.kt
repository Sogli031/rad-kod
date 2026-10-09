package com.rad.shifttracker

import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.rad.shifttracker.obracun.FinalPaymentDeduction
import com.rad.shifttracker.listici.ParsedPayslip
import com.rad.shifttracker.listici.PayslipDeduction
import com.rad.shifttracker.listici.PayslipParseResult
import com.rad.shifttracker.listici.finalPaymentDeductions
import com.rad.shifttracker.listici.parsePayslip
import com.rad.shifttracker.listici.payslipFromJsonOrNull
import com.rad.shifttracker.uvoz.payslipRefreshMonths
import com.rad.shifttracker.listici.payslipToJson
import com.rad.shifttracker.obracun.REGRESS_MONTHS
import com.rad.shifttracker.repo.foodPerDayValue
import com.rad.shifttracker.repo.getAdvancePaymentPrediction
import com.rad.shifttracker.repo.getMonthlyFinalPaymentBreakdown
import com.rad.shifttracker.repo.savePayslip
import com.rad.shifttracker.smene.MonthKey
import com.rad.shifttracker.smene.Shift
import com.rad.shifttracker.repo.getRegressNetForMonth
import com.rad.shifttracker.repo.setMonthlyAdvancePaymentNet
import com.rad.shifttracker.listici.PAYSLIP_PARSER_VERSION

private const val DEDUCTIONS_TABLE_HEADER = "Oznaka i naziv obustava Procenat/Ostatak duga Ostalo rata Iznos obustave"

/** Lista 08/2026 premeštena u [month] 2026, sa datim redovima u tabeli obustava. */
internal fun payslipWithDeductions(month: Int, vararg rows: String): ParsedPayslip {
    val header = "OBRAČUN ZARADE I NAKNADE ZARADE ZA MESEC %02d/2026 rb.3/konačna".format(month)
    // Regres je na listi samo u 2, 5, 8 i 11.
    val items = AUGUST_2026.drop(1).filterNot { month !in REGRESS_MONTHS && it.startsWith("REG ") }
    val lines = listOf(header) + items + "OBUSTAVE IZ ZARADE I NAKNADE ZARADE" +
        DEDUCTIONS_TABLE_HEADER + rows.toList() + "Ukupno obustava 0.00"
    return (parsePayslip(lines) as PayslipParseResult.Success).payslip
}

class PayslipDeductionsTest {
    @Test
    fun readsNameAmountAndRemainingInstallmentsWithoutMeal() {
        val slip = payslipWithDeductions(
            8,
            "T08261 Topli obrok 0.00 0 4,340.00",
            "000451 NLB Komercijalna banka 2,920,783.99 62 49,864.00"
        )

        val loan = slip.deductions!!.single()
        assertEquals("000451", loan.code)
        assertEquals("NLB Komercijalna banka", loan.name)
        assertEquals(0, BigDecimal("49864.00").compareTo(loan.amount))
        assertEquals(0, BigDecimal("2920783.99").compareTo(loan.remainingDebt))
        assertEquals(62, loan.remainingInstallments)
        assertEquals(0, BigDecimal("4340.00").compareTo(slip.mealDeduction))
    }

    @Test
    fun skipsDeductionsMarkedAsNotDeducted() {
        // 04/2026: deo rate nije obustavljen (nije bilo dovoljno zarade), pa je u redu sa „*“.
        val lines = listOf("OBRAČUN ZARADE I NAKNADE ZARADE ZA MESEC 04/2026 rb.2/konačna") + AUGUST_2026.drop(1) +
            DEDUCTIONS_TABLE_HEADER +
            "T04261 Topli obrok 0.00 0 4,000.00" +
            "* 000451 NLB Komercijalna banka 3,112,636.16 65 8,165.77" +
            "000451 NLB Komercijalna banka 3,112,636.16 65 41,698.23" +
            "Obustave obeležene * nisu obustavljene Ukupno obustava 45,698.23"
        val slip = (parsePayslip(lines) as PayslipParseResult.Success).payslip

        assertEquals(listOf("41698.23"), slip.deductions!!.map { it.amount.toPlainString() })
    }

    @Test
    fun readsPercentAndFixedDeductions() {
        val slip = payslipWithDeductions(
            8,
            "000001 OZ \"DUNAV\" UŽICE 150.00",
            "000010 FOND SOLIDARNOSTI BAKAR I DOO 1.00 % 1,518.62",
            "000006 SINDIKAT NEZAVISNOST ČLANARINA 1.50 % 2,277.92"
        )

        val deductions = slip.deductions!!
        assertEquals(listOf("OZ \"DUNAV\" UŽICE", "FOND SOLIDARNOSTI BAKAR I DOO", "SINDIKAT NEZAVISNOST ČLANARINA"), deductions.map { it.name })
        assertNull(deductions[0].remainingInstallments)
        assertNull(deductions[0].percent)
        assertEquals(0, BigDecimal("1.50").compareTo(deductions[2].percent))
        assertEquals(0, BigDecimal("2277.92").compareTo(deductions[2].amount))
    }

    @Test
    fun payslipWithoutDeductionsTableHasEmptyList() {
        val slip = (parsePayslip(AUGUST_2026) as PayslipParseResult.Success).payslip
        assertEquals(emptyList<PayslipDeduction>(), slip.deductions)
    }

    @Test
    fun deductionsSurviveJsonAndOldJsonHasNone() {
        val slip = payslipWithDeductions(8, "000451 NLB Komercijalna banka 2,920,783.99 62 49,864.00")
        val restored = payslipFromJsonOrNull(payslipToJson(slip))!!
        assertEquals(slip.deductions, restored.deductions)

        val old = payslipFromJsonOrNull(payslipToJson(slip.copy(deductions = null)))!!
        assertNull(old.deductions)
    }

    @Test
    fun installmentLoanContinuesForTheRemainingMonths() {
        // Dunav osiguranje: na listi 07/2025 ostale su 2 rate, pa ide još u 08 i 09, a u 10 više ne.
        val july = payslipWithDeductions(7, "000118 DUNAV OSIGURANJE A.D.O. BEOGRAD 4,400.00 2 2,200.00")
            .copy(month = MonthKey(2025, 7))
        fun names(month: Int) = finalPaymentDeductions(MonthKey(2025, month), listOf(july)).map { it.name }

        assertEquals(listOf("DUNAV OSIGURANJE A.D.O. BEOGRAD"), names(7))
        assertEquals(listOf("DUNAV OSIGURANJE A.D.O. BEOGRAD"), names(8))
        assertEquals(listOf("DUNAV OSIGURANJE A.D.O. BEOGRAD"), names(9))
        assertEquals(emptyList<String>(), names(10))
    }

    @Test
    fun sixMonthLoanStopsAfterTheLastInstallment() {
        // Pozajmica 60.000 na 6 rata od 10.000, prva rata na listi 03/2026: „Ostalo rata“ 5 posle nje.
        // Bez novih listića rate idu 03–08, a od 09 je više nema.
        val march = payslipWithDeductions(3, "000777 POZAJMICA FIRMA 50,000.00 5 10,000.00")
        fun amount(month: Int) = finalPaymentDeductions(MonthKey(2026, month), listOf(march)).sumOf { it.amountNet }

        assertEquals(listOf(10_000.0, 10_000.0, 10_000.0, 10_000.0, 10_000.0, 10_000.0), (3..8).map(::amount))
        assertEquals(0.0, amount(9), 0.001)
        assertEquals(0.0, amount(12), 0.001)
        assertEquals(0.0, amount(2), 0.001)

        // Kad stigne nova lista (05/2026, ostale 3 rate), računa se od nje i kraj je opet 08.
        val may = payslipWithDeductions(5, "000777 POZAJMICA FIRMA 30,000.00 3 10,000.00")
        fun amountWithMay(month: Int) = finalPaymentDeductions(MonthKey(2026, month), listOf(march, may)).sumOf { it.amountNet }
        assertEquals(10_000.0, amountWithMay(8), 0.001)
        assertEquals(0.0, amountWithMay(9), 0.001)

        // Poslednja rata na listi (ostatak duga 0, ostalo 0 rata): sledećeg meseca nema obustave.
        val august = payslipWithDeductions(8, "000777 POZAJMICA FIRMA 0.00 0 10,000.00")
        assertEquals(10_000.0, finalPaymentDeductions(MonthKey(2026, 8), listOf(march, may, august)).sumOf { it.amountNet }, 0.001)
        assertEquals(emptyList<FinalPaymentDeduction>(), finalPaymentDeductions(MonthKey(2026, 9), listOf(march, may, august)))
    }

    @Test
    fun loanFromTheLastPayslipIsCarriedToNextMonths() {
        val august = payslipWithDeductions(
            8,
            "000451 NLB Komercijalna banka 2,920,783.99 62 49,864.00",
            "000432 PREKRŠAJNI SUD 0.00 98 10,000.00",
            "000001 OZ \"DUNAV\" UŽICE 150.00"
        )

        val september = finalPaymentDeductions(MonthKey(2026, 9), listOf(august))

        // Ostatak duga 0 = poslednja rata; fiksna članarina ostaje.
        assertEquals(listOf("NLB Komercijalna banka", "OZ \"DUNAV\" UŽICE"), september.map { it.name })
        assertEquals(49_864.0, september[0].amountNet, 0.001)
        assertEquals(emptyList<FinalPaymentDeduction>(), finalPaymentDeductions(MonthKey(2026, 7), listOf(august)))
    }

    @Test
    fun newerPayslipReplacesTheCarriedDeductions() {
        val july = payslipWithDeductions(7, "000451 NLB Komercijalna banka 2,970,647.99 63 49,864.00")
        val august = payslipWithDeductions(8)

        assertEquals(emptyList<FinalPaymentDeduction>(), finalPaymentDeductions(MonthKey(2026, 9), listOf(july, august)))
    }

    @Test
    fun finalPaymentShowsTheLoanFromTheLastPayslip() {
        val repo = testRepository()
        repo.foodPerDayValue = 0.0
        repo.set(2026, 9, 4, Shift.FIRST, 8, holidayOverride = false)
        repo.savePayslip(payslipWithDeductions(8, "000451 NLB Komercijalna banka 2,920,783.99 62 49,864.00"), withPayBase = false)

        val october = repo.getMonthlyFinalPaymentBreakdown(2026, 10)

        assertEquals(listOf(FinalPaymentDeduction("NLB Komercijalna banka", 49_864.0)), october.deductions)
        assertEquals(49_864.0, october.deductionsNet, 0.001)
    }

    @Test
    fun payslipsSavedByAnOlderParserAreRereadOnce() {
        val repo = testRepository()
        val july = payslipWithDeductions(7, "000451 NLB Komercijalna banka 2,970,647.99 63 49,864.00")
        val august = payslipWithDeductions(8, "000451 NLB Komercijalna banka 2,920,783.99 62 49,864.00")
        repo.savePayslip(july.copy(deductions = null, advanceNet = null, paidNet = null), withPayBase = false)
        repo.savePayslip(august.copy(advanceNet = null, paidNet = null), withPayBase = false)
        assertEquals(listOf(MonthKey(2026, 7), MonthKey(2026, 8)), payslipRefreshMonths(repo))
        assertEquals(listOf(MonthKey(2026, 8)), payslipRefreshMonths(repo, setOf("v$PAYSLIP_PARSER_VERSION:2026-7")))

        repo.savePayslip(july, withPayBase = false)
        repo.savePayslip(august, withPayBase = false)
        assertTrue(payslipRefreshMonths(repo).isEmpty())
    }

    @Test
    fun readsAdvanceAndPaidAmounts() {
        val lines = AUGUST_2026 + DEDUCTIONS_TABLE_HEADER +
            "000451 NLB Komercijalna banka 2,920,783.99 62 49,864.00" + "Ukupno obustava 54,204.00" +
            "UPLATA PO RAČUNIMA" + "Ukupno uplaćeno 9,682.55"
        val slip = (parsePayslip(lines) as PayslipParseResult.Success).payslip

        assertEquals(0, BigDecimal("58942.00").compareTo(slip.advanceNet))
        assertEquals(0, BigDecimal("9682.55").compareTo(slip.paidNet))
        // 06/2026: sve otišlo na obustave, reda „Ukupno uplaćeno“ nema — uplaćeno je 0.
        assertEquals(0, BigDecimal.ZERO.compareTo(payslipWithDeductions(6).paidNet))
        assertNull((parsePayslip(AUGUST_2026) as PayslipParseResult.Success).payslip.paidNet)
    }

    @Test
    fun advanceMinimumComesFromThePayslipEvenWithoutFilledMonths() {
        // Jun: akontacija 37.080, konačna sva otišla na kredit → u julu je leglo 37.080;
        // minimum za avgust = 40% = 14.832 (firma je isplatila 14.837).
        val repo = testRepository()
        repo.savePayslip(payslipWithDeductions(6).copy(advanceNet = BigDecimal("37080.00")), withPayBase = false)

        val august = repo.getAdvancePaymentPrediction(2026, 8)

        assertTrue(august.isAvailable)
        assertEquals(37_080.0, august.previousMonthPaidTotalNet, 0.001)
        assertEquals(14_832.0, august.minimumAdvanceNet, 0.001)
    }

    @Test
    fun advanceMinimumLeavesRegressOutAndPrefersTheRecordedAdvance() {
        // Lista 08/2026: „isplaćeno“ 58.942 = akontacija 38.942 + regres 20.000 (28.08).
        val repo = testRepository()
        repo.savePayslip(
            payslipWithDeductions(8).copy(advanceNet = BigDecimal("58942.00"), paidNet = BigDecimal("9682.55")),
            withPayBase = false
        )

        val fromPayslip = repo.getAdvancePaymentPrediction(2026, 10)
        assertEquals(58_942.0 - repo.getRegressNetForMonth(MonthKey(2026, 8)), fromPayslip.previousMonthAdvanceNet, 0.001)

        repo.setMonthlyAdvancePaymentNet(2026, 9, 38_942.0)
        val recorded = repo.getAdvancePaymentPrediction(2026, 10)
        assertEquals(38_942.0 + 9_682.55, recorded.previousMonthPaidTotalNet, 0.001)
        assertEquals(19_450.0, recorded.minimumAdvanceNet, 0.001)
    }

    @Test
    fun septemberMinimumFromTheJulyPayslipIsBelowWhatWasPaid() {
        val repo = testRepository()
        repo.savePayslip(
            payslipWithDeductions(7).copy(advanceNet = BigDecimal("14837.00"), paidNet = BigDecimal("68357.75")),
            withPayBase = false
        )

        assertEquals(33_278.0, repo.getAdvancePaymentPrediction(2026, 9).minimumAdvanceNet, 0.001)
    }
}
