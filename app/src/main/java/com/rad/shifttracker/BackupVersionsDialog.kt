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
        scrollable = true
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
            versions.isEmpty() -> PopupCard {
                Text(
                    text = "Nema sačuvanih verzija u oblaku.",
                    color = MaterialTheme.extendedColors.textSecondary,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            else -> {
                PopupCard {
                    Text(
                        text = "Izaberi verziju za vraćanje. Pre vraćanja se automatski " +
                            "napravi sigurnosna kopija trenutnog stanja.",
                        color = MaterialTheme.extendedColors.textSecondary,
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
    PopupCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.md)
        ) {
            CircularProgressIndicator(
                modifier = Modifier.height(18.dp),
                color = MaterialTheme.extendedColors.info,
                strokeWidth = 2.dp
            )
            Text(
                text = "Učitavanje verzija…",
                color = MaterialTheme.extendedColors.info,
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
    PopupCard {
        Text(
            text = formatVersionTime(version.createdAtMillis),
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodyMediumEmphasized
        )
        Text(
            text = backupVersionSummary(version),
            color = MaterialTheme.extendedColors.textSecondary,
            style = MaterialTheme.typography.labelMedium
        )
        if (version.safety) {
            Text(
                text = "Sigurnosna kopija stanja pre jednog vraćanja — nije redovan backup.",
                color = MaterialTheme.extendedColors.info,
                style = MaterialTheme.typography.labelMedium
            )
        }
        if (version.flagged) {
            Text(
                text = "⚠ Mogući pad podataka — proveri pre vraćanja.",
                color = MaterialTheme.extendedColors.warning,
                style = MaterialTheme.typography.labelMedium
            )
        }
        RoundedButton(
            text = "Vrati ovu verziju",
            modifier = Modifier
                .fillMaxWidth()
                .height(40.dp),
            bgColor = MaterialTheme.extendedColors.info,
            textColor = MaterialTheme.colorScheme.onSurface,
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
    PopupCard {
        Text(
            text = "Vraćanje verzije od ${formatVersionTime(version.createdAtMillis)}",
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodyMediumEmphasized
        )
        Text(
            text = "Trenutno stanje će biti zamenjeno (${backupVersionSummary(version)}). " +
                "Pravi se sigurnosna kopija pre vraćanja, pa je i ovo poništivo.",
            color = MaterialTheme.extendedColors.textSecondary,
            style = MaterialTheme.typography.labelMedium
        )
        if (isBusy) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.md)
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.height(18.dp),
                    color = MaterialTheme.extendedColors.info,
                    strokeWidth = 2.dp
                )
                Text(
                    text = "Vraćanje u toku…",
                    color = MaterialTheme.extendedColors.info,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.md)
            ) {
                RoundedButton(
                    text = "Otkaži",
                    modifier = Modifier
                        .weight(1f)
                        .height(44.dp),
                    bgColor = MaterialTheme.colorScheme.surfaceContainer,
                    textColor = MaterialTheme.colorScheme.onSurface,
                    onClick = onCancel
                )
                RoundedButton(
                    text = "Vrati",
                    modifier = Modifier
                        .weight(1f)
                        .height(44.dp),
                    bgColor = MaterialTheme.extendedColors.warning,
                    textColor = MaterialTheme.colorScheme.onPrimary,
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
        onDismiss = onDismiss
    ) {
        PopupCard {
            Text(
                text = "Trenutno stanje će biti zamenjeno najnovijom verzijom iz oblaka. " +
                    "Izmene od poslednjeg backup-a bi bile izgubljene, pa se pre vraćanja " +
                    "automatski pravi sigurnosna kopija trenutnog stanja.",
                color = MaterialTheme.extendedColors.textSecondary,
                style = MaterialTheme.typography.labelMedium
            )
            if (isBusy) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.md)
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.height(18.dp),
                        color = MaterialTheme.extendedColors.info,
                        strokeWidth = 2.dp
                    )
                    Text(
                        text = "Vraćanje u toku…",
                        color = MaterialTheme.extendedColors.info,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.md)
                ) {
                    RoundedButton(
                        text = "Otkaži",
                        modifier = Modifier
                            .weight(1f)
                            .height(44.dp),
                        bgColor = MaterialTheme.colorScheme.surfaceContainer,
                        textColor = MaterialTheme.colorScheme.onSurface,
                        onClick = onDismiss
                    )
                    RoundedButton(
                        text = "Vrati",
                        modifier = Modifier
                            .weight(1f)
                            .height(44.dp),
                        bgColor = MaterialTheme.extendedColors.warning,
                        textColor = MaterialTheme.colorScheme.onPrimary,
                        bold = true,
                        onClick = onConfirm
                    )
                }
            }
        }
    }
}

/**
 * U oblaku postoji backup koji ovaj uređaj nije preuzeo (tipično: nova instalacija).
 * Korisnik bira: vrati sve iz oblaka (preporučeno) ili zadrži podatke sa ovog
 * uređaja kao merodavne. Dok ne izabere, automatski backup je pauziran.
 */
@Composable
fun CloudBackupFoundDialog(
    version: BackupVersion,
    isBusy: Boolean,
    onRestore: () -> Unit,
    onKeepLocal: () -> Unit,
    onDismiss: () -> Unit
) {
    PopupDialog(
        title = "Pronađen backup u oblaku",
        onDismiss = onDismiss
    ) {
        PopupCard {
            Text(
                text = "Backup od ${formatVersionTime(version.createdAtMillis)}",
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyMediumEmphasized
            )
            Text(
                text = "${backupVersionSummary(version)}. Ovaj uređaj ga još nije preuzeo. " +
                    "„Vrati sve iz oblaka“ vraća sve upise i sva podešavanja tačno kako su sačuvani. " +
                    "Dok ne izabereš, automatski backup je pauziran da ništa u oblaku ne bude pregaženo.",
                color = MaterialTheme.extendedColors.textSecondary,
                style = MaterialTheme.typography.labelMedium
            )
            if (isBusy) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.md)
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.height(18.dp),
                        color = MaterialTheme.extendedColors.info,
                        strokeWidth = 2.dp
                    )
                    Text(
                        text = "Vraćanje u toku…",
                        color = MaterialTheme.extendedColors.info,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            } else {
                RoundedButton(
                    text = "Vrati sve iz oblaka",
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp),
                    bgColor = MaterialTheme.extendedColors.info,
                    textColor = MaterialTheme.colorScheme.onSurface,
                    bold = true,
                    onClick = onRestore
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.md)
                ) {
                    RoundedButton(
                        text = "Kasnije",
                        modifier = Modifier
                            .weight(1f)
                            .height(44.dp),
                        bgColor = MaterialTheme.colorScheme.surfaceContainer,
                        textColor = MaterialTheme.colorScheme.onSurface,
                        onClick = onDismiss
                    )
                    RoundedButton(
                        text = "Zadrži ovaj uređaj",
                        modifier = Modifier
                            .weight(1f)
                            .height(44.dp),
                        bgColor = MaterialTheme.extendedColors.warning,
                        textColor = MaterialTheme.colorScheme.onPrimary,
                        onClick = onKeepLocal
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
