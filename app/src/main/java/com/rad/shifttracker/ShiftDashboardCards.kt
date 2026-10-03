package com.rad.shifttracker

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.ZeroCornerSize
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rad.shifttracker.ui.components.AppIcons
import com.rad.shifttracker.ui.components.AutoSizeText

// Kad je kartica „Primanja u …“ sakrivena, kartica plate ostaje sama i zaobljena.
internal const val SHOW_TOTAL_CARD = true
internal const val TOTAL_CARD_UNAVAILABLE_TEXT = "Nije moguće izračunati"
internal const val WORK_HOURS_CARD_LABEL = "Sati rada"
internal const val COMPACT_PAY_CARD_HEIGHT = 41
// Dve kartice plate su vizuelno jedna grupa: bez razmaka, gornja/donja zaobljena.
internal const val PAY_CARDS_SPACING = 0

/** Plata za rad u mesecu: „Septembarska plata“ (isplaćuje se sledećeg meseca). */
internal fun salaryCardLabel(month: Int): String {
    val adjective = when (month) {
        1 -> "Januarska"
        2 -> "Februarska"
        3 -> "Martovska"
        4 -> "Aprilska"
        5 -> "Majska"
        6 -> "Junska"
        7 -> "Julska"
        8 -> "Avgustovska"
        9 -> "Septembarska"
        10 -> "Oktobarska"
        11 -> "Novembarska"
        12 -> "Decembarska"
        else -> return "Plata"
    }
    return "$adjective plata"
}

/** Šta leže na račun u mesecu (plata za prošli mesec, akontacija, prevoz): „Primanja u Septembru“. */
internal fun totalCardLabel(year: Int, month: Int): String {
    if (month !in 1..12) return "Primanja"
    return "Primanja u ${monthLocativeText(MonthKey(year, month))}"
}

/** Četiri statistike u jednoj kartici, razdvojene tankim linijama. */
@Composable
internal fun StatsSection(
    summary: MonthSummary,
    payBreakdown: MonthlyNetPayBreakdown,
    onWorkHoursClick: () -> Unit,
    onOvertimeClick: () -> Unit,
    onFoodClick: () -> Unit,
    onMinuliClick: () -> Unit
) {
    val minuliLabel = if (summary.yearsOfService > 0) {
        "Minuli (${summary.yearsOfService}g)"
    } else {
        "Minuli"
    }

    val stats = listOf(
        StatItem(
            WORK_HOURS_CARD_LABEL,
            formatHours(summary.workHours),
            MaterialTheme.colorScheme.onSurface,
            onClick = onWorkHoursClick
        ),
        StatItem(
            "Prekovremeno",
            formatHours(summary.overtimeHours),
            if (summary.overtimeHours > 0) MaterialTheme.extendedColors.weekendOt else MaterialTheme.colorScheme.onSurfaceVariant,
            onClick = onOvertimeClick
        ),
        StatItem(
            "Hrana",
            formatNumber(summary.foodAllowance),
            if (summary.foodAllowance > 0) MaterialTheme.extendedColors.success else MaterialTheme.colorScheme.onSurfaceVariant,
            onClick = onFoodClick
        ),
        StatItem(
            minuliLabel,
            formatNumber(payBreakdown.netSeniorityBonus),
            if (payBreakdown.netSeniorityBonus > 0) MaterialTheme.extendedColors.compTime else MaterialTheme.colorScheme.onSurfaceVariant,
            onClick = onMinuliClick
        )
    )

    // Pri velikom fontu četiri stubca ne staju: kartica se lomi u 2 × 2.
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

/** Iznad ovog sistemskog fonta stubci (statistika, donja traka) idu u dva reda. */
internal const val WIDE_LAYOUT_FONT_SCALE = 1.3f

@Composable
internal fun TotalCard(
    receipt: MonthlyReceipt,
    includeTransport: Boolean = true,
    onClick: () -> Unit
) {
    PayAmountCard(
        label = totalCardLabel(receipt.year, receipt.month),
        amount = mainPayCardAmount(receipt, includeTransport),
        isAvailable = receipt.isSalaryAvailable,
        amountColor = MaterialTheme.colorScheme.onSurface,
        shape = MaterialTheme.shapes.medium.copy(topStart = ZeroCornerSize, topEnd = ZeroCornerSize),
        showTopDivider = true,
        onClick = onClick
    )
}

@Composable
internal fun SalaryCard(
    month: Int,
    nextMonthReceipt: MonthlyReceipt,
    includeTransport: Boolean = true,
    onClick: () -> Unit
) {
    PayAmountCard(
        label = salaryCardLabel(month),
        amount = mainPayCardAmount(nextMonthReceipt, includeTransport),
        isAvailable = nextMonthReceipt.isSalaryAvailable,
        amountColor = MaterialTheme.colorScheme.primary,
        shape = if (SHOW_TOTAL_CARD) {
            MaterialTheme.shapes.medium.copy(bottomStart = ZeroCornerSize, bottomEnd = ZeroCornerSize)
        } else {
            MaterialTheme.shapes.medium
        },
        showTopDivider = false,
        onClick = onClick
    )
}

/** Iznos na kartici ispod kalendara: sa prevozom (podrazumevano) ili samo neto plata i regres. */
internal fun mainPayCardAmount(receipt: MonthlyReceipt, includeTransport: Boolean): Double {
    return if (includeTransport) receipt.totalReceivedThisMonth else receipt.displayPay
}

/** Jedan red grupe plate: etiketa levo, iznos desno. */
@Composable
private fun PayAmountCard(
    label: String,
    amount: Double,
    isAvailable: Boolean,
    amountColor: Color,
    shape: Shape,
    showTopDivider: Boolean,
    onClick: (() -> Unit)? = null
) {
    val totalText = if (isAvailable) {
        "${formatCurrency(amount)} ${CONFIG.pay.currency}"
    } else TOTAL_CARD_UNAVAILABLE_TEXT
    val totalColor = if (isAvailable) amountColor else MaterialTheme.extendedColors.warning
    val clickModifier = if (onClick != null) Modifier.radClickable(onClick = onClick) else Modifier
    val borderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = COMPACT_PAY_CARD_HEIGHT.dp)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, shape)
            .then(clickModifier)
    ) {
        // Okvir se crta ručno da bi se spojio preko oba reda kao jedna kartica.
        Box(
            modifier = Modifier
                .matchParentSize()
                .border(1.dp, borderColor, shape)
        )
        if (showTopDivider) {
            HorizontalDivider(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(horizontal = Spacing.lg),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = CARD_BORDER_ALPHA)
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.lg, vertical = Spacing.xs),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AutoSizeText(
                text = label,
                color = MaterialTheme.extendedColors.textSecondary,
                style = MaterialTheme.typography.bodyMedium.copy(textAlign = TextAlign.Start),
                maxFontSize = MaterialTheme.typography.bodyMedium.fontSize,
                minFontSize = MaterialTheme.typography.labelSmall.fontSize,
                maxLines = 1,
                modifier = Modifier
                    .weight(1.15f)
                    .padding(end = Spacing.xs)
            )
            AutoSizeText(
                text = totalText,
                color = totalColor,
                style = MaterialTheme.typography.titleLargeEmphasized.copy(
                    textAlign = TextAlign.End
                ),
                maxFontSize = MaterialTheme.typography.titleLarge.fontSize,
                minFontSize = MaterialTheme.typography.titleMedium.fontSize,
                maxLines = 1,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            )
        }
    }
}

