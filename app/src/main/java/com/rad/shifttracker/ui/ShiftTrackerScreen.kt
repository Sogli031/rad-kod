package com.rad.shifttracker.ui

import android.Manifest
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.rad.shifttracker.data.backup.BackupVersion
import com.rad.shifttracker.data.backup.GoogleDriveBackup
import com.rad.shifttracker.data.backup.SignInState
import com.rad.shifttracker.data.backup.SyncState
import java.time.LocalDate
import java.time.YearMonth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.rad.shifttracker.osnova.CrashContext
import com.rad.shifttracker.krv.BloodDonationReminderScheduler
import com.rad.shifttracker.uvoz.SideRunMessages
import com.rad.shifttracker.repo.ShiftRepository
import com.rad.shifttracker.smene.MonthKey
import com.rad.shifttracker.smene.ShiftScheduler
import com.rad.shifttracker.ui.dialozi.BackupSignInPromptHost
import com.rad.shifttracker.ui.dialozi.UpdatePromptHost
import com.rad.shifttracker.ui.dialozi.SideRunMessagesDialog
import com.rad.shifttracker.ui.dialozi.rememberDriveBackupSignIn
import com.rad.shifttracker.ui.dialozi.takeGmailUpdateIntro
import com.rad.shifttracker.repo.reschedule

internal fun vacationCardAsOfDate(year: Int, month: Int, today: LocalDate): LocalDate {
    val displayedMonth = YearMonth.of(year, month)
    return if (displayedMonth == YearMonth.from(today)) {
        today
    } else {
        displayedMonth.atEndOfMonth()
    }
}

@Composable
fun RadApp(repo: ShiftRepository, backup: com.rad.shifttracker.data.backup.GoogleDriveBackup) {
    RadTheme {
        val snackbarHostState = remember { SnackbarHostState() }
        CompositionLocalProvider(LocalAppSnackbar provides snackbarHostState) {
            ShiftTrackerScreen(repo = repo, backup = backup)
        }
    }
}

