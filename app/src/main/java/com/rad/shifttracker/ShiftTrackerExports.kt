package com.rad.shifttracker

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
internal fun rememberMonthlyWorkAndPayExportLauncher(
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
internal fun rememberAllMonthsCalculationDebugExportLauncher(
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
