package com.rad.shifttracker

import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.rad.shifttracker.data.backup.GoogleDriveBackup
import java.io.File

class MainActivity : ComponentActivity() {
    private lateinit var repo: ShiftRepository
    private lateinit var backup: GoogleDriveBackup
    private var exactAlarmWarningShownThisForeground = false
    private var notificationAccessWarningShownThisForeground = false
    private var showExactAlarmPermissionDialog by mutableStateOf(false)
    private var showNotificationAccessDialog by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        @Suppress("DEPRECATION")
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        @Suppress("DEPRECATION")
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }

        // Proveri da li postoji crash log od prethodnog pokretanja
        val crashFile = File(filesDir, RadApplication.CRASH_LOG_FILE)
        if (crashFile.exists()) {
            val crashLog = crashFile.readText()
            crashFile.delete()
            setContent {
                RadTheme {
                    CrashLogScreen(crashLog)
                }
            }
            return
        }

        // Normalna inicijalizacija wrappovana u try-catch
        try {
            initializeApp()
        } catch (e: Exception) {
            Log.e("MainActivity", "Inicijalizacija pukla", e)
            setContent {
                RadTheme {
                    CrashLogScreen(Log.getStackTraceString(e))
                }
            }
        }
    }

    private fun initializeApp() {
        val app = application as RadApplication
        app.initializeServices()
        repo = app.shiftRepository
        backup = app.googleDriveBackup

        setContent {
            RadApp(repo, backup)
            if (showExactAlarmPermissionDialog) {
                ExactAlarmPermissionDialog(
                    onOpenSettings = ::openExactAlarmSettings,
                    onDismiss = { showExactAlarmPermissionDialog = false }
                )
            }
            if (showNotificationAccessDialog) {
                NotificationAccessPermissionDialog(
                    onOpenSettings = ::openNotificationAccessSettings,
                    onNeverAskAgain = ::suppressNotificationAccessPrompt,
                    onDismiss = { showNotificationAccessDialog = false }
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        exactAlarmWarningShownThisForeground = false
        notificationAccessWarningShownThisForeground = false
    }

    override fun onResume() {
        super.onResume()
        val app = application as? RadApplication
        if (app?.consumePersistenceFailure() == true) {
            Toast.makeText(
                this,
                "Poslednja izmena nije mogla da se sačuva. Proveri slobodan prostor i pokušaj ponovo.",
                Toast.LENGTH_LONG
            ).show()
        }
        if (::repo.isInitialized) {
            FoodReceiptReminderScheduler.reschedule(this, repo)
            BloodDonationReminderScheduler.reschedule(this, repo)
            requestNotificationAccessIfNeeded()
            requestExactAlarmPermissionIfNeeded()
        }
    }

    override fun onStop() {
        if (::repo.isInitialized) {
            (application as? RadApplication)?.flushPendingPersistence()
        }
        super.onStop()
    }

    private fun requestNotificationAccessIfNeeded() {
        if (notificationAccessWarningShownThisForeground) return
        if (!repo.autoAdvanceImportEnabled) return
        if (repo.notificationAccessPromptSuppressed) return
        if (isNotificationListenerAccessEnabled(this)) return

        notificationAccessWarningShownThisForeground = true
        showNotificationAccessDialog = true
    }

    private fun requestExactAlarmPermissionIfNeeded() {
        if (exactAlarmWarningShownThisForeground) return
        val settingsIntent = FoodReceiptReminderScheduler.exactAlarmSettingsIntent(this)
        if (!shouldRequestExactAlarmPermission(
                foodReceiptReminderEnabled = repo.foodReceiptReminderEnabled,
                bloodDonationReminderEnabled = repo.lastBloodDonationDate != null,
                exactAlarmPermissionMissing = settingsIntent != null
            )
        ) {
            return
        }

        exactAlarmWarningShownThisForeground = true
        showExactAlarmPermissionDialog = true
    }

    private fun openExactAlarmSettings() {
        showExactAlarmPermissionDialog = false
        val intent = FoodReceiptReminderScheduler.exactAlarmSettingsIntent(this) ?: return
        runCatching { startActivity(intent) }
    }

    private fun openNotificationAccessSettings() {
        showNotificationAccessDialog = false
        runCatching { startActivity(notificationListenerSettingsIntent()) }
    }

    private fun suppressNotificationAccessPrompt() {
        showNotificationAccessDialog = false
        if (::repo.isInitialized) {
            repo.notificationAccessPromptSuppressed = true
        }
    }
}

internal fun shouldRequestExactAlarmPermission(
    foodReceiptReminderEnabled: Boolean,
    bloodDonationReminderEnabled: Boolean,
    exactAlarmPermissionMissing: Boolean
): Boolean {
    return exactAlarmPermissionMissing && (foodReceiptReminderEnabled || bloodDonationReminderEnabled)
}

@Composable
private fun NotificationAccessPermissionDialog(
    onOpenSettings: () -> Unit,
    onNeverAskAgain: () -> Unit,
    onDismiss: () -> Unit
) {
    PopupDialog(
        title = NOTIFICATION_ACCESS_DIALOG_TITLE,
        onDismiss = onDismiss,
        widthRatio = 0.9f,
        scrollable = false,
        accent = THEME.accentPrimary
    ) {
        PopupCard(bgColor = THEME.bgCardElevated, borderColor = THEME.accentPrimary) {
            Text(
                text = NOTIFICATION_ACCESS_DIALOG_TEXT,
                color = THEME.textSecondary,
                style = MaterialTheme.typography.bodyMedium
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            RoundedButton(
                text = NOTIFICATION_ACCESS_LATER_LABEL,
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp),
                bgColor = THEME.bgCard,
                textColor = THEME.textPrimary,
                onClick = onDismiss
            )
            RoundedButton(
                text = NOTIFICATION_ACCESS_OPEN_SETTINGS_LABEL,
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp),
                bgColor = THEME.accentPrimary,
                textColor = THEME.textDark,
                bold = true,
                onClick = onOpenSettings
            )
        }
        RoundedButton(
            text = NOTIFICATION_ACCESS_NEVER_ASK_LABEL,
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp),
            bgColor = THEME.bgCard,
            textColor = THEME.textMuted,
            textStyle = MaterialTheme.typography.labelMedium,
            onClick = onNeverAskAgain
        )
    }
}

@Composable
private fun ExactAlarmPermissionDialog(
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit
) {
    PopupDialog(
        title = "Tačni alarmi",
        onDismiss = onDismiss,
        widthRatio = 0.9f,
        scrollable = false,
        accent = THEME.warning
    ) {
        PopupCard(bgColor = THEME.bgCardElevated, borderColor = THEME.warning) {
            Text(
                text = "Da bi podsetnici stizali u tačno vreme, uključi dozvolu Alarmi i podsetnici za ovu aplikaciju.",
                color = THEME.textSecondary,
                style = MaterialTheme.typography.bodyMedium
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            RoundedButton(
                text = "Kasnije",
                modifier = Modifier
                    .weight(1f)
                    .height(UI.buttonHeight.dp),
                bgColor = THEME.bgCardElevated,
                textColor = THEME.textPrimary,
                textStyle = MaterialTheme.typography.labelLarge,
                onClick = onDismiss
            )
            GlowButton(
                text = "Otvori podešavanje",
                modifier = Modifier
                    .weight(1f)
                    .height(UI.buttonHeight.dp),
                bgColor = THEME.warning,
                textColor = THEME.textDark,
                textStyle = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                onClick = onOpenSettings
            )
        }
    }
}