@Composable
fun ShiftTrackerScreen(repo: ShiftRepository, backup: com.rad.shifttracker.data.backup.GoogleDriveBackup) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val showMessage = rememberShowMessage()
    val viewModel: MainViewModel = viewModel(
        factory = viewModelFactory {
            initializer { MainViewModel(repo, context.applicationContext, createSavedStateHandle(), backup = backup) }
        }
    )
    val ui = viewModel.state.collectAsStateWithLifecycle().value
    val today by viewModel.today.collectAsStateWithLifecycle()
    val selectedMonth by viewModel.month.collectAsStateWithLifecycle()
    // Brojač izmena podataka za delove koji još sami čitaju repo (kalendar, godišnji, dijalozi).
    val repoTick = remember { mutableIntStateOf(0) }
    DisposableEffect(repo) {
        val callbackToken = repo.onChange { scope.launch { repoTick.intValue += 1 } }
        onDispose { repo.removeOnChange(callbackToken) }
    }

    // Google Drive backup state
    val signInState by backup.signInState.collectAsStateWithLifecycle()
    val syncState by backup.syncState.collectAsStateWithLifecycle()
    val lastSyncTime by backup.lastSyncTime.collectAsStateWithLifecycle()
    val backupRestore = rememberBackupRestoreState()
    // Backup u oblaku koji ovaj uređaj još nije preuzeo (nova instalacija): čim se
    // korisnik prijavi, ponudi Restore umesto da sveže stanje ode preko njega.
    val cloudBackupFound = remember { mutableStateOf<BackupVersion?>(null) }
    val startSignIn = rememberDriveBackupSignIn(backup, showMessage)
    // Prvo pokretanje se čita jednom: ko tek završi uvod, ne dobija odmah i ponudu za prijavu.
    val firstLaunchAtStart = rememberSaveable { backup.isFirstLaunch() }
    val bloodDonationNotificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        handleBloodDonationPermissionResult(granted, context, repo, showMessage)
    }

    if (ui == null) {
        // Prvi obračun meseca još traje (pozadinska nit): samo pozadina, bez praznog kalendara.
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
        return
    }

    // Izabrani mesec živi u MainViewModel (preživljava rotaciju i ubijanje procesa).
    val currentYear = selectedMonth.year
    val currentMonth = selectedMonth.month
    val launchSelectedMonthExport = rememberMonthlyWorkAndPayExportLauncher(repo, currentYear, currentMonth, showMessage)
    val launchAllMonthsDebugExport = rememberAllMonthsCalculationDebugExportLauncher(repo, showMessage)
    val vacationAsOfDate = remember(currentYear, currentMonth, today) {
        vacationCardAsOfDate(currentYear, currentMonth, today)
    }
    val scheduler = remember { ShiftScheduler() }

    // Otvoren dijalog preživljava rotaciju; DayEdit obnavlja zapis iz repo-a.
    var dialogState by rememberSaveable(
        stateSaver = dialogStateSaver { day -> repo.get(currentYear, currentMonth, day) }
    ) { mutableStateOf<DialogState>(DialogState.None) }
    val dismissDialog = { dialogState = DialogState.None }
    LaunchedEffect(dialogState) { CrashContext.screen = dialogStateToKey(dialogState) }
    // Prvo pokretanje posle instalacije: prvo „Popuni smene“ (izbor brigade utiče i na pravo na
    // godišnji), pa Gmail sa listićima (koeficijent, minuli rad, porez, prevoz, akontacija...).
    // Prvo pokretanje je završeno tek kad Gmail korak prođe (MainDialogData.onFirstLaunchDone): ako
    // se aplikacija ugasi usred čitanja listića, sledeći put se ceo uvod nudi ponovo.
    // Ažurirana stara verzija (nije prvo pokretanje, Gmail nije povezan): jednom ponudi Gmail i
    // proveru podešavanja po listićima, bez „Popuni smene“.
    LaunchedEffect(Unit) {
        if (backup.isFirstLaunch()) {
            takeGmailUpdateIntro(context, firstLaunch = true)
            if (dialogState == DialogState.None) dialogState = DialogState.OnboardingAutoFill
        } else if (takeGmailUpdateIntro(context, firstLaunch = false)) {
            dialogState = DialogState.GmailUpdate
        }
    }

    ShiftTrackerContent(
        ui = ui,
        month = MonthKey(currentYear, currentMonth),
        today = today,
        repo = repo,
        refreshKey = repoTick.intValue,
        onPrevMonth = viewModel::showPreviousMonth,
        onNextMonth = viewModel::showNextMonth,
        onOpenDialog = { state -> dialogState = state }
    )

    // Upisana akontacija / promenjen prevoz iz obračuna: poruka u aplikaciji, ne na telefonu.
    val sideRunMessages by SideRunMessages.pending.collectAsStateWithLifecycle()
    if (sideRunMessages.isNotEmpty() && dialogState == DialogState.None) {
        SideRunMessagesDialog(messages = sideRunMessages, onDismiss = SideRunMessages::clear)
    }

    MainDialogs(
        dialogState = dialogState,
        data = MainDialogData(
            currentYear = currentYear,
            currentMonth = currentMonth,
            today = today,
            vacationAsOfDate = vacationAsOfDate,
            scheduler = scheduler,
            repo = repo,
            actions = viewModel,
            repoTick = repoTick.intValue,
            workHoursPayDetails = ui.selectedMonthPayDetails,
            salaryDetailsReceipt = ui.nextMonthReceipt,
            salaryDetailsFinalPaymentBreakdown = ui.nextMonthFinalPaymentBreakdown,
            monthlyReceipt = ui.monthlyReceipt,
            monthlyFinalPaymentBreakdown = ui.monthlyFinalPaymentBreakdown,
            overtimeNetImpact = ui.overtimeNetImpact,
            payDetails = ui.payDetails,
            bloodDonationReminderStatus = ui.bloodDonationReminderStatus,
            onMonthlyExportClick = launchSelectedMonthExport,
            onDebugExportClick = launchAllMonthsDebugExport,
            onBloodDonationDateSaved = {
                handleBloodDonationDateSaved(
                    context = context,
                    repo = repo,
                    requestNotificationPermission = {
                        bloodDonationNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                )
            },
            onBloodDonationHistoryChanged = {
                BloodDonationReminderScheduler.reschedule(context, repo)
            },
            onDialogChange = { state -> dialogState = state },
            onFirstLaunchDone = { if (backup.isFirstLaunch()) backup.markFirstLaunchDone() }
        ),
        cloudBackup = cloudBackupDialogData(
            backup = backup,
            repo = repo,
            status = CloudBackupStatus(signInState, syncState, lastSyncTime),
            scope = scope,
            onSignIn = startSignIn,
            onCloudBackupFound = { cloudBackupFound.value = it },
            backupRestore = backupRestore,
            showMessage = showMessage
        ),
        onDismiss = dismissDialog
    )

    BackupRestoreDialogs(state = backupRestore, backup = backup, actions = viewModel, showMessage = showMessage)

    CloudBackupFoundHost(
        found = cloudBackupFound,
        signedIn = signInState is SignInState.SignedIn,
        backup = backup,
        actions = viewModel,
        showMessage = showMessage
    )

    // Imao backup, a nije prijavljen (npr. posle prelaska na novu Google prijavu): ponudi prijavu.
    BackupSignInPromptHost(
        lastSyncTime = lastSyncTime,
        signedIn = signInState is SignInState.SignedIn,
        // Čita se pri svakoj promeni stanja prijave (signInState), pa odjava odmah važi.
        signedOutByUser = backup.isSignedOutByUser(),
        firstLaunch = firstLaunchAtStart,
        otherDialogOpen = dialogState != DialogState.None || sideRunMessages.isNotEmpty() ||
            backupRestore.anyDialogOpen || cloudBackupFound.value != null,
        onSignIn = startSignIn
    )

    // Nova verzija na GitHub-u: ponudi ažuriranje iz aplikacije (bez Obtainium-a).
    UpdatePromptHost(
        firstLaunch = firstLaunchAtStart,
        otherDialogOpen = dialogState != DialogState.None || sideRunMessages.isNotEmpty() ||
            backupRestore.anyDialogOpen || cloudBackupFound.value != null
    )
}

