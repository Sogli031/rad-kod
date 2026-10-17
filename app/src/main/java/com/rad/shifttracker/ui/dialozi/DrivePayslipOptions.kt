package com.rad.shifttracker.ui.dialozi

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
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
import com.rad.shifttracker.listici.DRIVE_PAYSLIP_SWITCH_LABEL
import com.rad.shifttracker.listici.GmailAuthorizeResult
import com.rad.shifttracker.listici.authorizeDrivePayslipUpload
import com.rad.shifttracker.listici.drivePayslipUploadError
import com.rad.shifttracker.listici.isDrivePayslipUploadEnabled
import com.rad.shifttracker.listici.setDrivePayslipUploadEnabled
import com.rad.shifttracker.listici.uploadPayslipsToDrive
import com.rad.shifttracker.ui.FormErrorText
import com.rad.shifttracker.ui.LabeledSwitchRow

/**
 * Opcije → „Liste na Google Drive“: uključivanje traži Google dozvolu za Drive, pa odmah šalje
 * liste koje još nisu poslate; posle toga ih šalje pozadinska provera.
 */
@Composable
internal fun DrivePayslipUploadSwitch() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var enabled by remember { mutableStateOf(isDrivePayslipUploadEnabled(context)) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(drivePayslipUploadError(context)) }

    fun turnOn() {
        setDrivePayslipUploadEnabled(context, true)
        enabled = true
        scope.launch {
            withContext(Dispatchers.IO) { uploadPayslipsToDrive(context) }
            busy = false
            error = drivePayslipUploadError(context)
        }
    }

    lateinit var authorize: () -> Unit
    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            authorize()
        } else {
            busy = false
            error = "Dozvola za Google Drive nije data."
        }
    }
    authorize = {
        busy = true
        error = null
        scope.launch {
            when (val outcome = withContext(Dispatchers.IO) { authorizeDrivePayslipUpload(context) }) {
                GmailAuthorizeResult.Authorized -> turnOn()
                is GmailAuthorizeResult.NeedsConsent -> runCatching { consent.launch(outcome.intent) }
                    .onFailure { busy = false; error = "Ne mogu da otvorim Google dozvolu." }
                GmailAuthorizeResult.Failed -> {
                    busy = false
                    error = "Nema interneta ili nalog nije dostupan."
                }
            }
        }
    }

    LabeledSwitchRow(
        title = DRIVE_PAYSLIP_SWITCH_LABEL,
        checked = enabled,
        enabled = !busy,
        onCheckedChange = { on ->
            if (on) {
                authorize()
            } else {
                setDrivePayslipUploadEnabled(context, false)
                enabled = false
                error = null
            }
        }
    )
    error?.let { FormErrorText(message = it, style = MaterialTheme.typography.bodySmall) }
}
