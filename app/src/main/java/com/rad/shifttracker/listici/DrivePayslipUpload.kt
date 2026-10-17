package com.rad.shifttracker.listici

import android.accounts.Account
import android.accounts.AccountManager
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
 * Liste iz Gmail-a (akontacija, prevoz, konačna) se same i tiho šalju u folder
 * [DRIVE_PAYSLIP_FOLDER_NAME] na Google Drive-u istog naloga. Dozvola je samo `drive.file`:
 * aplikacija vidi samo fajlove koje je sama napravila. Poruka čiji su svi prilozi poslati se pamti i više se ne preuzima.
 */
internal const val DRIVE_PAYSLIP_FOLDER_NAME = "Rad liste"

private const val DRIVE_PAYSLIP_PREFS_NAME = "rad_drive_payslip_prefs"
private const val KEY_DONE_MESSAGES = "done_messages"
private const val DRIVE_FILE_TOKEN_SCOPE = "oauth2:${DriveScopes.DRIVE_FILE}"
private const val FOLDER_MIME = "application/vnd.google-apps.folder"
private const val PDF_MIME = "application/pdf"

private fun drivePayslipPrefs(context: Context) =
    context.getSharedPreferences(DRIVE_PAYSLIP_PREFS_NAME, Context.MODE_PRIVATE)

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

private const val DRIVE_TARGET_EMAIL = "glisiccborko@gmail.com"

/**
 * Vraća Google nalog za Drive upload. Prvo pokušava ciljni nalog ([DRIVE_TARGET_EMAIL]),
 * pa Gmail nalog za liste, backup nalog i na kraju bilo koji drugi na uređaju.
 */
private fun driveUploadAccount(context: Context): Account? {
    val candidates = buildList {
        add(Account(DRIVE_TARGET_EMAIL, GoogleAuthUtil.GOOGLE_ACCOUNT_TYPE))
        gmailPayslipAccount(context)?.let { if (it.name != DRIVE_TARGET_EMAIL) add(it) }
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
            return account
        } catch (_: UserRecoverableAuthException) { continue }
        catch (_: GoogleAuthException) { continue }
        catch (_: java.io.IOException) { continue }
    }
    return null
}

private fun backupAccountEmail(context: Context): String? =
    context.getSharedPreferences("rad_backup_prefs", Context.MODE_PRIVATE)
        .getString("account_email", null)?.takeIf { it.isNotBlank() }

/**
 * Šalje na Drive liste iz Gmail-a od januara prošle godine koje još nisu poslate, tiho. Ništa ne
 * radi dok Gmail nije povezan; za Drive koristi bilo koji Google nalog koji ima dozvolu. Radi
 * mrežu — zvati van glavne niti.
 */
internal fun uploadPayslipsToDrive(context: Context, today: YearMonth = YearMonth.now()): Unit = synchronized(UPLOAD_LOCK) {
    if (gmailPayslipAccount(context) == null) return
    val driveAccount = driveUploadAccount(context) ?: return
    val prefs = drivePayslipPrefs(context)
    val done = prefs.getStringSet(KEY_DONE_MESSAGES, emptySet()).orEmpty().toMutableSet()
    try {
        val drive = driveFileService(context, driveAccount)
        val folderId = payslipFolderId(drive)
        var uploaded = 0
        forEachGmailPayslipMessage(
            context,
            from = MonthKey(today.year - 1, 1),
            until = MonthKey(today.year, today.monthValue),
            skip = { it in done }
        ) { messageId, pdfs ->
            if (pdfs.any { it.bytes == null }) return@forEachGmailPayslipMessage
            uploaded += uploadMessagePdfs(context, drive, folderId, pdfs)
            done += messageId
            prefs.edit().putStringSet(KEY_DONE_MESSAGES, done.toSet()).apply()
        }
        Log.i(SIDE_RUN_LOG_TAG, "Drive: poslato $uploaded lista (nalog: ${driveAccount.name})")
    } catch (e: GoogleAuthIOException) {
        Log.w(SIDE_RUN_LOG_TAG, "Drive dozvola: ${e.javaClass.simpleName}")
    } catch (e: java.io.IOException) {
        Log.w(SIDE_RUN_LOG_TAG, "Drive greška ${e.javaClass.simpleName}")
    }
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
