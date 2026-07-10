package com.rad.shifttracker

import android.Manifest
import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.rad.shifttracker.data.backup.BackupOutcome
import com.rad.shifttracker.data.backup.BackupVersion
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Sealed class za upravljanje stanjem dijaloga - zamenjuje 10+ boolean varijabli */
internal sealed class DialogState {
    object None : DialogState()
    object AutoFill : DialogState()
    object SickVacation : DialogState()
    object BasePay : DialogState()
    object PayDetails : DialogState()
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

private fun dialogStateToKey(state: DialogState): String = when (state) {
    DialogState.None -> "None"
    DialogState.AutoFill -> "AutoFill"
    DialogState.SickVacation -> "SickVacation"
    DialogState.BasePay -> "BasePay"
    DialogState.PayDetails -> "PayDetails"
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
    DialogState.SickVacation,
    DialogState.BasePay,
    DialogState.PayDetails,
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
        ShiftTrackerScreen(repo = repo, backup = backup)
    }
}

@Composable
// Existing route owns cross-dialog state; detekt stays active for newly added methods.
@Suppress("LongMethod")
fun ShiftTrackerScreen(repo: ShiftRepository, backup: com.rad.shifttracker.data.backup.GoogleDriveBackup) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val repoTick = remember { mutableIntStateOf(0) }
    val monthlyTaxUpdateStore = remember(context) { MonthlyTaxUpdateStore(context) }
    val taxParameterUpdater = remember { TaxParameterUpdater() }
    val taxParameterCache = remember(context) { TaxParameterCache(context) }
    var monthlyTaxUpdateMessage by rememberSaveable { mutableStateOf<String?>(null) }

    // Use DisposableEffect to properly clean up the callback when composable leaves composition
    DisposableEffect(repo) {
        val callbackToken = repo.onChange {
            scope.launch {
                repoTick.intValue += 1
            }
        }
        onDispose {
            repo.removeOnChange(callbackToken)
        }
    }

    var today by remember { mutableStateOf(LocalDate.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            today = LocalDate.now()
            val nextMidnight = today.plusDays(1).atStartOfDay()
            val delayMillis = Duration
                .between(LocalDateTime.now(), nextMidnight)
                .toMillis()
                .coerceAtLeast(60_000L)
            delay(delayMillis)
        }
    }

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
    // Use rememberSaveable to persist state across configuration changes (rotation)
    var showOnboarding by rememberSaveable { mutableStateOf(backup.isFirstLaunch()) }
    var onboardingStep by rememberSaveable { mutableStateOf(defaultOnboardingSteps().first()) }
    var requestedEntryPermissions by rememberSaveable { mutableStateOf(false) }
    val signInLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
        val account = try {
            task.result
        } catch (e: Exception) {
            Toast.makeText(context, "Prijava neuspešna: ${e.message}", Toast.LENGTH_LONG).show()
            null
        }
        backup.handleSignInResult(account)
        if (account != null) {
            Toast.makeText(context, "Prijava uspešna!", Toast.LENGTH_SHORT).show()
        }
    }
    val entryPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { }
    val foodReceiptNotificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            enableFoodReceiptReminder(context, repo)
        } else {
            Toast.makeText(context, "Dozvola za obaveštenja nije uključena.", Toast.LENGTH_LONG).show()
        }
    }
    val bloodDonationNotificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        handleBloodDonationPermissionResult(granted, context, repo)
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

    MonthlyTaxUpdateEffect(
        showOnboarding = showOnboarding,
        today = today,
        repo = repo,
        taxParameterUpdater = taxParameterUpdater,
        taxParameterCache = taxParameterCache,
        monthlyTaxUpdateStore = monthlyTaxUpdateStore,
        onUpdated = { message -> monthlyTaxUpdateMessage = message }
    )

    // rememberSaveable: rotacija/ubijanje procesa ne sme da vrati kalendar
    // sa pregledanog meseca na tekući.
    var currentYear by rememberSaveable { mutableIntStateOf(today.year) }
    var currentMonth by rememberSaveable { mutableIntStateOf(today.monthValue) }
    val launchSelectedMonthExport = rememberMonthlyWorkAndPayExportLauncher(repo, currentYear, currentMonth)
    val vacationAsOfDate = remember(currentYear, currentMonth, today) {
        vacationCardAsOfDate(currentYear, currentMonth, today)
    }
    val summary = remember(repoTick.intValue, currentYear, currentMonth) {
        repo.getSummary(currentYear, currentMonth)
    }
    val payBreakdown = remember(repoTick.intValue, currentYear, currentMonth) {
        repo.getNetPayBreakdown(currentYear, currentMonth)
    }
    val monthlyReceipt = remember(repoTick.intValue, currentYear, currentMonth) {
        repo.getMonthlyReceipt(currentYear, currentMonth)
    }
    val nextMonthReceipt = remember(repoTick.intValue, currentYear, currentMonth) {
        val nextMonth = YearMonth.of(currentYear, currentMonth).plusMonths(1)
        repo.getMonthlyReceipt(nextMonth.year, nextMonth.monthValue)
    }
    val monthlyFinalPaymentBreakdown = remember(repoTick.intValue, currentYear, currentMonth) {
        repo.getMonthlyFinalPaymentBreakdown(currentYear, currentMonth)
    }
    val overtimeNetImpact = remember(repoTick.intValue, currentYear, currentMonth) {
        repo.getMonthlyOvertimeNetImpact(currentYear, currentMonth)
    }
    val payrollMonthKey = monthlyReceipt.payrollMonth
    val payrollSummary = remember(repoTick.intValue, payrollMonthKey) {
        repo.getSummary(payrollMonthKey.year, payrollMonthKey.month)
    }
    val payrollPayBreakdown = remember(repoTick.intValue, payrollMonthKey) {
        repo.getNetPayBreakdown(payrollMonthKey.year, payrollMonthKey.month)
    }
    val payDetails = remember(repoTick.intValue, payrollMonthKey) {
        repo.getMonthlyPayDetails(payrollMonthKey.year, payrollMonthKey.month)
    }
    val selectedMonthPayDetails = remember(repoTick.intValue, currentYear, currentMonth) {
        repo.getMonthlyPayDetails(currentYear, currentMonth)
    }
    val selectedMonthOvertimeNetImpact = remember(repoTick.intValue, currentYear, currentMonth) {
        repo.getMonthlyOvertimeNetImpact(currentYear, currentMonth)
    }
    val selectedMonthFoodOverageDeductionNet = remember(repoTick.intValue, currentYear, currentMonth) {
        repo.getMonthlyFoodSpendingBreakdown(currentYear, currentMonth).foodOverageNet
    }
    val annualNetPayAverages = remember(repoTick.intValue, payrollMonthKey) {
        repo.getAnnualNetPayAverages(payrollMonthKey.year)
    }
    val recentNetPayAverageAsOfDate = remember(payrollMonthKey, today) {
        val displayedMonth = YearMonth.of(payrollMonthKey.year, payrollMonthKey.month)
        if (displayedMonth == YearMonth.from(today)) {
            today
        } else {
            displayedMonth.atEndOfMonth()
        }
    }
    val recentNetPayAverage = remember(repoTick.intValue, payrollMonthKey, recentNetPayAverageAsOfDate) {
        repo.getRecentNetPayAverage(payrollMonthKey.year, payrollMonthKey.month, recentNetPayAverageAsOfDate)
    }
    val scheduler = remember { ShiftScheduler() }
    val foodReceiptReminderStatus = remember(repoTick.intValue, currentYear, currentMonth) {
        FoodReceiptReminderScheduler.selectedMonthStatus(context, repo, MonthKey(currentYear, currentMonth))
    }
    val bloodDonationReminderStatus = remember(repoTick.intValue, today) {
        BloodDonationReminderScheduler.currentStatus(context, repo)
    }

    // Otvoren dijalog preživljava rotaciju; DayEdit obnavlja zapis iz repo-a.
    var dialogState by rememberSaveable(
        stateSaver = dialogStateSaver { day -> repo.get(currentYear, currentMonth, day) }
    ) { mutableStateOf<DialogState>(DialogState.None) }
    val dismissDialog = { dialogState = DialogState.None }
    val completeOnboarding = {
        showOnboarding = false
        backup.markFirstLaunchDone()
        dialogState = DialogState.AutoFill
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(WindowInsets.safeDrawing.asPaddingValues())
    ) {
        BackgroundLayer()

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    start = UI.cardPadding.dp,
                    top = 16.dp,
                    end = UI.cardPadding.dp,
                    bottom = mainContentBottomPaddingDp().dp
                ),
            verticalArrangement = Arrangement.spacedBy(UI.largeSpacing.dp)
        ) {
            HeaderSection(
                month = currentMonth,
                year = currentYear,
                onPrev = {
                    if (currentMonth == 1) {
                        currentMonth = 12
                        currentYear -= 1
                    } else {
                        currentMonth -= 1
                    }
                },
                onNext = {
                    if (currentMonth == 12) {
                        currentMonth = 1
                        currentYear += 1
                    } else {
                        currentMonth += 1
                    }
                }
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(UI.defaultSpacing.dp)
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
            key(repoTick.intValue) {
                CalendarSection(
                    modifier = Modifier.weight(1f),
                    year = currentYear,
                    month = currentMonth,
                    today = today,
                    repo = repo,
                    onDayClick = { day, record ->
                        dialogState = DialogState.DayEdit(day, record)
                    }
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
                SalaryCard(month = currentMonth, nextMonthReceipt = nextMonthReceipt)
                TotalCard(
                    receipt = monthlyReceipt,
                    onClick = { dialogState = DialogState.PayDetails }
                )
            }
        }

        BottomBar(
            modifier = Modifier.align(Alignment.BottomCenter),
            onAutoFill = { dialogState = DialogState.AutoFill },
            onSickVacation = { dialogState = DialogState.SickVacation },
            onBasePay = { dialogState = DialogState.BasePay },
            onHolidays = { dialogState = DialogState.BloodDonation },
            onOptions = { dialogState = DialogState.Options },
            onDeleteMonth = { dialogState = DialogState.DeleteMonth }
        )
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
            workHoursFoodOverageDeductionNet = selectedMonthFoodOverageDeductionNet,
            monthlyReceipt = monthlyReceipt,
            monthlyFinalPaymentBreakdown = monthlyFinalPaymentBreakdown,
            overtimeNetImpact = overtimeNetImpact,
            payDetails = payDetails,
            annualNetPayAverages = annualNetPayAverages,
            recentNetPayAverage = recentNetPayAverage,
            foodReceiptReminderEnabled = repo.foodReceiptReminderEnabled,
            foodReceiptReminderStatus = foodReceiptReminderStatus,
            bloodDonationReminderStatus = bloodDonationReminderStatus,
            onMonthlyExportClick = launchSelectedMonthExport,
            onFoodReceiptReminderToggle = { enabled ->
                handleFoodReceiptReminderToggle(
                    enabled = enabled,
                    context = context,
                    repo = repo,
                    requestNotificationPermission = {
                        foodReceiptNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                )
            },
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
            onNotificationAccessClick = {
                runCatching { context.startActivity(notificationListenerSettingsIntent()) }
            },
            onDialogChange = { state -> dialogState = state }
        ),
        cloudBackup = CloudBackupDialogData(
            signInState = signInState,
            syncState = syncState,
            lastSyncTime = lastSyncTime,
            onSignIn = { signInLauncher.launch(backup.getSignInIntent()) },
            onBackup = {
                scope.launch {
                    // Verzionisani backup: nova verzija u oblaku (spaja sa prethodnom
                    // arhivom radi pune istorije), uz detekciju anomalija i retenciju.
                    val outcome = backup.backupVersioned(appVersion = BuildConfig.VERSION_NAME) { existing ->
                        repo.buildCloudBackup(existing)
                    }
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, backupResultMessage(outcome), Toast.LENGTH_LONG).show()
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
                    // Sigurnosna kopija trenutnog stanja pre vraćanja — i restore je
                    // poništiv. Ako kopija ne uspe, vraćanje se prekida (UI obećava
                    // da je poništivo, pa ne sme da nastavi bez kopije).
                    val safetyOutcome = backup.backupVersioned(appVersion = BuildConfig.VERSION_NAME) { existing ->
                        repo.buildCloudBackup(existing)
                    }
                    val message = if (safetyOutcome is BackupOutcome.Failure) {
                        "Vraćanje otkazano — sigurnosna kopija nije uspela: ${safetyOutcome.message}"
                    } else {
                        restoreVersionMessage(backup.restoreVersion(version.fileId), repo)
                    }
                    withContext(Dispatchers.Main) {
                        restoringVersion = false
                        showBackupVersionsDialog = false
                        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
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
                    val safetyOutcome = backup.backupVersioned(appVersion = BuildConfig.VERSION_NAME) { existing ->
                        repo.buildCloudBackup(existing)
                    }
                    val message = if (safetyOutcome is BackupOutcome.Failure) {
                        "Vraćanje otkazano — sigurnosna kopija nije uspela: ${safetyOutcome.message}"
                    } else {
                        restoreLatestMessage(backup.restoreLatest(), repo)
                    }
                    withContext(Dispatchers.Main) {
                        restoringLatest = false
                        showRestoreLatestConfirm = false
                        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                    }
                }
            },
            onDismiss = { if (!restoringLatest) showRestoreLatestConfirm = false }
        )
    }

    if (showOnboarding) {
        OnboardingDialog(
            step = onboardingStep,
            year = currentYear,
            month = currentMonth,
            repo = repo,
            onStepChange = { step -> onboardingStep = step },
            onComplete = completeOnboarding
        )
    }

    monthlyTaxUpdateMessage?.let { message ->
        MonthlyTaxUpdateCompletedDialog(
            message = message,
            onDismiss = { monthlyTaxUpdateMessage = null }
        )
    }

}

