package com.rad.shifttracker.data.backup

import com.rad.shifttracker.DayRecord
import com.rad.shifttracker.MonthKey

/**
 * Sažeti „otisak" jednog backup-a: koliko dana, meseci i radnih minuta sadrži,
 * plus najstariji/najnoviji mesec. Računa se iz zapisa pre upisa u oblak i čuva
 * uz svaku verziju (Drive appProperties) da bi:
 *  - restore meni mogao da prikaže „šta je unutra" bez skidanja celog fajla, i
 *  - [BackupAnomalyGuard] mogao da uporedi novi backup sa prethodnim i prepozna
 *    sumnjiv pad podataka (npr. nestao ceo mesec ili obrisano sve).
 *
 * Namerno zavisi samo od [DayRecord] (javni tip) — lako se računa i testira bez
 * celog snapshot-a.
 */
data class BackupStats(
    val recordCount: Int,
    val monthCount: Int,
    val totalWorkMinutes: Long,
    val earliestMonth: MonthKey?,
    val latestMonth: MonthKey?
) {
    fun isEmpty(): Boolean = recordCount == 0

    companion object {
        val EMPTY = BackupStats(
            recordCount = 0,
            monthCount = 0,
            totalWorkMinutes = 0L,
            earliestMonth = null,
            latestMonth = null
        )

        fun from(records: List<DayRecord>): BackupStats {
            if (records.isEmpty()) return EMPTY
            val months = records.mapTo(HashSet()) { it.monthKey }
            return BackupStats(
                recordCount = records.size,
                monthCount = months.size,
                totalWorkMinutes = records.sumOf { it.totalWorkMinutes.toLong() },
                earliestMonth = months.min(),
                latestMonth = months.max()
            )
        }
    }
}
