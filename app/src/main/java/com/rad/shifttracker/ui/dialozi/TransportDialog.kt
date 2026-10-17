package com.rad.shifttracker.ui.dialozi

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.style.TextAlign
import com.rad.shifttracker.osnova.CONFIG
import com.rad.shifttracker.osnova.formatMoney2
import com.rad.shifttracker.ui.CenteredDialog
import com.rad.shifttracker.ui.extendedColors

/**
 * Prevoz po danu za prikazan mesec rada ([daily] iz obračuna prevoza tog meseca, inače najnoviji),
 * samo prikaz (stavka „Prevoz“ postoji tek kad je učitan).
 */
@Composable
internal fun TransportDialog(daily: Double, onDismiss: () -> Unit) {
    CenteredDialog(title = "Prevoz", onDismiss = onDismiss) {
        Text(
            text = "${formatMoney2(daily)} ${CONFIG.pay.currency}",
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.headlineMedium
        )
        Text(
            text = TRANSPORT_DIALOG_SOURCE_TEXT,
            color = MaterialTheme.extendedColors.textSecondary,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center
        )
    }
}

internal const val TRANSPORT_DIALOG_SOURCE_TEXT = "Po radnom danu, iz obračuna prevoza"