internal fun mainContentBottomPaddingDp(): Int {
    return UI.bottomBarHeight + UI.largeSpacing
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

/** Uvezi najnoviji backup i vrati korisničku poruku. */
internal fun restoreLatestMessage(json: String?, repo: ShiftRepository): String {
    if (json == null) return "Nema backup-a."
    return if (repo.importFromJson(json)) "Restore uspešan." else "Restore neuspešan."
}

/** Uvezi izabranu verziju backup-a i vrati korisničku poruku. */
internal fun restoreVersionMessage(json: String?, repo: ShiftRepository): String {
    if (json == null) return "Vraćanje neuspešno."
    return if (repo.importFromJson(json)) "Vraćeno na izabranu verziju." else "Vraćanje neuspešno."
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
        val output = context.contentResolver.openOutputStream(uri)
            ?: error("Nije moguće otvoriti fajl.")
        output.use { stream ->
            stream.write(json.toByteArray(Charsets.UTF_8))
        }
    }
}

@Composable
private fun rememberMonthlyWorkAndPayExportLauncher(
    repo: ShiftRepository,
    currentYear: Int,
    currentMonth: Int
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
            scope = scope
        )
    }
    return {
        val target = MonthKey(currentYear, currentMonth)
        pendingMonthlyExport = target
        monthlyExportLauncher.launch(monthlyWorkAndPayExportFileName(target.year, target.month))
    }
}

