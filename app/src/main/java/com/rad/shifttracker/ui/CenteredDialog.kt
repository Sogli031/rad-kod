package com.rad.shifttracker.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog

/**
 * Mali prozor na sredini ekrana (za kratke preglede kao „Hrana“ i „Prekovremeno“): naslov,
 * sadržaj i „Zatvori“ (bez njega kad sadržaj ima svoja dugmad, npr. „Otkaži“ / „Obriši“).
 * Zatvara se i dodirom van prozora i nazad-gestom.
 */
@Composable
internal fun CenteredDialog(
    title: String,
    onDismiss: () -> Unit,
    showClose: Boolean = true,
    content: @Composable ColumnScope.() -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 640.dp)
                .clip(MaterialTheme.shapes.extraLarge)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .border(1.dp, MaterialTheme.colorScheme.primary, MaterialTheme.shapes.extraLarge)
                .padding(Spacing.xl),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.lg)
        ) {
            Text(
                text = title,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleLarge
            )
            content()
            if (showClose) RoundedButton(
                text = "Zatvori",
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                bgColor = MaterialTheme.colorScheme.surfaceContainer,
                textColor = MaterialTheme.colorScheme.onSurface,
                onClick = onDismiss
            )
        }
    }
}
