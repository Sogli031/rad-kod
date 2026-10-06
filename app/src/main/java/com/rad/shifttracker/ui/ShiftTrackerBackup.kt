package com.rad.shifttracker.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.rad.shifttracker.data.backup.BackupOutcome
import com.rad.shifttracker.data.backup.BackupScheduler
import com.rad.shifttracker.data.backup.BackupVersion
import com.rad.shifttracker.data.backup.GoogleDriveBackup
import com.rad.shifttracker.data.backup.VersionedBackupPlanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.rad.shifttracker.repo.ShiftRepository
import com.rad.shifttracker.ui.dialozi.BackupVersionsDialog
import com.rad.shifttracker.ui.dialozi.CloudBackupFoundDialog
import com.rad.shifttracker.ui.dialozi.RestoreLatestConfirmDialog
import com.rad.shifttracker.BuildConfig
import com.rad.shifttracker.repo.buildCloudBackup
import com.rad.shifttracker.repo.importFromJson

/** Dijalozi za vraćanje backup-a (ranije verzije i najnovija) i da li je neki od njih otvoren. */
@Stable
internal class BackupRestoreState(
    showVersions: MutableState<Boolean>,
    showRestoreLatestConfirm: MutableState<Boolean>
) {
    // Dijalog ranijih verzija backup-a (lista se učitava pri otvaranju).
    var showVersions by showVersions
    var versions by mutableStateOf<List<BackupVersion>?>(null)
    var restoringVersion by mutableStateOf(false)
    var showRestoreLatestConfirm by showRestoreLatestConfirm
    var restoringLatest by mutableStateOf(false)

    val anyDialogOpen: Boolean
        get() = showVersions || showRestoreLatestConfirm
}

@Composable
internal fun rememberBackupRestoreState(): BackupRestoreState {
    val showVersions = rememberSaveable { mutableStateOf(false) }
    val showRestoreLatestConfirm = rememberSaveable { mutableStateOf(false) }
    return remember { BackupRestoreState(showVersions, showRestoreLatestConfirm) }
}

/**
 * Ranije verzije i vraćanje najnovije. Jedan tap ne sme nepovratno da pregazi lokalne izmene —
 * prvo potvrda, pa sigurnosna kopija, pa restore (vidi [restoreWithSafetyCopy]).
 */
@Composable
internal fun BackupRestoreDialogs(
    state: BackupRestoreState,
    backup: GoogleDriveBackup,
    repo: ShiftRepository,
    showMessage: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    LaunchedEffect(state.showVersions) {
        if (state.showVersions) {
            state.versions = null
            val loaded = backup.listVersions().sortedByDescending { it.createdAtMillis }
            state.versions = loaded
        }
    }

    if (state.showVersions) {
        BackupVersionsDialog(
            versions = state.versions,
            isBusy = state.restoringVersion,
            onRestoreVersion = { version ->
                scope.launch {
                    state.restoringVersion = true
                    val message = restoreWithSafetyCopy(
                        backup = backup,
                        repo = repo,
                        fetch = { backup.restoreVersion(version.fileId) },
                        messages = RestoreMessages.VERSION
                    )
                    withContext(Dispatchers.Main) {
                        state.restoringVersion = false
                        state.showVersions = false
                        showMessage(message)
                    }
                }
            },
            onDismiss = { state.showVersions = false }
        )
    }

    if (state.showRestoreLatestConfirm) {
        RestoreLatestConfirmDialog(
            isBusy = state.restoringLatest,
            onConfirm = {
                scope.launch {
                    state.restoringLatest = true
                    val message = restoreWithSafetyCopy(
                        backup = backup,
                        repo = repo,
                        fetch = { backup.restoreLatest() },
                        messages = RestoreMessages.LATEST
                    )
                    withContext(Dispatchers.Main) {
                        state.restoringLatest = false
                        state.showRestoreLatestConfirm = false
                        showMessage(message)
                    }
                }
            },
            onDismiss = { if (!state.restoringLatest) state.showRestoreLatestConfirm = false }
        )
    }
}

/** Korisnička poruka posle verzionisanog backup-a (uspeh / upozorenje / greška). */
internal fun backupResultMessage(outcome: BackupOutcome): String = when (outcome) {
    is BackupOutcome.Success ->
        if (outcome.flagged) {
            "Backup sačuvan, ali liči na veliki pad podataka " +
                "(${outcome.reasons.joinToString("; ")}). " +
                "Ranije verzije su sačuvane i mogu se vratiti."
        } else {
            "Backup uspešan."
        }
    is BackupOutcome.Failure -> "Backup neuspešan: ${outcome.message}"
}

