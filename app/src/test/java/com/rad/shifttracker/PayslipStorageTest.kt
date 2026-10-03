package com.rad.shifttracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PayslipStorageTest {

    private fun august(): ParsedPayslip =
        (parsePayslip(AUGUST_2026) as PayslipParseResult.Success).payslip

    @Test
    fun deletedPayslipIsGoneAndOtherMonthsStay() {
        val repo = testRepository()
        repo.savePayslip(august())
        repo.savePayslip(august().copy(month = MonthKey(2026, 7)))

        repo.deletePayslip(MonthKey(2026, 8))

        assertNull(repo.getSavedPayslip(2026, 8))
        assertNotNull(repo.getSavedPayslip(2026, 7))
        assertEquals(MonthKey(2026, 7), repo.getLatestSavedPayslipMonth())
    }

    @Test
    fun deletedPayslipDoesNotComeBackFromCloudArchive() {
        val repo = testRepository()
        repo.savePayslip(august())
        val archive = repo.exportJson()

        repo.deletePayslip(MonthKey(2026, 8))
        val merged = repo.buildCloudBackup(archive).json

        val restored = testRepository()
        assertTrue(restored.importFromJson(merged))
        assertNull("obrisan listić ne sme da se vrati iz oblaka", restored.getSavedPayslip(2026, 8))

        // Ni sledeći backup (arhiva koja već zna za brisanje + stara arhiva) ga ne vraća.
        val again = restored.buildCloudBackup(merged).json
        val restoredAgain = testRepository()
        assertTrue(restoredAgain.importFromJson(again))
        assertNull(restoredAgain.getSavedPayslip(2026, 8))
    }

    @Test
    fun payslipLoadedAgainAfterDeleteIsKept() {
        val repo = testRepository()
        repo.savePayslip(august())
        repo.deletePayslip(MonthKey(2026, 8))
        val archiveWithDelete = repo.exportJson()
        assertTrue(archiveWithDelete.contains("deleted_payslip_months"))

        repo.savePayslip(august())
        assertFalse(repo.exportJson().contains("deleted_payslip_months"))

        val merged = repo.buildCloudBackup(archiveWithDelete).json
        val restored = testRepository()
        assertTrue(restored.importFromJson(merged))
        assertEquals(august(), restored.getSavedPayslip(2026, 8))
    }

    @Test
    fun payoutRowIsJustZaIsplatu() {
        assertEquals("Za isplatu", PAYSLIP_PAYOUT_ROW)
    }
}
