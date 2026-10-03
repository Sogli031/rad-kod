package com.rad.shifttracker

import android.Manifest
import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.window.core.layout.WindowSizeClass
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.rad.shifttracker.data.backup.BackupOutcome
import com.rad.shifttracker.data.backup.BackupScheduler
import com.rad.shifttracker.data.backup.BackupVersion
import com.rad.shifttracker.data.backup.GoogleDriveBackup
import com.rad.shifttracker.data.backup.SignInState
import com.rad.shifttracker.data.backup.VersionedBackupPlanner
import java.time.LocalDate
import java.time.YearMonth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Sealed class za upravljanje stanjem dijaloga - zamenjuje 10+ boolean varijabli */
internal sealed class DialogState {
    object None : DialogState()
    object AutoFill : DialogState()

    /** Prvo pokretanje: „Popuni smene“, a kad se zatvori, Gmail sa listama. */
    object OnboardingAutoFill : DialogState()
    object GmailOnboarding : DialogState()
    object GmailUpdate : DialogState()
    object SickVacation : DialogState()
    object BasePay : DialogState()
    object PayDetails : DialogState()
    object SalaryDetails : DialogState()
    object WorkHours : DialogState()
    object OvertimeNetImpact : DialogState()
    object Food : DialogState()
    object Minuli : DialogState()
    object Options : DialogState()
    object CloudBackup : DialogState()
    object AdvancePayment : DialogState()
    object InitialCompBalance : DialogState()
    object DeleteMonth : DialogState()
    object VacationSettings : DialogState()
    object Holidays : DialogState()
    object BloodDonation : DialogState()
    data class DayEdit(val day: Int, val record: DayRecord?) : DialogState()
}

/**
 * Saver za [DialogState]: objekat-stanja se čuvaju po imenu, DayEdit po danu
 * (zapis se pri obnovi ponovo čita iz repozitorijuma — snimljeni snapshot bi
 * mogao biti zastareo). Bez ovoga rotacija/ubijanje procesa zatvara dijalog.
 */
private fun dialogStateSaver(recordForDay: (Int) -> DayRecord?): Saver<DialogState, String> = Saver(
    save = { state -> dialogStateToKey(state) },
    restore = { saved -> dialogStateFromKey(saved, recordForDay) }
)

// Exhaustive one-to-one saver mapping intentionally has one branch per dialog state.
@Suppress("CyclomaticComplexMethod")
private fun dialogStateToKey(state: DialogState): String = when (state) {
    DialogState.None -> "None"
    DialogState.AutoFill -> "AutoFill"
    DialogState.OnboardingAutoFill -> "OnboardingAutoFill"
    DialogState.GmailOnboarding -> "GmailOnboarding"
    DialogState.GmailUpdate -> "GmailUpdate"
    DialogState.SickVacation -> "SickVacation"
    DialogState.BasePay -> "BasePay"
    DialogState.PayDetails -> "PayDetails"
    DialogState.SalaryDetails -> "SalaryDetails"
    DialogState.WorkHours -> "WorkHours"
    DialogState.OvertimeNetImpact -> "OvertimeNetImpact"
    DialogState.Food -> "Food"
    DialogState.Minuli -> "Minuli"
    DialogState.Options -> "Options"
    DialogState.CloudBackup -> "CloudBackup"
    DialogState.AdvancePayment -> "AdvancePayment"
    DialogState.InitialCompBalance -> "InitialCompBalance"
    DialogState.DeleteMonth -> "DeleteMonth"
    DialogState.VacationSettings -> "VacationSettings"
    DialogState.Holidays -> "Holidays"
    DialogState.BloodDonation -> "BloodDonation"
    is DialogState.DayEdit -> "DayEdit:${state.day}"
}

private val simpleDialogStatesByKey: Map<String, DialogState> = listOf(
    DialogState.None,
    DialogState.AutoFill,
    DialogState.OnboardingAutoFill,
    DialogState.GmailOnboarding,
    DialogState.GmailUpdate,
    DialogState.SickVacation,
    DialogState.BasePay,
    DialogState.PayDetails,
    DialogState.SalaryDetails,
    DialogState.WorkHours,
    DialogState.OvertimeNetImpact,
    DialogState.Food,
    DialogState.Minuli,
    DialogState.Options,
    DialogState.CloudBackup,
    DialogState.AdvancePayment,
    DialogState.InitialCompBalance,
    DialogState.DeleteMonth,
    DialogState.VacationSettings,
    DialogState.Holidays,
    DialogState.BloodDonation
).associateBy { dialogStateToKey(it) }