internal fun useCompactTotalCardLayout(cardWidthDp: Float): Boolean {
    return cardWidthDp < 320f
}

/**
 * Donja traka akcija (M3 stil donje trake): površina `surfaceContainer`, šest akcija sa ikonom i
 * nazivom; cela stavka je dodirna (najmanje 48dp), sa ripple-om i ulogom dugmeta. Jedino „Popuni“
 * ima tonski indikator kao glavna radnja, a „Briši“ je u boji `error`.
 */
@Composable
internal fun BottomBar(
    modifier: Modifier,
    onAutoFill: () -> Unit,
    onSickVacation: () -> Unit,
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
        BottomBarItem(specs.sickVacation.icon, specs.sickVacation.label, scheme.onSurfaceVariant, null, onSickVacation),
        BottomBarItem(specs.basePay.icon, specs.basePay.label, scheme.onSurfaceVariant, null, onBasePay),
        BottomBarItem(specs.holidays.icon, specs.holidays.label, scheme.onSurfaceVariant, null, onHolidays),
        BottomBarItem(specs.options.icon, specs.options.label, scheme.onSurfaceVariant, null, onOptions),
        BottomBarItem(specs.deleteMonth.icon, specs.deleteMonth.label, scheme.error, null, onDeleteMonth)
    )

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
    val sickVacation: String,
    val basePay: String,
    val holidays: String,
    val options: String,
    val deleteMonth: String
)

internal data class BottomBarButtonSpec(
    val icon: ImageVector,
    val label: String
)

internal data class BottomBarButtonSpecs(
    val autoFill: BottomBarButtonSpec,
    val sickVacation: BottomBarButtonSpec,
    val basePay: BottomBarButtonSpec,
    val holidays: BottomBarButtonSpec,
    val options: BottomBarButtonSpec,
    val deleteMonth: BottomBarButtonSpec
)

internal fun bottomBarButtonLabels(): BottomBarButtonLabels {
    val specs = bottomBarButtonSpecs()
    return BottomBarButtonLabels(
        autoFill = specs.autoFill.label,
        sickVacation = specs.sickVacation.label,
        basePay = specs.basePay.label,
        holidays = specs.holidays.label,
        options = specs.options.label,
        deleteMonth = specs.deleteMonth.label
    )
}

internal fun bottomBarButtonSpecs(): BottomBarButtonSpecs {
    return BottomBarButtonSpecs(
        autoFill = BottomBarButtonSpec(icon = AppIcons.today, label = "Popuni"),
        sickVacation = BottomBarButtonSpec(icon = AppIcons.sickLeave, label = "Bol/GO"),
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
    val onClick: (() -> Unit)? = null
)

private data class BottomBarItem(
    val icon: ImageVector,
    val label: String,
    val iconColor: Color,
    val containerColor: Color?,
    val onClick: () -> Unit
)
