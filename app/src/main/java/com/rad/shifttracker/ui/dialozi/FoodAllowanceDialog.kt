package com.rad.shifttracker.ui.dialozi

import java.math.BigDecimal
import java.math.RoundingMode
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import com.rad.shifttracker.smene.monthYearText
import com.rad.shifttracker.ui.DataActions
import com.rad.shifttracker.ui.GlowButton
import com.rad.shifttracker.ui.RoundedButton
import com.rad.shifttracker.ui.SectionLabel
import com.rad.shifttracker.ui.Spacing
import com.rad.shifttracker.ui.extendedColors
import com.rad.shifttracker.ui.rememberRepoValue
import com.rad.shifttracker.uvoz.MealPriceFromPayslips
import com.rad.shifttracker.uvoz.mealPriceFindings
import com.rad.shifttracker.uvoz.mealPriceSourceFor

/**
 * Topli obrok: cena po obroku pročitana sa platnih lista, bez ručnog unosa. Cena se vidi samo
 * na listi meseca u kom je pojedeno više nego što pripada; bez takve liste nudi se čitanje svih
 * lista iz Gmail-a („Proveri sve po listama“), posle kog se prozor osveži.
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
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "${money(price)} ${CONFIG.pay.currency}",
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.headlineMedium
                )
                Text(
                    text = "po obroku, ${monthYearText(selectedMonth)}",
                    color = MaterialTheme.extendedColors.textSecondary,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            Text(
                text = source?.let { sourceText(it, selectedMonth) } ?: "Cena nije pročitana sa liste — važi ugrađena cena.",
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center
            )

            Column(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {
                findings.forEach { period -> PeriodSection(period) }
                if (findings.isEmpty()) {
                    Text(
                        text = "Cena se vidi samo na listi meseca u kom je pojedeno više nego što pripada " +
                            "(tada je topli obrok pun iznos, a višak se skida sa plate).",
                        color = MaterialTheme.extendedColors.textSecondary,
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center
                    )
                }
            }

            if (findings.isEmpty()) {
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

@Composable
private fun PeriodSection(period: MealPriceFromPayslips) {
    val range = if (period.from == period.until) monthText(period.from) else "${monthText(period.from)} – ${monthText(period.until)}"
    SectionLabel(text = "${money(period.price.toDouble())} ${CONFIG.pay.currency} · $range")
    period.months.forEach { month ->
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "${monthText(month.month)}: ${period.mealCount(month)} × ${period.price}",
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = "višak ${money(month.eatenNet - month.allowanceNet)}",
                color = MaterialTheme.extendedColors.textSecondary,
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

private fun sourceText(period: MealPriceFromPayslips, month: MonthKey): String {
    val lists = period.months.size
    val word = if (lists == 1) "listi" else "listama"
    val base = "Pročitano sa liste: u $lists $word od ${monthText(period.from)} do ${monthText(period.until)} " +
        "pojedeno je više nego što pripada, pa je topli obrok tamo pun iznos (obroci × cena)."
    return if (month > period.until) "$base Posle ${monthText(period.until)} nije bilo prekoračenja, pa važi ista cena." else base
}

private fun monthText(month: MonthKey): String = "%02d/%d".format(month.month, month.year)

private fun money(value: Double): String =
    formatSerbianDecimal(BigDecimal.valueOf(value).setScale(0, RoundingMode.HALF_UP))
