package com.rad.shifttracker.listici

import android.accounts.Account
import android.accounts.AccountManager
import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.android.gms.auth.GoogleAuthException
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.UserRecoverableAuthException
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAuthIOException
import com.google.api.client.http.ByteArrayContent
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import com.google.api.services.drive.model.Permission
import java.time.YearMonth
import com.rad.shifttracker.smene.MonthKey

/**
 * Liste iz Gmail-a (akontacija, prevoz, konačna) se same i tiho šalju u folder
 * [DRIVE_PAYSLIP_FOLDER_NAME] na Google Drive-u istog naloga. Dozvola je samo `drive.file`:
 * aplikacija vidi samo fajlove koje je sama napravila. Šalje se samo poslednja lista svake vrste.
 */
internal const val DRIVE_PAYSLIP_FOLDER_NAME = "Rad liste"

private const val DRIVE_PAYSLIP_PREFS_NAME = "rad_drive_payslip_prefs"
private const val DRIVE_FILE_TOKEN_SCOPE = "oauth2:${DriveScopes.DRIVE_FILE}"
private const val FOLDER_MIME = "application/vnd.google-apps.folder"
private const val PDF_MIME = "application/pdf"

private fun drivePayslipPrefs(context: Context) =
    context.getSharedPreferences(DRIVE_PAYSLIP_PREFS_NAME, Context.MODE_PRIVATE)

/** Vrste lista koje idu na Drive; od svake se šalje samo poslednja. */
internal enum class DrivePayslipKind(val label: String) { ADVANCE("akontacija"), TRANSPORT("prevoz"), FINAL("konačna") }

/** Vrsta pročitanog PDF-a; za regres, nepoznat obračun i PDF koji nije lista je null. */
internal fun drivePayslipKind(result: PayslipParseResult): DrivePayslipKind? = when (result) {
    is PayslipParseResult.Success -> DrivePayslipKind.FINAL
    is PayslipParseResult.SideRun -> when (result.kind) {
        PayslipSideRunKind.ADVANCE -> DrivePayslipKind.ADVANCE
        PayslipSideRunKind.TRANSPORT -> DrivePayslipKind.TRANSPORT
        PayslipSideRunKind.REGRESS, PayslipSideRunKind.OTHER -> null
    }
    is PayslipParseResult.Failure -> null
}

/** Ime fajla na Drive-u za pročitan PDF: „2026-09 akontacija rb.1.pdf“; null kad se ne šalje. */
internal fun drivePayslipFileName(result: PayslipParseResult): String? {
    val kind = drivePayslipKind(result) ?: return null
    return when (result) {
        is PayslipParseResult.Success -> drivePayslipFileName(result.payslip.month, kind.label, result.payslip.runLabel)
        is PayslipParseResult.SideRun -> drivePayslipFileName(result.month, kind.label, result.runLabel)
        is PayslipParseResult.Failure -> null
    }
}

internal fun drivePayslipFileName(month: MonthKey, kind: String, runLabel: String?): String {
    val run = runLabel?.let { RUN_LABEL_REGEX.find(it)?.value }?.let { " $it" }.orEmpty()
    return "${month.year}-${month.month.toString().padStart(2, '0')} $kind$run.pdf"
}

/** Pozadinska provera i uključivanje u Opcijama ne šalju istovremeno (isti fajl dvaput). */
private val UPLOAD_LOCK = Any()

private val RUN_LABEL_REGEX = Regex("""rb\.\d+""")

private const val DRIVE_SHARE_EMAIL = "glisiccborko@gmail.com"

internal sealed interface DriveUploadAccountResult {
    data class Ready(val account: Account) : DriveUploadAccountResult
    data class NeedsConsent(val intent: Intent) : DriveUploadAccountResult
    data object NoAccount : DriveUploadAccountResult
}

/**
 * Vraća prvi Google nalog na uređaju koji ima Drive dozvolu (`drive.file`). Redom: Gmail nalog za
 * liste, backup nalog, pa bilo koji drugi Google nalog. Ako nijedan nema dozvolu — a Gmail nalog
 * treba consent — vraća [DriveUploadAccountResult.NeedsConsent] sa intentom za Google ekran.
 */
