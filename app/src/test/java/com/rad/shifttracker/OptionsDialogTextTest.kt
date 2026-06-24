package com.rad.shifttracker

import java.time.LocalDate
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OptionsDialogTextTest {
    @Test
    fun `options dialog exposes cloud export holidays and notification access`() {
        assertEquals(
            listOf(
                "Google cloud",
                "Export rada i obračuna za izabrani mesec",
                "Praznici",
                "Pristup obaveštenjima"
            ),
            optionsDialogActionLabels()
        )
    }

    @Test
    fun `bottom bar exposes blood donation shortcut label`() {
        assertEquals("Krv", BLOOD_DONATION_BOTTOM_BAR_LABEL)
    }

    @Test
    fun `options dialog exposes food receipt reminder label`() {
        assertEquals("Podsetnik za račun hrane", FOOD_RECEIPT_REMINDER_OPTION_LABEL)
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
    fun `main activity warns about exact alarm permission on every startup`() {
        val source = listOf(
            java.io.File("src/main/java/com/rad/shifttracker/MainActivity.kt"),
            java.io.File("app/src/main/java/com/rad/shifttracker/MainActivity.kt")
        ).first { it.exists() }.readText()

        assertTrue(source.contains("override fun onResume()"))
        assertTrue(source.contains("requestExactAlarmPermissionIfNeeded()"))
        assertTrue(source.contains("ExactAlarmPermissionDialog("))
        assertTrue(source.contains("mutableStateOf(false)"))
        assertTrue(source.contains("FoodReceiptReminderScheduler.exactAlarmSettingsIntent(this)"))
        assertFalse(source.contains("AlertDialog.Builder(this)"))
        assertFalse(source.contains("exact_alarm_permission_asked"))
    }

    @Test
    fun `options dialog exposes credit deduction settings labels`() {
        assertEquals("Kredit/obustava", CREDIT_DEDUCTION_SECTION_LABEL)
        assertEquals("Kredit aktivan", CREDIT_DEDUCTION_ENABLED_LABEL)
        assertEquals("Mesečna rata kredita", MONTHLY_CREDIT_DEDUCTION_AMOUNT_LABEL)
        assertEquals("RSD", CREDIT_DEDUCTION_CURRENCY_LABEL)
    }

    @Test
    fun `options dialog renders credit deduction controls`() {
        val source = listOf(
            java.io.File("src/main/java/com/rad/shifttracker/OptionsDialog.kt"),
            java.io.File("app/src/main/java/com/rad/shifttracker/OptionsDialog.kt")
        ).first { it.exists() }.readText()

        assertTrue(source.contains("CreditDeductionSettingsSection("))
        assertTrue(source.contains("repo.setCreditDeductionSettings("))
        assertTrue(source.contains("KeyboardType.Decimal"))
    }

    @Test
    fun `food receipt reminder status formats scheduled third shift reminder`() {
        val status = FoodReceiptReminderScheduleStatus(
            reminder = FoodReceiptReminder(
                month = MonthKey(2026, 5),
                mealTime = LocalDateTime.of(2026, 5, 31, 1, 0),
                shiftDate = LocalDate.of(2026, 5, 30),
                shift = Shift.THIRD
            ),
            exactAlarmAvailable = true,
            notificationPermissionGranted = true
        )

        assertEquals(
            "Podsetnik: 31.05.2026 u 01:00",
            foodReceiptReminderStatusText(status)
        )
    }

    @Test
    fun `food receipt reminder status shows no scheduled reminder`() {
        assertEquals(
            "Podsetnik: nema",
            foodReceiptReminderStatusText(FoodReceiptReminderScheduleStatus())
        )
    }
}
