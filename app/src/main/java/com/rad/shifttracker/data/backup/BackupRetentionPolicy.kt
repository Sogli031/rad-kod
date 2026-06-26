package com.rad.shifttracker.data.backup

import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.IsoFields

/**
 * Retencija po nivoima (deda-otac-sin): koje verzije zadržati, a koje obrisati,
 * tako da imaš i guste skorašnje tačke vraćanja i retke stare, bez beskonačnog
 * rasta broja fajlova.
 *
 * Zadržava se unija sledećih skupova:
 *  - poslednjih [Config.keepLatest] verzija,
 *  - po jedna (najnovija) za svaki od poslednjih [Config.dailyDays] dana koji
 *    imaju backup,
 *  - po jedna za svaku od poslednjih [Config.weeklyWeeks] nedelja,
 *  - po jedna za svaki od poslednjih [Config.monthlyMonths] meseci,
 *  - UVEK poslednja ne-flagovana („dobra") verzija — da masovno brisanje praćeno
 *    nizom automatskih backup-a nikad ne istisne poslednje ispravno stanje.
 *
 * Čista funkcija ulaza → testira se bez Drive-a; zona se prosleđuje radi
 * determinističnih testova.
 */
object BackupRetentionPolicy {

    data class Config(
        val keepLatest: Int = 10,
        val dailyDays: Int = 7,
        val weeklyWeeks: Int = 4,
        val monthlyMonths: Int = 12
    )

    /** Verzije koje treba obrisati (sve što nije u skupu za zadržavanje). */
    fun selectForDeletion(
        versions: List<BackupVersion>,
        config: Config = Config(),
        zone: ZoneId = ZoneId.systemDefault()
    ): List<BackupVersion> {
        if (versions.size <= 1) return emptyList()
        val keepIds = selectIdsToKeep(versions, config, zone)
        return versions.filter { it.fileId !in keepIds }
    }

    private fun selectIdsToKeep(
        versions: List<BackupVersion>,
        config: Config,
        zone: ZoneId
    ): Set<String> {
        val newestFirst = versions.sortedByDescending { it.createdAtMillis }
        val keep = LinkedHashSet<String>()

        // 1) Poslednjih N verzija.
        newestFirst.take(config.keepLatest.coerceAtLeast(1)).forEach { keep += it.fileId }

        // 2) Poslednja dobra (ne-flagovana) verzija — bezbednosna kotva.
        newestFirst.firstOrNull { !it.flagged }?.let { keep += it.fileId }

        // 3) Po jedna za poslednje dane / nedelje / mesece.
        keepNewestPerBucket(newestFirst, config.dailyDays, keep) { dayBucket(it, zone) }
        keepNewestPerBucket(newestFirst, config.weeklyWeeks, keep) { weekBucket(it, zone) }
        keepNewestPerBucket(newestFirst, config.monthlyMonths, keep) { monthBucket(it, zone) }

        return keep
    }

    /**
     * Iz [newestFirst] (već sortirano po opadajućem vremenu) uzmi najviše
     * [limit] najskorijih različitih „kofa" i za svaku zadrži najnoviju verziju.
     */
    private fun keepNewestPerBucket(
        newestFirst: List<BackupVersion>,
        limit: Int,
        keep: MutableSet<String>,
        bucketOf: (Long) -> Long
    ) {
        if (limit <= 0) return
        val seenBuckets = LinkedHashSet<Long>()
        for (version in newestFirst) {
            val bucket = bucketOf(version.createdAtMillis)
            if (bucket in seenBuckets) continue
            if (seenBuckets.size >= limit) break
            seenBuckets += bucket
            keep += version.fileId
        }
    }

    private fun dayBucket(millis: Long, zone: ZoneId): Long =
        Instant.ofEpochMilli(millis).atZone(zone).toLocalDate().toEpochDay()

    private fun weekBucket(millis: Long, zone: ZoneId): Long {
        val date = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()
        val weekYear = date.get(IsoFields.WEEK_BASED_YEAR)
        val week = date.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)
        return weekYear * 100L + week
    }

    private fun monthBucket(millis: Long, zone: ZoneId): Long {
        val ym = YearMonth.from(Instant.ofEpochMilli(millis).atZone(zone).toLocalDate())
        return ym.year * 12L + ym.monthValue
    }
}
