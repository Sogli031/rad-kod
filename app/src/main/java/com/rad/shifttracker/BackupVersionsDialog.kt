package com.rad.shifttracker

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.rad.shifttracker.data.backup.BackupVersion
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Lista sačuvanih verzija backup-a iz oblaka sa mogućnošću vraćanja konkretne.
 * [versions] == null znači da se lista još učitava. Pre samog vraćanja traži se
 * potvrda (a pozivalac napravi sigurnosnu kopiju trenutnog stanja).
 */
@Composable
fun BackupVersionsDialog(
    versions: List<BackupVersion>?,
    isBusy: Boolean,
    onRestoreVersion: (BackupVersion) -> Unit,
    onDismiss: () -> Unit
) {
    var pendingRestore by remember { mutableStateOf<BackupVersion?>(null) }

    PopupDialog(
        title = "Ranije verzije",
        onDismiss = onDismiss,
        widthRatio = 0.9f,
        scrollable = true,
        accent = THEME.info
    ) {
        val pending = pendingRestore
        when {
            pending != null -> RestoreConfirmCard(
                version = pending,
                isBusy = isBusy,
                onConfirm = { onRestoreVersion(pending) },
                onCancel = { pendingRestore = null }
            )
            versions == null -> LoadingCard()
            versions.isEmpty() -> PopupCard(bgColor = THEME.bgCardElevated, borderColor = THEME.info) {
                Text(
                    text = "Nema sačuvanih verzija u oblaku.",
                    color = THEME.textSecondary,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            else -> {
                PopupCard(bgColor = THEME.bgCardElevated, borderColor = THEME.info) {
                    Text(
                        text = "Izaberi verziju za vraćanje. Pre vraćanja se automatski " +
                            "napravi sigurnosna kopija trenutnog stanja.",
                        color = THEME.textSecondary,
                        style = MaterialTheme.typography.labelMedium
                    )
                }
                versions.forEach { version ->
                    BackupVersionRow(
                        version = version,
                        enabled = !isBusy,
                        onClick = { pendingRestore = version }
                    )
                }
            }
        }
    }
}

@Composable
private fun LoadingCard() {
    PopupCard(bgColor = THEME.bgCardElevated, borderColor = THEME.info) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            CircularProgressIndicator(
                modifier = Modifier.height(18.dp),
                color = THEME.info,
                strokeWidth = 2.dp
            )
            Text(
                text = "Učitavanje verzija…",
                color = THEME.info,
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

@Composable
private fun BackupVersionRow(
    version: BackupVersion,
    enabled: Boolean,
    onClick: () -> Unit
) {
    PopupCard(bgColor = THEME.bgCard, borderColor = THEME.bgCardElevated) {
        Text(
            text = formatVersionTime(version.createdAtMillis),
            color = THEME.textPrimary,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold)
        )
        Text(
            text = backupVersionSummary(version),
            color = THEME.textSecondary,
            style = MaterialTheme.typography.labelMedium
        )
        if (version.flagged) {
            Text(
                text = "⚠ Mogući pad podataka — proveri pre vraćanja.",
                color = THEME.warning,
                style = MaterialTheme.typography.labelMedium
            )
        }
        RoundedButton(
            text = "Vrati ovu verziju",
            modifier = Modifier
                .fillMaxWidth()
                .height(40.dp),
            bgColor = THEME.info,
            textColor = THEME.textPrimary,
            radius = 12.dp,
            textStyle = MaterialTheme.typography.labelLarge,
            bold = true,
            enabled = enabled,
            onClick = onClick
        )
    }
}

@Composable
private fun RestoreConfirmCard(
    version: BackupVersion,
    isBusy: Boolean,
    onConfirm: () -> Unit,
    onCancel: () -> Unit
) {
    PopupCard(bgColor = THEME.bgCardElevated, borderColor = THEME.warning) {
        Text(
            text = "Vraćanje verzije od ${formatVersionTime(version.createdAtMillis)}",
            color = THEME.textPrimary,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold)
        )
        Text(
            text = "Trenutno stanje će biti zamenjeno (${backupVersionSummary(version)}). " +
                "Pravi se sigurnosna kopija pre vraćanja, pa je i ovo poništivo.",
            color = THEME.textSecondary,
            style = MaterialTheme.typography.labelMedium
        )
        if (isBusy) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.height(18.dp),
                    color = THEME.info,
                    strokeWidth = 2.dp
                )
                Text(
                    text = "Vraćanje u toku…",
                    color = THEME.info,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                RoundedButton(
                    text = "Otkaži",
                    modifier = Modifier
                        .weight(1f)
                        .height(44.dp),
                    bgColor = THEME.bgCard,
                    textColor = THEME.textPrimary,
                    radius = 12.dp,
                    onClick = onCancel
                )
                RoundedButton(
                    text = "Vrati",
                    modifier = Modifier
                        .weight(1f)
                        .height(44.dp),
                    bgColor = THEME.warning,
                    textColor = THEME.textDark,
                    radius = 12.dp,
                    bold = true,
                    onClick = onConfirm
                )
            }
        }
    }
}

/**
 * Potvrda pre "Vrati najnovije" iz oblaka: jedan tap ne sme nepovratno da
 * pregazi lokalne izmene nastale posle poslednjeg backup-a.
 */
@Composable
fun RestoreLatestConfirmDialog(
    isBusy: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    PopupDialog(
        title = "Vraćanje iz oblaka",
        onDismiss = onDismiss,
        widthRatio = 0.9f,
        accent = THEME.warning
    ) {
        PopupCard(bgColor = THEME.bgCardElevated, borderColor = THEME.warning) {
            Text(
                text = "Trenutno stanje će biti zamenjeno najnovijom verzijom iz oblaka. " +
                    "Izmene od poslednjeg backup-a bi bile izgubljene, pa se pre vraćanja " +
                    "automatski pravi sigurnosna kopija trenutnog stanja.",
                color = THEME.textSecondary,
                style = MaterialTheme.typography.labelMedium
            )
            if (isBusy) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.height(18.dp),
                        color = THEME.info,
                        strokeWidth = 2.dp
                    )
                    Text(
                        text = "Vraćanje u toku…",
                        color = THEME.info,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    RoundedButton(
                        text = "Otkaži",
                        modifier = Modifier
                            .weight(1f)
                            .height(44.dp),
                        bgColor = THEME.bgCard,
                        textColor = THEME.textPrimary,
                        radius = 12.dp,
                        onClick = onDismiss
                    )
                    RoundedButton(
                        text = "Vrati",
                        modifier = Modifier
                            .weight(1f)
                            .height(44.dp),
                        bgColor = THEME.warning,
                        textColor = THEME.textDark,
                        radius = 12.dp,
                        bold = true,
                        onClick = onConfirm
                    )
                }
            }
        }
    }
}

internal fun backupVersionSummary(version: BackupVersion): String {
    val stats = version.stats ?: return "starija verzija (sadržaj nepoznat)"
    val hours = stats.totalWorkMinutes / 60
    return "${stats.monthCount} mes · ${stats.recordCount} dana · $hours h"
}

private fun formatVersionTime(millis: Long): String {
    if (millis <= 0L) return "nepoznato vreme"
    val formatter = SimpleDateFormat("dd.MM.yyyy. HH:mm", Locale.getDefault())
    return formatter.format(java.util.Date(millis))
}
