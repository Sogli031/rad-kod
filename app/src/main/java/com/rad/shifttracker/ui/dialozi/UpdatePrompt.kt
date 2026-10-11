package com.rad.shifttracker.ui.dialozi

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import kotlinx.coroutines.delay
import com.rad.shifttracker.azuriranje.UpdateInstallFailure
import com.rad.shifttracker.azuriranje.UpdateInstallFailures
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.LifecycleResumeEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.rad.shifttracker.BuildConfig
import com.rad.shifttracker.azuriranje.AvailableUpdate
import com.rad.shifttracker.azuriranje.UpdateInstallResult
import com.rad.shifttracker.azuriranje.canInstallUpdates
import com.rad.shifttracker.azuriranje.downloadAndInstallUpdate
import com.rad.shifttracker.azuriranje.fetchAvailableUpdate
import com.rad.shifttracker.osnova.UI
import com.rad.shifttracker.ui.FormErrorText
import com.rad.shifttracker.ui.RoundedButton
import com.rad.shifttracker.ui.Spacing
import com.rad.shifttracker.ui.extendedColors

internal const val UPDATE_PROMPT_LATER = "Kasnije"
internal const val UPDATE_PROMPT_CONFIRM = "Ažuriraj"
internal const val UPDATE_CHECK_LABEL = "Proveri ažuriranje"
internal const val UPDATE_PERMISSION_TEXT =
    "Dozvoli „Instaliraj nepoznate aplikacije“ za Rad u podešavanjima koja su se otvorila, " +
        "pa se vrati nazad; preuzimanje kreće samo."

internal const val UPDATE_INSTALLING_TEXT = "Instaliram... aplikacija će se zatvoriti, pa je otvori ponovo."
private const val UPDATE_INSTALL_TIMEOUT_MS = 90_000L

internal fun updatePromptTitle(update: AvailableUpdate): String = "Stigla je verzija ${update.version}"

/**
 * Pri pokretanju jednom pita GitHub za novu verziju; ako je ima, nudi ažuriranje. Ne prikazuje se
 * pri prvom pokretanju ni preko drugog prozora; posle „Kasnije“ ćuti do sledećeg pokretanja.
 */
@Composable
internal fun UpdatePromptHost(firstLaunch: Boolean, otherDialogOpen: Boolean) {
    var checked by rememberSaveable { mutableStateOf(false) }
    var update by remember { mutableStateOf<AvailableUpdate?>(null) }
    var dismissed by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(firstLaunch) {
        if (checked || firstLaunch) return@LaunchedEffect
        checked = true
        update = withContext(Dispatchers.IO) { fetchAvailableUpdate(BuildConfig.VERSION_NAME) }
    }
    val available = update
    if (available == null || dismissed || otherDialogOpen) return
    UpdatePromptDialog(update = available, onDismiss = { dismissed = true })
}

