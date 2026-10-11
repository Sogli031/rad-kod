package com.rad.shifttracker.ui.dialozi

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.rad.shifttracker.ui.MIN_TOUCH_TARGET
import com.rad.shifttracker.ui.RadMotion
import com.rad.shifttracker.ui.Spacing
import com.rad.shifttracker.ui.components.AutoSizeText
import com.rad.shifttracker.ui.rememberReducedMotion

private const val CHOICE_CHECK_ICON_DP = 18
private const val CHOICE_CHECK_SCALE = 0.6f

/** Boje dugmeta izbora u izabranom i neizabranom stanju. */
internal class ChoiceButtonColors(
    val selectedContainer: Color,
    val container: Color,
    val selectedContent: Color,
    val content: Color,
    val selectedBorder: Color,
    val border: Color
)

/**
 * Dugme izbora ponaša se kao segment u „Izaberi smenu“ (M3 SegmentedButton): boje se glatko
 * pretapaju, a uz izabrano dugme uleti kvačica (skaliranje + širenje), pa susedi ne skaču.
 * Najavljuje se kao radio dugme; bez animacija kad je u sistemu isključen pokret.
 */
@Composable
internal fun AnimatedChoiceButton(
    text: String,
    selected: Boolean,
    colors: ChoiceButtonColors,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    minHeight: Dp = MIN_TOUCH_TARGET,
    shape: Shape = MaterialTheme.shapes.small,
    enabled: Boolean = true,
    disabledAlpha: Float = 1f,
    textStyle: TextStyle = MaterialTheme.typography.labelLarge,
    selectedTextStyle: TextStyle = textStyle,
    maxLines: Int = 1
) {
    val reducedMotion = rememberReducedMotion()
    val alpha = if (enabled) 1f else disabledAlpha
    val bg by animateColorAsState(
        targetValue = (if (selected) colors.selectedContainer else colors.container).let {
            if (selected) it.copy(alpha = it.alpha * alpha) else it
        },
        animationSpec = if (reducedMotion) snap() else RadMotion.effects(),
        label = "choiceBg"
    )
    val fg by animateColorAsState(
        targetValue = (if (selected) colors.selectedContent else colors.content).let { it.copy(alpha = it.alpha * alpha) },
        animationSpec = if (reducedMotion) snap() else RadMotion.effects(),
        label = "choiceFg"
    )
    val borderColor by animateColorAsState(
        targetValue = (if (selected) colors.selectedBorder else colors.border).let {
            if (selected) it.copy(alpha = it.alpha * alpha) else it
        },
        animationSpec = if (reducedMotion) snap() else RadMotion.effects(),
        label = "choiceBorder"
    )
    Box(
        modifier = modifier
            .heightIn(min = MIN_TOUCH_TARGET)
            .heightIn(min = minHeight)
            .clip(shape)
            .background(bg)
            .border(1.dp, borderColor, shape)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.sm, vertical = Spacing.xs),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            AnimatedVisibility(
                visible = selected,
                enter = if (reducedMotion) {
                    EnterTransition.None
                } else {
                    fadeIn(RadMotion.effects()) +
                        expandHorizontally(RadMotion.spatial(), expandFrom = Alignment.Start) +
                        scaleIn(RadMotion.spatial(), initialScale = CHOICE_CHECK_SCALE)
                },
                exit = if (reducedMotion) {
                    ExitTransition.None
                } else {
                    fadeOut(RadMotion.fastEffects()) +
                        shrinkHorizontally(RadMotion.fastSpatial(), shrinkTowards = Alignment.Start) +
                        scaleOut(RadMotion.fastSpatial(), targetScale = CHOICE_CHECK_SCALE)
                }
            ) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = null,
                    tint = fg,
                    modifier = Modifier
                        .padding(end = Spacing.xs)
                        .size(CHOICE_CHECK_ICON_DP.dp)
                )
            }
            AutoSizeText(
                text = text,
                color = fg,
                style = (if (selected) selectedTextStyle else textStyle).copy(textAlign = TextAlign.Center),
                maxFontSize = textStyle.fontSize,
                minFontSize = MaterialTheme.typography.labelSmall.fontSize,
                maxLines = maxLines,
                modifier = Modifier.weight(1f, fill = false)
            )
        }
    }
}
