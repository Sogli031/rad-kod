package com.rad.shifttracker

import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.foundation.layout.Arrangement
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

@Composable
fun DeleteMonthDialog(
    year: Int,
    month: Int,
    repo: ShiftRepository,
    onDismiss: () -> Unit
) {
    var deleteAll by remember { mutableStateOf(false) }

    PopupDialog(
        title = "Potvrda brisanja",
        onDismiss = onDismiss,
        scrollable = true
    ) {
        PopupCard(
            spacing = UI.largeSpacing.dp
        ) {
            AutoSizeText(
                text = "!",
                color = THEME.danger,
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
                color = THEME.textPrimary,
                style = MaterialTheme.typography.bodyMedium.copy(textAlign = TextAlign.Center),
                modifier = Modifier.fillMaxWidth(),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }

        PopupCard {
            SectionLabel(text = "Šta obrisati?")
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(UI.buttonHeight.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                RoundedButton(
                    text = "Ovaj mesec",
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    bgColor = if (!deleteAll) THEME.danger else THEME.bgCardElevated,
                    textColor = THEME.textPrimary,
                    textStyle = MaterialTheme.typography.labelLarge,
                    bold = true,
                    onClick = { deleteAll = false }
                )
                RoundedButton(
                    text = "Svi meseci",
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    bgColor = if (deleteAll) THEME.danger else THEME.bgCardElevated,
                    textColor = THEME.textPrimary,
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
            horizontalArrangement = Arrangement.spacedBy(UI.largeSpacing.dp)
        ) {
            RoundedButton(
                text = "Otkaži",
                modifier = Modifier.weight(1f).fillMaxHeight(),
                bgColor = THEME.bgCardElevated,
                textColor = THEME.textPrimary,
                borderColor = THEME.glassBorder,
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
                        repo.deleteAllData()
                    } else {
                        repo.deleteMonth(year, month)
                    }
                    onDismiss()
                }
            )
        }
    }
}
