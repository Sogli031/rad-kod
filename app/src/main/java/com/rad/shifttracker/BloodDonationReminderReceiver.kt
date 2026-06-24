package com.rad.shifttracker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BloodDonationReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as? RadApplication
        val repo = app?.shiftRepositoryOrNull ?: ShiftRepository().also { it.loadFromLocal(context) }
        if (repo.lastBloodDonationDate == null) {
            BloodDonationReminderScheduler.cancel(context)
            return
        }

        if (intent.action == BloodDonationReminderScheduler.ACTION_SHOW_BLOOD_DONATION_REMINDER) {
            BloodDonationReminderScheduler.showNotification(context)
        }
        BloodDonationReminderScheduler.reschedule(context, repo)
    }
}
