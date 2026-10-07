package com.rad.shifttracker.data.backup

import com.rad.shifttracker.smene.MonthKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.rad.shifttracker.data.storage.BackupStats

class BackupAnomalyGuardTest {

    private fun stats(records: Int, months: Int, hours: Int): BackupStats =
        BackupStats(
            recordCount = records,
            monthCount = months,
            totalWorkMinutes = hours * 60L,
            earliestMonth = MonthKey(2026, 1),
            latestMonth = MonthKey(2026, months.coerceAtLeast(1))
        )

    @Test
    fun `prvi backup je uvek Ok`() {
        val verdict = BackupAnomalyGuard.assess(previous = null, candidate = stats(40, 3, 320))

        assertEquals(BackupAnomalyGuard.Verdict.Ok, verdict)
    }

    @Test
    fun `prazna prethodna verzija ne pravi alarm`() {
        val verdict = BackupAnomalyGuard.assess(
            previous = BackupStats.EMPTY,
            candidate = stats(10, 1, 80)
        )

        assertEquals(BackupAnomalyGuard.Verdict.Ok, verdict)
    }

    @Test
    fun `mali normalan rast je Ok`() {
        val verdict = BackupAnomalyGuard.assess(
            previous = stats(40, 3, 320),
            candidate = stats(42, 3, 336)
        )

        assertFalse(verdict.isSuspicious)
    }

    @Test
    fun `brisanje svih podataka je sumnjivo`() {
        val verdict = BackupAnomalyGuard.assess(
            previous = stats(40, 3, 320),
            candidate = BackupStats.EMPTY
        )

        assertTrue(verdict.isSuspicious)
        val reasons = (verdict as BackupAnomalyGuard.Verdict.Suspicious).reasons
        assertTrue(reasons.any { it.contains("nestali") })
    }

    @Test
    fun `nov korisnik s par dana koji obrise sve nije alarm`() {
        // Ispod praga minRecordsToTriggerWipe i bez relevantnog pada — Ok.
        val verdict = BackupAnomalyGuard.assess(
            previous = stats(3, 1, 24),
            candidate = BackupStats.EMPTY
        )

        // Pad meseci/dana na nulu ovde svejedno može da se javi; bitno je da
        // „svi nestali" pravilo ne okida ispod praga.
        val reasons = (verdict as? BackupAnomalyGuard.Verdict.Suspicious)?.reasons.orEmpty()
        assertFalse(reasons.any { it.contains("nestali") })
    }

    @Test
    fun `nestanak celog meseca je sumnjiv`() {
        val verdict = BackupAnomalyGuard.assess(
            previous = stats(40, 3, 320),
            candidate = stats(38, 2, 300)
        )

        assertTrue(verdict.isSuspicious)
        assertTrue((verdict as BackupAnomalyGuard.Verdict.Suspicious).reasons.any { it.contains("meseci") })
    }

    @Test
    fun `pad dana preko pola je sumnjiv`() {
        val verdict = BackupAnomalyGuard.assess(
            previous = stats(40, 3, 320),
            candidate = stats(18, 3, 300)
        )

        assertTrue(verdict.isSuspicious)
        assertTrue((verdict as BackupAnomalyGuard.Verdict.Suspicious).reasons.any { it.contains("dana") })
    }

    @Test
    fun `pad radnih sati preko pola je sumnjiv iako broj dana ostaje`() {
        val verdict = BackupAnomalyGuard.assess(
            previous = stats(40, 3, 320),
            candidate = stats(40, 3, 120)
        )

        assertTrue(verdict.isSuspicious)
        assertTrue((verdict as BackupAnomalyGuard.Verdict.Suspicious).reasons.any { it.contains("sati") })
    }
}
