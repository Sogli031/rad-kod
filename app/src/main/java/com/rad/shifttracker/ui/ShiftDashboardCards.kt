package com.rad.shifttracker.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rad.shifttracker.ui.components.AppIcons
import com.rad.shifttracker.ui.components.AutoSizeText
import com.rad.shifttracker.osnova.UI
import com.rad.shifttracker.osnova.formatHours
import com.rad.shifttracker.osnova.formatNumber
import com.rad.shifttracker.krv.BLOOD_DONATION_BOTTOM_BAR_LABEL
import com.rad.shifttracker.obracun.MonthSummary

internal const val WORK_HOURS_CARD_LABEL = "Sati rada"
internal const val TOTAL_CARD_UNAVAILABLE_TEXT = "Nije moguće izračunati"

/**
 * Statistike u jednoj kartici, razdvojene tankim linijama. [transportAmount] je prevoz za rad u
 * mesecu (samo informativno); `null` kad korisnik nema prevoz — tada ostaju tri stavke.
 */
@Composable
internal fun StatsSection(
    summary: MonthSummary,
    onOvertimeClick: () -> Unit,
    onFoodClick: () -> Unit,
    transportAmount: Double? = null,
    onTransportClick: () -> Unit,
    onWorkHoursClick: () -> Unit
) {
    // Vrednosti odbroje do novog meseca; font se meri po ciljnoj vrednosti.
    val workHours = rememberCountingValue(summary.workHours)
    val overtimeHours = rememberCountingValue(summary.overtimeHours)
    val foodAllowance = rememberCountingValue(summary.foodAllowance)
    val shownTransport = rememberCountingValue(transportAmount ?: 0.0)
    val stats = listOfNotNull(
        // Dodir otvara obračun plate za ovaj mesec (ista tabela kao u prozoru plate).
        StatItem(
            WORK_HOURS_CARD_LABEL,
            formatHours(workHours),
            MaterialTheme.colorScheme.onSurface,
            onClick = onWorkHoursClick,
            measureKey = formatHours(summary.workHours)
        ),
        StatItem(
            "Prekovremeno",
            formatHours(overtimeHours),
            if (summary.overtimeHours > 0) MaterialTheme.extendedColors.weekendOt else MaterialTheme.colorScheme.onSurfaceVariant,
            onClick = onOvertimeClick,
            measureKey = formatHours(summary.overtimeHours)
        ),
        StatItem(
            "Hrana",
            formatNumber(foodAllowance),
            if (summary.foodAllowance > 0) MaterialTheme.extendedColors.success else MaterialTheme.colorScheme.onSurfaceVariant,
            onClick = onFoodClick,
            measureKey = formatNumber(summary.foodAllowance)
        ),
        transportAmount?.let { amount ->
            StatItem(
                "Prevoz",
                formatNumber(shownTransport),
                if (amount > 0) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                onClick = onTransportClick,
                measureKey = formatNumber(amount)
            )
        }
    )

    // Pri velikom fontu stubci ne staju: kartica se lomi po dva u redu.
    val perRow = if (LocalDensity.current.fontScale > WIDE_LAYOUT_FONT_SCALE) 2 else stats.size
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Column {
            stats.chunked(perRow).forEachIndexed { rowIndex, rowStats ->
                if (rowIndex > 0) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = CARD_BORDER_ALPHA))
                }
                Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Max)) {
                    rowStats.forEachIndexed { index, stat ->
                        if (index > 0) {
                            Box(
                                modifier = Modifier
                                    .fillMaxHeight()
                                    .width(1.dp)
                                    .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = CARD_BORDER_ALPHA))
                            )
                        }
                        val clickModifier = stat.onClick?.let { Modifier.radClickable(onClick = it) } ?: Modifier
                        StatColumn(
                            label = stat.label,
                            value = stat.value,
                            color = stat.color,
                            measureKey = stat.measureKey,
                            // U četiri stubca „PREKOVREMENO“ ne staje: etiketa se smanjuje umesto da se seče.
                            labelMinFontSize = STAT_LABEL_MIN_SP.sp,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .then(clickModifier)
                        )
                    }
                }
            }
        }
    }
}

private const val STAT_LABEL_MIN_SP = 5

/** Iznad ovog sistemskog fonta stubci (statistika, donja traka) idu u dva reda. */
internal const val WIDE_LAYOUT_FONT_SCALE = 1.3f

/**
 * Donja traka akcija (M3 stil donje trake): površina `surfaceContainer`, pet akcija sa ikonom i
 * nazivom; cela stavka je dodirna (najmanje 48dp), sa ripple-om i ulogom dugmeta. Jedino „Popuni“
 * ima tonski indikator kao glavna radnja, a „Briši“ je u boji `error`.
 */