/** Stanje prijave i sinhronizacije za dijalog „Cloud backup“. */
private data class CloudBackupStatus(val signInState: SignInState, val syncState: SyncState, val lastSyncTime: Long?)

private fun cloudBackupDialogData(
    backup: GoogleDriveBackup,
    repo: ShiftRepository,
    status: CloudBackupStatus,
    scope: CoroutineScope,
    onSignIn: () -> Unit,
    onCloudBackupFound: (BackupVersion) -> Unit,
    backupRestore: BackupRestoreState,
    showMessage: (String) -> Unit
) = CloudBackupDialogData(
    signInState = status.signInState,
    syncState = status.syncState,
    lastSyncTime = status.lastSyncTime,
    onSignIn = onSignIn,
    onBackup = {
        scope.launch {
            val outcome = backupUnlessCloudUnadopted(backup, repo, onCloudBackupFound) ?: return@launch
            withContext(Dispatchers.Main) {
                showMessage(backupResultMessage(outcome))
            }
        }
    },
    // Jedan tap ne sme nepovratno da pregazi lokalne izmene —
    // prvo potvrda, pa sigurnosna kopija, pa restore.
    onRestore = { backupRestore.showRestoreLatestConfirm = true },
    onShowVersions = { backupRestore.showVersions = true },
    onSignOut = {
        scope.launch { backup.signOut() }
    }
)

private fun handleBloodDonationDateSaved(
    context: Context,
    repo: ShiftRepository,
    requestNotificationPermission: () -> Unit
) {
    BloodDonationReminderScheduler.reschedule(context, repo)
    if (BloodDonationReminderScheduler.notificationPermissionRequired(context)) {
        requestNotificationPermission()
    }
}

private fun handleBloodDonationPermissionResult(
    granted: Boolean,
    context: Context,
    repo: ShiftRepository,
    showMessage: (String) -> Unit
) {
    if (granted) {
        BloodDonationReminderScheduler.reschedule(context, repo)
    } else {
        showMessage("Dozvola za obaveštenja nije uključena.")
    }
}
