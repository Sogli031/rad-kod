package com.rad.shifttracker

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import java.time.LocalDateTime
import java.time.ZoneId

internal object BloodDonationReminderScheduler {
    const val ACTION_SHOW_BLOOD_DONATION_REMINDER =
        "com.rad.shifttracker.action.SHOW_BLOOD_DONATION_REMINDER"

    private const val CHANNEL_ID = "blood_donation_reminder"
    private const val CHANNEL_NAME = "Davanje krvi"
    private const val NOTIFICATION_ID = 2406
    private const val ALARM_REQUEST_CODE = 240601
    private const val OPEN_REQUEST_CODE = 240602

    fun reschedule(context: Context, repo: ShiftRepository): BloodDonationReminderScheduleStatus {
        cancelAlarm(context)
        val plan = planBloodDonationReminderAlarm(
            lastDonationDate = repo.lastBloodDonationDate,
            now = LocalDateTime.now()
        )
        if (plan !is BloodDonationReminderAlarmPlan.Schedule) {
            return currentStatus(context, repo)
        }
        scheduleAlarm(context, plan.reminderTime.toEpochMillis())
        return currentStatus(context, repo)
    }

    fun cancel(context: Context) {
        cancelAlarm(context)
    }

    fun currentStatus(context: Context, repo: ShiftRepository): BloodDonationReminderScheduleStatus {
        val lastDonationDate = repo.lastBloodDonationDate
        val plan = planBloodDonationReminderAlarm(
            lastDonationDate = lastDonationDate,
            now = LocalDateTime.now()
        )
        return BloodDonationReminderScheduleStatus(
            lastDonationDate = lastDonationDate,
            donationCount = repo.bloodDonationCount,
            nextDonationDate = lastDonationDate?.let(::calculateNextBloodDonationDate),
            nextReminderTime = (plan as? BloodDonationReminderAlarmPlan.Schedule)?.reminderTime,
            notificationPermissionGranted = hasBloodDonationNotificationPermission(context)
        )
    }

    fun notificationPermissionRequired(context: Context): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !hasBloodDonationNotificationPermission(context)
    }

    @SuppressLint("MissingPermission")
    fun showNotification(context: Context) {
        if (!hasBloodDonationNotificationPermission(context)) return
        ensureChannel(context)
        val notificationText = "Prošla su 3 meseca od poslednjeg davanja krvi. Možeš ponovo da daš krv."
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_blood_donation_notification)
            .setContentTitle("Davanje krvi")
            .setContentText(notificationText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(notificationText))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(openAppPendingIntent(context))
            .build()
        runCatching {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        }
    }

    /**
     * Običan (netačan) alarm: podsetnik „možeš ponovo da daš krv“ ne mora da stigne u minut,
     * pa aplikaciji ne treba dozvola za tačne alarme. Android ga može malo pomeriti.
     */
    private fun scheduleAlarm(context: Context, triggerAtMillis: Long) {
        context.getSystemService(AlarmManager::class.java).setAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            triggerAtMillis,
            reminderPendingIntent(context)
        )
    }

    private fun cancelAlarm(context: Context) {
        context.getSystemService(AlarmManager::class.java).cancel(reminderPendingIntent(context))
    }

    private fun reminderPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, BloodDonationReminderReceiver::class.java).apply {
            action = ACTION_SHOW_BLOOD_DONATION_REMINDER
        }
        return PendingIntent.getBroadcast(
            context,
            ALARM_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun openAppPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context,
            OPEN_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_DEFAULT)
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }
}

private fun hasBloodDonationNotificationPermission(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
    return ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED
}

private fun LocalDateTime.toEpochMillis(): Long {
    return atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
}