@Composable
internal fun BottomBar(
    modifier: Modifier,
    onAutoFill: () -> Unit,
    onAi: () -> Unit,
    onBasePay: () -> Unit,
    onHolidays: () -> Unit,
    onOptions: () -> Unit,
    onDeleteMonth: () -> Unit,
    basePayEnabled: Boolean = true
) {
    val scheme = MaterialTheme.colorScheme
    val specs = bottomBarButtonSpecs()
    val buttons = listOf(
        BottomBarItem(specs.autoFill.icon, specs.autoFill.label, scheme.primary, scheme.primaryContainer, onAutoFill),
        BottomBarItem(specs.ai.icon, specs.ai.label, scheme.onSurfaceVariant, null, onAi).takeIf { AI_BUTTON_VISIBLE },
        BottomBarItem(specs.basePay.icon, specs.basePay.label, scheme.onSurfaceVariant, null, onBasePay),
        BottomBarItem(specs.holidays.icon, specs.holidays.label, scheme.onSurfaceVariant, null, onHolidays),
        BottomBarItem(specs.options.icon, specs.options.label, scheme.onSurfaceVariant, null, onOptions),
        BottomBarItem(specs.deleteMonth.icon, specs.deleteMonth.label, scheme.error, null, onDeleteMonth)
    ).filterNotNull()

    val perRow = if (LocalDensity.current.fontScale > WIDE_LAYOUT_FONT_SCALE) 3 else buttons.size
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(scheme.surfaceContainer),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        HorizontalDivider(color = scheme.outlineVariant.copy(alpha = 0.5f))
        Column(
            modifier = Modifier
                .widthIn(max = UI.contentMaxWidth.dp)
                .fillMaxWidth()
                .padding(start = Spacing.lg, top = Spacing.sm, end = Spacing.lg, bottom = Spacing.sm),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            buttons.chunked(perRow).forEach { rowButtons ->
                Row(
                    modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Max),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    rowButtons.forEach { item ->
                        // „Lista“ je siva dok mesec traje — lista za njega još ne postoji.
                        val enabled = basePayEnabled || item.label != specs.basePay.label
                        BottomBarAction(item = item, enabled = enabled, modifier = Modifier.weight(1f).fillMaxHeight())
                    }
                }
            }
        }
    }
}

/** Jedna stavka donje trake; ikona i naziv čine jednu dodirnu površinu (spojena semantika). */
@Composable
private fun BottomBarAction(item: BottomBarItem, enabled: Boolean, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val contentAlpha = if (enabled) 1f else DISABLED_ALPHA
    Column(
        modifier = modifier
            .clip(MaterialTheme.shapes.medium)
            .radClickable(enabled = enabled, onClick = item.onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.xs)
    ) {
        Box(
            modifier = Modifier
                .height(BOTTOM_BAR_ICON_HEIGHT_DP.dp)
                .fillMaxWidth()
                .background(item.containerColor ?: Color.Transparent, MaterialTheme.shapes.medium),
            contentAlignment = Alignment.Center
        ) {
            // Naziv ispod je deo iste dodirne površine, pa ikona nema poseban opis.
            Icon(
                imageVector = item.icon,
                contentDescription = null,
                tint = item.iconColor.copy(alpha = contentAlpha),
                modifier = Modifier.size(22.dp)
            )
        }
        AutoSizeText(
            text = item.label,
            color = scheme.onSurfaceVariant.copy(alpha = contentAlpha),
            style = MaterialTheme.typography.labelSmall.copy(
                textAlign = TextAlign.Center
            ),
            maxFontSize = MaterialTheme.typography.labelSmall.fontSize,
            // sp raste sa sistemskim fontom, pa nizak minimum čuva ceo naziv i na fontu 1.5.
            minFontSize = BOTTOM_BAR_LABEL_MIN_SP.sp,
            maxLines = 1,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = Spacing.xxs)
        )
    }
}

private const val BOTTOM_BAR_ICON_HEIGHT_DP = 32
private const val BOTTOM_BAR_LABEL_MIN_SP = 11

/** M3 vrednost za onemogućen sadržaj. */
private const val DISABLED_ALPHA = 0.38f

internal data class BottomBarButtonLabels(
    val autoFill: String,
    val ai: String,
    val basePay: String,
    val holidays: String,
    val options: String,
    val deleteMonth: String
)

/** Privremeno sakriveno AI dugme u donjoj traci (kod i dijalog ostaju); true ga vraća. */
internal const val AI_BUTTON_VISIBLE = false

internal data class BottomBarButtonSpec(
    val icon: ImageVector,
    val label: String
)

internal data class BottomBarButtonSpecs(
    val autoFill: BottomBarButtonSpec,
    val ai: BottomBarButtonSpec,
    val basePay: BottomBarButtonSpec,
    val holidays: BottomBarButtonSpec,
    val options: BottomBarButtonSpec,
    val deleteMonth: BottomBarButtonSpec
)

internal fun bottomBarButtonLabels(): BottomBarButtonLabels {
    val specs = bottomBarButtonSpecs()
    return BottomBarButtonLabels(
        autoFill = specs.autoFill.label,
        ai = specs.ai.label,
        basePay = specs.basePay.label,
        holidays = specs.holidays.label,
        options = specs.options.label,
        deleteMonth = specs.deleteMonth.label
    )
}

internal fun bottomBarButtonSpecs(): BottomBarButtonSpecs {
    return BottomBarButtonSpecs(
        autoFill = BottomBarButtonSpec(icon = AppIcons.today, label = "Popuni"),
        ai = BottomBarButtonSpec(icon = AppIcons.ai, label = "AI"),
        basePay = BottomBarButtonSpec(icon = AppIcons.pay, label = "Lista"),
        holidays = BottomBarButtonSpec(icon = AppIcons.sick, label = BLOOD_DONATION_BOTTOM_BAR_LABEL),
        options = BottomBarButtonSpec(icon = AppIcons.settings, label = "Opcije"),
        deleteMonth = BottomBarButtonSpec(icon = AppIcons.delete, label = "Briši")
    )
}

private data class StatItem(
    val label: String,
    val value: String,
    val color: Color,
    val onClick: (() -> Unit)? = null,
    val measureKey: String = value
)

private data class BottomBarItem(
    val icon: ImageVector,
    val label: String,
    val iconColor: Color,
    val containerColor: Color?,
    val onClick: () -> Unit
)
