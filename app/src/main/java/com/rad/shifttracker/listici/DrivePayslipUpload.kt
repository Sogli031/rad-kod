package com.rad.shifttracker.listici

import android.accounts.Account
import android.content.Context
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
import java.time.YearMonth
import com.rad.shifttracker.smene.MonthKey

/**
 * Liste iz Gmail-a (akontacija, prevoz, konačna) se same šalju u folder [DRIVE_PAYSLIP_FOLDER_NAME]
 * na Google Drive-u istog naloga. Dozvola je samo `drive.file`: aplikacija vidi samo fajlove koje je
 * sama napravila. Poruka čiji su svi prilozi poslati se pamti i više se ne preuzima.
 */
internal const val DRIVE_PAYSLIP_SWITCH_LABEL = "Liste na Google Drive"
internal const val DRIVE_PAYSLIP_FOLDER_NAME = "Rad liste"

private const val DRIVE_PAYSLIP_PREFS_NAME = "rad_drive_payslip_prefs"
private const val KEY_ENABLED = "enabled"
private const val KEY_DONE_MESSAGES = "done_messages"
private const val KEY_LAST_ERROR = "last_error"
private const val DRIVE_FILE_TOKEN_SCOPE = "oauth2:${DriveScopes.DRIVE_FILE}"
private const val FOLDER_MIME = "application/vnd.google-apps.folder"
private const val PDF_MIME = "application/pdf"

private fun drivePayslipPrefs(context: Context) =
    context.getSharedPreferences(DRIVE_PAYSLIP_PREFS_NAME, Context.MODE_PRIVATE)

internal fun isDrivePayslipUploadEnabled(context: Context): Boolean =
    drivePayslipPrefs(context).getBoolean(KEY_ENABLED, false)

internal fun setDrivePayslipUploadEnabled(context: Context, enabled: Boolean) {
    drivePayslipPrefs(context).edit().putBoolean(KEY_ENABLED, enabled).remove(KEY_LAST_ERROR).apply()
}

/** Poslednja greška slanja (prikazuje se u Opcijama), ili null. */
internal fun drivePayslipUploadError(context: Context): String? =
    drivePayslipPrefs(context).getString(KEY_LAST_ERROR, null)

/**
 * Proverava Drive dozvolu (`drive.file`) za Gmail nalog sa listama. Radi mrežu — zvati van glavne
 * niti.
 */
internal fun authorizeDrivePayslipUpload(context: Context): GmailAuthorizeResult {
    val account = gmailPayslipAccount(context) ?: return GmailAuthorizeResult.Failed
    return try {
        GoogleAuthUtil.getToken(context, account, DRIVE_FILE_TOKEN_SCOPE)
        GmailAuthorizeResult.Authorized
    } catch (e: UserRecoverableAuthException) {
        e.intent?.let { GmailAuthorizeResult.NeedsConsent(it) } ?: GmailAuthorizeResult.Failed
    } catch (e: GoogleAuthException) {
        GmailAuthorizeResult.Failed
    } catch (e: java.io.IOException) {
        GmailAuthorizeResult.Failed
    }
}

/**
 * Ime fajla na Drive-u za pročitan PDF: „2026-09 akontacija rb.1.pdf“. Šalju se samo akontacija,
 * prevoz i konačna lista; za ostalo (regres, nepoznat obračun, PDF koji nije lista) je null.
 */
internal fun drivePayslipFileName(result: PayslipParseResult): String? = when (result) {
    is PayslipParseResult.Success -> drivePayslipFileName(result.payslip.month, "konačna", result.payslip.runLabel)
    is PayslipParseResult.SideRun -> when (result.kind) {
        PayslipSideRunKind.ADVANCE -> drivePayslipFileName(result.month, "akontacija", result.runLabel)
        PayslipSideRunKind.TRANSPORT -> drivePayslipFileName(result.month, "prevoz", result.runLabel)
        PayslipSideRunKind.REGRESS, PayslipSideRunKind.OTHER -> null
    }
    is PayslipParseResult.Failure -> null
}

internal fun drivePayslipFileName(month: MonthKey, kind: String, runLabel: String?): String {
    val run = runLabel?.let { RUN_LABEL_REGEX.find(it)?.value }?.let { " $it" }.orEmpty()
    return "${month.year}-${month.month.toString().padStart(2, '0')} $kind$run.pdf"
}

/** Pozadinska provera i uključivanje u Opcijama ne šalju istovremeno (isti fajl dvaput). */
private val UPLOAD_LOCK = Any()

