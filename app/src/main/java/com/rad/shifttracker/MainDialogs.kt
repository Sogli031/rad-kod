package com.rad.shifttracker

import androidx.compose.runtime.Composable
import com.rad.shifttracker.data.backup.SignInState
import com.rad.shifttracker.data.backup.SyncState
import java.time.LocalDate

/** Gmail pri ulasku: na prvom pokretanju posle „Popuni smene“ (tada se uvod beleži kao završen), posle ažuriranja samo provera. */
@Composable
private fun GmailStartDialog(state: DialogState, data: MainDialogData, onDismiss: () -> Unit) {
    val update = state == DialogState.GmailUpdate
    GmailOnboardingDialog(
        repo = data.repo,
        mode = if (update) GmailImportMode.UPDATE else GmailImportMode.ONBOARDING,
        onFinished = {
            if (!update) data.onFirstLaunchDone()
            onDismiss()
        }
    )
}

/** „Popuni smene“; na prvom pokretanju posle njega (popunjeno ili ne) ide Gmail sa listama. */
@Composable
private fun AutoFillStartDialog(state: DialogState, data: MainDialogData, onDismiss: () -> Unit) {
    val onboarding = state == DialogState.OnboardingAutoFill
    AutoFillDialog(
        today = data.today,
        scheduler = data.scheduler,
        repo = data.repo,
        onDismiss = { if (onboarding) data.onDialogChange(DialogState.GmailOnboarding) else onDismiss() }
    )
}

@Composable
internal fun MainDialogs(
    dialogState: DialogState,
    data: MainDialogData,
    cloudBackup: CloudBackupDialogData,
    onDismiss: () -> Unit
) {
    // Renderovanje aktivnog dijaloga koristeći sealed class
    when (val state = dialogState) {
        DialogState.None -> { /* Nema aktivnog dijaloga */ }

        is DialogState.DayEdit -> {
            DayEditDialog(
                year = data.currentYear,
                month = data.currentMonth,
                day = state.day,
                record = state.record,
                repo = data.repo,
                onDismiss = onDismiss
            )
        }

        DialogState.AutoFill,
        DialogState.OnboardingAutoFill -> AutoFillStartDialog(state, data, onDismiss)

        DialogState.GmailOnboarding,
        DialogState.GmailUpdate -> GmailStartDialog(state, data, onDismiss)

        DialogState.SickVacation -> {
            SickVacationDialog(
                initialYear = data.currentYear,
                initialMonth = data.currentMonth,
                scheduler = data.scheduler,
                repo = data.repo,
                onDismiss = onDismiss
            )
        }

        DialogState.BasePay -> {
            PayslipReaderDialog(
                repo = data.repo,
                month = MonthKey(data.currentYear, data.currentMonth),
                repoTick = data.repoTick,
                onDismiss = onDismiss
            )
        }

        DialogState.PayDetails,
        DialogState.SalaryDetails,
        DialogState.WorkHours -> MainPayrollDialog(state, data, onDismiss)

        DialogState.Food -> {
            FoodAllowanceDialog(
                year = data.currentYear,
                month = data.currentMonth,
                repo = data.repo,
                onDismiss = onDismiss
            )
        }

        DialogState.OvertimeNetImpact -> {
            OvertimeNetImpactDialog(
                impact = data.overtimeNetImpact,
                onDismiss = onDismiss
            )
        }

        DialogState.Minuli -> {
            MinuliRadDialog(
                year = data.currentYear,
                month = data.currentMonth,
                repo = data.repo,
                onDismiss = onDismiss
            )
        }

        DialogState.Options -> {
            MainOptionsDialog(data = data, onDismiss = onDismiss)
        }

        DialogState.BloodDonation -> {
            MainBloodDonationDialog(data = data, onDismiss = onDismiss)
        }

        DialogState.AdvancePayment -> {
            AdvancePaymentDialog(
                year = data.currentYear,
                month = data.currentMonth,
                repo = data.repo,
                onDismiss = onDismiss
            )
        }

        DialogState.CloudBackup -> {
            CloudBackupDialog(
                signInState = cloudBackup.signInState,
                syncState = cloudBackup.syncState,
                lastSyncTime = cloudBackup.lastSyncTime,
                onSignIn = cloudBackup.onSignIn,
                onBackup = cloudBackup.onBackup,
                onRestore = cloudBackup.onRestore,
                onShowVersions = cloudBackup.onShowVersions,
                onSignOut = cloudBackup.onSignOut,
                onDismiss = onDismiss
            )
        }

        DialogState.InitialCompBalance -> {
            InitialCompBalanceDialog(
                repo = data.repo,
                onDismiss = onDismiss
            )
        }

        DialogState.DeleteMonth -> {
            DeleteMonthDialog(
                year = data.currentYear,
                month = data.currentMonth,
                repo = data.repo,
                onDismiss = onDismiss
            )
        }

        DialogState.VacationSettings -> {
            VacationBalanceDialog(
                year = data.currentYear,
                today = data.vacationAsOfDate,
                repo = data.repo,
                onDismiss = onDismiss
            )
        }

        DialogState.Holidays -> {
            HolidayManagementDialog(
                year = data.currentYear,
                repo = data.repo,
                refreshKey = data.repoTick,
                onDismiss = onDismiss
            )
        }
    }
}

