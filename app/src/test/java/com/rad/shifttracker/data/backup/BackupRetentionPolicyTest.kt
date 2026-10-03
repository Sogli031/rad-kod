package com.rad.shifttracker.data.backup

import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupRetentionPolicyTest {

    private val utc = ZoneId.of("UTC")
    private val base = LocalDate.of(2026, 6, 15) // ponedeljak

    private fun millisDaysAgo(daysAgo: Int, extraHoursBack: Int = 0): Long =
        base.minusDays(daysAgo.toLong())
            .atTime(12, 0).toInstant(ZoneOffset.UTC).toEpochMilli() - extraHoursBack * 3_600_000L

    private fun millisMonthsAgo(monthsAgo: Int): Long =
        base.minusMonths(monthsAgo.toLong())
            .atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    private fun version(id: String, millis: Long, flagged: Boolean = false): BackupVersion =
        BackupVersion(fileId = id, fileName = "rad_backup_$id.json", createdAtMillis = millis, flagged = flagged)

    private fun deletedIds(
        versions: List<BackupVersion>,
        config: BackupRetentionPolicy.Config = BackupRetentionPolicy.Config()
    ): Set<String> = BackupRetentionPolicy.selectForDeletion(versions, config, utc).map { it.fileId }.toSet()

    @Test
    fun `sigurnosna kopija pre vracanja nikad nije najnovija verzija za restore`() {
        val good = version("good", millisDaysAgo(1))
        val safety = version("safety", millisDaysAgo(0)).copy(safety = true)

        assertEquals("good", VersionedBackupPlanner.latest(listOf(good, safety))?.fileId)
        assertEquals(null, VersionedBackupPlanner.latest(listOf(safety)))
    }

    @Test
    fun `safety oznaka prezivljava appProperties`() {
        val stats = BackupStats(recordCount = 1, monthCount = 1, totalWorkMinutes = 480, null, null)
        val props = BackupMetadata.toProperties(1_000L, stats, flagged = false, appVersion = "1.0", safety = true)
        val regular = BackupMetadata.toProperties(1_000L, stats, flagged = false, appVersion = "1.0")

        assertTrue(BackupMetadata.buildVersion("id", "rad_backup_1000.json", null, props).safety)
        assertFalse(BackupMetadata.buildVersion("id", "rad_backup_1000.json", null, regular).safety)
    }

    @Test
    fun `sigurnosne kopije ne istiskuju redovne verzije i same se orezuju`() {
        val regular = (1..3).map { version("r$it", millisDaysAgo(it)) }
        val safeties = (0..7).map { version("s$it", millisDaysAgo(0, extraHoursBack = it)).copy(safety = true) }
        val config = BackupRetentionPolicy.Config(keepLatest = 1, dailyDays = 0, weeklyWeeks = 0, monthlyMonths = 0)

        val deleted = deletedIds(regular + safeties, config)

        assertFalse("r1" in deleted)
        assertEquals(setOf("s5", "s6", "s7"), deleted.filter { it.startsWith("s") }.toSet())
    }

    @Test
    fun `jedna ili nijedna verzija se ne brise`() {
        assertTrue(BackupRetentionPolicy.selectForDeletion(emptyList(), zone = utc).isEmpty())
        assertTrue(
            BackupRetentionPolicy.selectForDeletion(
                listOf(version("a", millisDaysAgo(0))),
                zone = utc
            ).isEmpty()
        )
    }

    @Test
    fun `najnovija verzija se nikad ne brise`() {
        val versions = (0..30).map { version("v$it", millisDaysAgo(it)) }

        assertFalse("v0" in deletedIds(versions))
    }

    @Test
    fun `visak u istoj kofi se brise, najnoviji ostaje`() {
        // Tri verzije u istom danu, razmak po sat; tesna konfiguracija (sve granice 1).
        val versions = listOf(
            version("v0", millisDaysAgo(0, extraHoursBack = 0)),
            version("v1", millisDaysAgo(0, extraHoursBack = 1)),
            version("v2", millisDaysAgo(0, extraHoursBack = 2))
        )
        val config = BackupRetentionPolicy.Config(keepLatest = 1, dailyDays = 1, weeklyWeeks = 1, monthlyMonths = 1)

        val deleted = deletedIds(versions, config)

        assertEquals(setOf("v1", "v2"), deleted)
    }

    @Test
    fun `vise meseci od mesecnog limita brise najstarije`() {
        // Po jedna verzija mesečno, 15 meseci unazad; drži se 12 mesečnih.
        val versions = (0..14).map { version("m$it", millisMonthsAgo(it)) }
        val config = BackupRetentionPolicy.Config(keepLatest = 1, dailyDays = 0, weeklyWeeks = 0, monthlyMonths = 12)

        val deleted = deletedIds(versions, config)

        // Najstarija 3 (m12, m13, m14) ispadaju iz prozora od 12 meseci.
        assertEquals(setOf("m12", "m13", "m14"), deleted)
        assertFalse("m0" in deleted)
    }

    @Test
    fun `poslednja dobra verzija se cuva i kad su sve novije flagovane`() {
        // 13 flagovanih mesečnih (m0..m12) + jedna dobra još starija (m13).
        val flagged = (0..12).map { version("bad$it", millisMonthsAgo(it), flagged = true) }
        val lastGood = version("lastGood", millisMonthsAgo(13), flagged = false)
        val config = BackupRetentionPolicy.Config(keepLatest = 1, dailyDays = 0, weeklyWeeks = 0, monthlyMonths = 12)

        val deleted = deletedIds(flagged + lastGood, config)

        // Dobra kotva opstaje iako je najstarija i van mesečnog prozora.
        assertFalse("lastGood" in deleted)
        // Najstarija flagovana (van prozora od 12 meseci) se orezuje.
        assertTrue("bad12" in deleted)
        assertFalse("bad0" in deleted)
    }
}
