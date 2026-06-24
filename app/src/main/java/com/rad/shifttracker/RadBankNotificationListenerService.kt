package com.rad.shifttracker

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

const val AUTO_ADVANCE_IMPORT_CHANNEL_ID = "rad_auto_advance_import"
const val AUTO_ADVANCE_IMPORT_CHANNEL_NAME = "Auto akontacija"
const val AUTO_ADVANCE_IMPORT_CONFIRMATION_TITLE = "Akontacija upisana"

class RadBankNotificationListenerService : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (!isRadBankNotificationPackage(sbn.packageName)) return
        val payload = collectRadNotificationText(sbn.notification) ?: return
        val candidate = parseRadAdvancePaymentNotification(payload) ?: return
        val app = application as? RadApplication ?: return
        app.initializeServices()
        val result = app.shiftRepository.recordAdvancePaymentNotificationImport(candidate)
        if (result.decision != AdvancePaymentNotificationImportDecision.IMPORTED) return
        postAutoAdvanceImportConfirmation(this, candidate)
    }
}

private fun ensureAutoAdvanceImportChannel(context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
    val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        ?: return
    if (manager.getNotificationChannel(AUTO_ADVANCE_IMPORT_CHANNEL_ID) != null) return
    val channel = NotificationChannel(
        AUTO_ADVANCE_IMPORT_CHANNEL_ID,
        AUTO_ADVANCE_IMPORT_CHANNEL_NAME,
        NotificationManager.IMPORTANCE_DEFAULT
    )
    channel.description = "Potvrde automatskog upisa akontacije"
    manager.createNotificationChannel(channel)
}

fun postAutoAdvanceImportConfirmation(
    context: Context,
    candidate: RadAdvanceImportCandidate
) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
    }
    ensureAutoAdvanceImportChannel(context)
    val intent = Intent(context, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
    }
    val pendingIntent = PendingIntent.getActivity(
        context,
        0,
        intent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
    val amountText = "${formatMoney2(candidate.amount)} ${CONFIG.pay.currency}"
    val monthText = monthYearText(candidate.month)
    val notification = NotificationCompat.Builder(context, AUTO_ADVANCE_IMPORT_CHANNEL_ID)
        .setSmallIcon(R.mipmap.rad)
        .setContentTitle(AUTO_ADVANCE_IMPORT_CONFIRMATION_TITLE)
        .setContentText("$amountText za $monthText")
        .setStyle(
            NotificationCompat.BigTextStyle().bigText(
                "Iznos: $amountText\nMesec: $monthText\nOpis: ${candidate.place}"
            )
        )
        .setAutoCancel(true)
        .setContentIntent(pendingIntent)
        .setPriority(NotificationCompat.PRIORITY_DEFAULT)
        .build()
    NotificationManagerCompat.from(context)
        .notify((System.currentTimeMillis() % Int.MAX_VALUE).toInt(), notification)
}