private fun dialogStateFromKey(saved: String, recordForDay: (Int) -> DayRecord?): DialogState {
    if (saved.startsWith("DayEdit:")) {
        return saved.removePrefix("DayEdit:").toIntOrNull()
            ?.let { day -> DialogState.DayEdit(day, recordForDay(day)) }
            ?: DialogState.None
    }
    return simpleDialogStatesByKey[saved] ?: DialogState.None
}

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
// Existing route owns cross-dialog state; detekt stays active for newly added methods.
@Suppress("LongMethod", "CyclomaticComplexMethod")
fun ShiftTrackerScreen(repo: ShiftRepository, backup: com.rad.shifttracker.data.backup.GoogleDriveBackup) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val showMessage = rememberShowMessage()
    val snackbarHostState = LocalAppSnackbar.current
    val viewModel: MainViewModel = viewModel(
        factory = viewModelFactory {
            initializer { MainViewModel(repo, context.applicationContext, createSavedStateHandle()) }
        }
    )
    val ui by viewModel.state.collectAsStateWithLifecycle()
    val today by viewModel.today.collectAsStateWithLifecycle()
    val selectedMonth by viewModel.month.collectAsStateWithLifecycle()
    // Brojač izmena podataka za delove koji još sami čitaju repo (kalendar, godišnji, dijalozi).
    val repoTick = remember { mutableIntStateOf(0) }
    DisposableEffect(repo) {
        val callbackToken = repo.onChange { scope.launch { repoTick.intValue += 1 } }
        onDispose { repo.removeOnChange(callbackToken) }
    }

    val reducedMotion = rememberReducedMotion()

    // Google Drive backup state
    val signInState by backup.signInState.collectAsState()
    val syncState by backup.syncState.collectAsState()
    val lastSyncTime by backup.lastSyncTime.collectAsState()
    // Dijalog ranijih verzija backup-a (lista se učitava pri otvaranju).
    var showBackupVersionsDialog by rememberSaveable { mutableStateOf(false) }
    var backupVersions by remember { mutableStateOf<List<BackupVersion>?>(null) }
    var restoringVersion by remember { mutableStateOf(false) }
    var showRestoreLatestConfirm by rememberSaveable { mutableStateOf(false) }
    var restoringLatest by remember { mutableStateOf(false) }
    LaunchedEffect(showBackupVersionsDialog) {
        if (showBackupVersionsDialog) {
            backupVersions = null
            val loaded = backup.listVersions().sortedByDescending { it.createdAtMillis }
            backupVersions = loaded
        }
    }
    // Backup u oblaku koji ovaj uređaj još nije preuzeo (nova instalacija): čim se
    // korisnik prijavi, ponudi Restore umesto da sveže stanje ode preko njega.
    val cloudBackupFound = remember { mutableStateOf<BackupVersion?>(null) }
    var requestedEntryPermissions by rememberSaveable { mutableStateOf(false) }
    val signInLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
        val account = try {
            task.result
        } catch (e: Exception) {
            showMessage("Prijava neuspešna: ${e.message}")
            null
        }
        backup.handleSignInResult(account)
        if (account != null) {
            showMessage("Prijava uspešna!")
        }
    }
    val entryPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { }
    val bloodDonationNotificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        handleBloodDonationPermissionResult(granted, context, repo, showMessage)
    }
    LaunchedEffect(requestedEntryPermissions) {
        if (!requestedEntryPermissions) {
            requestedEntryPermissions = true
            val permissions = requiredOnboardingPermissions(context)
            if (permissions.isNotEmpty()) {
                entryPermissionLauncher.launch(permissions)
            }
        }
    }

    // Izabrani mesec živi u MainViewModel (preživljava rotaciju i ubijanje procesa).
    val currentYear = selectedMonth.year
    val currentMonth = selectedMonth.month
    val launchSelectedMonthExport = rememberMonthlyWorkAndPayExportLauncher(repo, currentYear, currentMonth, showMessage)
    val launchAllMonthsDebugExport = rememberAllMonthsCalculationDebugExportLauncher(repo, showMessage)
    val vacationAsOfDate = remember(currentYear, currentMonth, today) {
        vacationCardAsOfDate(currentYear, currentMonth, today)
    }
    val summary = ui.summary
    val payBreakdown = ui.payBreakdown
    val monthlyReceipt = ui.monthlyReceipt
    val nextMonthReceipt = ui.nextMonthReceipt
    val mainPayIncludesTransport = ui.mainPayIncludesTransport
    val monthlyFinalPaymentBreakdown = ui.monthlyFinalPaymentBreakdown
    val nextMonthFinalPaymentBreakdown = ui.nextMonthFinalPaymentBreakdown
    val overtimeNetImpact = ui.overtimeNetImpact
    val payrollSummary = ui.payrollSummary
    val payrollPayBreakdown = ui.payrollPayBreakdown
    val payDetails = ui.payDetails
    val selectedMonthPayDetails = ui.selectedMonthPayDetails
    val selectedMonthOvertimeNetImpact = ui.overtimeNetImpact
    val annualNetPayAverages = ui.annualNetPayAverages
    val recentNetPayAverage = ui.recentNetPayAverage
    val selectedMonthAnnualNetPayAverages = ui.selectedMonthAnnualNetPayAverages
    val selectedMonthRecentNetPayAverage = ui.selectedMonthRecentNetPayAverage
    val scheduler = remember { ShiftScheduler() }
    val bloodDonationReminderStatus = ui.bloodDonationReminderStatus

    // Otvoren dijalog preživljava rotaciju; DayEdit obnavlja zapis iz repo-a.
    var dialogState by rememberSaveable(
        stateSaver = dialogStateSaver { day -> repo.get(currentYear, currentMonth, day) }
    ) { mutableStateOf<DialogState>(DialogState.None) }
    val dismissDialog = { dialogState = DialogState.None }
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            // Pozadina ide i ispod statusne i navigacione trake (inseti), u boji izabrane teme.
            .background(MaterialTheme.colorScheme.background)
            .padding(WindowInsets.safeDrawing.asPaddingValues())
    ) {
        // Cela strana skroluje kad ne staje (veći font, mesec od šest nedelja); donja traka ostaje zakucana.
        val wide = currentWindowAdaptiveInfo().windowSizeClass
            .isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_EXPANDED_LOWER_BOUND)
        var selectedDay by rememberSaveable(currentYear, currentMonth) { mutableStateOf<Int?>(null) }
        // Kalendar sa zaglavljem i statistika sa platom: jedna kolona na telefonu, dve kolone na širokom ekranu.
        val calendarPane: @Composable ColumnScope.() -> Unit = {
            HeaderSection(
                month = currentMonth,
                year = currentYear,
                onPrev = viewModel::showPreviousMonth,
                onNext = viewModel::showNextMonth
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {
                CompTimeCard(
                    summary = summary,
                    modifier = Modifier.weight(1f),
                    onClick = { dialogState = DialogState.InitialCompBalance }
                )
                VacationCard(
                    repo = repo,
                    repoTick = repoTick.intValue,
                    year = currentYear,
                    today = vacationAsOfDate,
                    modifier = Modifier.weight(1f),
                    onClick = { dialogState = DialogState.VacationSettings }
                )
            }
            WeekdayHeader()
            // Prelaz meseca: M3 „shared axis“ (klizanje po x + bledenje); stari mesec izlazi sa svojim podacima.
            AnimatedContent(
                targetState = currentYear * MONTHS_PER_YEAR + (currentMonth - 1),
                transitionSpec = {
                    if (reducedMotion) {
                        EnterTransition.None togetherWith ExitTransition.None
                    } else {
                        monthTransition(forward = targetState > initialState)
                    }
                },
                label = "month"
            ) { monthIndex ->
                key(repoTick.intValue) {
                    CalendarSection(
                        year = monthIndex / MONTHS_PER_YEAR,
                        month = monthIndex % MONTHS_PER_YEAR + 1,
                        today = today,
                        repo = repo,
                        onDayClick = { day, record ->
                            // Na širokom ekranu dodir bira dan (panel desno), izmena ide dugmetom „Izmeni“.
                            if (wide) selectedDay = day else dialogState = DialogState.DayEdit(day, record)
                        }
                    )
                }
            }
        }
        val summaryPane: @Composable ColumnScope.() -> Unit = {
            selectedDay?.takeIf { wide }?.let { day ->
                SelectedDayCard(
                    year = currentYear,
                    month = currentMonth,
                    day = day,
                    today = today,
                    repo = repo,
                    onEdit = { dialogState = DialogState.DayEdit(day, repo.get(currentYear, currentMonth, day)) }
                )
            }
            StatsSection(
                summary = summary,
                payBreakdown = payBreakdown,
                onWorkHoursClick = { dialogState = DialogState.WorkHours },
                onOvertimeClick = { dialogState = DialogState.OvertimeNetImpact },
                onFoodClick = { dialogState = DialogState.Food },
                onMinuliClick = { dialogState = DialogState.Minuli }
            )
            Column(verticalArrangement = Arrangement.spacedBy(PAY_CARDS_SPACING.dp)) {
                SalaryCard(
                    month = currentMonth,
                    nextMonthReceipt = nextMonthReceipt,
                    includeTransport = mainPayIncludesTransport,
                    onClick = { dialogState = DialogState.SalaryDetails }
                )
                if (SHOW_TOTAL_CARD) {
                    TotalCard(
                        receipt = monthlyReceipt,
                        includeTransport = mainPayIncludesTransport,
                        onClick = { dialogState = DialogState.PayDetails }
                    )
                }
            }
        }
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            BackgroundLayer()

            Column(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxHeight()
                    .widthIn(max = (if (wide) UI.wideContentMaxWidth else UI.contentMaxWidth).dp)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(
                        start = Spacing.lg,
                        top = Spacing.lg,
                        end = Spacing.lg,
                        bottom = Spacing.sm
                    ),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {
                if (wide) {
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.lg)) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                            content = calendarPane
                        )
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                            content = summaryPane
                        )
                    }
                } else {
                    calendarPane()
                    summaryPane()
                }
            }

            snackbarHostState?.let { host ->
                SnackbarHost(
                    hostState = host,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(start = Spacing.lg, end = Spacing.lg)
                )
            }
        }

        BottomBar(
            modifier = Modifier,
            onAutoFill = { dialogState = DialogState.AutoFill },
            onSickVacation = { dialogState = DialogState.SickVacation },
            onBasePay = { dialogState = DialogState.BasePay },
            onHolidays = { dialogState = DialogState.BloodDonation },
            onOptions = { dialogState = DialogState.Options },
            onDeleteMonth = { dialogState = DialogState.DeleteMonth },
            basePayEnabled = !isPayslipMonthUnfinished(MonthKey(currentYear, currentMonth), today)
        )
    }

    // Upisana akontacija / promenjen prevoz iz obračuna: poruka u aplikaciji, ne na telefonu.
    val sideRunMessages by SideRunMessages.pending.collectAsState()
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
            repoTick = repoTick.intValue,
            summary = payrollSummary,
            payBreakdown = payrollPayBreakdown,
            workHoursSummary = summary,
            workHoursPayBreakdown = payBreakdown,
            workHoursMonth = MonthKey(currentYear, currentMonth),
            workHoursPayDetails = selectedMonthPayDetails,
            workHoursOvertimeNetImpact = selectedMonthOvertimeNetImpact,
            salaryDetailsReceipt = nextMonthReceipt,
            salaryDetailsFinalPaymentBreakdown = nextMonthFinalPaymentBreakdown,
            salaryDetailsAnnualNetPayAverages = selectedMonthAnnualNetPayAverages,
            salaryDetailsRecentNetPayAverage = selectedMonthRecentNetPayAverage,
            monthlyReceipt = monthlyReceipt,
            monthlyFinalPaymentBreakdown = monthlyFinalPaymentBreakdown,
            overtimeNetImpact = overtimeNetImpact,
            payDetails = payDetails,
            annualNetPayAverages = annualNetPayAverages,
            recentNetPayAverage = recentNetPayAverage,
            bloodDonationReminderStatus = bloodDonationReminderStatus,
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
        cloudBackup = CloudBackupDialogData(
            signInState = signInState,
            syncState = syncState,
            lastSyncTime = lastSyncTime,
            onSignIn = { signInLauncher.launch(backup.getSignInIntent()) },
            onBackup = {
                scope.launch {
                    val outcome = backupUnlessCloudUnadopted(backup, repo) { cloudBackupFound.value = it }
                        ?: return@launch
                    withContext(Dispatchers.Main) {
                        showMessage(backupResultMessage(outcome))
                    }
                }
            },
            // Jedan tap ne sme nepovratno da pregazi lokalne izmene —
            // prvo potvrda, pa sigurnosna kopija, pa restore.
            onRestore = { showRestoreLatestConfirm = true },
            onShowVersions = { showBackupVersionsDialog = true },
            onSignOut = {
                scope.launch { backup.signOut() }
            }
        ),
        onDismiss = dismissDialog
    )

    if (showBackupVersionsDialog) {
        BackupVersionsDialog(
            versions = backupVersions,
            isBusy = restoringVersion,
            onRestoreVersion = { version ->
                scope.launch {
                    restoringVersion = true
                    val message = restoreWithSafetyCopy(
                        backup = backup,
                        repo = repo,
                        fetch = { backup.restoreVersion(version.fileId) },
                        messages = RestoreMessages.VERSION
                    )
                    withContext(Dispatchers.Main) {
                        restoringVersion = false
                        showBackupVersionsDialog = false
                        showMessage(message)
                    }
                }
            },
            onDismiss = { showBackupVersionsDialog = false }
        )
    }

    if (showRestoreLatestConfirm) {
        RestoreLatestConfirmDialog(
            isBusy = restoringLatest,
            onConfirm = {
                scope.launch {
                    restoringLatest = true
                    val message = restoreWithSafetyCopy(
                        backup = backup,
                        repo = repo,
                        fetch = { backup.restoreLatest() },
                        messages = RestoreMessages.LATEST
                    )
                    withContext(Dispatchers.Main) {
                        restoringLatest = false
                        showRestoreLatestConfirm = false
                        showMessage(message)
                    }
                }
            },
            onDismiss = { if (!restoringLatest) showRestoreLatestConfirm = false }
        )
    }

    CloudBackupFoundHost(
        found = cloudBackupFound,
        signedIn = signInState is SignInState.SignedIn,
        backup = backup,
        repo = repo,
        showMessage = showMessage
    )
}

