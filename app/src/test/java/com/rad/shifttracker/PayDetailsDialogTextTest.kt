package com.rad.shifttracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import com.rad.shifttracker.obracun.ADVANCE_NOT_PAID_TEXT
import com.rad.shifttracker.obracun.ADVANCE_PAYMENT_DIALOG_TITLE
import com.rad.shifttracker.obracun.ADVANCE_PAYMENT_NET_LABEL
import com.rad.shifttracker.obracun.FINAL_PAYMENT_ADVANCE_LABEL
import com.rad.shifttracker.obracun.FINAL_PAYMENT_ADVANCE_WARNING_TEXT
import com.rad.shifttracker.obracun.FINAL_PAYMENT_DEDUCTIONS_LABEL
import com.rad.shifttracker.obracun.FINAL_PAYMENT_NET_PAY_LABEL
import com.rad.shifttracker.obracun.FINAL_PAYMENT_REMAINING_20_LABEL
import com.rad.shifttracker.obracun.FINAL_PAYMENT_REMAINING_LABEL
import com.rad.shifttracker.obracun.FINAL_PAYMENT_REMAINING_REGRESS_LABEL
import com.rad.shifttracker.obracun.FINAL_PAYMENT_UNAVAILABLE_TEXT
import com.rad.shifttracker.obracun.FOOD_NOT_OVERAGE_TEXT
import com.rad.shifttracker.obracun.MANUAL_ENTRY_BADGE_TEXT
import com.rad.shifttracker.obracun.MEAL_SPENT_ASSUMED_HELP_TEXT
import com.rad.shifttracker.obracun.MEAL_SPENT_MANUAL_HELP_TEXT
import com.rad.shifttracker.obracun.MonthlyReceipt
import com.rad.shifttracker.obracun.calculateMonthlyFoodSpendingBreakdown
import com.rad.shifttracker.obracun.mealSpentSupportingText
import com.rad.shifttracker.smene.MonthKey
import com.rad.shifttracker.ui.TOTAL_CARD_UNAVAILABLE_TEXT
import com.rad.shifttracker.ui.dialozi.PAY_DETAILS_MIN_BOTTOM_SAFE_PADDING_DP
import com.rad.shifttracker.ui.dialozi.PAY_DETAILS_RECEIPT_UNAVAILABLE_TEXT
import com.rad.shifttracker.ui.dialozi.PAY_DETAILS_WORK_HOURS_LABEL
import com.rad.shifttracker.ui.dialozi.defaultHourlyHistoryInfoText
import com.rad.shifttracker.ui.dialozi.payDetailsNetSalaryLabel
import com.rad.shifttracker.ui.dialozi.payDetailsReceiptDescriptionText
import com.rad.shifttracker.ui.dialozi.payDetailsRegressLabel
import com.rad.shifttracker.ui.dialozi.payDetailsTotalLabel
import com.rad.shifttracker.ui.dialozi.paymentMonthTitleText
import com.rad.shifttracker.ui.dialozi.payrollMonthTitleText

class PayDetailsDialogTextTest {

    @Test
    fun `pay details uses requested work hours label`() {
        assertEquals("Rad u režiji", PAY_DETAILS_WORK_HOURS_LABEL)
    }

    @Test
    fun `pay details explains missing receipt input`() {
        assertEquals("Nije moguće izračunati", TOTAL_CARD_UNAVAILABLE_TEXT)
        assertEquals("Nedostaje obračun prethodnog meseca.", PAY_DETAILS_RECEIPT_UNAVAILABLE_TEXT)
    }

    @Test
    fun `receipt labels separate payment and payroll month`() {
        val receipt = MonthlyReceipt(
            year = 2026,
            month = 7,
            salaryMonth = MonthKey(2026, 6),
            salaryNetPay = 92_131.0,
            regressNetPay = 0.0,
            transportEligibleDays = 19,
            transportDailyAmount = 240.0
        )

        assertEquals("Isplata u Julu 2026", paymentMonthTitleText(receipt))
        assertEquals("Isplata u Januaru 2027", paymentMonthTitleText(receipt.copy(year = 2027, month = 1)))
        assertEquals("Obračun za Jun 2026", payrollMonthTitleText(receipt))
        assertEquals(null, payDetailsReceiptDescriptionText(receipt))
    }

    @Test
    fun `receipt labels current payment month regress separately`() {
        val receipt = MonthlyReceipt(
            year = 2026,
            month = 5,
            salaryMonth = MonthKey(2026, 4),
            salaryNetPay = 100_000.0,
            regressNetPay = 20_000.0,
            transportEligibleDays = 21,
            transportDailyAmount = 240.0
        )

        assertEquals("Neto plata za April", payDetailsNetSalaryLabel(receipt))
        assertEquals("Regres za Maj", payDetailsRegressLabel(receipt))
        assertEquals("Ukupno za isplatu u Maju", payDetailsTotalLabel(receipt))
    }