private val RUN_LABEL_REGEX = Regex("""rb\.\d+""")

/**
 * Šalje na Drive liste iz Gmail-a od januara prošle godine koje još nisu poslate. Ništa ne radi
 * dok slanje nije uključeno ili Gmail nije povezan. Radi mrežu — zvati van glavne niti.
 */
internal fun uploadPayslipsToDrive(context: Context, today: YearMonth = YearMonth.now()): Unit = synchronized(UPLOAD_LOCK) {
    if (!isDrivePayslipUploadEnabled(context)) return
    val account = gmailPayslipAccount(context) ?: return
    val prefs = drivePayslipPrefs(context)
    val done = prefs.getStringSet(KEY_DONE_MESSAGES, emptySet()).orEmpty().toMutableSet()
    val error = try {
        val drive = driveFileService(context, account)
        val folderId = payslipFolderId(drive)
        var uploaded = 0
        forEachGmailPayslipMessage(
            context,
            from = MonthKey(today.year - 1, 1),
            until = MonthKey(today.year, today.monthValue),
            skip = { it in done }
        ) { messageId, pdfs ->
            // Prilog koji nije preuzet: poruka ostaje neobrađena i pokušava se sledeći put.
            if (pdfs.any { it.bytes == null }) return@forEachGmailPayslipMessage
            uploaded += uploadMessagePdfs(context, drive, folderId, pdfs)
            done += messageId
            prefs.edit().putStringSet(KEY_DONE_MESSAGES, done.toSet()).apply()
        }
        Log.i(SIDE_RUN_LOG_TAG, "Drive: poslato $uploaded lista")
        null
    } catch (e: GoogleAuthIOException) {
        Log.w(SIDE_RUN_LOG_TAG, "Drive dozvola: ${e.javaClass.simpleName}")
        "Nema dozvole za Google Drive"
    } catch (e: java.io.IOException) {
        Log.w(SIDE_RUN_LOG_TAG, "Drive greška ${e.javaClass.simpleName}")
        "Slanje na Google Drive nije uspelo"
    }
    prefs.edit().apply { if (error != null) putString(KEY_LAST_ERROR, error) else remove(KEY_LAST_ERROR) }.apply()
}

/** Šalje PDF-ove jedne poruke koji su akontacija, prevoz ili konačna, a još ih nema u folderu. */
private fun uploadMessagePdfs(context: Context, drive: Drive, folderId: String, pdfs: List<GmailPdfFile>): Int {
    var uploaded = 0
    for (pdf in pdfs) {
        val bytes = pdf.bytes ?: continue
        val name = drivePayslipFileName(readPayslip(context, bytes)) ?: continue
        if (existsInFolder(drive, folderId, name)) continue
        val metadata = com.google.api.services.drive.model.File().apply {
            this.name = name
            parents = listOf(folderId)
            mimeType = PDF_MIME
        }
        drive.files().create(metadata, ByteArrayContent(PDF_MIME, bytes)).setFields("id").execute()
        uploaded++
    }
    return uploaded
}

private fun driveFileService(context: Context, account: Account): Drive {
    val credential = GoogleAccountCredential.usingOAuth2(context, listOf(DriveScopes.DRIVE_FILE))
    credential.selectedAccount = account
    return Drive.Builder(NetHttpTransport(), GsonFactory.getDefaultInstance(), credential)
        .setApplicationName("Rad")
        .build()
}

/** Folder za liste; pravi se kad ga nema (ili ga je korisnik obrisao). */
private fun payslipFolderId(drive: Drive): String {
    val query = "name = '$DRIVE_PAYSLIP_FOLDER_NAME' and mimeType = '$FOLDER_MIME' and trashed = false"
    drive.files().list().setQ(query).setSpaces("drive").setFields("files(id)").execute()
        .files.orEmpty().firstOrNull()?.id?.let { return it }
    val metadata = com.google.api.services.drive.model.File().apply {
        name = DRIVE_PAYSLIP_FOLDER_NAME
        mimeType = FOLDER_MIME
    }
    return drive.files().create(metadata).setFields("id").execute().id
        ?: error("Drive nije vratio id foldera")
}

private fun existsInFolder(drive: Drive, folderId: String, name: String): Boolean =
    drive.files().list()
        .setQ("name = '$name' and '$folderId' in parents and trashed = false")
        .setSpaces("drive")
        .setFields("files(id)")
        .execute()
        .files.orEmpty().isNotEmpty()
