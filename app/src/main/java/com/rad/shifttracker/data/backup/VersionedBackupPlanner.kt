package com.rad.shifttracker.data.backup

/**
 * Sitne, čiste odluke nad listom postojećih verzija koje [GoogleDriveBackup]
 * koristi pri verzionisanom backup-u: koja verzija nosi najsvežiju arhivu za
 * spajanje i koja je „bazna dobra" za poređenje pri detekciji anomalija.
 */
object VersionedBackupPlanner {

    /**
     * Najsvežija REDOVNA verzija (izvor arhive za merge i cilj za „Restore"), ili
     * null ako još nema verzija. Sigurnosne kopije pre vraćanja se preskaču — one
     * su dostupne samo kroz listu verzija.
     */
    fun latest(versions: List<BackupVersion>): BackupVersion? =
        versions.filterNot { it.safety }.maxByOrNull { it.createdAtMillis }

    /**
     * Otisak poslednje poznate DOBRE verzije za poređenje sa novim backup-om:
     * najsvežija ne-flagovana verzija sa metapodacima, a ako takve nema —
     * najsvežija verzija sa metapodacima. Null ako nijedna nema otisak (legacy).
     */
    fun baselineStats(versions: List<BackupVersion>): BackupStats? {
        val newestFirst = versions.filterNot { it.safety }.sortedByDescending { it.createdAtMillis }
        val good = newestFirst.firstOrNull { !it.flagged && it.stats != null }
        return (good ?: newestFirst.firstOrNull { it.stats != null })?.stats
    }
}
