package com.rad.shifttracker

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent

/**
 * Ostaci ukinutih funkcija kod instalacija iz ranijih verzija. Sve je bezbedno ponavljati,
 * pa se poziva pri svakom pokretanju (kao brisanje starih prefs u [RadApplication]).
 *
 * - Podsetnik „uzmi račun za hranu“: topli obrok se sada čita sa listića. Alarm zakazan ranije
 *   se otkazuje po imenu stare klase (klasa više ne postoji, ali PendingIntent se poklapa po
 *   komponenti, akciji i request kodu), a kanal i sačuvan raspored se brišu.
 * - Mesečno ažuriranje poreza sa interneta: vrednosti sada upisuje poslednji listić.
 */
internal fun cleanUpRemovedFeatures(context: Context) {
    runCatching { cancelLegacyFoodReceiptAlarm(context) }
    runCatching {
        context.getSystemService(NotificationManager::class.java)
            ?.deleteNotificationChannel(LEGACY_FOOD_RECEIPT_CHANNEL_ID)
    }
    LEGACY_PREFS.forEach { name -> runCatching { context.deleteSharedPreferences(name) } }
}

private fun cancelLegacyFoodReceiptAlarm(context: Context) {
    val intent = Intent().apply {
        setClassName(context, LEGACY_FOOD_RECEIPT_RECEIVER)
        action = LEGACY_FOOD_RECEIPT_ACTION
    }
    val pendingIntent = PendingIntent.getBroadcast(
        context,
        LEGACY_FOOD_RECEIPT_ALARM_REQUEST_CODE,
        intent,
        PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
    ) ?: return
    context.getSystemService(AlarmManager::class.java)?.cancel(pendingIntent)
    pendingIntent.cancel()
}

private const val LEGACY_FOOD_RECEIPT_RECEIVER = "com.rad.shifttracker.FoodReceiptReminderReceiver"
private const val LEGACY_FOOD_RECEIPT_ACTION = "com.rad.shifttracker.action.SHOW_FOOD_RECEIPT_REMINDER"
private const val LEGACY_FOOD_RECEIPT_ALARM_REQUEST_CODE = 240501
private const val LEGACY_FOOD_RECEIPT_CHANNEL_ID = "food_receipt_reminder"
private val LEGACY_PREFS = listOf(
    "food_receipt_reminder",
    "monthly_tax_update_prefs",
    "tax_parameter_cache_prefs"
)
