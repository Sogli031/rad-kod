package com.rad.shifttracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import com.rad.shifttracker.smene.DayRecord
import com.rad.shifttracker.smene.Shift
import com.rad.shifttracker.ui.DialogState
import com.rad.shifttracker.ui.dialogStateFromKey
import com.rad.shifttracker.ui.dialogStateToKey

/** Ključevi otvorenog dijaloga se čuvaju pri rotaciji/ubijanju procesa i moraju ostati isti među verzijama. */
class DialogStateTest {
    private val expectedKeys = mapOf(
        DialogState.None to "None",
        DialogState.AutoFill to "AutoFill",
        DialogState.OnboardingAutoFill to "OnboardingAutoFill",
        DialogState.GmailOnboarding to "GmailOnboarding",
        DialogState.GmailUpdate to "GmailUpdate",
        DialogState.BasePay to "BasePay",
        DialogState.PayDetails to "PayDetails",
        DialogState.SalaryDetails to "SalaryDetails",
        DialogState.OvertimeNetImpact to "OvertimeNetImpact",
        DialogState.Food to "Food",
        DialogState.Minuli to "Minuli",
        DialogState.Options to "Options",
        DialogState.CloudBackup to "CloudBackup",
        DialogState.AdvancePayment to "AdvancePayment",
        DialogState.InitialCompBalance to "InitialCompBalance",
        DialogState.DeleteMonth to "DeleteMonth",
        DialogState.VacationSettings to "VacationSettings",
        DialogState.BloodDonation to "BloodDonation"
    )

    @Test
    fun `svako stanje ima isti kljuc kao ranije i vraca se iz njega`() {
        for ((state, key) in expectedKeys) {
            assertEquals(key, dialogStateToKey(state))
            assertEquals(state, dialogStateFromKey(key) { null })
        }
    }

    @Test
    fun `izmena dana cuva dan i zapis cita ponovo`() {
        val record = DayRecord(2026, 5, 4, Shift.FIRST, 8)
        assertEquals("DayEdit:4", dialogStateToKey(DialogState.DayEdit(4, record)))

        val restored = dialogStateFromKey("DayEdit:4") { day -> record.takeIf { day == 4 } }

        assertEquals(4, (restored as DialogState.DayEdit).day)
        assertEquals(record, restored.record)
    }

    @Test
    fun `nepoznat ili ostecen kljuc zatvara dijalog`() {
        assertEquals(DialogState.None, dialogStateFromKey("Nepostojeci") { null })
        assertEquals(DialogState.None, dialogStateFromKey("DayEdit:x") { null })
        assertNull((dialogStateFromKey("DayEdit:9") { null } as DialogState.DayEdit).record)
    }
}