/** „Stigla je verzija X“: izmene iz release-a, pa preuzimanje sa napretkom i sistemska instalacija. */
@Composable
internal fun UpdatePromptDialog(update: AvailableUpdate, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var progress by remember { mutableStateOf<Int?>(null) }
    var message by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    // Posle dozvole „Instaliraj nepoznate aplikacije“ preuzimanje kreće samo kad se korisnik vrati.
    var waitingForPermission by remember { mutableStateOf(false) }
    // APK je kod sistema: dugmad su zaključana dok se aplikacija ne zameni (proces se tada ugasi)
    // ili sistem ne javi neuspeh / otkazivanje.
    var installing by remember { mutableStateOf(false) }
    val busy = progress != null || installing

    val failure by UpdateInstallFailures.events.collectAsState()
    // Neuspeh iz ranijeg pokušaja se ne računa; prati se samo onaj posle ovog predavanja sistemu.
    var failureBeforeInstall by remember { mutableStateOf<UpdateInstallFailure?>(null) }
    LaunchedEffect(failure) {
        val current = failure
        if (installing && current != null && current !== failureBeforeInstall) {
            installing = false
            message = current.message to true
        }
    }
    LaunchedEffect(installing) {
        if (!installing) return@LaunchedEffect
        // Ako sistem ne javi ništa (npr. prozor za potvrdu je samo sklonjen), dugme se vraća.
        delay(UPDATE_INSTALL_TIMEOUT_MS)
        installing = false
    }

    val startInstall: () -> Unit = {
        if (!busy) {
            progress = 0
            message = null
            failureBeforeInstall = UpdateInstallFailures.events.value
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    downloadAndInstallUpdate(context, update) { percent ->
                        scope.launch { progress = percent }
                    }
                }
                progress = null
                installing = result == UpdateInstallResult.Started
                waitingForPermission = result == UpdateInstallResult.NeedsPermission
                message = when (result) {
                    UpdateInstallResult.Started -> null
                    UpdateInstallResult.NeedsPermission -> UPDATE_PERMISSION_TEXT to false
                    is UpdateInstallResult.Failed -> result.message to true
                }
            }
        }
    }
    LifecycleResumeEffect(waitingForPermission) {
        if (waitingForPermission && canInstallUpdates(context)) {
            waitingForPermission = false
            startInstall()
        }
        onPauseOrDispose { }
    }

    // Centrirani prozor (kao u BanketLux-u): obaveštenje koje traži odluku, ne list sa dna.
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        properties = DialogProperties(dismissOnBackPress = !busy, dismissOnClickOutside = !busy),
        title = { Text(updatePromptTitle(update)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {
                Text(
                    text = "Sada imaš ${BuildConfig.VERSION_NAME}. Podaci ostaju, ažuriranje samo menja aplikaciju.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall
                )
                if (update.notes.isNotBlank()) {
                    Text(
                        text = update.notes,
                        color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                UpdateProgressStatus(progress = progress, installing = installing, message = message)
            }
        },
        confirmButton = {
            Button(onClick = startInstall, enabled = !busy) {
                Text(
                    when {
                        progress != null -> "Preuzimam..."
                        installing -> "Instaliram..."
                        else -> UPDATE_PROMPT_CONFIRM
                    }
                )
            }
        },
        dismissButton = {
            TextButton(onClick = { if (!busy) onDismiss() }, enabled = !busy) {
                Text(UPDATE_PROMPT_LATER)
            }
        }
    )
}

/** Napredak preuzimanja, „Instaliram...“ dok sistem menja aplikaciju, pa poruka (greška ili upozorenje). */
@Composable
private fun UpdateProgressStatus(progress: Int?, installing: Boolean, message: Pair<String, Boolean>?) {
    progress?.let { percent ->
        Text(
            text = "Preuzimam... $percent%",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall
        )
        LinearProgressIndicator(progress = { percent / 100f }, modifier = Modifier.fillMaxWidth())
    }
    if (installing) {
        Text(
            text = UPDATE_INSTALLING_TEXT,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall
        )
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    }
    message?.let { (text, isError) ->
        if (isError) {
            FormErrorText(message = text, style = MaterialTheme.typography.bodySmall)
        } else {
            Text(text = text, color = MaterialTheme.extendedColors.warning, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** Opcije: ručna provera nove verzije; ako je ima, otvara isti prozor kao pri pokretanju. */
@Composable
internal fun UpdateCheckButton() {
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }
    var update by remember { mutableStateOf<AvailableUpdate?>(null) }
    var status by remember { mutableStateOf<String?>(null) }

    update?.let { UpdatePromptDialog(update = it, onDismiss = { update = null }) }
    RoundedButton(
        text = if (checking) "Proveravam..." else UPDATE_CHECK_LABEL,
        modifier = Modifier.fillMaxWidth().height(UI.buttonHeight.dp),
        bgColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        textColor = MaterialTheme.colorScheme.onSurface,
        borderColor = MaterialTheme.colorScheme.outlineVariant,
        textStyle = MaterialTheme.typography.labelLarge,
        onClick = {
            if (checking) return@RoundedButton
            checking = true
            status = null
            scope.launch {
                val found = withContext(Dispatchers.IO) { fetchAvailableUpdate(BuildConfig.VERSION_NAME) }
                checking = false
                if (found != null) {
                    update = found
                } else {
                    status = "Imaš najnoviju verziju (${BuildConfig.VERSION_NAME}), ili nema interneta."
                }
            }
        }
    )
    status?.let {
        Text(text = it, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
    }
}
