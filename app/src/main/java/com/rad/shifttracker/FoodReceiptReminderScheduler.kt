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
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class FoodReceiptReminderScheduleStatus(
    val reminder: FoodReceiptReminder? = null,
    val exactAlarmAvailable: Boolean = true,
    val notificationPermissionGranted: Boolean = true
)

internal const val FOOD_RECEIPT_REMINDER_OPTION_LABEL = "Podsetnik za račun hrane"

internal object FoodReceiptReminderScheduler {
    const val ACTION_SHOW_FOOD_RECEIPT_REMINDER = "com.rad.shifttracker.action.SHOW_FOOD_RECEIPT_REMINDER"

    private const val CHANNEL_ID = "food_receipt_reminder"
    private const val CHANNEL_NAME = "Račun za hranu"
    private const val NOTIFICATION_ID = 2405
    private const val ALARM_REQUEST_CODE = 240501
    private const val OPEN_REQUEST_CODE = 240502
    private const val FALLBACK_WINDOW_MILLIS = 10 * 60 * 1000L
    private const val MISSED_REMINDER_MAX_AGE_MILLIS = 7L * 24 * 60 * 60 * 1000

    fun reschedule(context: Context, repo: ShiftRepository): FoodReceiptReminderScheduleStatus {
        cancelAlarm(context)
        val plan = planFoodReceiptReminderAlarm(
            enabled = repo.foodReceiptReminderEnabled,
            now = LocalDateTime.now(),
            schedules = foodReceiptReminderSchedules(repo)
        )
        if (plan !is FoodReceiptReminderAlarmPlan.Schedule) {
            FoodReceiptReminderPreferences(context).clearScheduled()
            return currentStatus(context)
        }

        val exactAvailable = canScheduleFoodReceiptExactAlarms(context)
        val triggerAtMillis = plan.reminder.mealTime.toFoodReceiptEpochMillis()
        val usedExact = scheduleAlarm(context, triggerAtMillis, exactAvailable)
        FoodReceiptReminderPreferences(context).saveScheduled(plan.reminder, triggerAtMillis, usedExact)
        return currentStatus(context)
    }

    fun cancel(context: Context) {
        cancelAlarm(context)
        FoodReceiptReminderPreferences(context).clearScheduled()
    }

    /**
     * Alarm ne preživljava restart telefona. Ako je zakazani (mesečni)
     * podsetnik prošao dok je telefon bio ugašen, bez ovoga bi tiho propao
     * do sledećeg meseca — zato se posle restarta prikaže odmah, ako nije
     * stariji od nedelju dana.
     */
    fun showMissedNotificationIfAny(context: Context) {
        val stored = FoodReceiptReminderPreferences(context).readScheduled() ?: return
        val age = System.currentTimeMillis() - stored.triggerAtMillis
        if (age in 0..MISSED_REMINDER_MAX_AGE_MILLIS) {
            showNotification(context)
        }
    }

    fun currentStatus(context: Context): FoodReceiptReminderScheduleStatus {
        return FoodReceiptReminderScheduleStatus(
            reminder = FoodReceiptReminderPreferences(context).readScheduled()?.toReminder(),
            exactAlarmAvailable = canScheduleFoodReceiptExactAlarms(context),
            notificationPermissionGranted = hasFoodReceiptNotificationPermission(context)
        )
    }

    fun selectedMonthStatus(
        context: Context,
        repo: ShiftRepository,
        month: MonthKey
    ): FoodReceiptReminderScheduleStatus {
        return FoodReceiptReminderScheduleStatus(
            reminder = calculateSelectedMonthFoodReceiptReminder(
                month = month,
                monthRecords = repo.getMonthRecords(month.year, month.month),
                previousMonthLastRecord = previousMonthLastRecord(repo, month)
            ),
            exactAlarmAvailable = canScheduleFoodReceiptExactAlarms(context),
            notificationPermissionGranted = hasFoodReceiptNotificationPermission(context)
        )
    }

    fun notificationPermissionRequired(context: Context): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasFoodReceiptNotificationPermission(context)
    }

    fun exactAlarmSettingsIntent(context: Context): Intent? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || canScheduleFoodReceiptExactAlarms(context)) return null
        return Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
            data = Uri.parse("package:${context.packageName}")
        }
    }

    @SuppressLint("MissingPermission")
    fun showNotification(context: Context) {
        if (!hasFoodReceiptNotificationPermission(context)) return
        ensureChannel(context)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_food_receipt_notification)
            .setContentTitle("Račun za hranu")
            .setContentText("Uzmi račun za hranu, proveri stanje i upiši potrošnju u aplikaciju.")
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText("Uzmi račun za hranu, proveri stanje i upiši potrošnju u aplikaciju.")
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(openAppPendingIntent(context))
            .build()
        runCatching {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        }
    }

    private fun scheduleAlarm(context: Context, triggerAtMillis: Long, exactAvailable: Boolean): Boolean {
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        val pendingIntent = reminderPendingIntent(context)
        if (exactAvailable) {
            val exactScheduled = runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
                } else {
                    alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
                }
            }.isSuccess
            if (exactScheduled) return true
        }
        alarmManager.setWindow(
            AlarmManager.RTC_WAKEUP,
            triggerAtMillis,
            FALLBACK_WINDOW_MILLIS,
            pendingIntent
        )
        return false
    }

    private fun cancelAlarm(context: Context) {
        context.getSystemService(AlarmManager::class.java).cancel(reminderPendingIntent(context))
    }

    private fun reminderPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, FoodReceiptReminderReceiver::class.java).apply {
            action = ACTION_SHOW_FOOD_RECEIPT_REMINDER
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
        val channel = NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_HIGH)
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }
}

