package com.rad.shifttracker

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.rad.shifttracker.ui.components.AppIcons
import com.rad.shifttracker.ui.components.AutoSizeText

internal const val TOTAL_CARD_LABEL = "Primanja u ovom mesecu"
internal const val TOTAL_CARD_UNAVAILABLE_TEXT = "Nije moguće izračunati"
internal const val WORK_HOURS_CARD_LABEL = "Sati rada"
internal const val COMPACT_PAY_CARD_HEIGHT = 38
internal const val PAY_CARDS_SPACING = 6

internal fun salaryCardLabel(month: Int): String {
    val monthName = MONTH_NAMES.getOrElse(month) { "Mesec" }
    return "Plata za $monthName"
}

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

    val stats = mutableListOf(
        StatItem(
            WORK_HOURS_CARD_LABEL,
            formatHours(summary.workHours),
            THEME.textPrimary,
            onClick = onWorkHoursClick
        ),
        StatItem(
            "Prekovremeno",
            formatHours(summary.overtimeHours),
            if (summary.overtimeHours > 0) THEME.weekendOt else THEME.textMuted,
            onClick = onOvertimeClick
        ),
        StatItem(
            "Hrana",
            formatNumber(summary.foodAllowance),
            if (summary.foodAllowance > 0) THEME.success else THEME.textMuted,
            onClick = onFoodClick
        ),
        StatItem(
            minuliLabel,
            formatNumber(payBreakdown.netSeniorityBonus),
            if (payBreakdown.netSeniorityBonus > 0) THEME.compTime else THEME.textMuted,
            onClick = onMinuliClick
        )
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(UI.statCardHeight.dp),
        horizontalArrangement = Arrangement.spacedBy(UI.defaultSpacing.dp)
    ) {
        for (stat in stats) {
            StatCard(
                label = stat.label,
                value = stat.value,
                color = stat.color,
                modifier = Modifier.weight(1f),
                onClick = stat.onClick
            )
        }
    }
}

@Composable
internal fun TotalCard(receipt: MonthlyReceipt, onClick: () -> Unit) {
    PayAmountCard(
        label = TOTAL_CARD_LABEL,
        amount = receipt.totalReceivedThisMonth,
        isAvailable = receipt.isSalaryAvailable,
        onClick = onClick
    )
}

@Composable
internal fun SalaryCard(month: Int, nextMonthReceipt: MonthlyReceipt, onClick: () -> Unit) {
    PayAmountCard(
        label = salaryCardLabel(month),
        amount = nextMonthReceipt.totalReceivedThisMonth,
        isAvailable = nextMonthReceipt.isSalaryAvailable,
        onClick = onClick
    )
}

@Composable
private fun PayAmountCard(
    label: String,
    amount: Double,
    isAvailable: Boolean,
    onClick: (() -> Unit)? = null
) {
    val totalText = if (isAvailable) {
        "${formatCurrency(amount)} ${CONFIG.pay.currency}"
    } else TOTAL_CARD_UNAVAILABLE_TEXT
    val totalColor = if (isAvailable) THEME.accentPrimary else THEME.warning
    val clickModifier = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier
    GlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .height(COMPACT_PAY_CARD_HEIGHT.dp)
            .then(clickModifier),
        bgColor = THEME.bgCardElevated,
        borderColor = THEME.accentPrimary
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = UI.cardPadding.dp, vertical = 4.dp)
        ) {
            val compactLayout = useCompactTotalCardLayout(maxWidth.value)
            if (compactLayout) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    AutoSizeText(
                        text = label,
                        color = THEME.textMuted,
                        style = MaterialTheme.typography.labelMedium.copy(
                            textAlign = TextAlign.Center
                        ),
                        maxFontSize = MaterialTheme.typography.labelMedium.fontSize,
                        minFontSize = MaterialTheme.typography.labelSmall.fontSize,
                        maxLines = 1,
                        modifier = Modifier.fillMaxWidth()
                    )
                    AutoSizeText(
                        text = totalText,
                        color = totalColor,
                        style = MaterialTheme.typography.headlineSmall.copy(
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center
                        ),
                        maxFontSize = MaterialTheme.typography.headlineSmall.fontSize,
                        minFontSize = MaterialTheme.typography.titleMedium.fontSize,
                        maxLines = 1,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxSize(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AutoSizeText(
                        text = label,
                        color = THEME.textMuted,
                        style = MaterialTheme.typography.titleMedium.copy(
                            textAlign = TextAlign.Start
                        ),
                        maxFontSize = MaterialTheme.typography.titleMedium.fontSize,
                        minFontSize = MaterialTheme.typography.labelSmall.fontSize,
                        maxLines = 1,
                        modifier = Modifier
                            .weight(1.15f)
                            .padding(end = UI.smallSpacing.dp)
                    )
                    AutoSizeText(
                        text = totalText,
                        color = totalColor,
                        style = MaterialTheme.typography.headlineSmall.copy(
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.End
                        ),
                        maxFontSize = MaterialTheme.typography.headlineSmall.fontSize,
                        minFontSize = MaterialTheme.typography.titleMedium.fontSize,
                        maxLines = 1,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                    )
                }
            }
        }
    }
}

