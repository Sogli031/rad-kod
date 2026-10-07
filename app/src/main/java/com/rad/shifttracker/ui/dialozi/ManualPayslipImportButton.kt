package com.rad.shifttracker.ui.dialozi

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import com.rad.shifttracker.repo.ShiftRepository
import com.rad.shifttracker.ui.FormErrorText
import com.rad.shifttracker.ui.extendedColors
import com.rad.shifttracker.osnova.UI
import com.rad.shifttracker.ui.RoundedButton
import com.rad.shifttracker.uvoz.MANUAL_PAYSLIP_IMPORT_LABEL
import com.rad.shifttracker.listici.PayslipParseResult
import com.rad.shifttracker.uvoz.importManualPayslip
import com.rad.shifttracker.listici.readPayslip
import com.rad.shifttracker.uvoz.rememberTransportWorkMonth

/** Opcije: dugme koje otvara sistemski birač PDF-a (bez dozvola za skladište) i poruka o ishodu. */
@Composable
internal fun ManualPayslipImportButton(repo: ShiftRepository) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var isReading by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<Pair<String, Boolean>?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        isReading = true
        message = null
        scope.launch {
            val result = withContext(Dispatchers.IO) { readPayslip(context, uri) }
            message = withContext(Dispatchers.Default) { importManualPayslip(repo, result) }
            (result as? PayslipParseResult.SideRun)?.let { rememberTransportWorkMonth(context, listOf(it)) }
            isReading = false
        }
    }

    RoundedButton(
        text = if (isReading) "Čitam listu..." else MANUAL_PAYSLIP_IMPORT_LABEL,
        modifier = Modifier
            .fillMaxWidth()
            .height(UI.buttonHeight.dp),
        bgColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        textColor = MaterialTheme.colorScheme.onSurface,
        borderColor = MaterialTheme.colorScheme.outlineVariant,
        textStyle = MaterialTheme.typography.labelLarge,
        onClick = { if (!isReading) picker.launch(arrayOf("application/pdf")) }
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