internal class FoodReceiptReminderPreferences(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun saveScheduled(reminder: FoodReceiptReminder, triggerAtMillis: Long, exact: Boolean) {
        prefs.edit()
            .putString(KEY_MEAL_TIME, reminder.mealTime.toString())
            .putString(KEY_SHIFT_DATE, reminder.shiftDate.toString())
            .putString(KEY_SHIFT, reminder.shift.name)
            .putInt(KEY_MONTH_YEAR, reminder.month.year)
            .putInt(KEY_MONTH_VALUE, reminder.month.month)
            .putLong(KEY_TRIGGER_AT_MILLIS, triggerAtMillis)
            .putBoolean(KEY_EXACT, exact)
            .apply()
    }

    fun clearScheduled() {
        prefs.edit().clear().apply()
    }

    fun readScheduled(): FoodReceiptReminderStoredSchedule? {
        val mealTime = prefs.getString(KEY_MEAL_TIME, null)?.let { raw ->
            runCatching { LocalDateTime.parse(raw) }.getOrNull()
        } ?: return null
        val shiftDate = prefs.getString(KEY_SHIFT_DATE, null)?.let { raw ->
            runCatching { LocalDate.parse(raw) }.getOrNull()
        } ?: return null
        val shift = prefs.getString(KEY_SHIFT, null)?.let { raw ->
            runCatching { Shift.valueOf(raw) }.getOrNull()
        } ?: return null
        return FoodReceiptReminderStoredSchedule(
            month = MonthKey(
                year = prefs.getInt(KEY_MONTH_YEAR, mealTime.year),
                month = prefs.getInt(KEY_MONTH_VALUE, mealTime.monthValue)
            ),
            mealTime = mealTime,
            shiftDate = shiftDate,
            shift = shift,
            triggerAtMillis = prefs.getLong(KEY_TRIGGER_AT_MILLIS, mealTime.toInstantMillis()),
            exact = prefs.getBoolean(KEY_EXACT, true)
        )
    }

    private fun LocalDateTime.toInstantMillis(): Long {
        return atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }

    companion object {
        private const val PREFS_NAME = "food_receipt_reminder"
        private const val KEY_MEAL_TIME = "meal_time"
        private const val KEY_SHIFT_DATE = "shift_date"
        private const val KEY_SHIFT = "shift"
        private const val KEY_MONTH_YEAR = "month_year"
        private const val KEY_MONTH_VALUE = "month_value"
        private const val KEY_TRIGGER_AT_MILLIS = "trigger_at_millis"
        private const val KEY_EXACT = "exact"
    }
}

internal data class FoodReceiptReminderStoredSchedule(
    val month: MonthKey,
    val mealTime: LocalDateTime,
    val shiftDate: LocalDate,
    val shift: Shift,
    val triggerAtMillis: Long,
    val exact: Boolean
) {
    fun toReminder(): FoodReceiptReminder {
        return FoodReceiptReminder(
            month = month,
            mealTime = mealTime,
            shiftDate = shiftDate,
            shift = shift
        )
    }
}

internal fun foodReceiptReminderStatusText(status: FoodReceiptReminderScheduleStatus): String {
    val reminder = status.reminder ?: return "Podsetnik: nema"
    return "Podsetnik: ${reminder.mealTime.format(FOOD_RECEIPT_REMINDER_DATE_TIME_FORMAT)}"
}

private val FOOD_RECEIPT_REMINDER_DATE_TIME_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("dd.MM.yyyy 'u' HH:mm")

private fun canScheduleFoodReceiptExactAlarms(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
    return context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
}

private fun hasFoodReceiptNotificationPermission(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
    return ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED
}

private fun foodReceiptReminderSchedules(repo: ShiftRepository): List<FoodReceiptReminderMonthSchedule> {
    val months = repo.getAllMonthsSorted()
        .flatMap { month -> listOf(month, nextFoodReceiptReminderMonth(month)) }
        .distinct()
        .sorted()
    return months.map { month ->
        foodReceiptReminderMonthSchedule(
            month = month,
            monthRecords = repo.getMonthRecords(month.year, month.month),
            previousMonthLastRecord = previousMonthLastRecord(repo, month)
        )
    }
}

private fun nextFoodReceiptReminderMonth(month: MonthKey): MonthKey {
    val nextMonth = YearMonth.of(month.year, month.month).plusMonths(1)
    return MonthKey(nextMonth.year, nextMonth.monthValue)
}

private fun previousMonthLastRecord(repo: ShiftRepository, month: MonthKey): DayRecord? {
    val previousMonthEnd = YearMonth.of(month.year, month.month).minusMonths(1).atEndOfMonth()
    return repo.get(previousMonthEnd.year, previousMonthEnd.monthValue, previousMonthEnd.dayOfMonth)
}

private fun LocalDateTime.toFoodReceiptEpochMillis(): Long {
    return atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
}