@Composable
private fun MainPayrollDialog(
    state: DialogState,
    data: MainDialogData,
    onDismiss: () -> Unit
) {
    when (state) {
        DialogState.PayDetails -> {
            PayDetailsDialog(
                summary = data.summary,
                payBreakdown = data.payBreakdown,
                monthlyReceipt = data.monthlyReceipt,
                monthlyFinalPaymentBreakdown = data.monthlyFinalPaymentBreakdown,
                payDetails = data.payDetails,
                annualNetPayAverages = data.annualNetPayAverages,
                recentNetPayAverage = data.recentNetPayAverage,
                repo = data.repo,
                onDismiss = onDismiss
            )
        }

        DialogState.SalaryDetails -> {
            PayDetailsDialog(
                summary = data.workHoursSummary,
                payBreakdown = data.workHoursPayBreakdown,
                monthlyReceipt = data.salaryDetailsReceipt,
                monthlyFinalPaymentBreakdown = data.salaryDetailsFinalPaymentBreakdown,
                payDetails = data.workHoursPayDetails,
                annualNetPayAverages = data.salaryDetailsAnnualNetPayAverages,
                recentNetPayAverage = data.salaryDetailsRecentNetPayAverage,
                repo = data.repo,
                onDismiss = onDismiss
            )
        }

        DialogState.WorkHours -> {
            WorkHoursDialog(
                summary = data.workHoursSummary,
                payBreakdown = data.workHoursPayBreakdown,
                month = data.workHoursMonth,
                payDetails = data.workHoursPayDetails,
                deficitNetLoss = data.workHoursOvertimeNetImpact.deficitNetLoss,
                seniorityConfigured = data.repo.isSeniorityConfiguredValue,
                onDismiss = onDismiss
            )
        }

        else -> Unit
    }
}

@Composable
private fun MainOptionsDialog(
    data: MainDialogData,
    onDismiss: () -> Unit
) {
    OptionsDialog(
        repo = data.repo,
        onCloudClick = { data.onDialogChange(DialogState.CloudBackup) },
        onMonthlyExportClick = {
            data.onMonthlyExportClick()
            data.onDialogChange(DialogState.None)
        },
        onDebugExportClick = {
            data.onDebugExportClick()
            data.onDialogChange(DialogState.None)
        },
        onHolidaysClick = { data.onDialogChange(DialogState.Holidays) },
        onDismiss = onDismiss
    )
}

@Composable
private fun MainBloodDonationDialog(
    data: MainDialogData,
    onDismiss: () -> Unit
) {
    BloodDonationDialog(
        repo = data.repo,
        selectedYear = data.currentYear,
        selectedMonth = data.currentMonth,
        today = data.today,
        status = data.bloodDonationReminderStatus,
        onDateSaved = data.onBloodDonationDateSaved,
        onHistoryChanged = data.onBloodDonationHistoryChanged,
        onDismiss = onDismiss
    )
}

internal data class MainDialogData(
    val currentYear: Int,
    val currentMonth: Int,
    val today: LocalDate,
    val vacationAsOfDate: LocalDate,
    val scheduler: ShiftScheduler,
    val repo: ShiftRepository,
    val repoTick: Int,
    val summary: MonthSummary,
    val payBreakdown: MonthlyNetPayBreakdown,
    val workHoursSummary: MonthSummary,
    val workHoursPayBreakdown: MonthlyNetPayBreakdown,
    val workHoursMonth: MonthKey,
    val workHoursPayDetails: MonthlyPayDetails,
    val workHoursOvertimeNetImpact: MonthlyOvertimeNetImpact,
    val salaryDetailsReceipt: MonthlyReceipt,
    val salaryDetailsFinalPaymentBreakdown: MonthlyFinalPaymentBreakdown,
    val salaryDetailsAnnualNetPayAverages: AnnualNetPayAverages,
    val salaryDetailsRecentNetPayAverage: RecentNetPayAverage,
    val monthlyReceipt: MonthlyReceipt,
    val monthlyFinalPaymentBreakdown: MonthlyFinalPaymentBreakdown,
    val overtimeNetImpact: MonthlyOvertimeNetImpact,
    val payDetails: MonthlyPayDetails,
    val annualNetPayAverages: AnnualNetPayAverages,
    val recentNetPayAverage: RecentNetPayAverage,
    val bloodDonationReminderStatus: BloodDonationReminderScheduleStatus,
    val onMonthlyExportClick: () -> Unit,
    val onDebugExportClick: () -> Unit,
    val onBloodDonationDateSaved: () -> Unit,
    val onBloodDonationHistoryChanged: () -> Unit,
    val onDialogChange: (DialogState) -> Unit,
    /** Uvod pri prvom pokretanju je prošao (posle Gmail koraka). */
    val onFirstLaunchDone: () -> Unit = {}
)

internal data class CloudBackupDialogData(
    val signInState: SignInState,
    val syncState: SyncState,
    val lastSyncTime: Long?,
    val onSignIn: () -> Unit,
    val onBackup: () -> Unit,
    val onRestore: () -> Unit,
    val onShowVersions: () -> Unit,
    val onSignOut: () -> Unit
)
