package com.rad.shifttracker

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings

const val NOTIFICATION_ACCESS_DIALOG_TITLE = "Čitanje obaveštenja"
const val NOTIFICATION_ACCESS_DIALOG_TEXT =
    "Da bi Rad automatski upisao akontaciju iz NLB obaveštenja, uključi pristup obaveštenjima za ovu aplikaciju."
const val NOTIFICATION_ACCESS_OPEN_SETTINGS_LABEL = "Uključi"
const val NOTIFICATION_ACCESS_LATER_LABEL = "Kasnije"

fun isNotificationListenerAccessEnabled(context: Context): Boolean {
    val enabled = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
        ?: return false
    val component = ComponentName(context, RadBankNotificationListenerService::class.java)
    val flattened = component.flattenToString()
    val short = component.flattenToShortString()
    return enabled.split(':').any { item ->
        item.equals(flattened, ignoreCase = true) ||
            item.equals(short, ignoreCase = true) ||
            (item.contains(context.packageName, ignoreCase = true) &&
                item.contains(RadBankNotificationListenerService::class.java.name, ignoreCase = true))
    }
}

fun notificationListenerSettingsIntent(): Intent =
    Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