    @Test
    fun `receipt total label uses payment month locative`() {
        val months = listOf(
            1 to "Ukupno za isplatu u Januaru",
            2 to "Ukupno za isplatu u Februaru",
            3 to "Ukupno za isplatu u Martu",
            4 to "Ukupno za isplatu u Aprilu",
            5 to "Ukupno za isplatu u Maju",
            6 to "Ukupno za isplatu u Junu",
            7 to "Ukupno za isplatu u Julu",
            8 to "Ukupno za isplatu u Avgustu",
            9 to "Ukupno za isplatu u Septembru",
            10 to "Ukupno za isplatu u Oktobru",
            11 to "Ukupno za isplatu u Novembru",
            12 to "Ukupno za isplatu u Decembru"
        )

        months.forEach { (month, expectedLabel) ->
            val receipt = MonthlyReceipt(
                year = 2026,
                month = month,
                salaryMonth = MonthKey(2026, month.minus(1).takeIf { it >= 1 } ?: 12),
                salaryNetPay = 100_000.0,
                regressNetPay = 0.0
            )

            assertEquals(expectedLabel, payDetailsTotalLabel(receipt))
        }
    }

    @Test
    fun `advance payment labels and unavailable text match requested wording`() {
        assertEquals("Akontacija", ADVANCE_PAYMENT_DIALOG_TITLE)
        assertEquals("Akontacija neto", ADVANCE_PAYMENT_NET_LABEL)
        assertEquals("Neto plata", FINAL_PAYMENT_NET_PAY_LABEL)
        assertEquals("Akontacija", FINAL_PAYMENT_ADVANCE_LABEL)
        assertEquals("Obustave", FINAL_PAYMENT_DEDUCTIONS_LABEL)
        assertEquals("Preostalo za konačnu isplatu", FINAL_PAYMENT_REMAINING_LABEL)
        assertEquals("Preostalo 20.", FINAL_PAYMENT_REMAINING_20_LABEL)
        assertEquals("Preostalo regres", FINAL_PAYMENT_REMAINING_REGRESS_LABEL)
        assertEquals("ručno uneto", MANUAL_ENTRY_BADGE_TEXT)
        assertEquals("Hrana nije prekoračena", FOOD_NOT_OVERAGE_TEXT)
        assertEquals(
            "Nije moguće izračunati konačnu isplatu jer mesečna plata nije izračunata.",
            FINAL_PAYMENT_UNAVAILABLE_TEXT
        )
        assertEquals(
            "Akontacija je veća od obračunate neto plate.",
            FINAL_PAYMENT_ADVANCE_WARNING_TEXT
        )
    }

    @Test
    fun `fixed hourly history info explains default cutoff`() {
        assertEquals(
            "Fiksno: pre januara 2026. koristi se 214.5 RSD, od januara 2026. koristi se 226 RSD.",
            defaultHourlyHistoryInfoText()
        )
    }

    @Test
    fun `pay details keeps shared bottom safe padding for long content`() {
        val source = mainSource("PayDetailsDialog.kt")

        assertEquals(72, PAY_DETAILS_MIN_BOTTOM_SAFE_PADDING_DP)
        assertTrue(source.contains("WindowInsets.safeDrawing.asPaddingValues()"))
        assertTrue(source.contains("WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()"))
        assertTrue(source.contains("maxOf("))
        assertTrue(source.contains("PAY_DETAILS_MIN_BOTTOM_SAFE_PADDING_DP.dp"))
        assertTrue(!source.contains("WindowInsets.systemBars"))
    }

    @Test
    fun `isplata je jedan spisak - plata, akontacija, regres, obustave, ukupno, pa preostalo 20`() {
        val source = mainSource("PayDetailsDialog.kt")
        val body = source.substring(source.indexOf("private fun PaymentRows("))
        val order = listOf(
            "payDetailsNetSalaryLabel(monthlyReceipt)",
            "AdvancePaymentRow(",
            "payDetailsRegressLabel(monthlyReceipt)",
            "FINAL_PAYMENT_DEDUCTIONS_LABEL",
            "payDetailsTotalLabel(monthlyReceipt)",
            "FINAL_PAYMENT_REMAINING_20_LABEL"
        ).map { body.indexOf(it) }

        assertTrue(order.all { it >= 0 })
        assertEquals(order.sorted(), order)
        assertTrue(body.contains("if (finalPayment.isNetPayAvailable && finalPayment.advancePaymentNet > 0.0)"))
        assertTrue(source.contains("mainPayCardAmount(monthlyReceipt, finalPayment.deductionsNet)"))
        assertTrue(!body.contains("Transport"))
        assertTrue(source.contains("ADVANCE_NOT_PAID_TEXT"))
        assertTrue(source.contains("AdvanceMinimumText("))
        assertTrue(!source.contains("FINAL_PAYMENT_REMAINING_LABEL"))
        assertTrue(!source.contains("MonthlyPaymentInputRows("))
        assertEquals("nije još uplaćena", ADVANCE_NOT_PAID_TEXT)
        assertTrue(mainSource("PayDetailsPaymentInputs.kt").contains("Minimalno po KU: "))
    }