private fun handleMonthlyExportDocumentResult(
    uri: Uri?,
    target: MonthKey?,
    context: Context,
    repo: ShiftRepository,
    scope: kotlinx.coroutines.CoroutineScope
) {
    if (uri == null) {
        Toast.makeText(context, "Export otkazan.", Toast.LENGTH_SHORT).show()
        return
    }
    if (target == null) {
        Toast.makeText(context, "Export nije uspeo: nedostaje izabrani mesec.", Toast.LENGTH_LONG).show()
        return
    }
    scope.launch {
        val result = runCatching {
            saveSelectedMonthWorkAndPayExport(context, repo, target, uri)
        }
        Toast.makeText(context, monthlyExportResultMessage(result), Toast.LENGTH_LONG).show()
    }
}

private fun monthlyExportResultMessage(result: Result<Unit>): String {
    return result.fold(
        onSuccess = { "Export sačuvan." },
        onFailure = { error -> "Export nije uspeo: ${error.message ?: "nepoznata greška"}" }
    )
}

private fun handleFoodReceiptReminderToggle(
    enabled: Boolean,
    context: Context,
    repo: ShiftRepository,
    requestNotificationPermission: () -> Unit
) {
    if (!enabled) {
        repo.foodReceiptReminderEnabled = false
        FoodReceiptReminderScheduler.cancel(context)
        return
    }
    if (FoodReceiptReminderScheduler.notificationPermissionRequired(context)) {
        requestNotificationPermission()
        return
    }
    enableFoodReceiptReminder(context, repo)
}

