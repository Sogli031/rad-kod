package com.rad.shifttracker

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.rad.shifttracker.ui.components.AutoSizeText

@Composable
fun PopupDialog(
    title: String,
    onDismiss: () -> Unit,
    widthRatio: Float = UI.popupWidthRatio,
    scrollable: Boolean = true,
    accent: Color = THEME.accentPrimary,
    titleTextAlign: TextAlign = TextAlign.Start,
    footer: (@Composable ColumnScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val config = LocalConfiguration.current
    val popupWidth = config.screenWidthDp.dp * widthRatio
    val overlayInteraction = remember { MutableInteractionSource() }
    val cardInteraction = remember { MutableInteractionSource() }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .background(THEME.bgDark.copy(alpha = 0.94f))
                .clickable(
                    interactionSource = overlayInteraction,
                    indication = null,
                    onClick = onDismiss
                )
                .padding(WindowInsets.safeDrawing.asPaddingValues())
                .padding(
                    top = UI.popupEdgePadding.dp,
                    bottom = UI.popupBottomSafePadding.dp
                ),
            contentAlignment = Alignment.Center
        ) {
            val popupHeight = UI.popupMaxHeight.dp.coerceAtMost(
                maxHeight - UI.popupEdgePadding.dp - UI.popupBottomSafePadding.dp
            )
            val popupSizeModifier = Modifier.heightIn(max = popupHeight)
            val popupContentModifier = Modifier.fillMaxWidth().wrapContentHeight()

            GlassCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = popupWidth)
                    .then(popupSizeModifier)
                    .clickable(
                        interactionSource = cardInteraction,
                        indication = null,
                        onClick = {}
                    )
            ) {
                Column(
                    modifier = popupContentModifier.padding(UI.cardPadding.dp),
                    verticalArrangement = Arrangement.spacedBy(UI.defaultSpacing.dp)
                ) {
                    AutoSizeText(
                        text = title,
                        color = THEME.textPrimary,
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.Bold,
                            textAlign = titleTextAlign
                        ),
                        maxFontSize = MaterialTheme.typography.titleLarge.fontSize,
                        minFontSize = MaterialTheme.typography.titleMedium.fontSize,
                        maxLines = 1,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(2.dp)
                            .background(accent.copy(alpha = 0.9f))
                    )
                    if (scrollable) {
                        val scrollState = rememberScrollState()
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f, fill = false)
                                .verticalScroll(scrollState),
                            verticalArrangement = Arrangement.spacedBy(UI.largeSpacing.dp)
                        ) {
                            content()
                        }
                    } else {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(UI.largeSpacing.dp)
                        ) {
                            content()
                        }
                    }
                    footer?.let { footerContent ->
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(UI.defaultSpacing.dp)
                        ) {
                            footerContent()
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun PopupCard(
    modifier: Modifier = Modifier,
    bgColor: Color = THEME.bgCard,
    borderColor: Color = THEME.glassBorder,
    spacing: Dp = UI.defaultSpacing.dp,
    content: @Composable ColumnScope.() -> Unit
) {
    GlassCard(modifier = modifier, bgColor = bgColor, borderColor = borderColor) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(spacing)
        ) {
            content()
        }
    }
}

@Composable
fun HoursPickerDialog(
    title: String,
    currentHours: Int,
    minHours: Int = 1,
    accent: Color = THEME.accentPrimary,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    PopupDialog(
        title = title,
        onDismiss = onDismiss,
        widthRatio = 0.85f,
        scrollable = false,
        accent = accent
    ) {
        val hours = (minHours..CONFIG.pay.defaultHours).toList()
        val rows = hours.chunked(4)
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            for (row in rows) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    for (hour in row) {
                        val isSelected = hour == currentHours
                        RoundedButton(
                            text = hour.toString(),
                            modifier = Modifier
                                .weight(1f)
                                .height(UI.buttonHeight.dp),
                            bgColor = if (isSelected) accent else THEME.bgCard,
                            textColor = if (isSelected) THEME.textDark else THEME.textPrimary,
                            textStyle = MaterialTheme.typography.titleLarge,
                            bold = true,
                            onClick = {
                                onSelect(hour)
                                onDismiss()
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ShortageMinutesDialog(
    currentMinutes: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    PopupDialog(
        title = "Minuti",
        onDismiss = onDismiss,
        widthRatio = 0.85f,
        scrollable = false,
        accent = THEME.accentPrimary
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            for (minutes in regularShortageMinuteOptions()) {
                val isSelected = minutes == currentMinutes
                RoundedButton(
                    text = formatShortageMinutes(minutes),
                    modifier = Modifier
                        .weight(1f)
                        .height(UI.buttonHeight.dp),
                    bgColor = if (isSelected) THEME.accentPrimary else THEME.bgCard,
                    textColor = if (isSelected) THEME.textDark else THEME.textPrimary,
                    textStyle = MaterialTheme.typography.titleMedium,
                    bold = true,
                    onClick = {
                        onSelect(minutes)
                        onDismiss()
                    }
                )
            }
        }
    }
}

@Composable
fun CompUsedDialog(
    availableHours: Int,
    currentHours: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val maxHours = minOf(CONFIG.pay.defaultHours, availableHours.coerceAtLeast(0))
    val hourOptions = (0..maxHours).toList()
    val rows = hourOptions.chunked(4)

    PopupDialog(
        title = "Iskoristi zara\u0111ene sate",
        onDismiss = onDismiss,
        widthRatio = 0.85f,
        scrollable = true,
        accent = THEME.compTimeUsed
    ) {
        AutoSizeText(
            text = "Dostupno: ${formatHours(availableHours)}",
            color = THEME.compTime,
            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold),
            maxFontSize = MaterialTheme.typography.bodyLarge.fontSize,
            minFontSize = MaterialTheme.typography.bodyMedium.fontSize,
            maxLines = 1
        )
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            for (row in rows) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    for (hours in row) {
                        val isSelected = hours == currentHours
                        RoundedButton(
                            text = formatHours(hours),
                            modifier = Modifier
                                .weight(1f)
                                .height(UI.buttonHeight.dp),
                            bgColor = if (isSelected) THEME.compTimeUsed else THEME.bgCard,
                            textColor = THEME.textPrimary,
                            textStyle = MaterialTheme.typography.titleLarge,
                            bold = true,
                            onClick = {
                                onSelect(hours)
                                onDismiss()
                            }
                        )
                    }
                    if (row.size < 4) {
                        Spacer(modifier = Modifier.weight((4 - row.size).toFloat()))
                    }
                }
            }
        }
    }
}
