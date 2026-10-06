package com.rad.shifttracker

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

internal const val BACKUP_SIGN_IN_PROMPT_TITLE = "Prijavi se ponovo za Google backup"
internal const val BACKUP_SIGN_IN_PROMPT_TEXT =
    "Backup u oblaku je ranije bio uključen, ali sada nisi prijavljen, pa se podaci ne čuvaju " +
        "na Google Drive-u. Posle prelaska na novi Google način prijave potrebno je ponovo " +
        "izabrati nalog i odobriti pristup Drive-u."
internal const val BACKUP_SIGN_IN_PROMPT_CONFIRM = "Prijavi se"
internal const val BACKUP_SIGN_IN_PROMPT_LATER = "Kasnije"

/**
 * Da li pri pokretanju ponuditi ponovnu prijavu za backup: korisnik je ranije imao backup
 * ([lastSyncTime] > 0), a sada nije prijavljen. Ne prikazuje se pri prvom pokretanju (uvod),
 * posle „Kasnije“ ([dismissed], do sledećeg pokretanja) ni preko drugog otvorenog prozora.
 */
internal fun shouldShowBackupSignInPrompt(
    lastSyncTime: Long?,
    signedIn: Boolean,
    firstLaunch: Boolean,
    dismissed: Boolean,
    otherDialogOpen: Boolean
): Boolean {
    val hadBackup = (lastSyncTime ?: 0L) > 0L
    return hadBackup && !signedIn && !firstLaunch && !dismissed && !otherDialogOpen
}

/**
 * Prozor „Prijavi se ponovo“ na glavnom ekranu (vidi [shouldShowBackupSignInPrompt]).
 * [onSignIn] pokreće postojeću prijavu (izbor naloga, pa Drive dozvola).
 */
@Composable
internal fun BackupSignInPromptHost(
    lastSyncTime: Long?,
    signedIn: Boolean,
    firstLaunch: Boolean,
    otherDialogOpen: Boolean,
    onSignIn: () -> Unit
) {
    // Važi samo za ovo pokretanje (preživi okretanje telefona); sledeće pokretanje ponovo pita.
    var dismissed by rememberSaveable { mutableStateOf(false) }
    if (!shouldShowBackupSignInPrompt(lastSyncTime, signedIn, firstLaunch, dismissed, otherDialogOpen)) return
    BackupSignInPromptDialog(
        onSignIn = {
            // Neuspela ili otkazana prijava ne otvara prozor odmah ponovo; ostaje „Poveži Google“ u Opcijama.
            dismissed = true
            onSignIn()
        },
        onLater = { dismissed = true }
    )
}

@Composable
internal fun BackupSignInPromptDialog(onSignIn: () -> Unit, onLater: () -> Unit) {
    PopupDialog(
        title = BACKUP_SIGN_IN_PROMPT_TITLE,
        onDismiss = onLater
    ) {
        PopupCard {
            Text(
                text = BACKUP_SIGN_IN_PROMPT_TEXT,
                color = MaterialTheme.extendedColors.textSecondary,
                style = MaterialTheme.typography.labelMedium
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.md)
            ) {
                RoundedButton(
                    text = BACKUP_SIGN_IN_PROMPT_LATER,
                    modifier = Modifier
                        .weight(1f)
                        .height(44.dp),
                    bgColor = MaterialTheme.colorScheme.surfaceContainer,
                    textColor = MaterialTheme.colorScheme.onSurface,
                    onClick = onLater
                )
                RoundedButton(
                    text = BACKUP_SIGN_IN_PROMPT_CONFIRM,
                    modifier = Modifier
                        .weight(1f)
                        .height(44.dp),
                    bgColor = MaterialTheme.colorScheme.primary,
                    textColor = MaterialTheme.colorScheme.onPrimary,
                    bold = true,
                    onClick = onSignIn
                )
            }
        }
    }
}
