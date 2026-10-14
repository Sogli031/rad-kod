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
import com.rad.shifttracker.ui.dialozi.PAY_DETAILS_WORK_HOURS_LABEL
import com.rad.shifttracker.ui.dialozi.defaultHourlyHistoryInfoText
import com.rad.shifttracker.ui.dialozi.payDetailsNetSalaryLabel

class PayDetailsDialogTextTest {

    @Test
    fun `pay details uses requested work hours label`() {
        assertEquals("Rad u režiji", PAY_DETAILS_WORK_HOURS_LABEL)
    }

    @Test
    fun `pay details explains missing receipt input`() {
        assertEquals("Nije moguće izračunati", TOTAL_CARD_UNAVAILABLE_TEXT)
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
    }

    @Test
    fun `advance payment labels and unavailable text match requested wording`() {
        assertEquals("Akontacija", ADVANCE_PAYMENT_DIALOG_TITLE)
        assertEquals("Akontacija neto", ADVANCE_PAYMENT_NET_LABEL)
        assertEquals("Neto plata", FINAL_PAYMENT_NET_PAY_LABEL)
        assertEquals("Akontacija", FINAL_PAYMENT_ADVANCE_LABEL)
        assertEquals("Obustave", FINAL_PAYMENT_DEDUCTIONS_LABEL)
        assertEquals("Preostalo za konačnu isplatu", FINAL_PAYMENT_REMAINING_LABEL)
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
            "Fiksno: do 2023. koristi se 185, 2024. 195, 2025. 214.5, od januara 2026. 226 RSD.",
            defaultHourlyHistoryInfoText()
        )
    }

    @Test
    fun `isplata je neto plata, akontacija, obustave, pa 20 tek kad stigne akontacija`() {
        val source = mainSource("PayDetailsDialog.kt")
        val body = source.substring(source.indexOf("private fun PaymentRows("))
        val order = listOf(
            "payDetailsNetSalaryLabel(monthlyReceipt)",
            "AdvancePaymentRow(",
            "FINAL_PAYMENT_DEDUCTIONS_LABEL",
            "payDetailsTotalLabel(monthlyReceipt)",
            "payDetailsTotalOn20Label(monthlyReceipt)"
        ).map { body.indexOf(it) }

        assertTrue(order.all { it >= 0 })
        assertEquals(order.sorted(), order)
        assertTrue(body.contains("if (finalPayment.isNetPayAvailable && finalPayment.advancePaymentNet > 0.0)"))
        // Regres i prevoz se ne prikazuju u isplati.
        assertTrue(!source.contains("regressNetPay"))
        assertTrue(!source.contains("transportAmount"))
        assertTrue(source.contains("ADVANCE_NOT_PAID_TEXT"))
        assertTrue(source.contains("AdvanceMinimumText("))
        assertTrue(!source.contains("FINAL_PAYMENT_REMAINING_LABEL"))
        assertTrue(!source.contains("MonthlyPaymentInputRows("))
        assertEquals("nije još uplaćena", ADVANCE_NOT_PAID_TEXT)
        assertTrue(mainSource("PayDetailsPaymentInputs.kt").contains("Minimalna akontacija: "))
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

        assertTrue(source.contains("PayDetailsSectionCard("))
    }
}
