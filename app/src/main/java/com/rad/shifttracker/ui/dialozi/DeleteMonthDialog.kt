package com.rad.shifttracker.ui.dialozi

import com.rad.shifttracker.ui.DataActions
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.rad.shifttracker.ui.components.AutoSizeText
import com.rad.shifttracker.osnova.MONTH_NAMES
import com.rad.shifttracker.ui.CenteredDialog
import com.rad.shifttracker.ui.Spacing
import com.rad.shifttracker.osnova.UI
import com.rad.shifttracker.ui.GlowButton
import com.rad.shifttracker.ui.RoundedButton
import com.rad.shifttracker.ui.SectionLabel

@Composable
internal fun DeleteMonthDialog(
    year: Int,
    month: Int,
    actions: DataActions,
    onDismiss: () -> Unit
) {
    var deleteAll by remember { mutableStateOf(false) }

    CenteredDialog(
        title = "Potvrda brisanja",
        onDismiss = onDismiss,
        showClose = false
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            AutoSizeText(
                text = "!",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.displaySmall.copy(textAlign = TextAlign.Center),
                maxFontSize = MaterialTheme.typography.displaySmall.fontSize,
                minFontSize = MaterialTheme.typography.headlineMedium.fontSize,
                maxLines = 1,
                modifier = Modifier.fillMaxWidth().clearAndSetSemantics { },
            )
            Text(
                text = if (deleteAll) {
                    "Da li ste sigurni da želite da obrišete sve popunjene mesece?"
                } else {
                    "Da li ste sigurni da želite da obrišete sve podatke za ${MONTH_NAMES[month]}?"
                },
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyMedium.copy(textAlign = TextAlign.Center),
                modifier = Modifier.fillMaxWidth(),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            SectionLabel(text = "Šta obrisati?")
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(UI.buttonHeight.dp),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {
                RoundedButton(
                    text = "Ovaj mesec",
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    bgColor = if (!deleteAll) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.surfaceContainer,
                    textColor = MaterialTheme.colorScheme.onSurface,
                    textStyle = MaterialTheme.typography.labelLarge,
                    bold = true,
                    onClick = { deleteAll = false }
                )
                RoundedButton(
                    text = "Svi meseci",
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    bgColor = if (deleteAll) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.surfaceContainer,
                    textColor = MaterialTheme.colorScheme.onSurface,
                    textStyle = MaterialTheme.typography.labelLarge,
                    bold = true,
                    onClick = { deleteAll = true }
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(UI.saveButtonHeight.dp),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            RoundedButton(
                text = "Otkaži",
                modifier = Modifier.weight(1f).fillMaxHeight(),
                bgColor = MaterialTheme.colorScheme.surfaceContainer,
                textColor = MaterialTheme.colorScheme.onSurface,
                borderColor = MaterialTheme.colorScheme.outlineVariant,
                textStyle = MaterialTheme.typography.labelLarge,
                bold = true,
                onClick = onDismiss
            )
            GlowButton(
                text = "Obriši",
                modifier = Modifier.weight(1f).fillMaxHeight(),
                destructive = true,
                textStyle = MaterialTheme.typography.labelLarge,
                onClick = {
                    if (deleteAll) {
                        actions.deleteAllData()
                    } else {
                        actions.deleteMonth(year, month)
                    }
                    onDismiss()
                }
            )
        }
    }
}
