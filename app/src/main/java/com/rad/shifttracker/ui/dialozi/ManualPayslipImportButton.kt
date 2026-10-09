package com.rad.shifttracker.ui.dialozi

import com.rad.shifttracker.ui.DataActions
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.rad.shifttracker.ui.FormErrorText
import com.rad.shifttracker.ui.extendedColors
import com.rad.shifttracker.osnova.UI
import com.rad.shifttracker.ui.RoundedButton
import com.rad.shifttracker.uvoz.MANUAL_PAYSLIP_IMPORT_LABEL
import com.rad.shifttracker.listici.PayslipParseResult
import com.rad.shifttracker.listici.readPayslip
import com.rad.shifttracker.uvoz.rememberTransportWorkMonth

/**
 * Opcije: dugme otvara sistemski birač (bez dozvola za skladište) u Downloads-u, gde se označi
 * više lista odjednom. Ceo folder Downloads se od Androida 11 ne može izabrati kao folder,
 * pa se biraju fajlovi; ne-liste se preskaču.
 */
@Composable
internal fun ManualPayslipImportButton(actions: DataActions) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var isReading by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<Pair<String, Boolean>?>(null) }

    val picker = rememberLauncherForActivityResult(OpenPdfsInDownloads()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        isReading = true
        message = null
        scope.launch {
            val results = withContext(Dispatchers.IO) { uris.map { readPayslip(context, it) } }
            message = withContext(Dispatchers.Default) { actions.importManualPayslips(results) }
            val sideRuns = results.filterIsInstance<PayslipParseResult.SideRun>()
            if (sideRuns.isNotEmpty()) rememberTransportWorkMonth(context, sideRuns)
            isReading = false
        }
    }

    RoundedButton(
        text = if (isReading) "Čitam liste..." else MANUAL_PAYSLIP_IMPORT_LABEL,
        modifier = Modifier
            .fillMaxWidth()
            .height(UI.buttonHeight.dp),
        bgColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        textColor = MaterialTheme.colorScheme.onSurface,
        borderColor = MaterialTheme.colorScheme.outlineVariant,
        textStyle = MaterialTheme.typography.labelLarge,
        onClick = { if (!isReading) picker.launch(Unit) }
    )
    message?.let { (text, isError) ->
        if (isError) {
            FormErrorText(message = text, style = MaterialTheme.typography.bodySmall, maxLines = 5)
        } else {
            // Bez ReaderText-a: spisak izmena ume da bude duži od 5 redova.
            Text(
                text = text,
                color = MaterialTheme.extendedColors.success,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
            )
        }
    }
}

/** Više PDF-ova odjednom, sa početkom u Downloads-u (ako birač to podržava). */
private class OpenPdfsInDownloads : ActivityResultContract<Unit, List<Uri>>() {
    private val multiple = ActivityResultContracts.OpenMultipleDocuments()

    override fun createIntent(context: Context, input: Unit): Intent =
        multiple.createIntent(context, arrayOf("application/pdf"))
            .putExtra(DocumentsContract.EXTRA_INITIAL_URI, DOWNLOADS_INITIAL_URI)

    override fun parseResult(resultCode: Int, intent: Intent?): List<Uri> = multiple.parseResult(resultCode, intent)
}

private val DOWNLOADS_INITIAL_URI: Uri =
    DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:Download")
