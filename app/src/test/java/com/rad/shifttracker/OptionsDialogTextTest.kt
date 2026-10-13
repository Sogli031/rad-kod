package com.rad.shifttracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.rad.shifttracker.krv.BLOOD_DONATION_BOTTOM_BAR_LABEL
import com.rad.shifttracker.obracun.MonthlyReceipt
import com.rad.shifttracker.smene.MonthKey
import com.rad.shifttracker.ui.dialozi.SHOW_TEST_EXPORT_ACTIONS
import com.rad.shifttracker.ui.dialozi.difficultWorkConditionsStatusText
import com.rad.shifttracker.ui.dialozi.optionsDialogActionLabels
import com.rad.shifttracker.ui.mainPayCardAmount

class OptionsDialogTextTest {
    @Test
    fun `options dialog exposes cloud and export, without holidays`() {
        assertEquals(
            listOf(
                "Google cloud",
                "Export rada i obračuna za izabrani mesec",
                "Debug export za ChatGPT – svi meseci"
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
    fun `opcije nemaju prekidac samo plata za rad`() {
        val optionsSource = mainSource("OptionsDialog.kt")
        assertTrue(!optionsSource.contains("mainPaySalaryOnly"))
        assertTrue(!mainSource("ShiftTrackerContent.kt").contains("salaryOnly"))
    }

    @Test
    fun `kartice ispod kalendara su neto plata posle obustava, bez regresa i prevoza`() {
        val receipt = MonthlyReceipt(
            year = 2026,
            month = 8,
            salaryMonth = MonthKey(2026, 7),
            salaryNetPay = 100_000.0,
            regressNetPay = 20_000.0,
            transportEligibleDays = 10,
            transportDailyAmount = 240.0
        )

        assertEquals(100_000.0, mainPayCardAmount(receipt), 0.001)
        assertEquals(0.0, mainPayCardAmount(receipt.copy(isSalaryAvailable = false)), 0.001)
        // Obustave sa listića se oduzimaju kao u detaljima isplate, nikad ispod nule.
        assertEquals(60_000.0, mainPayCardAmount(receipt, deductionsNet = 40_000.0), 0.001)
        assertEquals(0.0, mainPayCardAmount(receipt, deductionsNet = 150_000.0), 0.001)
    }

    @Test
    fun `options dialog does not expose exact alarm permission action`() {
        val source = mainSource("OptionsDialog.kt")

        assertFalse(source.contains("ExactAlarmPermissionStatus("))
        assertFalse(source.contains("onExactAlarmSettingsClick"))
        assertFalse(source.contains("EXACT_ALARM_PERMISSION_ACTION_LABEL"))
        assertFalse(source.contains("exactAlarmPermissionStatusText"))
    }

    @Test
    fun `main activity no longer asks for exact alarm permission`() {
        val source = mainSource("MainActivity.kt")

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
        val options = mainSource("OptionsDialog.kt")
        val lista = mainSource("PayslipReaderDialog.kt")

        assertTrue(options.contains("SectionLabel(text = DIFFICULT_WORK_CONDITIONS_SECTION_LABEL)"))
        // Sekcija „Odbici od plate“ (Član sindikata) je ukinuta: obustave su samo sa liste.
        assertFalse(options.contains("Odbici od plate"))
        // Otežani uslovi se više ne kucaju: upisuje ih lista (šifra 21), ovde se samo prikazuju.
        assertFalse(options.contains("repo.difficultWorkConditionsPercentValue ="))
        assertTrue(options.contains("rememberRepoValue(repo) { difficultWorkConditionsPercentValue }"))
        // Prevoz se ne vidi u Opcijama: prikazuje ga stavka „Prevoz“ ispod kalendara.
        assertFalse(options.contains("transportDailyAmountValue"))
        assertFalse(lista.contains("difficultWorkConditionsPercentValue"))
        assertFalse(lista.contains("transportDailyAmountValue"))
    }

    @Test
    fun `difficult conditions status shows percent from payslip or none`() {
        assertEquals("Otežani uslovi rada: 32%", difficultWorkConditionsStatusText(32.0))
        assertEquals(null, difficultWorkConditionsStatusText(0.0))
    }

    @Test
    fun `options dialog has no manual loan anymore`() {
        val source = mainSource("OptionsDialog.kt")

        assertFalse(source.contains("CreditDeduction"))
        assertFalse(source.contains("Kredit"))
        // Obustave u % (sindikat) idu samo sa liste, bez prekidača „Član sindikata“.
        assertFalse(source.contains("UnionDues"))
        assertFalse(source.contains("sindikat", ignoreCase = true))
    }
}
