package com.rad.shifttracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OptionsDialogTextTest {
    @Test
    fun `options dialog exposes cloud export and holidays`() {
        assertEquals(
            listOf(
                "Google cloud",
                "Export rada i obračuna za izabrani mesec",
                "Debug export za ChatGPT – svi meseci",
                "Praznici"
            ),
            optionsDialogActionLabels()
        )
    }

    @Test
    fun `test export actions are hidden from options`() {
        assertFalse(SHOW_TEST_EXPORT_ACTIONS)
    }

    @Test
    fun `bottom bar exposes blood donation shortcut label`() {
        assertEquals("Krv", BLOOD_DONATION_BOTTOM_BAR_LABEL)
    }

    @Test
    fun `options dialog exposes transport toggle for pay cards below calendar`() {
        assertEquals("Prikaz ispod kalendara", MAIN_PAY_TRANSPORT_SECTION_LABEL)
        assertEquals("Prevoz u iznosu za isplatu", MAIN_PAY_TRANSPORT_LABEL)

        val optionsSource = listOf(
            java.io.File("src/main/java/com/rad/shifttracker/OptionsDialog.kt"),
            java.io.File("app/src/main/java/com/rad/shifttracker/OptionsDialog.kt")
        ).first { it.exists() }.readText()
        assertTrue(optionsSource.contains("title = MAIN_PAY_TRANSPORT_LABEL"))
        assertTrue(optionsSource.contains("repo.mainPayIncludesTransport = checked"))

        val screenSource = listOf(
            java.io.File("src/main/java/com/rad/shifttracker/ShiftTrackerScreen.kt"),
            java.io.File("app/src/main/java/com/rad/shifttracker/ShiftTrackerScreen.kt")
        ).first { it.exists() }.readText()
        assertEquals(2, screenSource.split("includeTransport = mainPayIncludesTransport").size - 1)
    }

    @Test
    fun `pay cards below calendar drop transport only when toggle is off`() {
        val receipt = MonthlyReceipt(
            year = 2026,
            month = 8,
            salaryMonth = MonthKey(2026, 7),
            salaryNetPay = 100_000.0,
            regressNetPay = 20_000.0,
            transportEligibleDays = 10,
            transportDailyAmount = 240.0
        )

        assertEquals(122_400.0, mainPayCardAmount(receipt, includeTransport = true), 0.001)
        assertEquals(120_000.0, mainPayCardAmount(receipt, includeTransport = false), 0.001)
        assertEquals(receipt.totalReceivedThisMonth, mainPayCardAmount(receipt, includeTransport = true), 0.001)
    }

    @Test
    fun `options dialog does not expose exact alarm permission action`() {
        val source = listOf(
            java.io.File("src/main/java/com/rad/shifttracker/OptionsDialog.kt"),
            java.io.File("app/src/main/java/com/rad/shifttracker/OptionsDialog.kt")
        ).first { it.exists() }.readText()

        assertFalse(source.contains("ExactAlarmPermissionStatus("))
        assertFalse(source.contains("onExactAlarmSettingsClick"))
        assertFalse(source.contains("EXACT_ALARM_PERMISSION_ACTION_LABEL"))
        assertFalse(source.contains("exactAlarmPermissionStatusText"))
    }

    @Test
    fun `main activity no longer asks for exact alarm permission`() {
        val source = listOf(
            java.io.File("src/main/java/com/rad/shifttracker/MainActivity.kt"),
            java.io.File("app/src/main/java/com/rad/shifttracker/MainActivity.kt")
        ).first { it.exists() }.readText()

        assertTrue(source.contains("override fun onResume()"))
        assertFalse(source.contains("ExactAlarm"))
        assertFalse(source.contains("FoodReceiptReminder"))
    }

    @Test
    fun `manifest has no exact alarm permission or food receipt receiver`() {
        val manifest = listOf(
            java.io.File("src/main/AndroidManifest.xml"),
            java.io.File("app/src/main/AndroidManifest.xml")
        ).first { it.exists() }.readText()

        assertFalse(manifest.contains("SCHEDULE_EXACT_ALARM"))
        assertFalse(manifest.contains("USE_EXACT_ALARM"))
        assertFalse(manifest.contains("FoodReceiptReminderReceiver"))
        assertTrue(manifest.contains(".BloodDonationReminderReceiver"))
    }

    @Test
    fun `difficult conditions and transport moved from lista to options`() {
        val options = listOf(
            java.io.File("src/main/java/com/rad/shifttracker/OptionsDialog.kt"),
            java.io.File("app/src/main/java/com/rad/shifttracker/OptionsDialog.kt")
        ).first { it.exists() }.readText()
        val lista = listOf(
            java.io.File("src/main/java/com/rad/shifttracker/PayslipReaderDialog.kt"),
            java.io.File("app/src/main/java/com/rad/shifttracker/PayslipReaderDialog.kt")
        ).first { it.exists() }.readText()

        assertTrue(options.contains("SectionLabel(text = MANUAL_PAY_SETTINGS_SECTION_LABEL)"))
        // Sekcija „Odbici od plate“ (Član sindikata) je ukinuta: obustave su samo sa liste.
        assertFalse(options.contains("Odbici od plate"))
        // Otežani uslovi se više ne kucaju: upisuje ih lista (šifra 21), ovde se samo prikazuju.
        assertFalse(options.contains("repo.difficultWorkConditionsPercentValue ="))
        assertTrue(options.contains("difficultWorkConditionsStatusText(repo.difficultWorkConditionsPercentValue)"))
        // Prevoz po danu se više ne kuca: samo se prikazuje, upisuje ga obračun prevoza.
        assertFalse(options.contains("repo.transportDailyAmountValue ="))
        assertTrue(options.contains("transportDailyAmountStatusText(repo.transportDailyAmountValue)"))
        assertFalse(lista.contains("difficultWorkConditionsPercentValue"))
        assertFalse(lista.contains("transportDailyAmountValue"))
    }

    @Test
    fun `difficult conditions status shows percent from payslip or none`() {
        assertEquals("Otežani uslovi rada: 32%", difficultWorkConditionsStatusText(32.0))
        assertEquals("Otežani uslovi rada: nema", difficultWorkConditionsStatusText(0.0))
    }

    @Test
    fun `options dialog has no manual loan anymore`() {
        val source = listOf(
            java.io.File("src/main/java/com/rad/shifttracker/OptionsDialog.kt"),
            java.io.File("app/src/main/java/com/rad/shifttracker/OptionsDialog.kt")
        ).first { it.exists() }.readText()

        assertFalse(source.contains("CreditDeduction"))
        assertFalse(source.contains("Kredit"))
        // Obustave u % (sindikat) idu samo sa liste, bez prekidača „Član sindikata“.
        assertFalse(source.contains("UnionDues"))
        assertFalse(source.contains("sindikat", ignoreCase = true))
    }
}
