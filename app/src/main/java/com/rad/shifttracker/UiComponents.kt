package com.rad.shifttracker

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rad.shifttracker.ui.components.AutoSizeText
import java.time.LocalDate
import java.util.Locale

@Composable
fun BackgroundLayer(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.fillMaxSize()) {
        drawRect(color = THEME.bgDark)
        drawRect(
            color = THEME.bgSecondary.copy(alpha = 0.48f),
            topLeft = Offset.Zero,
            size = Size(size.width, size.height * 0.18f)
        )
        drawLine(
            color = THEME.glassBorder.copy(alpha = 0.22f),
            start = Offset(0f, size.height * 0.18f),
            end = Offset(size.width, size.height * 0.18f),
            strokeWidth = 1.dp.toPx()
        )
    }
}

@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    bgColor: Color = THEME.bgCard,
    borderColor: Color = THEME.glassBorder,
    radius: Dp = UI.cardRadius.dp,
    content: @Composable BoxScope.() -> Unit
) {
    val shape = RoundedCornerShape(radius)
    val background = bgColor.copy(alpha = bgColor.alpha * 0.9f)
    Box(
        modifier = modifier
            .shadow(2.dp, shape, ambientColor = THEME.shadow.copy(alpha = 0.22f))
            .clip(shape)
            .background(background, shape)
            .border(1.2.dp, borderColor.copy(alpha = 0.25f), shape)
    ) {
        content()
    }
}

