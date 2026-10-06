package com.rad.shifttracker

import android.accounts.AccountManager
import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.rad.shifttracker.data.backup.DriveAuthorizeResult
import com.rad.shifttracker.data.backup.GoogleDriveBackup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Prijava za cloud backup: izbor Google naloga, pa Google dozvola za Drive (AuthorizationClient).
 * Vraća funkciju koja pokreće prijavu.
 */
@Composable
internal fun rememberDriveBackupSignIn(backup: GoogleDriveBackup, showMessage: (String) -> Unit): () -> Unit {
    val scope = rememberCoroutineScope()
    // Preživi okretanje telefona i gašenje procesa dok je otvoren Google ekran.
    var pendingEmail by rememberSaveable { mutableStateOf<String?>(null) }

    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        val email = pendingEmail
        pendingEmail = null
        if (email != null && result.resultCode == Activity.RESULT_OK && backup.isConsentGranted(result.data)) {
            backup.completeSignIn(email)
            showMessage("Prijava uspešna!")
        } else {
            showMessage("Prijava neuspešna: dozvola za Google Drive nije data")
        }
    }
    val chooser = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val email = result.data?.getStringExtra(AccountManager.KEY_ACCOUNT_NAME)
        if (result.resultCode != Activity.RESULT_OK || email == null) return@rememberLauncherForActivityResult
        scope.launch {
            when (val outcome = withContext(Dispatchers.IO) { backup.authorize(email) }) {
                DriveAuthorizeResult.Authorized -> {
                    backup.completeSignIn(email)
                    showMessage("Prijava uspešna!")
                }
                is DriveAuthorizeResult.NeedsConsent -> {
                    pendingEmail = email
                    runCatching { consent.launch(IntentSenderRequest.Builder(outcome.intentSender).build()) }
                        .onFailure {
                            pendingEmail = null
                            showMessage("Prijava neuspešna: ne mogu da otvorim Google dozvolu")
                        }
                }
                DriveAuthorizeResult.Failed ->
                    showMessage("Prijava neuspešna: nema interneta ili nalog nije dostupan")
            }
        }
    }
    return {
        runCatching { chooser.launch(backup.accountChooserIntent()) }
            .onFailure { showMessage("Prijava neuspešna: ne mogu da otvorim izbor naloga") }
    }
}
