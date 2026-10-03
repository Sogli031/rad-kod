package com.rad.shifttracker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BloodDonationReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // Učitavanje lokalnog fajla ide van glavne niti; goAsync drži receiver živim dok se ne završi.
        val pending = goAsync()
        val appContext = context.applicationContext
        Thread {
            try {
                handle(appContext, intent.action)
            } finally {
                pending.finish()
            }
        }.start()
    }

    private fun handle(context: Context, action: String?) {
        val app = context as? RadApplication
        val repo = app?.shiftRepositoryOrNull ?: ShiftRepository().also { it.loadFromLocal(context) }
        if (repo.lastBloodDonationDate == null) {
            BloodDonationReminderScheduler.cancel(context)
            return
        }

        if (action == BloodDonationReminderScheduler.ACTION_SHOW_BLOOD_DONATION_REMINDER) {
            BloodDonationReminderScheduler.showNotification(context)
        }
        BloodDonationReminderScheduler.reschedule(context, repo)
    }
}
