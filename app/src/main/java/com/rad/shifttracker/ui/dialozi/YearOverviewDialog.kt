package com.rad.shifttracker.ui.dialozi

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import java.time.YearMonth
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.rad.shifttracker.obracun.YearOverview
import com.rad.shifttracker.osnova.CONFIG
import com.rad.shifttracker.osnova.MONTH_NAMES
import com.rad.shifttracker.osnova.formatCurrency
import com.rad.shifttracker.repo.ShiftRepository
import com.rad.shifttracker.repo.getYearOverview
import com.rad.shifttracker.ui.CenteredDialog
import com.rad.shifttracker.ui.HeaderChevron
import com.rad.shifttracker.ui.Spacing
import com.rad.shifttracker.ui.bodyMediumEmphasized
import com.rad.shifttracker.ui.headlineSmallEmphasized
import com.rad.shifttracker.ui.extendedColors
import com.rad.shifttracker.ui.rememberRepoTick

/** „Pregled“: neto i sati za celu godinu, po mesecima, i poređenje sa prošlom godinom. */
@Composable
internal fun YearOverviewDialog(repo: ShiftRepository, today: YearMonth, initialYear: Int, onDismiss: () -> Unit) {
    CenteredDialog(title = YEAR_OVERVIEW_TITLE, onDismiss = onDismiss) {
        YearOverviewBody(repo = repo, today = today, initialYear = initialYear)
    }
}

/** Sadržaj prozora „Pregled“ (bez okvira prozora): izbor godine i pregled. */
@Composable
internal fun ColumnScope.YearOverviewBody(repo: ShiftRepository, today: YearMonth, initialYear: Int) {
    var year by rememberSaveable { mutableIntStateOf(initialYear) }
    val tick = rememberRepoTick(repo)
    val overview by produceState<YearOverview?>(null, repo, tick, year) {
        value = withContext(Dispatchers.Default) { repo.getYearOverview(year, today) }
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        HeaderChevron(icon = Icons.Filled.ChevronLeft, label = "Prethodna godina") { year-- }
        Text(text = "$year", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
        HeaderChevron(icon = Icons.Filled.ChevronRight, label = "Sledeća godina") { if (year < today.year) year++ }
    }
    val data = overview
    when {
        data == null || data.year != year -> Unit
        data.months.isEmpty() -> Text(
            text = YEAR_OVERVIEW_EMPTY,
            color = MaterialTheme.extendedColors.textSecondary,
            style = MaterialTheme.typography.bodyMedium
        )
        else -> Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(Spacing.md)
        ) {
            YearOverviewContent(data)
        }
    }
}

@Composable
private fun YearOverviewContent(data: YearOverview) {
    val scheme = MaterialTheme.colorScheme
    Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = "Neto", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.extendedColors.textSecondary)
        Text(
            text = money(data.totalNet),
            style = MaterialTheme.typography.headlineSmallEmphasized,
            color = scheme.primary
        )
        data.netDifference?.let { difference ->
            val previous = data.previousYearNet ?: 0.0
            val percent = if (previous > 0.0) " (${signed((difference / previous * 100).roundToInt().toDouble())}%)" else ""
            Text(
                text = "${signed(difference)} ${CONFIG.pay.currency}$percent prema ${data.year - 1}.",
                style = MaterialTheme.typography.bodySmall,
                color = if (difference >= 0.0) MaterialTheme.extendedColors.success else scheme.error,
                textAlign = TextAlign.Center
            )
        }
    }
    NetBarChart(data)
    HorizontalDivider(color = scheme.outlineVariant)
    data.averageNet?.let { OverviewRow("Prosek mesečno", money(it)) }
    data.bestMonth?.let { OverviewRow("Najbolji mesec", "${MONTH_NAMES[it.month]} · ${money(it.net ?: 0.0)}") }
    HorizontalDivider(color = scheme.outlineVariant)
    OverviewRow("Sati rada", "${data.workHours} h")
    OverviewRow("Prekovremeno", "${data.overtimeHours} h")
    OverviewRow("Noćni rad", "${data.nightHours} h")
    OverviewRow("Nedelja", "${data.sundayHours} h")
    OverviewRow("Bolovanje", days(data.sickDays))
    OverviewRow("Godišnji", days(data.vacationDays))
    HorizontalDivider(color = scheme.outlineVariant)
    data.months.forEach { month ->
        OverviewRow(MONTH_NAMES[month.month], month.net?.let(::money) ?: "—")
    }
}

/** Stubići neto plate po mesecima (jan–dec); mesec bez neta ostaje prazan. */
@Composable
private fun NetBarChart(data: YearOverview) {
    val barColor = MaterialTheme.colorScheme.primary
    val emptyColor = MaterialTheme.colorScheme.outlineVariant
    val labelColor = MaterialTheme.extendedColors.textSecondary
    val byMonth = data.months.associateBy { it.month }
    val max = data.months.maxOfOrNull { it.net ?: 0.0 }?.takeIf { it > 0.0 } ?: 1.0
    Column(modifier = Modifier.fillMaxWidth()) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(CHART_HEIGHT_DP.dp)
                .semantics { contentDescription = "Neto po mesecima" }
        ) {
            val slot = size.width / 12
            val barWidth = slot * BAR_WIDTH_RATIO
            for (month in 1..12) {
                val net = byMonth[month]?.net
                val left = slot * (month - 1) + (slot - barWidth) / 2
                val height = if (net != null) (size.height * (net / max)).toFloat().coerceAtLeast(2f) else 2f
                drawRoundRect(
                    color = if (net != null) barColor else emptyColor,
                    topLeft = Offset(left, size.height - height),
                    size = Size(barWidth, height),
                    cornerRadius = CornerRadius(barWidth / 4, barWidth / 4)
                )
            }
        }
        Row(modifier = Modifier.fillMaxWidth()) {
            for (month in 1..12) {
                Text(
                    text = MONTH_NAMES[month].take(1),
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (byMonth[month]?.net != null) labelColor else labelColor.copy(alpha = 0.5f)
                )
            }
        }
    }
}

@Composable
private fun OverviewRow(label: String, value: String, valueColor: Color = MaterialTheme.colorScheme.onSurface) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.extendedColors.textSecondary)
        Text(text = value, style = MaterialTheme.typography.bodyMediumEmphasized, color = valueColor)
    }
}

private fun money(value: Double) = "${formatCurrency(value)} ${CONFIG.pay.currency}"

private fun signed(value: Double): String = (if (value >= 0.0) "+" else "−") + formatCurrency(abs(value))

private fun days(value: Double): String {
    val rounded = (value * 2).roundToInt() / 2.0
    val text = if (rounded % 1.0 == 0.0) rounded.toInt().toString() else rounded.toString().replace('.', ',')
    return "$text d"
}

internal const val YEAR_OVERVIEW_TITLE = "Godišnji pregled"
private const val YEAR_OVERVIEW_EMPTY = "Nema upisanih meseci"
private const val CHART_HEIGHT_DP = 96
private const val BAR_WIDTH_RATIO = 0.6f
