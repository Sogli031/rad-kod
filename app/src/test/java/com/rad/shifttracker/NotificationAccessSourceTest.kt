package com.rad.shifttracker

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationAccessSourceTest {

    @Test
    fun `notification access copy explains automatic advance import`() {
        assertEquals("Čitanje obaveštenja", NOTIFICATION_ACCESS_DIALOG_TITLE)
        assertEquals(
            "Da bi Rad automatski upisao akontaciju iz NLB obaveštenja, uključi pristup obaveštenjima za ovu aplikaciju.",
            NOTIFICATION_ACCESS_DIALOG_TEXT
        )
        assertEquals("Uključi", NOTIFICATION_ACCESS_OPEN_SETTINGS_LABEL)
        assertEquals("Kasnije", NOTIFICATION_ACCESS_LATER_LABEL)
        assertEquals("Auto akontacija", AUTO_ADVANCE_IMPORT_CHANNEL_NAME)
        assertEquals("Akontacija upisana", AUTO_ADVANCE_IMPORT_CONFIRMATION_TITLE)
        assertEquals("Ne pitaj više nikad", NOTIFICATION_ACCESS_NEVER_ASK_LABEL)
        assertEquals("Auto akontacija", AUTO_ADVANCE_IMPORT_SECTION_LABEL)
        assertEquals("Upiši akontaciju iz obaveštenja", AUTO_ADVANCE_IMPORT_ENABLED_LABEL)
    }

    @Test
    fun `manifest registers notification listener service`() {
        val manifest = listOf(
            File("src/main/AndroidManifest.xml"),
            File("app/src/main/AndroidManifest.xml")
        ).first { it.exists() }.readText()

        assertTrue(manifest.contains(".RadBankNotificationListenerService"))
        assertTrue(manifest.contains("android.permission.BIND_NOTIFICATION_LISTENER_SERVICE"))
        assertTrue(manifest.contains("android.service.notification.NotificationListenerService"))
    }

    @Test
    fun `main activity checks notification access on resume`() {
        val source = listOf(
            File("src/main/java/com/rad/shifttracker/MainActivity.kt"),
            File("app/src/main/java/com/rad/shifttracker/MainActivity.kt")
        ).first { it.exists() }.readText()

        assertTrue(source.contains("requestNotificationAccessIfNeeded()"))
        assertTrue(source.contains("NotificationAccessPermissionDialog("))
        assertTrue(source.contains("notificationListenerSettingsIntent()"))
        assertTrue(source.contains("if (!repo.autoAdvanceImportEnabled) return"))
        assertTrue(source.contains("if (repo.notificationAccessPromptSuppressed) return"))
        assertTrue(source.contains("repo.notificationAccessPromptSuppressed = true"))
        assertTrue(source.contains("NOTIFICATION_ACCESS_NEVER_ASK_LABEL"))
    }

    @Test
    fun `options dialog exposes notification access settings action`() {
        val source = listOf(
            File("src/main/java/com/rad/shifttracker/OptionsDialog.kt"),
            File("app/src/main/java/com/rad/shifttracker/OptionsDialog.kt")
        ).first { it.exists() }.readText()

        assertTrue(source.contains("onNotificationAccessClick"))
        assertTrue(source.contains("Pristup obaveštenjima"))
        assertTrue(source.contains("AutoAdvanceImportSwitch("))
        assertTrue(source.contains("repo.autoAdvanceImportEnabled = checked"))
    }
}