private const val MONTHS_PER_YEAR = 12
/** M3 shared axis X (spring iz [RadMotion]): novi mesec ulazi sa strane smera, stari izlazi na suprotnu stranu. */
private fun monthTransition(forward: Boolean): ContentTransform {
    val direction = if (forward) 1 else -1
    val enter = slideInHorizontally(RadMotion.spatial()) {
        direction * it / MONTH_SLIDE_DIVISOR
    } + fadeIn(RadMotion.effects())
    val exit = slideOutHorizontally(RadMotion.fastSpatial()) {
        -direction * it / MONTH_SLIDE_DIVISOR
    } + fadeOut(RadMotion.fastEffects())
    return enter togetherWith exit
}

private const val MONTH_SLIDE_DIVISOR = 8

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
private fun CloudBackupFoundHost(
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

private suspend fun saveSelectedMonthWorkAndPayExport(
    context: Context,
    repo: ShiftRepository,
    target: MonthKey,
    uri: Uri
) {
    withContext(Dispatchers.IO) {
        val json = repo.exportSelectedMonthWorkAndPayJson(
            selectedYear = target.year,
            selectedMonth = target.month,
            appName = context.getString(R.string.app_name),
            appVersion = BuildConfig.VERSION_NAME
        )
        // SAF providers do not have to truncate for plain "w". Explicit "wt"
        // prevents stale bytes from an older, longer export trailing the new JSON.
        val output = context.contentResolver.openOutputStream(
            uri,
            MONTHLY_WORK_AND_PAY_EXPORT_OUTPUT_MODE
        )
            ?: error("Nije moguće otvoriti fajl.")
        output.use { stream ->
            stream.write(json.toByteArray(Charsets.UTF_8))
        }
    }
}

private suspend fun saveAllMonthsCalculationDebugExport(
    context: Context,
    repo: ShiftRepository,
    uri: Uri
) {
    withContext(Dispatchers.IO) {
        val json = repo.exportAllSavedMonthsCalculationDebugJson(
            appName = context.getString(R.string.app_name),
            appVersion = BuildConfig.VERSION_NAME
        )
        val output = context.contentResolver.openOutputStream(
            uri,
            MONTHLY_WORK_AND_PAY_EXPORT_OUTPUT_MODE
        ) ?: error("Nije moguće otvoriti fajl.")
        output.use { stream ->
            stream.write(json.toByteArray(Charsets.UTF_8))
        }
    }
}

@Composable
private fun rememberMonthlyWorkAndPayExportLauncher(
    repo: ShiftRepository,
    currentYear: Int,
    currentMonth: Int,
    showMessage: (String) -> Unit
): () -> Unit {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var pendingMonthlyExport by remember { mutableStateOf<MonthKey?>(null) }
    val monthlyExportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        val target = pendingMonthlyExport
        pendingMonthlyExport = null
        handleMonthlyExportDocumentResult(
            uri = uri,
            target = target,
            context = context,
            repo = repo,
            scope = scope,
            showMessage = showMessage
        )
    }
    return {
        val target = MonthKey(currentYear, currentMonth)
        pendingMonthlyExport = target
        monthlyExportLauncher.launch(monthlyWorkAndPayExportFileName(target.year, target.month))
    }
}