    @Test
    fun `final payment deductions are listed under their payslip names`() {
        val source = mainSource("PayDetailsDialog.kt")
        val deductionsIndex = source.indexOf("FINAL_PAYMENT_DEDUCTIONS_LABEL")
        val rowsIndex = source.indexOf("finalPayment.deductions.forEach", deductionsIndex)

        assertTrue(deductionsIndex >= 0)
        assertTrue(rowsIndex > deductionsIndex)
        assertTrue(source.contains("label = deduction.name"))
        assertTrue(!source.contains("Kredit"))
    }
    @Test
    fun `food spending input shows net allowance spent and basic data only shows entitlement`() {
        val dialogSource = mainSource("PayDetailsDialog.kt")
        val inputSource = mainSource("PayDetailsPaymentInputs.kt")

        assertTrue(!dialogSource.contains("\"Hrana pravo\""))
        assertTrue(!dialogSource.contains("\"Hrana obračunato\""))
        assertTrue(!dialogSource.contains("\"Hrana u obračunu\""))
        assertTrue(!dialogSource.contains("\"Hrana razlika\""))
        assertTrue(!dialogSource.contains("TO2 neto se računa"))
        assertTrue(!inputSource.contains("FOOD_ALLOWANCE_STATUS_LABEL"))
        assertTrue(!inputSource.contains("FOOD_UNUSED_LABEL"))
        assertTrue(!dialogSource.contains("TO2 bruto"))
        assertTrue(!inputSource.contains("TO2 bruto"))
    }

    @Test
    fun `standardni UI nema nikakav unos toplog obroka`() {
        val inputSource = mainSource("PayDetailsPaymentInputs.kt")

        // Topli obrok se računa kao da je pojedeno koliko pripada; prekoračenje javlja „Lista“.
        assertTrue(!inputSource.contains("ACTUAL_FOOD_SPENT_INPUT_LABEL"))
        assertTrue(!inputSource.contains("repo.setMonthlyActualFoodSpentNet"))
        assertTrue(!inputSource.contains("Pravo na topli obrok"))
        assertTrue(!inputSource.contains("repo.setMonthlyMealEntitlementNetOverride"))
        assertTrue(!inputSource.contains("label = \"Potrošeno\""))
        assertTrue(!inputSource.contains("\"Ostaje u plati\""))
        assertTrue(!inputSource.contains("MEAL_SAFE_DEFAULT_STATUS_TEXT"))
        assertTrue(!inputSource.contains("Neiskorišćeno"))
        assertTrue(!inputSource.contains("repo.setMonthlyMealGrossOverride"))
        assertTrue(!inputSource.contains("repo.setMonthlyMealSettlementAdjustmentNet"))
        assertTrue(!inputSource.contains("mealGrossInputLabel"))
        assertTrue(!inputSource.contains("mealSettlementInputLabel"))
        assertTrue(!inputSource.contains("Topli obrok — bruto sa obračuna"))
        assertTrue(!inputSource.contains("Ručno unet bruto iznos"))
        assertTrue(!inputSource.contains("filterSignedDecimalInput"))
        assertTrue(!inputSource.contains("MEAL_SPENT_MANUAL_HELP_TEXT"))
        assertTrue(!inputSource.contains("MEAL_SPENT_ASSUMED_HELP_TEXT"))
        assertTrue(!inputSource.contains("mealGross = value ?: food"))
        assertTrue(!inputSource.contains("Stvarni neto"))
        assertTrue(!inputSource.contains("actualSalaryNetInputLabel"))
        assertTrue(!inputSource.contains("setMonthlyActualSalaryNetPay"))
    }

    @Test
    fun `meal spent helper razlikuje rucni i podrazumevani izvor`() {
        val month = MonthKey(2026, 4)
        val assumed = calculateMonthlyFoodSpendingBreakdown(month, 5_880.0, null)
        val manual = calculateMonthlyFoodSpendingBreakdown(month, 5_880.0, 2_240.0)

        assertEquals(MEAL_SPENT_ASSUMED_HELP_TEXT, mealSpentSupportingText(assumed))
        assertEquals(MEAL_SPENT_MANUAL_HELP_TEXT, mealSpentSupportingText(manual))
    }

    @Test
    fun `compact rows autosize labels to avoid unnecessary ellipsis`() {
        val source = mainSource("PayDetailsDialog.kt")

        assertTrue(source.contains("AutoSizeText(\n            text = label"))
    }

    @Test
    fun `pay details extracts visual card shells`() {
        val source = mainSource("PayDetailsDialog.kt")

        assertTrue(source.contains("PayDetailsHeroCard("))
        assertTrue(source.contains("PayDetailsSectionCard("))
    }
}
