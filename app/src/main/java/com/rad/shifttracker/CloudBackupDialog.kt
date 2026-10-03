package com.rad.shifttracker

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.util.Locale

// Existing dialog flow is intentionally grouped; detekt stays active for newly added methods.
@Suppress("LongMethod")
@Composable
fun CloudBackupDialog(
    signInState: com.rad.shifttracker.data.backup.SignInState,
    syncState: com.rad.shifttracker.data.backup.SyncState,
    lastSyncTime: Long?,
    onSignIn: () -> Unit,
    onBackup: () -> Unit,
    onRestore: () -> Unit,
    onShowVersions: () -> Unit,
    onSignOut: () -> Unit,
    onDismiss: () -> Unit
) {
    val isSignedIn = signInState is com.rad.shifttracker.data.backup.SignInState.SignedIn
    val email = (signInState as? com.rad.shifttracker.data.backup.SignInState.SignedIn)?.email
    val isSyncing = syncState is com.rad.shifttracker.data.backup.SyncState.Syncing
    val syncError = (syncState as? com.rad.shifttracker.data.backup.SyncState.Error)?.message

    PopupDialog(
        title = "Cloud Backup",
        onDismiss = onDismiss,
        scrollable = true
    ) {
        PopupCard {
            SectionLabel(text = "Status")
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Nalog:",
                    color = MaterialTheme.extendedColors.textSecondary,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = email ?: "Nije povezano",
                    color = if (isSignedIn) MaterialTheme.extendedColors.success else MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (isSyncing) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                ) {
                    Text(
                        text = "Status:",
                        color = MaterialTheme.extendedColors.textSecondary,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Row(
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            color = MaterialTheme.extendedColors.info,
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Sinhronizacija...",
                            color = MaterialTheme.extendedColors.info,
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
            if (lastSyncTime != null && !isSyncing) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Poslednji backup:",
                        color = MaterialTheme.extendedColors.textSecondary,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = formatSyncTime(lastSyncTime),
                        color = MaterialTheme.extendedColors.success,
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            // Show sync error if present
            if (syncError != null && !isSyncing) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.error.copy(alpha = 0.15f), MaterialTheme.shapes.small)
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    FormErrorText(
                        message = syncError,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }

        if (!isSignedIn) {
            GlowButton(
                text = "Poveži Google",
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
                textStyle = MaterialTheme.typography.labelLarge,
                onClick = onSignIn
            )
        } else {
            PopupCard {
                SectionLabel(text = "Akcije")
                RoundedButton(
                    text = "Backup",
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp),
                    bgColor = MaterialTheme.extendedColors.success,
                    textColor = MaterialTheme.colorScheme.onPrimary,
                    textStyle = MaterialTheme.typography.labelLarge,
                    bold = true,
                    enabled = !isSyncing,
                    onClick = onBackup
                )
                RoundedButton(
                    text = "Restore (najnovije)",
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp),
                    bgColor = MaterialTheme.extendedColors.info,
                    textColor = MaterialTheme.colorScheme.onSurface,
                    textStyle = MaterialTheme.typography.labelLarge,
                    bold = true,
                    enabled = !isSyncing,
                    onClick = onRestore
                )
                RoundedButton(
                    text = "Vrati raniju verziju…",
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp),
                    bgColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    textColor = MaterialTheme.extendedColors.info,
                    borderColor = MaterialTheme.extendedColors.info,
                    textStyle = MaterialTheme.typography.labelLarge,
                    bold = true,
                    enabled = !isSyncing,
                    onClick = onShowVersions
                )
                RoundedButton(
                    text = "Odjavi se",
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp),
                    bgColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    textColor = MaterialTheme.colorScheme.error,
                    borderColor = MaterialTheme.colorScheme.error,
                    textStyle = MaterialTheme.typography.labelLarge,
                    bold = true,
                    enabled = !isSyncing,
                    onClick = onSignOut
                )
            }
        }
    }
}

private fun formatSyncTime(timestamp: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - timestamp
    val seconds = diff / 1000
    val hours = seconds / 3600
    val days = hours / 24

    return when {
        seconds < 3600 -> "Pre manje od sat"
        hours < 24 -> "Pre $hours h"
        days == 1L -> "Juče"
        days < 7 -> "Pre $days dana"
        else -> {
            val date = java.text.SimpleDateFormat("dd.MM. HH:mm", Locale.getDefault())
            date.format(java.util.Date(timestamp))
        }
    }
}