@Composable
private fun rememberAllMonthsCalculationDebugExportLauncher(
    repo: ShiftRepository,
    showMessage: (String) -> Unit
): () -> Unit {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val debugExportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        handleAllMonthsDebugExportDocumentResult(
            uri = uri,
            context = context,
            repo = repo,
            scope = scope,
            showMessage = showMessage
        )
    }
    return {
        if (repo.getAllMonthsSorted().isEmpty()) {
            showMessage("Nema sačuvanih meseci za debug export.")
        } else {
            debugExportLauncher.launch(ALL_MONTHS_CALCULATION_DEBUG_EXPORT_FILE_NAME)
        }
    }
}

private fun handleMonthlyExportDocumentResult(
    uri: Uri?,
    target: MonthKey?,
    context: Context,
    repo: ShiftRepository,
    scope: kotlinx.coroutines.CoroutineScope,
    showMessage: (String) -> Unit
) {
    if (uri == null) {
        showMessage("Export otkazan.")
        return
    }
    if (target == null) {
        showMessage("Export nije uspeo: nedostaje izabrani mesec.")
        return
    }
    scope.launch {
        val result = runCatching {
            saveSelectedMonthWorkAndPayExport(context, repo, target, uri)
        }
        showMessage(monthlyExportResultMessage(result))
    }
}

private fun handleAllMonthsDebugExportDocumentResult(
    uri: Uri?,
    context: Context,
    repo: ShiftRepository,
    scope: kotlinx.coroutines.CoroutineScope,
    showMessage: (String) -> Unit
) {
    if (uri == null) {
        showMessage("Debug export otkazan.")
        return
    }
    scope.launch {
        val result = runCatching {
            saveAllMonthsCalculationDebugExport(context, repo, uri)
        }
        showMessage(allMonthsDebugExportResultMessage(result))
    }
}

private fun monthlyExportResultMessage(result: Result<Unit>): String {
    return result.fold(
        onSuccess = { "Export sačuvan." },
        onFailure = { error -> "Export nije uspeo: ${error.message ?: "nepoznata greška"}" }
    )
}

private fun allMonthsDebugExportResultMessage(result: Result<Unit>): String {
    return result.fold(
        onSuccess = { "Debug export svih sačuvanih meseci je sačuvan." },
        onFailure = { error -> "Debug export nije uspeo: ${error.message ?: "nepoznata greška"}" }
    )
}

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