/**
 * Čim je korisnik prijavljen, a uređaj još nije preuzeo backup iz oblaka (nova
 * instalacija), ponudi Restore umesto da sveže stanje ode preko postojećeg backup-a.
 * [found] se puni i iz ručnog „Backup" dugmeta (vidi [backupUnlessCloudUnadopted]).
 */
@Composable
internal fun CloudBackupFoundHost(
    found: androidx.compose.runtime.MutableState<BackupVersion?>,
    signedIn: Boolean,
    backup: GoogleDriveBackup,
    repo: ShiftRepository,
    showMessage: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var restoring by remember { mutableStateOf(false) }
    LaunchedEffect(signedIn) {
        if (signedIn && !backup.isCloudAdopted()) {
            found.value = findUnadoptedCloudBackup(backup)
        }
    }
    val version = found.value ?: return
    CloudBackupFoundDialog(
        version = version,
        isBusy = restoring,
        onRestore = {
            scope.launch {
                restoring = true
                val message = restoreWithSafetyCopy(
                    backup = backup,
                    repo = repo,
                    fetch = { backup.restoreVersion(version.fileId) },
                    messages = RestoreMessages.LATEST
                )
                withContext(Dispatchers.Main) {
                    restoring = false
                    found.value = null
                    showMessage(message)
                }
            }
        },
        onKeepLocal = {
            // Izričita odluka: ovaj uređaj je merodavan, backup se nastavlja odavde.
            backup.markCloudAdopted()
            found.value = null
            BackupScheduler.scheduleDebounced(context.applicationContext)
        },
        onDismiss = { if (!restoring) found.value = null }
    )
}

/**
 * Ručni backup. Uređaj koji još nije preuzeo postojeći backup iz oblaka prvo pita
 * korisnika ([onFound], vraća null) — njegovo sveže stanje ne sme ćutke da postane
 * „najnovija verzija". Inače: nova verzija u oblaku, spojena sa prethodnom arhivom
 * radi pune istorije, uz detekciju anomalija i retenciju.
 */
internal suspend fun backupUnlessCloudUnadopted(
    backup: GoogleDriveBackup,
    repo: ShiftRepository,
    onFound: (BackupVersion) -> Unit
): BackupOutcome? {
    val found = if (backup.isCloudAdopted()) null else findUnadoptedCloudBackup(backup)
    if (found != null) {
        onFound(found)
        return null
    }
    return backup.backupVersioned(appVersion = BuildConfig.VERSION_NAME) { existing ->
        repo.buildCloudBackup(existing)
    }
}

/**
 * Najnovija redovna verzija u oblaku koju ovaj uređaj još nije preuzeo, ili null.
 * Prazan oblak se odmah „usvaja" (nema šta da se pregazi); ako lista nije mogla
 * da se pročita, ništa se ne menja pa se pita sledeći put.
 */
internal suspend fun findUnadoptedCloudBackup(backup: GoogleDriveBackup): BackupVersion? {
    val versions = backup.cloudVersionsOrNull() ?: return null
    val latest = VersionedBackupPlanner.latest(versions)
    if (latest == null) backup.markCloudAdopted()
    return latest
}

/**
 * Restore uz sigurnosnu kopiju. Cilj se skida PRE pravljenja kopije: inače bi
 * „najnovija verzija" bila upravo ta kopija (sveže stanje spojeno sa arhivom), pa
 * bi se na novoj instalaciji vratilo pola podataka. Ako kopija ne uspe, vraćanje
 * se prekida (UI obećava da je poništivo, pa ne sme da nastavi bez kopije).
 */
internal suspend fun restoreWithSafetyCopy(
    backup: GoogleDriveBackup,
    repo: ShiftRepository,
    fetch: suspend () -> String?,
    messages: RestoreMessages
): String {
    val json = fetch() ?: return messages.missing
    val safetyOutcome = backup.backupVersioned(appVersion = BuildConfig.VERSION_NAME, safety = true) { existing ->
        repo.buildCloudBackup(existing)
    }
    if (safetyOutcome is BackupOutcome.Failure) {
        return "Vraćanje otkazano — sigurnosna kopija nije uspela: ${safetyOutcome.message}"
    }
    if (!repo.importFromJson(json)) return messages.failure
    // Uspešan uvoz = stanje je sada nastavak oblaka, pa auto-backup sme da krene.
    backup.markCloudAdopted()
    return messages.success
}

internal data class RestoreMessages(val missing: String, val success: String, val failure: String) {
    companion object {
        val LATEST = RestoreMessages("Nema backup-a.", "Restore uspešan.", "Restore neuspešan.")
        val VERSION = RestoreMessages("Vraćanje neuspešno.", "Vraćeno na izabranu verziju.", "Vraćanje neuspešno.")
    }
}