@Composable
fun RoundedButton(
    text: String,
    modifier: Modifier = Modifier,
    bgColor: Color,
    textColor: Color,
    borderColor: Color = THEME.glassBorder,
    radius: Dp = 12.dp,
    textStyle: TextStyle = MaterialTheme.typography.labelMedium,
    bold: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(radius)
    Box(
        modifier = modifier
            .clip(shape)
            .background(if (enabled) bgColor else bgColor.copy(alpha = 0.6f))
            .border(1.dp, borderColor.copy(alpha = 0.5f), shape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        val resolvedStyle = if (bold) {
            textStyle.copy(fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        } else {
            textStyle.copy(textAlign = TextAlign.Center)
        }
        AutoSizeText(
            text = text,
            color = if (enabled) textColor else textColor.copy(alpha = 0.6f),
            style = resolvedStyle,
            maxFontSize = resolvedStyle.fontSize,
            minFontSize = MaterialTheme.typography.labelSmall.fontSize,
            maxLines = 1,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 2.dp)
        )
    }
}

@Composable
fun GlowButton(
    text: String,
    modifier: Modifier = Modifier,
    bgColor: Color,
    textColor: Color,
    radius: Dp = 14.dp,
    textStyle: TextStyle = MaterialTheme.typography.labelLarge,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(radius)
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val resolvedBackground = if (enabled) bgColor else THEME.empty
    val resolvedTextColor = if (enabled) textColor else THEME.textMuted
    val glowAlpha by animateFloatAsState(if (enabled && pressed) 0.4f else 0.0f, label = "glow")
    val elevation by animateDpAsState(if (enabled && pressed) 12.dp else 0.dp, label = "elev")

    Box(
        modifier = modifier
            .shadow(
                elevation = elevation,
                shape = shape,
                ambientColor = resolvedBackground.copy(alpha = glowAlpha),
                spotColor = resolvedBackground.copy(alpha = glowAlpha)
            )
            .clip(shape)
            .background(resolvedBackground)
            .clickable(
                enabled = enabled,
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        val resolvedStyle = textStyle.copy(fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        AutoSizeText(
            text = text,
            color = resolvedTextColor,
            style = resolvedStyle,
            maxFontSize = resolvedStyle.fontSize,
            minFontSize = MaterialTheme.typography.labelSmall.fontSize,
            maxLines = 1,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 2.dp)
        )
    }
}

@Composable
fun IconButton(
    text: String,
    modifier: Modifier = Modifier,
    bgColor: Color,
    textColor: Color,
    onClick: () -> Unit
) {
    RoundedButton(
        text = text,
        modifier = modifier,
        bgColor = bgColor,
        textColor = textColor,
        textStyle = MaterialTheme.typography.titleLarge,
        bold = true,
        onClick = onClick
    )
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        color = THEME.textSecondary,
        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
        modifier = modifier.fillMaxWidth(),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
}

@Composable
fun StatCard(
    label: String,
    value: String,
    color: Color,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null
) {
    val cardModifier = if (onClick != null) {
        modifier
            .height(UI.statCardHeight.dp)
            .clickable(onClick = onClick)
    } else {
        modifier.height(UI.statCardHeight.dp)
    }
    GlassCard(modifier = cardModifier) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = UI.cardPadding.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            AutoSizeText(
                text = value,
                color = color,
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                maxFontSize = MaterialTheme.typography.titleLarge.fontSize,
                minFontSize = MaterialTheme.typography.titleMedium.fontSize,
                maxLines = 1
            )
            AutoSizeText(
                text = label,
                color = THEME.textMuted,
                style = MaterialTheme.typography.labelMedium.copy(textAlign = TextAlign.Center),
                maxFontSize = MaterialTheme.typography.labelMedium.fontSize,
                minFontSize = 8.sp,
                maxLines = 1,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
fun CompTimeCard(
    summary: MonthSummary,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null
) {
    val balanceMinutes = summary.compTimeEndingMinutes
    val mainColor = when {
        balanceMinutes > 0 -> THEME.compTime
        balanceMinutes < 0 -> THEME.danger
        else -> THEME.textMuted
    }
    val cardModifier = if (onClick != null) {
        modifier
            .heightIn(min = UI.compTimeBarHeight.dp)
            .fillMaxWidth()
            .background(mainColor.copy(alpha = 0.19f), RoundedCornerShape(UI.cardRadius.dp))
            .border(1.2.dp, mainColor.copy(alpha = 0.5f), RoundedCornerShape(UI.cardRadius.dp))
            .clickable(onClick = onClick)
    } else {
        modifier
            .heightIn(min = UI.compTimeBarHeight.dp)
            .fillMaxWidth()
            .background(mainColor.copy(alpha = 0.19f), RoundedCornerShape(UI.cardRadius.dp))
            .border(1.2.dp, mainColor.copy(alpha = 0.5f), RoundedCornerShape(UI.cardRadius.dp))
    }
    Box(
        modifier = cardModifier
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.Center)
                .padding(UI.cardPadding.dp, 8.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            AutoSizeText(
                text = formatHoursAndMinutes(balanceMinutes, showPlusForPositive = true),
                color = mainColor,
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                maxFontSize = MaterialTheme.typography.titleLarge.fontSize,
                minFontSize = MaterialTheme.typography.titleMedium.fontSize,
                maxLines = 1
            )
            AutoSizeText(
                text = "Bilans sati",
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

@Composable
fun VacationCard(
    repo: ShiftRepository,
    repoTick: Int,
    year: Int,
    today: LocalDate,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val summary = remember(repoTick, year, today) { repo.getVacationBalanceSummary(year, today) }
    val exceeded = summary.warnings.contains(VacationWarnings.VACATION_EXCEEDED)

    val mainColor = if (exceeded) THEME.danger else THEME.vacation
    Box(
        modifier = modifier
            .heightIn(min = UI.compTimeBarHeight.dp)
            .fillMaxWidth()
            .background(mainColor.copy(alpha = 0.19f), RoundedCornerShape(UI.cardRadius.dp))
            .border(1.2.dp, mainColor.copy(alpha = 0.5f), RoundedCornerShape(UI.cardRadius.dp))
            .clickable(onClick = onClick)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.Center)
                .padding(UI.cardPadding.dp, 8.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            val displayText = if (exceeded) {
                "Prekora\u010deno!"
            } else {
                vacationCardMainText(summary)
            }
            AutoSizeText(
                text = displayText,
                color = mainColor,
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                maxFontSize = MaterialTheme.typography.titleLarge.fontSize,
                minFontSize = MaterialTheme.typography.titleMedium.fontSize,
                maxLines = 1
            )
            val subtitleText = if (summary.currentYearTotal > 0 || summary.carryoverInitial > 0) {
                vacationCardSubtitleText(summary)
            } else {
                "Godi\u0161nji (podesi fond)"
            }
            AutoSizeText(
                text = subtitleText,
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

internal fun vacationCardMainText(summary: VacationBalanceSummary): String {
    return when {
        summary.availableFromJuly > 0.0 && summary.availableNow > 0.0 ->
            "${formatVacationDays(summary.availableNow)} starog"
        summary.availableFromJuly > 0.0 && summary.carryoverInitial > 0.0 ->
            "0 starog"
        summary.availableFromJuly > 0.0 ->
            "0 sada"
        else ->
            "${formatVacationDays(summary.availableNow)} dana"
    }
}

internal fun vacationCardSubtitleText(summary: VacationBalanceSummary): String {
    return if (summary.availableFromJuly > 0.0) {
        val availableFrom = formatVacationDate(summary.currentYearAvailableFrom)
        if (summary.carryoverInitial > 0.0) {
            "potrošeno ${formatVacationDays(summary.carryoverUsed)}/${formatVacationDays(summary.carryoverInitial)}, " +
                "do $availableFrom"
        } else if (summary.availableNow > 0.0) {
            "${formatVacationDays(summary.availableFromJuly)} dana novog od $availableFrom"
        } else {
            "${formatVacationDays(summary.availableFromJuly)} dana od $availableFrom"
        }
    } else {
        "od sada do ${formatVacationDate(summary.currentYearExpiresOn)}"
    }
}

internal fun formatVacationDays(value: Double): String {
    return if (value == value.toLong().toDouble()) {
        value.toLong().toString()
    } else {
        String.format(Locale.US, "%.1f", value)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModernTextField(
    value: String,
    onValueChange: (String) -> Unit,
    hintText: String,
    keyboardOptions: KeyboardOptions,
    modifier: Modifier = Modifier
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyLarge.copy(
            color = THEME.textPrimary
        ),
        placeholder = {
            Text(
                text = hintText,
                color = THEME.textMuted,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        keyboardOptions = keyboardOptions,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = THEME.accentPrimary.copy(alpha = 0.63f),
            unfocusedBorderColor = THEME.glassBorder.copy(alpha = 0.63f),
            cursorColor = THEME.accentPrimary,
            focusedTextColor = THEME.textPrimary,
            unfocusedTextColor = THEME.textPrimary
        )
    )
}
