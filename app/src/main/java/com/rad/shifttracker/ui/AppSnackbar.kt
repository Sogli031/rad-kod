package com.rad.shifttracker.ui

import android.widget.Toast
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch

/**
 * Zajednički M3 [SnackbarHostState] aplikacije. Glavni ekran ga prikazuje iznad donje trake, a
 * [PopupDialog] u svom prozoru (dijalog je poseban prozor pa bi poruka ispod njega bila skrivena).
 */
val LocalAppSnackbar = staticCompositionLocalOf<SnackbarHostState?> { null }

/**
 * Funkcija za kratku poruku korisniku (umesto `Toast`-a). Radi iz bilo koje korutine; ako
 * domaćin poruka ne postoji (npr. van glavnog ekrana), vraća se na sistemski Toast.
 */
@Composable
fun rememberShowMessage(): (String) -> Unit {
    val host = LocalAppSnackbar.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    return remember(host, context, scope) {
        { message: String ->
            if (host == null) {
                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            } else {
                scope.launch {
                    host.currentSnackbarData?.dismiss()
                    host.showSnackbar(message, withDismissAction = true, duration = SnackbarDuration.Long)
                }
            }
        }
    }
}