internal fun driveUploadAccount(context: Context): DriveUploadAccountResult {
    var consentIntent: Intent? = null
    val candidates = buildList {
        gmailPayslipAccount(context)?.let(::add)
        backupAccountEmail(context)?.let { email ->
            if (none { it.name == email }) add(Account(email, GoogleAuthUtil.GOOGLE_ACCOUNT_TYPE))
        }
        val seen = map { it.name }.toSet()
        AccountManager.get(context).getAccountsByType(GoogleAuthUtil.GOOGLE_ACCOUNT_TYPE)
            .filter { it.name !in seen }
            .forEach(::add)
    }
    for (account in candidates) {
        try {
            GoogleAuthUtil.getToken(context, account, DRIVE_FILE_TOKEN_SCOPE)
            return DriveUploadAccountResult.Ready(account)
        } catch (e: UserRecoverableAuthException) {
            if (consentIntent == null) consentIntent = e.intent
            continue
        }
        catch (_: GoogleAuthException) { continue }
        catch (_: java.io.IOException) { continue }
    }
    return consentIntent?.let { DriveUploadAccountResult.NeedsConsent(it) }
        ?: DriveUploadAccountResult.NoAccount
}

private fun backupAccountEmail(context: Context): String? =
    context.getSharedPreferences("rad_backup_prefs", Context.MODE_PRIVATE)
        .getString("account_email", null)?.takeIf { it.isNotBlank() }

/** Obračun se traži u mejlovima za poslednja 3 meseca rada (konačna stiže mesec kasnije). */
private const val LATEST_SEARCH_MONTHS = 3L

/**
 * Šalje na Drive poslednju akontaciju, poslednji prevoz i poslednju konačnu listu iz Gmail-a, ako
 * ih još nema u folderu; starije liste se ne šalju. Ništa ne radi dok Gmail nije povezan; za Drive
 * koristi bilo koji Google nalog koji ima dozvolu. Ako nijedan nema dozvolu a treba consent, vraća
 * intent za Google ekran. Radi mrežu — zvati van glavne niti.
 */
internal fun uploadPayslipsToDrive(context: Context, today: YearMonth = YearMonth.now()): Intent? = synchronized(UPLOAD_LOCK) {
    if (gmailPayslipAccount(context) == null) return null
    val driveAccount = when (val result = driveUploadAccount(context)) {
        is DriveUploadAccountResult.Ready -> result.account
        is DriveUploadAccountResult.NeedsConsent -> return result.intent
        DriveUploadAccountResult.NoAccount -> return null
    }
    // Stari zapis „poslatih“ poruka više ne treba.
    drivePayslipPrefs(context).edit().clear().apply()
    try {
        val drive = driveFileService(context, driveAccount)
        val folderId = payslipFolderId(drive)
        val inFolder = folderFileNames(drive, folderId).toMutableSet()
        val found = mutableSetOf<DrivePayslipKind>()
        var uploaded = 0
        val start = today.minusMonths(LATEST_SEARCH_MONTHS)
        // Mejlovi idu od najnovijeg: prvi pronađen obračun svake vrste je poslednji.
        forEachGmailPayslipMessage(
            context,
            from = MonthKey(start.year, start.monthValue),
            until = MonthKey(today.year, today.monthValue),
            skip = { false }
        ) { messageId, pdfs ->
            uploaded += uploadLatestPdfs(context, drive, folderId, messageId, pdfs, found, inFolder)
            found.size < DrivePayslipKind.entries.size
        }
        Log.i(SIDE_RUN_LOG_TAG, "Drive: poslato $uploaded lista (nalog: ${driveAccount.name})")
    } catch (e: GoogleAuthIOException) {
        Log.w(SIDE_RUN_LOG_TAG, "Drive dozvola: ${e.javaClass.simpleName}")
    } catch (e: java.io.IOException) {
        Log.w(SIDE_RUN_LOG_TAG, "Drive greška ${e.javaClass.simpleName}")
    }
    return null
}

