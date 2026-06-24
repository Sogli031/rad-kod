package com.rad.shifttracker.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.rad.shifttracker.UI

/**
 * Position options for status indicator dots within a container.
 */
enum class IndicatorPosition {
    TOP_START,
    TOP_END,
    BOTTOM_START,
    BOTTOM_END,
    BOTTOM_CENTER
}

/**
 * A small circular status indicator dot.
 * Used to show various states on calendar day cells.
 */
@Composable
fun BoxScope.StatusIndicator(
    color: Color,
    position: IndicatorPosition,
    modifier: Modifier = Modifier
) {
    val alignment = when (position) {
        IndicatorPosition.TOP_START -> Alignment.TopStart
        IndicatorPosition.TOP_END -> Alignment.TopEnd
        IndicatorPosition.BOTTOM_START -> Alignment.BottomStart
        IndicatorPosition.BOTTOM_END -> Alignment.BottomEnd
        IndicatorPosition.BOTTOM_CENTER -> Alignment.BottomCenter
    }

    Box(
        modifier = modifier
            .size(UI.indicatorDotSize.dp)
            .align(alignment)
            .padding(4.dp)
            .background(color, CircleShape)
    )
}

/**
 * A horizontal bar indicator shown at the bottom of a cell.
 * Used to indicate double shifts or other extended states.
 */
@Composable
fun BoxScope.BottomBarIndicator(
    color: Color,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .align(Alignment.BottomCenter)
            .padding(start = 8.dp, end = 8.dp, bottom = 2.dp)
            .height(UI.indicatorBarHeight.dp)
            .fillMaxWidth()
            .background(color, RoundedCornerShape(UI.indicatorBarHeight.dp))
    )
}

/**
 * Data class containing all possible indicator colors for a day cell.
 * Null values mean the indicator should not be shown.
 */
data class DayIndicators(
    /** Color for double shift bar indicator (bottom) */
    val doubleShift: Color? = null,
    /** Color for comp time dot (top-end) */
    val compTime: Color? = null,
    /** Color for holiday dot (top-start) */
    val holiday: Color? = null
)

/**
 * Renders all indicator components for a day cell based on the provided data.
 * This is a convenience composable that replaces the inline indicator logic
 * previously embedded in DayButton.
 */
@Composable
fun BoxScope.DayIndicatorGroup(indicators: DayIndicators) {
    indicators.doubleShift?.let {
        BottomBarIndicator(color = it)
    }
    indicators.compTime?.let {
        StatusIndicator(color = it, position = IndicatorPosition.TOP_END)
    }
    indicators.holiday?.let {
        StatusIndicator(color = it, position = IndicatorPosition.TOP_START)
    }
}
