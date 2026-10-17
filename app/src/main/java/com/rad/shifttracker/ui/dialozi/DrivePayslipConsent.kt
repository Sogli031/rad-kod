package com.rad.shifttracker.ui.dialozi

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.rad.shifttracker.listici.GmailAuthorizeResult
import com.rad.shifttracker.listici.authorizeDrivePayslipUpload
import com.rad.shifttracker.listici.hasGmailPayslipAccess
import com.rad.shifttracker.listici.markDrivePayslipConsentAsked
import com.rad.shifttracker.listici.uploadPayslipsToDrive
import com.rad.shifttracker.listici.wasDrivePayslipConsentAsked

/**
 * Gmail povezan pre nego što su liste išle na Drive: Google dozvola za Drive se jednom otvori pri
 * ulasku, pa liste odmah odu na Drive. Novo povezivanje Gmail-a već traži i Drive, pa ovde nema ničega.
 */
@Composable
internal fun DrivePayslipConsentOnce() {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        scope.launch(Dispatchers.IO) { uploadPayslipsToDrive(context) }
    }
    LaunchedEffect(Unit) {
        if (!hasGmailPayslipAccess(context) || wasDrivePayslipConsentAsked(context)) return@LaunchedEffect
        val outcome = withContext(Dispatchers.IO) { authorizeDrivePayslipUpload(context) }
        if (outcome is GmailAuthorizeResult.NeedsConsent) {
            markDrivePayslipConsentAsked(context)
            runCatching { consent.launch(outcome.intent) }
        } else if (outcome == GmailAuthorizeResult.Authorized) {
            markDrivePayslipConsentAsked(context)
        }
    }
}
