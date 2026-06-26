package com.rad.shifttracker.data.backup

/**
 * Sitne, čiste odluke nad listom postojećih verzija koje [GoogleDriveBackup]
 * koristi pri verzionisanom backup-u: koja verzija nosi najsvežiju arhivu za
 * spajanje i koja je „bazna dobra" za poređenje pri detekciji anomalija.
 */
object VersionedBackupPlanner {

    /** Najsvežija verzija (izvor arhive za merge), ili null ako još nema verzija. */
    fun latest(versions: List<BackupVersion>): BackupVersion? =
        versions.maxByOrNull { it.createdAtMillis }

    /**
     * Otisak poslednje poznate DOBRE verzije za poređenje sa novim backup-om:
     * najsvežija ne-flagovana verzija sa metapodacima, a ako takve nema —
     * najsvežija verzija sa metapodacima. Null ako nijedna nema otisak (legacy).
     */
    fun baselineStats(versions: List<BackupVersion>): BackupStats? {
        val newestFirst = versions.sortedByDescending { it.createdAtMillis }
        val good = newestFirst.firstOrNull { !it.flagged && it.stats != null }
        return (good ?: newestFirst.firstOrNull { it.stats != null })?.stats
    }
}
