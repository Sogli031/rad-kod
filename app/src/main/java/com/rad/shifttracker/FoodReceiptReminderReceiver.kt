package com.rad.shifttracker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class FoodReceiptReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as? RadApplication
        val repo = app?.shiftRepositoryOrNull ?: ShiftRepository().also { it.loadFromLocal(context) }
        if (!repo.foodReceiptReminderEnabled) {
            FoodReceiptReminderScheduler.cancel(context)
            return
        }

        if (intent.action == FoodReceiptReminderScheduler.ACTION_SHOW_FOOD_RECEIPT_REMINDER) {
            FoodReceiptReminderScheduler.showNotification(context)
        } else {
            // BOOT_COMPLETED: prikaži podsetnik propušten dok je telefon
            // bio ugašen pre nego što reschedule pregazi sačuvani plan.
            FoodReceiptReminderScheduler.showMissedNotificationIfAny(context)
        }
        FoodReceiptReminderScheduler.reschedule(context, repo)
    }
}
