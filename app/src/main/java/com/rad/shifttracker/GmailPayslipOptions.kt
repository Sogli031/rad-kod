package com.rad.shifttracker

import android.accounts.AccountManager
import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Povezivanje Gmail-a za listiće: [connect] otvara izbor naloga, pa Google dozvolu. */
internal class GmailPayslipConnector(
    val busy: Boolean,
    val error: String?,
    val connect: () -> Unit
)

/**
 * Izbor Google naloga za listiće i Google dozvola za čitanje mejla (Opcije i onboarding).
 * [onConnected] dobija mejl kad je dozvola data.
 */
@Composable
internal fun rememberGmailPayslipConnector(onConnected: (String) -> Unit): GmailPayslipConnector {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pendingEmail by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    lateinit var authorize: (String) -> Unit
    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val email = pendingEmail
        if (result.resultCode == Activity.RESULT_OK && email != null) {
            authorize(email)
        } else {
            busy = false
            error = "Gmail nije povezan (dozvola odbijena)."
        }
    }
    authorize = { email ->
        pendingEmail = email
        busy = true
        error = null
        scope.launch {
            val outcome = withContext(Dispatchers.IO) { authorizeGmailPayslipAccount(context, email) }
            when (outcome) {
                GmailAuthorizeResult.Authorized -> {
                    busy = false
                    onConnected(email)
                }
                is GmailAuthorizeResult.NeedsConsent -> runCatching { consent.launch(outcome.intent) }
                    .onFailure { busy = false; error = "Ne mogu da otvorim Google dozvolu." }
                GmailAuthorizeResult.Failed -> {
                    busy = false
                    error = "Gmail nije povezan (nema interneta ili nalog nije dostupan)."
                }
            }
        }
    }
    val chooser = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val email = result.data?.getStringExtra(AccountManager.KEY_ACCOUNT_NAME)
        if (result.resultCode == Activity.RESULT_OK && email != null) authorize(email)
    }
    return GmailPayslipConnector(busy = busy, error = error) {
        if (!busy) {
            error = null
            runCatching { chooser.launch(gmailPayslipAccountChooserIntent(context)) }
        }
    }
}

/**
 * Opcije → „Listići iz Gmail-a“: status i izbor mejla sa kog se čitaju listići. Nalog se bira
 * nezavisno od backup naloga; posle izbora se traži dozvola za čitanje mejla.
 */
@Composable
internal fun GmailPayslipSettingsSection(repo: ShiftRepository) {
    val context = LocalContext.current
    var accountEmail by remember { mutableStateOf(gmailPayslipAccount(context)?.name) }
    // Preživi okretanje telefona, da se provera koja još traje (ili njen rezultat) ponovo prikaže.
    var recheckOpen by rememberSaveable { mutableStateOf(false) }
    if (recheckOpen) {
        GmailOnboardingDialog(repo = repo, mode = GmailImportMode.RECHECK) {
            recheckOpen = false
            accountEmail = gmailPayslipAccount(context)?.name
        }
    }
    val connector = rememberGmailPayslipConnector { email -> accountEmail = email }
    val busy = connector.busy
    val error = connector.error

    Column(
        modifier = Modifier.padding(vertical = Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        Text(
            text = gmailPayslipAccessStatusText(accountEmail),
            color = if (accountEmail != null) MaterialTheme.extendedColors.success else MaterialTheme.extendedColors.warning,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis
        )
        error?.let {
            FormErrorText(message = it, style = MaterialTheme.typography.bodySmall)
        }
        RoundedButton(
            text = when {
                busy -> "Povezujem..."
                accountEmail != null -> GMAIL_PAYSLIP_CHANGE_LABEL
                else -> GMAIL_PAYSLIP_CONNECT_LABEL
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(UI.buttonHeight.dp),
            bgColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            textColor = MaterialTheme.colorScheme.onSurface,
            borderColor = MaterialTheme.colorScheme.outlineVariant,
            textStyle = MaterialTheme.typography.labelLarge,
            onClick = connector.connect
        )
        RoundedButton(
            text = PAYSLIP_RECHECK_LABEL,
            modifier = Modifier
                .fillMaxWidth()
                .height(UI.buttonHeight.dp),
            bgColor = MaterialTheme.colorScheme.primary,
            textColor = MaterialTheme.colorScheme.onPrimary,
            bold = true,
            textStyle = MaterialTheme.typography.labelLarge,
            onClick = { recheckOpen = true }
        )
        ManualPayslipImportButton(repo = repo)
        PayslipFillAllButton(repo = repo)
    }
}