private fun enableFoodReceiptReminder(context: Context, repo: ShiftRepository) {
    repo.foodReceiptReminderEnabled = true
    FoodReceiptReminderScheduler.reschedule(context, repo)
    FoodReceiptReminderScheduler.exactAlarmSettingsIntent(context)?.let { intent ->
        Toast.makeText(
            context,
            "Android možda neće poslati podsetnik baš tačno dok ne dozvoliš tačne alarme.",
            Toast.LENGTH_LONG
        ).show()
        runCatching { context.startActivity(intent) }
    }
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

private fun handleBloodDonationPermissionResult(granted: Boolean, context: Context, repo: ShiftRepository) {
    if (granted) {
        BloodDonationReminderScheduler.reschedule(context, repo)
    } else {
        Toast.makeText(context, "Dozvola za obaveštenja nije uključena.", Toast.LENGTH_LONG).show()
    }
}

@Composable
private fun MonthlyTaxUpdateEffect(
    showOnboarding: Boolean,
    today: LocalDate,
    repo: ShiftRepository,
    taxParameterUpdater: TaxParameterUpdater,
    taxParameterCache: TaxParameterCache,
    monthlyTaxUpdateStore: MonthlyTaxUpdateStore,
    onUpdated: (String) -> Unit
) {
    LaunchedEffect(showOnboarding, today.year, today.monthValue) {
        if (showOnboarding) return@LaunchedEffect

        val updateMonth = YearMonth.from(today)
        if (!monthlyTaxUpdateStore.shouldRun(updateMonth)) return@LaunchedEffect

        runCatching {
            fetchAndApplyLatestTaxParameters(repo, taxParameterUpdater, today, taxParameterCache)
        }.onSuccess { result ->
            monthlyTaxUpdateStore.markProcessed(updateMonth)
            onUpdated(taxUpdateResultMessage(result))
        }
    }
}

@Composable
private fun MonthlyTaxUpdateCompletedDialog(
    message: String,
    onDismiss: () -> Unit
) {
    PopupDialog(
        title = "Ažuriranje završeno",
        onDismiss = onDismiss,
        widthRatio = 0.86f,
        scrollable = false,
        accent = THEME.success
    ) {
        PopupCard(bgColor = THEME.bgCardElevated, borderColor = THEME.success) {
            SectionLabel(text = "Poreski parametri su ažurirani")
            Text(
                text = message,
                color = THEME.textSecondary,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis
            )
            GlowButton(
                text = "U redu",
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                bgColor = THEME.success,
                textColor = THEME.textDark,
                textStyle = MaterialTheme.typography.labelLarge,
                onClick = onDismiss
            )
        }
    }
}