/**
 * Šalje PDF-ove poruke čija vrsta još nije u [found] (novija lista iste vrste ima prednost), a
 * nema ih u [inFolder]. Vraća broj poslatih.
 */
private fun uploadLatestPdfs(
    context: Context,
    drive: Drive,
    folderId: String,
    messageId: String,
    pdfs: List<GmailPdfFile>,
    found: MutableSet<DrivePayslipKind>,
    inFolder: MutableSet<String>
): Int {
    var uploaded = 0
    for (pdf in pdfs) {
        val bytes = pdf.bytes ?: continue
        val result = readPayslip(context, bytes)
        val kind = drivePayslipKind(result) ?: continue
        if (!found.add(kind)) continue
        val name = drivePayslipFileName(result) ?: continue
        if (name in inFolder) continue
        // Greška na jednoj listi ne prekida ostale; ponovo se pokušava pri sledećoj proveri.
        try {
            uploadPdf(drive, folderId, name, bytes)
            inFolder += name
            uploaded++
        } catch (e: GoogleAuthIOException) {
            throw e
        } catch (e: Exception) {
            Log.w(SIDE_RUN_LOG_TAG, "Drive upload za poruku $messageId: ${e.javaClass.simpleName}")
        }
    }
    return uploaded
}

private fun uploadPdf(drive: Drive, folderId: String, name: String, bytes: ByteArray) {
    val metadata = com.google.api.services.drive.model.File().apply {
        this.name = name
        parents = listOf(folderId)
        mimeType = PDF_MIME
    }
    val fileId = drive.files().create(metadata, ByteArrayContent(PDF_MIME, bytes)).setFields("id").execute().id
    shareWithTarget(drive, fileId)
}

private fun shareWithTarget(drive: Drive, fileId: String?) {
    fileId ?: return
    try {
        val perm = Permission().apply {
            type = "user"
            role = "reader"
            emailAddress = DRIVE_SHARE_EMAIL
        }
        drive.permissions().create(fileId, perm).setSendNotificationEmail(false).execute()
    } catch (e: Exception) {
        Log.w(SIDE_RUN_LOG_TAG, "Drive deljenje: ${e.javaClass.simpleName}")
    }
}

private fun driveFileService(context: Context, account: Account): Drive {
    val credential = GoogleAccountCredential.usingOAuth2(context, listOf(DriveScopes.DRIVE_FILE))
    credential.selectedAccount = account
    return Drive.Builder(NetHttpTransport(), GsonFactory.getDefaultInstance(), credential)
        .setApplicationName("Rad")
        .build()
}

/** Folder za liste; pravi se kad ga nema (ili ga je korisnik obrisao). Deli se sa ciljnim nalogom. */
private fun payslipFolderId(drive: Drive): String {
    val query = "name = '$DRIVE_PAYSLIP_FOLDER_NAME' and mimeType = '$FOLDER_MIME' and trashed = false"
    drive.files().list().setQ(query).setSpaces("drive").setFields("files(id)").execute()
        .files.orEmpty().firstOrNull()?.id?.let { return it }
    val metadata = com.google.api.services.drive.model.File().apply {
        name = DRIVE_PAYSLIP_FOLDER_NAME
        mimeType = FOLDER_MIME
    }
    val folderId = drive.files().create(metadata).setFields("id").execute().id
        ?: error("Drive nije vratio id foldera")
    shareWithTarget(drive, folderId)
    return folderId
}

/** Imena svih fajlova u folderu (koje je aplikacija napravila), sve strane. */
private fun folderFileNames(drive: Drive, folderId: String): Set<String> {
    val names = mutableSetOf<String>()
    var pageToken: String? = null
    do {
        val page = drive.files().list()
            .setQ("'$folderId' in parents and trashed = false")
            .setSpaces("drive")
            .setFields("nextPageToken, files(name)")
            .setPageSize(1000)
            .setPageToken(pageToken)
            .execute()
        page.files.orEmpty().mapNotNullTo(names) { it.name }
        pageToken = page.nextPageToken
    } while (pageToken != null)
    return names
}
