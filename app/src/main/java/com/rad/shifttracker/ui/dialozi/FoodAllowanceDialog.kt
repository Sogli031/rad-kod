package com.rad.shifttracker.ui.dialozi

import java.math.BigDecimal
import java.math.RoundingMode
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.rad.shifttracker.listici.formatSerbianDecimal
import com.rad.shifttracker.osnova.CONFIG
import com.rad.shifttracker.repo.ShiftRepository
import com.rad.shifttracker.repo.getFoodPerDayForMonth
import com.rad.shifttracker.smene.MonthKey
import com.rad.shifttracker.ui.DataActions
import com.rad.shifttracker.ui.GlowButton
import com.rad.shifttracker.ui.RoundedButton
import com.rad.shifttracker.ui.Spacing
import com.rad.shifttracker.ui.extendedColors
import com.rad.shifttracker.ui.rememberRepoValue
import com.rad.shifttracker.uvoz.mealPriceFindings
import com.rad.shifttracker.uvoz.mealPriceSourceFor

/**
 * Topli obrok: cena po obroku izračunata sa platnih lista ([mealPriceFindings]), bez ručnog unosa.
 * Bez cene sa lista nudi se čitanje svih lista iz Gmail-a, posle kog se prozor osveži.
 */
@Composable
internal fun FoodAllowanceDialog(
    year: Int,
    month: Int,
    repo: ShiftRepository,
    actions: DataActions,
    onDismiss: () -> Unit
) {
    var scanning by remember { mutableStateOf(false) }
    if (scanning) {
        GmailOnboardingDialog(repo = repo, actions = actions, mode = GmailImportMode.RECHECK) { scanning = false }
        return
    }
    val selectedMonth = MonthKey(year, month)
    val price = rememberRepoValue(repo, year, month) { getFoodPerDayForMonth(year, month) }
    val findings = rememberRepoValue(repo) { mealPriceFindings(this) }
    val source = mealPriceSourceFor(findings, selectedMonth)
        ?.takeIf { it.price.toDouble() == price }

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
                text = "Topli obrok",
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleLarge
            )
            Text(
                text = "${money(price)} ${CONFIG.pay.currency}",
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.headlineMedium
            )
            Text(
                text = source?.let { "Izračunato iz ${listsText(it.months.size)}" } ?: "Nije pronađeno na listama",
                color = MaterialTheme.extendedColors.textSecondary,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center
            )
            if (source == null) {
                GlowButton(
                    text = "Skeniraj liste iz Gmail-a",
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp),
                    onClick = { scanning = true }
                )
            }
            RoundedButton(
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

/** „iz 1 liste“, „iz 3 liste“, „iz 5 listi“. */
private fun listsText(count: Int): String {
    val paucal = count % 10 in 1..4 && count % 100 !in 11..14
    return "$count ${if (paucal) "liste" else "listi"}"
}

private fun money(value: Double): String =
    formatSerbianDecimal(BigDecimal.valueOf(value).setScale(0, RoundingMode.HALF_UP))
