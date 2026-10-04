package com.rad.shifttracker.data.backup

import android.util.Log
import com.google.api.client.http.ByteArrayContent
import com.google.api.services.drive.Drive
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream

private const val TAG = "GoogleDriveBackup"
private const val APP_DATA_FOLDER = "appDataFolder"

/**
 * Četiri operacije nad fajlovima verzija u oblaku. [GoogleDriveBackup] koristi Drive
 * implementaciju; testovi koriste memorijsku, pa se redosled upis → orezivanje i zaštite
 * od gubitka podataka ([VersionedBackupWriter]) proveravaju bez mreže.
 */
internal interface BackupFileStore {
    /** Sve naše verzije (redovne, sigurnosne i stari `rad_backup.json`), bez sadržaja. */
    fun listVersions(): List<BackupVersion>

    /** Sadržaj verzije, ili null ako nije mogao da se pročita. */
    fun download(fileId: String): String?

    /** Novi fajl verzije; vraća njegov id. */
    fun create(fileName: String, json: String, props: Map<String, String>): String

    fun delete(fileId: String)
}

internal class DriveBackupFileStore(private val drive: Drive) : BackupFileStore {

    override fun listVersions(): List<BackupVersion> {
        val versions = mutableListOf<BackupVersion>()
        var pageToken: String? = null
        do {
            val response = drive.files().list()
                .setSpaces(APP_DATA_FOLDER)
                .setQ("trashed = false")
                .setOrderBy("createdTime desc")
                .setFields("nextPageToken, files(id, name, createdTime, appProperties)")
                .setPageToken(pageToken)
                .execute()
            for (file in response.files.orEmpty()) {
                val id = file.id ?: continue
                val name = file.name ?: continue
                if (!isOurBackupFile(name)) continue
                versions += BackupMetadata.buildVersion(
                    fileId = id,
                    fileName = name,
                    driveCreatedAtMillis = file.createdTime?.value,
                    props = file.appProperties
                )
            }
            pageToken = response.nextPageToken
        } while (pageToken != null)
        return versions
    }

    override fun download(fileId: String): String? = try {
        val outputStream = ByteArrayOutputStream()
        drive.files().get(fileId).executeMediaAndDownloadTo(outputStream)
        outputStream.toString("UTF-8")
    } catch (e: Exception) {
        Log.w(TAG, "Neuspešno čitanje verzije $fileId", e)
        null
    }

    override fun create(fileName: String, json: String, props: Map<String, String>): String {
        val metadata = com.google.api.services.drive.model.File().apply {
            name = fileName
            parents = listOf(APP_DATA_FOLDER)
            appProperties = HashMap(props)
        }
        val content = ByteArrayContent.fromString("application/json", json)
        val created = drive.files().create(metadata, content).setFields("id").execute()
        return created.id ?: error("Drive nije vratio id nove verzije")
    }

    override fun delete(fileId: String) {
        drive.files().delete(fileId).execute()
    }

    private fun isOurBackupFile(name: String): Boolean =
        BackupMetadata.isLegacyName(name) || BackupMetadata.parseCreatedAt(name) != null
}

/** Ishod jednog upisa verzije, pre nego što ga [GoogleDriveBackup] pretvori u stanje i poruku. */
internal sealed class VersionedWriteResult {
    /** Lista verzija ili sadržaj najsvežije nisu stigli na vreme. */
    data object ReadTimeout : VersionedWriteResult()

    /** Najsvežija verzija postoji, ali nije pročitana: upis je prekinut da arhiva ne izgubi istoriju. */
    data object LatestUnreadable : VersionedWriteResult()

    data class Created(val version: BackupVersion, val flagged: Boolean, val reasons: List<String>) : VersionedWriteResult()
}

/**
 * Jedan verzionisani backup: pročitaj postojeće verzije i sadržaj najsvežije redovne, napravi
 * novu verziju od [build] i TEK POTOM oreži stare. Ako upis pukne, ništa nije obrisano.
 */
internal object VersionedBackupWriter {

    suspend fun write(
        store: BackupFileStore,
        nowMillis: Long,
        appVersion: String?,
        safety: Boolean,
        readTimeoutMillis: Long,
        build: (existingJson: String?) -> CloudBackupContent
    ): VersionedWriteResult {
        // Rok važi samo za čitanje. Drive pozivi su blokirajući i ne mogu da se prekinu, pa bi
        // rok oko upisa odbacio rezultat posle uspešnog upisa: verzija bi postojala u oblaku, a
        // prijavilo bi se „Timeout" i pokušalo ponovo (duplikati verzija).
        val archive = withTimeoutOrNull(readTimeoutMillis) {
            val versions = store.listVersions()
            val latest = VersionedBackupPlanner.latest(versions)
            Triple(versions, latest, latest?.let { store.download(it.fileId) })
        } ?: return VersionedWriteResult.ReadTimeout
        val (versions, latest, existingJson) = archive
        if (latest != null && existingJson == null) return VersionedWriteResult.LatestUnreadable

        val content = build(existingJson)
        val verdict = BackupAnomalyGuard.assess(VersionedBackupPlanner.baselineStats(versions), content.stats)
        val props = BackupMetadata.toProperties(nowMillis, content.stats, verdict.isSuspicious, appVersion, safety = safety)
        val fileName = BackupMetadata.fileName(nowMillis)
        val newId = store.create(fileName, content.json, props)
        val newVersion = BackupMetadata.buildVersion(
            fileId = newId,
            fileName = fileName,
            driveCreatedAtMillis = nowMillis,
            props = props
        )
        prune(store, versions + newVersion)
        val reasons = (verdict as? BackupAnomalyGuard.Verdict.Suspicious)?.reasons.orEmpty()
        return VersionedWriteResult.Created(newVersion, verdict.isSuspicious, reasons)
    }

    private fun prune(store: BackupFileStore, allVersions: List<BackupVersion>) {
        for (version in BackupRetentionPolicy.selectForDeletion(allVersions)) {
            try {
                store.delete(version.fileId)
            } catch (e: Exception) {
                Log.w(TAG, "Neuspešno orezivanje verzije ${version.fileId}", e)
            }
        }
    }

    /**
     * Pozadinski backup sme odmah ako je oblak usvojen. Inače: nepoznata lista → čekaj,
     * postoji redovna verzija → čeka se odluka korisnika, prazan oblak → usvoji i šalji.
     */
    fun gate(adopted: Boolean, cloudVersions: List<BackupVersion>?): AutoBackupGate = when {
        adopted -> AutoBackupGate.ALLOWED
        cloudVersions == null -> AutoBackupGate.UNKNOWN
        VersionedBackupPlanner.latest(cloudVersions) != null -> AutoBackupGate.NEEDS_USER_DECISION
        else -> AutoBackupGate.ALLOWED
    }
}