internal fun useCompactTotalCardLayout(cardWidthDp: Float): Boolean {
    return cardWidthDp < 320f
}

@Composable
internal fun BottomBar(
    modifier: Modifier,
    onAutoFill: () -> Unit,
    onSickVacation: () -> Unit,
    onBasePay: () -> Unit,
    onHolidays: () -> Unit,
    onOptions: () -> Unit,
    onDeleteMonth: () -> Unit
) {
    val specs = bottomBarButtonSpecs()
    val buttons = listOf(
        Quad(specs.autoFill.icon, specs.autoFill.label, THEME.accentPrimary, onAutoFill),
        Quad(specs.sickVacation.icon, specs.sickVacation.label, THEME.info, onSickVacation),
        Quad(specs.basePay.icon, specs.basePay.label, THEME.success, onBasePay),
        Quad(specs.holidays.icon, specs.holidays.label, THEME.warning, onHolidays),
        Quad(specs.options.icon, specs.options.label, THEME.accentSecondary, onOptions),
        Quad(specs.deleteMonth.icon, specs.deleteMonth.label, THEME.danger, onDeleteMonth)
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(THEME.bgSecondary.copy(alpha = 0.94f))
    ) {
        // Buttons row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = UI.cardPadding.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(UI.defaultSpacing.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            buttons.forEach { item ->
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    BottomBarIconButton(
                        icon = item.icon,
                        label = item.label,
                        bgColor = item.color,
                        textColor = THEME.textPrimary,
                        modifier = Modifier
                            .height(44.dp)
                            .fillMaxWidth(),
                        onClick = item.onClick
                    )
                    AutoSizeText(
                        text = item.label,
                        color = THEME.textMuted,
                        style = MaterialTheme.typography.labelMedium.copy(textAlign = TextAlign.Center),
                        maxFontSize = MaterialTheme.typography.labelMedium.fontSize,
                        minFontSize = MaterialTheme.typography.labelSmall.fontSize,
                        maxLines = 1,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}

@Composable
private fun BottomBarIconButton(
    icon: ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    bgColor: Color,
    textColor: Color,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(10.dp)
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val glowAlpha by animateFloatAsState(if (pressed) 0.28f else 0.0f, label = "bottomBarGlow")
    val elevation by animateDpAsState(if (pressed) 8.dp else 0.dp, label = "bottomBarElev")

    Box(
        modifier = modifier
            .shadow(
                elevation = elevation,
                shape = shape,
                ambientColor = bgColor.copy(alpha = glowAlpha),
                spotColor = bgColor.copy(alpha = glowAlpha)
            )
            .clip(shape)
            .background(bgColor)
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = textColor,
            modifier = Modifier.size(23.dp)
        )
    }
}

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
        basePay = BottomBarButtonSpec(icon = AppIcons.pay, label = "Satnica"),
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

private data class Quad(
    val icon: ImageVector,
    val label: String,
    val color: Color,
    val onClick: () -> Unit
)
