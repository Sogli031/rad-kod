package com.rad.shifttracker.data.backup

import com.rad.shifttracker.MonthKey

/**
 * Imenovanje i metapodaci verzionisanih backup fajlova. Svaka verzija je zaseban
 * Drive fajl `rad_backup_<epochMillis>.json`, a otisak sadržaja ([BackupStats]) i
 * oznaka anomalije se čuvaju u Drive `appProperties` (string→string) da bi se
 * lista verzija prikazala i orezala bez skidanja samog sadržaja.
 *
 * Sve je čista (Drive-nezavisna) logika → lako se testira; [GoogleDriveBackup]
 * samo dovlači sirove vrednosti iz Drive API-ja i prosleđuje ih ovde.
 */
object BackupMetadata {

    const val FILE_PREFIX = "rad_backup_"
    const val FILE_SUFFIX = ".json"

    /** Stari format pre verzionisanja — jedan fiksni fajl; čita se radi migracije. */
    const val LEGACY_FILE_NAME = "rad_backup.json"

    const val CURRENT_SCHEMA_VERSION = 1

    private const val KEY_CREATED_AT = "radCreatedAt"
    private const val KEY_SCHEMA = "radSchema"
    private const val KEY_APP = "radApp"
    private const val KEY_FLAGGED = "radFlagged"
    private const val KEY_RECORDS = "radRecords"
    private const val KEY_MONTHS = "radMonths"
    private const val KEY_WORK_MINUTES = "radWorkMinutes"
    private const val KEY_EARLIEST = "radEarliest"
    private const val KEY_LATEST = "radLatest"

    fun fileName(createdAtMillis: Long): String = "$FILE_PREFIX$createdAtMillis$FILE_SUFFIX"

    /** Izvuci epoch ms iz imena verzionisanog fajla, ili null ako ime ne odgovara. */
    fun parseCreatedAt(fileName: String?): Long? {
        if (fileName == null) return null
        if (!fileName.startsWith(FILE_PREFIX) || !fileName.endsWith(FILE_SUFFIX)) return null
        val middle = fileName.substring(FILE_PREFIX.length, fileName.length - FILE_SUFFIX.length)
        return middle.toLongOrNull()
    }

    fun isLegacyName(fileName: String?): Boolean = fileName == LEGACY_FILE_NAME

    fun toProperties(
        createdAtMillis: Long,
        stats: BackupStats,
        flagged: Boolean,
        appVersion: String?,
        schemaVersion: Int = CURRENT_SCHEMA_VERSION
    ): Map<String, String> {
        val props = linkedMapOf(
            KEY_CREATED_AT to createdAtMillis.toString(),
            KEY_SCHEMA to schemaVersion.toString(),
            KEY_FLAGGED to if (flagged) "1" else "0",
            KEY_RECORDS to stats.recordCount.toString(),
            KEY_MONTHS to stats.monthCount.toString(),
            KEY_WORK_MINUTES to stats.totalWorkMinutes.toString()
        )
        appVersion?.let { props[KEY_APP] = it }
        stats.earliestMonth?.let { props[KEY_EARLIEST] = formatMonth(it) }
        stats.latestMonth?.let { props[KEY_LATEST] = formatMonth(it) }
        return props
    }

    /** Otisak iz appProperties, ili null ako ključni metapodaci nedostaju (legacy). */
    fun statsFromProperties(props: Map<String, String>?): BackupStats? {
        if (props == null) return null
        val records = props[KEY_RECORDS]?.toIntOrNull() ?: return null
        val months = props[KEY_MONTHS]?.toIntOrNull() ?: return null
        val minutes = props[KEY_WORK_MINUTES]?.toLongOrNull() ?: return null
        return BackupStats(
            recordCount = records,
            monthCount = months,
            totalWorkMinutes = minutes,
            earliestMonth = parseMonth(props[KEY_EARLIEST]),
            latestMonth = parseMonth(props[KEY_LATEST])
        )
    }

    fun flaggedFromProperties(props: Map<String, String>?): Boolean = props?.get(KEY_FLAGGED) == "1"

    fun createdAtFromProperties(props: Map<String, String>?): Long? =
        props?.get(KEY_CREATED_AT)?.toLongOrNull()

    fun schemaFromProperties(props: Map<String, String>?): Int =
        props?.get(KEY_SCHEMA)?.toIntOrNull() ?: 0

    fun appVersionFromProperties(props: Map<String, String>?): String? = props?.get(KEY_APP)

    /**
     * Sastavi [BackupVersion] iz sirovih Drive vrednosti. Vreme nastanka se uzima
     * iz appProperties (najpouzdanije), pa iz imena fajla, pa iz Drive createdTime;
     * tako i legacy/ručno ubačeni fajlovi dobiju upotrebljiv timestamp.
     */
    fun buildVersion(
        fileId: String,
        fileName: String,
        driveCreatedAtMillis: Long?,
        props: Map<String, String>?
    ): BackupVersion {
        val createdAt = createdAtFromProperties(props)
            ?: parseCreatedAt(fileName)
            ?: driveCreatedAtMillis
            ?: 0L
        return BackupVersion(
            fileId = fileId,
            fileName = fileName,
            createdAtMillis = createdAt,
            flagged = flaggedFromProperties(props),
            stats = statsFromProperties(props),
            schemaVersion = schemaFromProperties(props),
            appVersion = appVersionFromProperties(props)
        )
    }

    private fun formatMonth(month: MonthKey): String =
        "%04d-%02d".format(month.year, month.month)

    private fun parseMonth(value: String?): MonthKey? {
        if (value == null) return null
        val parts = value.split("-")
        if (parts.size != 2) return null
        val year = parts[0].toIntOrNull() ?: return null
        val monthValue = parts[1].toIntOrNull() ?: return null
        return MonthKey(year, monthValue)
    }
}
