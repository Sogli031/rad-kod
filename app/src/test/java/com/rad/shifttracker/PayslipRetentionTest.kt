package com.rad.shifttracker

import com.rad.shifttracker.data.storage.SavedPayslipsJsonCodec
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import com.rad.shifttracker.godisnji.VacationYearBalance
import com.rad.shifttracker.listici.ParsedPayslip
import com.rad.shifttracker.listici.PayslipParseResult
import com.rad.shifttracker.listici.parsePayslip
import com.rad.shifttracker.repo.BatchRecord
import com.rad.shifttracker.repo.exportCloudBackupJson
import com.rad.shifttracker.repo.exportJson
import com.rad.shifttracker.repo.getSavedPayslip
import com.rad.shifttracker.repo.getVacationBalanceSummary
import com.rad.shifttracker.repo.getVacationYearBalance
import com.rad.shifttracker.repo.importFromJson
import com.rad.shifttracker.repo.isVacationCarryoverAutomatic
import com.rad.shifttracker.repo.pruneIrrelevantHistory
import com.rad.shifttracker.repo.savePayslip
import com.rad.shifttracker.repo.setVacationYearBalance
import com.rad.shifttracker.smene.MonthKey
import com.rad.shifttracker.smene.Shift

class PayslipRetentionTest {
    /** Listić iz fixture-a prebačen u drugi mesec/godinu (iste stavke). */
    private fun slip(fixtureMonth: Int, year: Int, month: Int): ParsedPayslip {
        val from = "%02d/2026".format(fixtureMonth)
        val to = "%02d/%d".format(month, year)
        val lines = requireNotNull(PAYSLIPS_2026[fixtureMonth]).map { it.replace(from, to) }
        return (parsePayslip(lines) as PayslipParseResult.Success).payslip
    }

    private fun balance(year: Int, carryover: Double = 0.0) =
        VacationYearBalance(year, LocalDate.of(year, 1, 1), carryover, 24.0, 0.0, LocalDate.of(year, 7, 1), true)

    @Test
    fun `listici stariji od 24 meseca pre najnovijeg upisa se brisu`() {
        val repo = testRepository()
        repo.retentionClock = { java.time.YearMonth.of(2026, 9) }
        repo.setBatch(listOf(BatchRecord(2024, 6, 3, Shift.FIRST, 8), BatchRecord(2026, 9, 1, Shift.FIRST, 8)))
        repo.savePayslip(slip(8, 2024, 8))
        repo.savePayslip(slip(8, 2024, 9))

        assertTrue(repo.pruneIrrelevantHistory())

        assertNull(repo.getSavedPayslip(2024, 8))
        assertNotNull(repo.getSavedPayslip(2024, 9))
    }

    @Test
    fun `prenos godisnjeg se zamrzne pre brisanja prosle godine`() {
        val repo = testRepository()
        repo.savePayslip(slip(7, 2025, 7)) // 96 h GO = 12 dana
        repo.setVacationYearBalance(balance(2025))
        repo.setVacationYearBalance(balance(2026, carryover = 99.0))
        assertEquals(12.0, repo.getVacationBalanceSummary(2026, LocalDate.of(2026, 2, 1)).carryoverInitial, 0.001)

        // Najnoviji upis 03/2027 → granica 03/2025, prva cela godina 2026.
        repo.retentionClock = { java.time.YearMonth.of(2027, 3) }
        repo.setBatch(listOf(BatchRecord(2025, 2, 3, Shift.FIRST, 8), BatchRecord(2027, 3, 1, Shift.FIRST, 8)))
        assertTrue(repo.pruneIrrelevantHistory())

        assertNull(repo.getVacationYearBalance(2025))
        assertTrue(repo.getVacationYearBalance(2026)?.carryoverFrozen == true)
        assertFalse(repo.isVacationCarryoverAutomatic(2026))

        // I kad listić jula 2025 kasnije ispadne, prenos ostaje 12.
        repo.retentionClock = { java.time.YearMonth.of(2027, 9) }
        repo.setBatch(listOf(BatchRecord(2027, 9, 1, Shift.FIRST, 8)))
        repo.pruneIrrelevantHistory()
        assertNull(repo.getSavedPayslip(2025, 7))
        assertEquals(12.0, repo.getVacationBalanceSummary(2026, LocalDate.of(2026, 2, 1)).carryoverInitial, 0.001)

        // Zamrznut prenos preživi backup.
        val restored = testRepository()
        assertTrue(restored.importFromJson(repo.exportJson()))
        assertTrue(restored.getVacationYearBalance(2026)?.carryoverFrozen == true)
    }

    @Test
    fun `smene popunjene unapred ne brisu prave podatke`() {
        val repo = testRepository()
        repo.retentionClock = { java.time.YearMonth.of(2026, 9) }
        repo.savePayslip(slip(7, 2025, 7))
        repo.setVacationYearBalance(balance(2025))
        repo.setVacationYearBalance(balance(2026))
        // Popuna 24 meseca unapred: najkasniji upis 09/2028.
        repo.setBatch(listOf(BatchRecord(2025, 7, 1, Shift.FIRST, 8), BatchRecord(2028, 9, 1, Shift.FIRST, 8)))
        repo.pruneIrrelevantHistory()

        assertNotNull(repo.getSavedPayslip(2025, 7))
        assertNotNull(repo.getVacationYearBalance(2025))
        assertTrue(repo.isVacationCarryoverAutomatic(2026))
    }

    @Test
    fun `backup u oblaku izbacuje stare listice iz arhive`() {
        val archiveRepo = testRepository()
        archiveRepo.setBatch(listOf(BatchRecord(2023, 1, 2, Shift.FIRST, 8)))
        archiveRepo.savePayslip(slip(1, 2023, 1))
        val archive = archiveRepo.exportCloudBackupJson(null)

        val repo = testRepository()
        repo.setBatch(listOf(BatchRecord(2026, 9, 1, Shift.FIRST, 8)))
        repo.savePayslip(slip(8, 2026, 8))
        val merged = SavedPayslipsJsonCodec.parse(JSONObject(repo.exportCloudBackupJson(archive)))

        assertEquals(setOf(MonthKey(2026, 8)), merged.keys)
    }
}
