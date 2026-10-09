package com.rad.shifttracker.azuriranje

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.cancellation.CancellationException

private const val CONNECT_TIMEOUT_MS = 15_000
private const val READ_TIMEOUT_MS = 30_000
private const val BUFFER_BYTES = 64 * 1024
private const val MAX_APK_BYTES = 100L * 1024 * 1024
private const val INSTALL_REQUEST_CODE = 4_201
internal const val UPDATE_INSTALL_ACTION = "com.rad.shifttracker.UPDATE_INSTALL_STATUS"

/** Ishod preuzimanja i predaje sistemskoj instalaciji. */
internal sealed interface UpdateInstallResult {
    /** Sistem prikazuje „Ažurirati aplikaciju?“; dalje vodi korisnik. */
    data object Started : UpdateInstallResult

    /** Telefon još ne dozvoljava Radu da instalira aplikacije: otvorena su podešavanja. */
    data object NeedsPermission : UpdateInstallResult
    data class Failed(val message: String) : UpdateInstallResult
}

/** Da li korisnik već dozvolio „Instaliraj nepoznate aplikacije“ za Rad. */
internal fun canInstallUpdates(context: Context): Boolean =
    context.packageManager.canRequestPackageInstalls()

/** Otvara podešavanje „Instaliraj nepoznate aplikacije“ baš za Rad. */
internal fun openInstallPermissionSettings(context: Context) {
    val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(intent)
}

/**
 * Skida APK direktno u sesiju sistemskog instalera (bez fajla na disku) i predaje ga sistemu.
 * Android sam proverava da je potpisan istim ključem kao instalirani Rad, pa tuđi APK ne može
 * da zameni aplikaciju. Zove se van glavne niti; [onProgress] dobija 0..100.
 */
internal fun downloadAndInstallUpdate(
    context: Context,
    update: AvailableUpdate,
    onProgress: (Int) -> Unit
): UpdateInstallResult {
    if (!canInstallUpdates(context)) {
        openInstallPermissionSettings(context)
        return UpdateInstallResult.NeedsPermission
    }
    val installer = context.packageManager.packageInstaller
    var sessionId = -1
    return try {
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        }
        sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            writeApk(update, session, onProgress)
            session.commit(installStatusIntent(context, sessionId).intentSender)
        }
        UpdateInstallResult.Started
    } catch (e: CancellationException) {
        abandon(installer, sessionId)
        throw e
    } catch (e: IOException) {
        abandon(installer, sessionId)
        UpdateInstallResult.Failed("Preuzimanje nije uspelo. Proveri internet i probaj ponovo.")
    } catch (e: SecurityException) {
        abandon(installer, sessionId)
        UpdateInstallResult.Failed("Telefon nije dozvolio instalaciju.")
    }
}

private fun writeApk(update: AvailableUpdate, session: PackageInstaller.Session, onProgress: (Int) -> Unit) {
    val connection = (URL(update.apkUrl).openConnection() as HttpURLConnection).apply {
        connectTimeout = CONNECT_TIMEOUT_MS
        readTimeout = READ_TIMEOUT_MS
        instanceFollowRedirects = true
    }
    try {
        if (connection.responseCode != HttpURLConnection.HTTP_OK) throw IOException("HTTP ${connection.responseCode}")
        val total = connection.contentLengthLong.takeIf { it > 0 } ?: update.apkSize
        connection.inputStream.use { input ->
            session.openWrite(UPDATE_APK_NAME, 0, if (total > 0) total else -1).use { output ->
                copyWithProgress(input, output, total, onProgress)
                session.fsync(output)
            }
        }
    } finally {
        connection.disconnect()
    }
}

/** Kopira APK u sesiju i javlja procenat; prevelik fajl (> 100 MB) se odbija. */
private fun copyWithProgress(input: InputStream, output: OutputStream, total: Long, onProgress: (Int) -> Unit) {
    if (total > MAX_APK_BYTES) throw IOException("APK je prevelik")
    val buffer = ByteArray(BUFFER_BYTES)
    var written = 0L
    var read = input.read(buffer)
    while (read >= 0) {
        written += read
        if (written > MAX_APK_BYTES) throw IOException("APK je prevelik")
        output.write(buffer, 0, read)
        if (total > 0) onProgress((written * PERCENT / total).toInt().coerceIn(0, PERCENT))
        read = input.read(buffer)
    }
}

private const val PERCENT = 100

private fun installStatusIntent(context: Context, sessionId: Int): PendingIntent {
    val intent = Intent(context, UpdateInstallReceiver::class.java).setAction(UPDATE_INSTALL_ACTION)
    // Mutable: sistem u intent upisuje status i intent za potvrdu korisnika.
    return PendingIntent.getBroadcast(
        context,
        INSTALL_REQUEST_CODE + sessionId,
        intent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
    )
}

private fun abandon(installer: PackageInstaller, sessionId: Int) {
    if (sessionId < 0) return
    try {
        installer.abandonSession(sessionId)
    } catch (e: SecurityException) {
        // Sesija je već zatvorena.
    }
}

/**
 * Status instalacije od sistema: kad treba potvrda korisnika, otvara sistemski prozor
 * „Ažurirati aplikaciju?“. Uspešna instalacija zatvara i ponovo pokreće aplikaciju sama.
 */
class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != UPDATE_INSTALL_ACTION) return
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        if (status != PackageInstaller.STATUS_PENDING_USER_ACTION) return
        val confirm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_INTENT)
        } ?: return
        context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
