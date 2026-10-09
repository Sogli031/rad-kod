package com.rad.shifttracker.ui.dialozi

import java.math.BigDecimal
import java.math.RoundingMode
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.rad.shifttracker.listici.formatSerbianDecimal
import com.rad.shifttracker.osnova.CONFIG
import com.rad.shifttracker.repo.ShiftRepository
import com.rad.shifttracker.repo.getFoodPerDayForMonth
import com.rad.shifttracker.smene.MonthKey
import com.rad.shifttracker.ui.DataActions
import com.rad.shifttracker.ui.CenteredDialog
import com.rad.shifttracker.ui.FormErrorText
import com.rad.shifttracker.ui.ModernTextField
import com.rad.shifttracker.ui.GlowButton
import com.rad.shifttracker.ui.RoundedButton
import com.rad.shifttracker.ui.extendedColors
import com.rad.shifttracker.ui.rememberRepoValue
import com.rad.shifttracker.uvoz.mealPriceFindings
import com.rad.shifttracker.uvoz.mealPriceSourceFor

/**
 * Topli obrok: pouzdana cena sa platnih lista ([mealPriceFindings]) se samo prikazuje. Kad je
 * cena nepouzdana ili je nema, unosi se ručno (nepouzdana je predlog u polju); bez ijedne liste
 * sa cenom nudi se i čitanje svih lista iz Gmail-a, posle kog se prozor osveži.
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
    val found = mealPriceSourceFor(findings, selectedMonth)
    val reliable = found?.takeIf { it.reliable && it.price.toDouble() == price }
    // Najnovija pouzdana cena sa lista (findings su najnoviji prvi), kao oslonac pri ručnom unosu.
    val lastReliable = findings.firstOrNull { it.reliable }
    // Nepouzdana cena sa lista se samo predlaže u polju; upisuje je korisnik.
    var input by remember(selectedMonth, found?.price, price) {
        mutableStateOf(formatRateInput(found?.price?.toDouble() ?: price))
    }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    CenteredDialog(title = "Topli obrok", onDismiss = onDismiss) {
        if (reliable != null) {
            Text(
                text = "${money(price)} ${CONFIG.pay.currency}",
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.headlineMedium
            )
        } else {
            ModernTextField(
                value = input,
                onValueChange = {
                    input = filterDecimalInput(it)
                    errorMessage = null
                },
                hintText = "Iznos po obroku...",
                label = "Iznos po obroku (${CONFIG.pay.currency})",
                isError = errorMessage != null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth()
            )
            errorMessage?.let { FormErrorText(message = it) }
        }
        Text(
            text = when {
                reliable != null -> "Izračunato iz ${listsText(reliable.months.size)}"
                found != null -> "Moguće iz ${listsText(found.months.size)}, nije sigurno — proveri i sačuvaj"
                else -> "Nije pronađeno na listama"
            },
            color = MaterialTheme.extendedColors.textSecondary,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center
        )
        if (reliable == null && lastReliable != null) {
            Text(
                text = "Poslednja pouzdana cena: ${lastReliable.price} ${CONFIG.pay.currency} " +
                    "(${monthText(lastReliable.from)} – ${monthText(lastReliable.until)})",
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center
            )
        }
        if (reliable == null) {
            GlowButton(
                text = "Sačuvaj",
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
                onClick = {
                    val value = input.toDoubleOrNull()
                    if (value == null || value < 0) {
                        errorMessage = "Unesi ispravan iznos."
                        return@GlowButton
                    }
                    actions.setFoodPerDayFromMonth(year, month, value)
                    onDismiss()
                }
            )
        }
        if (found == null) {
            RoundedButton(
                text = "Skeniraj liste iz Gmail-a",
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                bgColor = MaterialTheme.colorScheme.surfaceContainer,
                textColor = MaterialTheme.colorScheme.onSurface,
                onClick = { scanning = true }
            )
        }
    }
}

/** „iz 1 liste“, „iz 3 liste“, „iz 5 listi“. */
private fun listsText(count: Int): String {
    val paucal = count % 10 in 1..4 && count % 100 !in 11..14
    return "$count ${if (paucal) "liste" else "listi"}"
}

private fun monthText(month: MonthKey): String = "%02d/%d".format(month.month, month.year)

private fun money(value: Double): String =
    formatSerbianDecimal(BigDecimal.valueOf(value).setScale(0, RoundingMode.HALF_UP))
