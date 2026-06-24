package com.rad.shifttracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PayDetailsDialogTextTest {

    @Test
    fun `pay details uses requested work hours label`() {
        assertEquals("Rad u režiji", PAY_DETAILS_WORK_HOURS_LABEL)
    }

    @Test
    fun `annual net pay average labels match requested wording`() {
        assertEquals(
            listOf(
                "Prosek plate",
                "Bez regresa",
                "Bez regresa i treće smene"
            ),
            annualNetPayAverageLabels()
        )
    }

    @Test
    fun `recent net pay average text names net average and incomplete data`() {
        val average = RecentNetPayAverage(
            listOf(
                RecentNetPayMonth(MonthKey(2025, 12), 100_000.0),
                RecentNetPayMonth(MonthKey(2026, 1), null),
                RecentNetPayMonth(MonthKey(2026, 2), 120_000.0)
            )
        )

        assertEquals("Neto prosek 3 poslednje plate", RECENT_NET_PAY_AVERAGE_SECTION_LABEL)
        assertEquals("Neto prosek", RECENT_NET_PAY_AVERAGE_VALUE_LABEL)
        assertEquals(
            "Decembar 2025, Januar 2026, Februar 2026",
            recentNetPayAverageMonthsText(average)
        )
        assertEquals(
            "Neto prosek 3 poslednje plate trenutno nije moguće izračunati zbog nepotpunih podataka.",
            RECENT_NET_PAY_AVERAGE_UNAVAILABLE_TEXT
        )
    }

    @Test
    fun `pay details explains annual average and missing receipt input`() {
        assertEquals("Računa samo mesece sa unetim zapisima.", ANNUAL_NET_PAY_AVERAGE_HINT_TEXT)
        assertEquals("Nema unetih meseci za godišnji prosek.", ANNUAL_NET_PAY_AVERAGE_EMPTY_TEXT)
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

        assertEquals("Isplata u Jul 2026", paymentMonthTitleText(receipt))
        assertEquals("Obračun za Jun 2026", payrollMonthTitleText(receipt))
        assertEquals(
            "Procena uplate na račun za obračunski mesec Jun 2026, uključujući odvojenu stavku prevoza.",
            payDetailsReceiptDescriptionText(receipt)
        )
        assertEquals(
            "4560.00 RSD",
            payDetailsTransportAmountText(receipt)
        )
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
        assertEquals("Prevoz za April", payDetailsTransportLabel(receipt))
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
    fun `pay details shows hourly value label and text`() {
        assertEquals("Vrednost sata", PAY_DETAILS_HOURLY_VALUE_LABEL)
        assertEquals("226 RSD", payDetailsHourlyValueText(226.0))
        assertEquals("214.5 RSD", payDetailsHourlyValueText(214.5))
    }

    @Test
    fun `advance payment labels and unavailable text match requested wording`() {
        assertEquals("Akontacija", ADVANCE_PAYMENT_DIALOG_TITLE)
        assertEquals("Akontacija neto", ADVANCE_PAYMENT_NET_LABEL)
        assertEquals(
            "Unesi neto iznos akontacije koji je već isplaćen. Ovaj iznos se ne koristi za obračun bruto plate, poreza i doprinosa, već samo umanjuje iznos koji ostaje za konačnu isplatu.",
            ADVANCE_PAYMENT_EXPLANATION_TEXT
        )
        assertEquals("Neto plata", FINAL_PAYMENT_NET_PAY_LABEL)
        assertEquals("Isplaćena akontacija", FINAL_PAYMENT_ADVANCE_LABEL)
        assertEquals("Obustave", FINAL_PAYMENT_DEDUCTIONS_LABEL)
        assertEquals("Preostalo za konačnu isplatu", FINAL_PAYMENT_REMAINING_LABEL)
        assertEquals("Preostalo 20.", FINAL_PAYMENT_REMAINING_20_LABEL)
        assertEquals("Preostalo regres", FINAL_PAYMENT_REMAINING_REGRESS_LABEL)
        assertEquals("Kredit", FINAL_PAYMENT_LOAN_DEDUCTION_LABEL)
        assertEquals("Hrana prekoračeno", FINAL_PAYMENT_FOOD_OVERAGE_LABEL)
        assertEquals("Ostale obustave", FINAL_PAYMENT_OTHER_DEDUCTIONS_LABEL)
        assertEquals("ručno uneto", MANUAL_ENTRY_BADGE_TEXT)
        assertEquals("Hrana nije prekoračena", FOOD_NOT_OVERAGE_TEXT)
        assertEquals("Neiskorišćeno", FOOD_UNUSED_LABEL)
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
        val source = listOf(
            java.io.File("src/main/java/com/rad/shifttracker/PayDetailsDialog.kt"),
            java.io.File("app/src/main/java/com/rad/shifttracker/PayDetailsDialog.kt")
        ).first { it.exists() }.readText()

        assertEquals(72, PAY_DETAILS_MIN_BOTTOM_SAFE_PADDING_DP)
        assertTrue(source.contains("WindowInsets.safeDrawing.asPaddingValues()"))
        assertTrue(source.contains("WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()"))
        assertTrue(source.contains("maxOf("))
        assertTrue(source.contains("PAY_DETAILS_MIN_BOTTOM_SAFE_PADDING_DP.dp"))
        assertTrue(!source.contains("WindowInsets.systemBars"))
    }

    @Test
    fun `recent net pay average shows value before months list`() {
        val source = listOf(
            java.io.File("src/main/java/com/rad/shifttracker/PayDetailsDialog.kt"),
            java.io.File("app/src/main/java/com/rad/shifttracker/PayDetailsDialog.kt")
        ).first { it.exists() }.readText()
        val sectionIndex = source.indexOf("SectionLabel(text = RECENT_NET_PAY_AVERAGE_SECTION_LABEL)")
        val valueIndex = source.indexOf("CompactMoneyRow(RECENT_NET_PAY_AVERAGE_VALUE_LABEL", sectionIndex)
        val monthsIndex = source.indexOf("CompactTextBlock(\"Meseci\"", sectionIndex)

        assertTrue(sectionIndex >= 0)
        assertTrue(valueIndex > sectionIndex)
        assertTrue(monthsIndex > valueIndex)
    }

    @Test
    fun `advance payment action is inside pay details`() {
        val dialogSource = listOf(
            java.io.File("src/main/java/com/rad/shifttracker/PayDetailsDialog.kt"),
            java.io.File("app/src/main/java/com/rad/shifttracker/PayDetailsDialog.kt")
        ).first { it.exists() }.readText()
        val inputSource = listOf(
            java.io.File("src/main/java/com/rad/shifttracker/PayDetailsPaymentInputs.kt"),
            java.io.File("app/src/main/java/com/rad/shifttracker/PayDetailsPaymentInputs.kt")
        ).first { it.exists() }.readText()
        val finalPaymentIndex = dialogSource.indexOf("FinalPaymentRows(")
        val inlineInputsIndex = dialogSource.indexOf("MonthlyPaymentInputRows(", finalPaymentIndex)

        assertTrue(finalPaymentIndex >= 0)
        assertTrue(inlineInputsIndex > finalPaymentIndex)
        assertTrue(inputSource.contains("Modifier.weight(0.7f)"))
        assertTrue(inputSource.contains("Modifier.weight(0.3f)"))
        assertTrue(!dialogSource.contains("AdvancePaymentButton("))
    }

    @Test
    fun `payment input save button uses adaptive minimum height`() {
        val inputSource = listOf(
            java.io.File("src/main/java/com/rad/shifttracker/PayDetailsPaymentInputs.kt"),
            java.io.File("app/src/main/java/com/rad/shifttracker/PayDetailsPaymentInputs.kt")
        ).first { it.exists() }.readText()

        assertTrue(inputSource.contains("Modifier.weight(0.3f).heightIn(min = 56.dp)"))
        assertTrue(!inputSource.contains("Modifier.weight(0.3f).height(56.dp)"))
    }

    @Test
    fun `final payment rows hide empty deductions and show regress separately`() {
        val source = listOf(
            java.io.File("src/main/java/com/rad/shifttracker/PayDetailsDialog.kt"),
            java.io.File("app/src/main/java/com/rad/shifttracker/PayDetailsDialog.kt")
        ).first { it.exists() }.readText()

        assertTrue(source.contains("if (finalPayment.deductionsNet > 0.0)"))
        assertTrue(source.contains("FINAL_PAYMENT_FOOD_OVERAGE_LABEL"))
        assertTrue(source.contains("FINAL_PAYMENT_REMAINING_20_LABEL"))
        assertTrue(source.contains("FINAL_PAYMENT_REMAINING_REGRESS_LABEL"))
        assertTrue(source.contains("if (finalPayment.remainingRegressNetPay > 0.0)"))
    }

    @Test
    fun `final payment deductions show loan separately and food overage only when present`() {
        val source = listOf(
            java.io.File("src/main/java/com/rad/shifttracker/PayDetailsDialog.kt"),
            java.io.File("app/src/main/java/com/rad/shifttracker/PayDetailsDialog.kt")
        ).first { it.exists() }.readText()
        val deductionsIndex = source.indexOf("FINAL_PAYMENT_DEDUCTIONS_LABEL")
        val loanIndex = source.indexOf("FINAL_PAYMENT_LOAN_DEDUCTION_LABEL", deductionsIndex)
        val foodIndex = source.indexOf("FINAL_PAYMENT_FOOD_OVERAGE_LABEL", deductionsIndex)

        assertTrue(deductionsIndex >= 0)
        assertTrue(loanIndex > deductionsIndex)
        assertTrue(foodIndex > loanIndex)
        assertTrue(source.contains("finalPayment.loanDeductionNet > 0.0"))
        assertTrue(source.contains("finalPayment.foodOverageDeductionNet > 0.0"))
    }

    @Test
    fun `food spending input shows net allowance spent and basic data only shows entitlement`() {
        val dialogSource = listOf(
            java.io.File("src/main/java/com/rad/shifttracker/PayDetailsDialog.kt"),
            java.io.File("app/src/main/java/com/rad/shifttracker/PayDetailsDialog.kt")
        ).first { it.exists() }.readText()
        val inputSource = listOf(
            java.io.File("src/main/java/com/rad/shifttracker/PayDetailsPaymentInputs.kt"),
            java.io.File("app/src/main/java/com/rad/shifttracker/PayDetailsPaymentInputs.kt")
        ).first { it.exists() }.readText()

        assertTrue(dialogSource.contains("\"Hrana pravo\""))
        assertTrue(!dialogSource.contains("\"Hrana obračunato\""))
        assertTrue(!dialogSource.contains("\"Hrana u obračunu\""))
        assertTrue(!dialogSource.contains("\"Hrana razlika\""))
        assertTrue(!dialogSource.contains("TO2 neto se računa"))
        assertTrue(inputSource.contains("FOOD_ALLOWANCE_STATUS_LABEL"))
        assertTrue(inputSource.contains("FOOD_ACTUAL_SPENT_STATUS_LABEL"))
        assertTrue(!dialogSource.contains("TO2 bruto"))
        assertTrue(!inputSource.contains("TO2 bruto"))
    }

    @Test
    fun `manual payout input labels use payroll month`() {
        val payrollMonth = MonthKey(2026, 4)
        val inputSource = listOf(
            java.io.File("src/main/java/com/rad/shifttracker/PayDetailsPaymentInputs.kt"),
            java.io.File("app/src/main/java/com/rad/shifttracker/PayDetailsPaymentInputs.kt")
        ).first { it.exists() }.readText()

        assertEquals("Hrana potrošeno za April 2026", actualFoodSpentInputLabel(payrollMonth))
        assertTrue(!inputSource.contains("Stvarni neto"))
        assertTrue(!inputSource.contains("actualSalaryNetInputLabel"))
        assertTrue(!inputSource.contains("setMonthlyActualSalaryNetPay"))
    }

    @Test
    fun `compact rows autosize labels to avoid unnecessary ellipsis`() {
        val source = listOf(
            java.io.File("src/main/java/com/rad/shifttracker/PayDetailsDialog.kt"),
            java.io.File("app/src/main/java/com/rad/shifttracker/PayDetailsDialog.kt")
        ).first { it.exists() }.readText()

        assertTrue(source.contains("AutoSizeText(\n            text = label"))
    }

    @Test
    fun `pay details extracts visual card shells`() {
        val source = listOf(
            java.io.File("src/main/java/com/rad/shifttracker/PayDetailsDialog.kt"),
            java.io.File("app/src/main/java/com/rad/shifttracker/PayDetailsDialog.kt")
        ).first { it.exists() }.readText()

        assertTrue(source.contains("PayDetailsHeroCard("))
        assertTrue(source.contains("PayDetailsSectionCard("))
    }
}
