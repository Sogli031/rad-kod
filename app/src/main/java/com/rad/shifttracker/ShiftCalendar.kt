package com.rad.shifttracker

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import com.rad.shifttracker.ui.components.AutoSizeText
import com.rad.shifttracker.ui.components.DayIndicatorGroup
import com.rad.shifttracker.ui.components.DayIndicators
import java.time.LocalDate

@Composable
internal fun HeaderSection(month: Int, year: Int, onPrev: () -> Unit, onNext: () -> Unit) {
    GlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .height(UI.headerHeight.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            IconButton(
                text = "<",
                modifier = Modifier.size(UI.iconButtonSize.dp),
                bgColor = THEME.bgCardElevated,
                textColor = THEME.textPrimary,
                onClick = onPrev
            )
            AutoSizeText(
                text = "${MONTH_NAMES[month]} $year",
                color = THEME.textPrimary,
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                maxFontSize = MaterialTheme.typography.titleLarge.fontSize,
                minFontSize = MaterialTheme.typography.titleMedium.fontSize,
                maxLines = 1
            )
            IconButton(
                text = ">",
                modifier = Modifier.size(UI.iconButtonSize.dp),
                bgColor = THEME.bgCardElevated,
                textColor = THEME.textPrimary,
                onClick = onNext
            )
        }
    }
}

@Composable
internal fun WeekdayHeader() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(UI.weekdayHeaderHeight.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        WEEKDAY_NAMES.forEachIndexed { index, name ->
            val color = if (index >= 5) THEME.weekendOt else THEME.textMuted
            Text(
                text = name,
                color = color,
                style = MaterialTheme.typography.labelMedium.copy(
                    fontWeight = FontWeight.W700,
                    textAlign = TextAlign.Center
                ),
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
internal fun CalendarSection(
    modifier: Modifier = Modifier,
    year: Int,
    month: Int,
    today: LocalDate,
    repo: ShiftRepository,
    onDayClick: (Int, DayRecord?) -> Unit
) {
    val weeks = remember(year, month) { getMonthMatrix(year, month) }
    val scrollState = rememberScrollState()
    val brigadeType = repo.brigadeTypeValue
    GlassCard(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(6.dp)
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(UI.smallSpacing.dp)
        ) {
            for (week in weeks) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(UI.smallSpacing.dp)
                ) {
                    for (day in week) {
                        val record = if (day != 0) repo.get(year, month, day) else null
                        val isToday = day == today.dayOfMonth &&
                            month == today.monthValue &&
                            year == today.year
                        DayButton(
                            day = day,
                            record = record,
                            isToday = isToday,
                            year = year,
                            month = month,
                            brigadeType = brigadeType,
                            onClick = { onDayClick(day, record) }
                        )
                    }
                }
            }
        }
    }
}

// Existing calendar cell combines visual states and click behavior; keep detekt active for new methods.
@Suppress("CyclomaticComplexMethod")
@Composable
private fun RowScope.DayButton(
    day: Int,
    record: DayRecord?,
    isToday: Boolean,
    year: Int,
    month: Int,
    brigadeType: BrigadeType,
    onClick: () -> Unit
) {
    if (day == 0) {
        Spacer(modifier = Modifier.height(UI.dayButtonHeight.dp).weight(1f))
        return
    }

    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val highlightAlpha by animateFloatAsState(
        targetValue = if (pressed) 0.15f else 0f,
        label = "highlight"
    )

    val isHolidayDay = record?.holidayOverride ?: isSerbianHoliday(year, month, day)
    val workedHoliday = record?.workedOnHoliday ?: false

    val defaultBgColor = when {
        record == null -> THEME.empty
        else -> record.shift.color
    }
    val defaultTextColor = when {
        record == null -> THEME.textMuted
        else -> record.shift.textColor
    }
    val bgColor = calendarDayBackgroundColor(isHolidayDay, workedHoliday, defaultBgColor)
    val textColor = calendarDayTextColor(isHolidayDay, workedHoliday, defaultTextColor)

    val detailText = calendarDayDetailText(record, isHolidayDay, workedHoliday)

    val doubleIndicatorColor = if (record?.isDoubleShift == true && record.shift2 != null) {
        if (record.isCompTimeOvertime) THEME.compTime else record.shift2.color
    } else {
        null
    }

    val compIndicatorColor = if (record != null) {
        when {
            record.hoursDeficitMinutesFor(brigadeType) > 0 -> THEME.compTimeDeficit
            record.compHoursUsed > 0 -> THEME.compTimeUsed
            record.compensatoryAdditionalWorkMinutes > 0 -> THEME.compTime
            else -> null
        }
    } else {
        null
    }

    Box(
        modifier = Modifier
            .height(UI.dayButtonHeight.dp)
            .weight(1f)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
    ) {
        if (isToday) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .padding(1.dp)
                    .border(1.dp, THEME.accentPrimary, RoundedCornerShape(14.dp))
            )
        }
        Box(
            modifier = Modifier
                .matchParentSize()
                .padding(4.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(bgColor)
        ) {
            if (highlightAlpha > 0f) {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(androidx.compose.ui.graphics.Color.White.copy(alpha = highlightAlpha))
                )
            }

            DayIndicatorGroup(
                indicators = DayIndicators(
                    doubleShift = doubleIndicatorColor,
                    compTime = compIndicatorColor
                )
            )

            CalendarDayText(
                day = day,
                detailText = detailText,
                textColor = textColor,
                modifier = Modifier
                    .align(Alignment.Center)
                    .fillMaxWidth()
                    .padding(horizontal = 2.dp)
            )
        }
    }
}

@Composable
private fun CalendarDayText(
    day: Int,
    detailText: String,
    textColor: Color,
    modifier: Modifier = Modifier
) {
    val configuration = LocalConfiguration.current
    val dayFontSize = MaterialTheme.typography.labelMedium.fontSize
    val detailFontSize = MaterialTheme.typography.labelSmall.fontSize
    var detailTextScale by remember(
        day,
        detailText,
        dayFontSize,
        detailFontSize,
        configuration.screenWidthDp,
        configuration.fontScale
    ) { mutableStateOf(1f) }
    val text = buildDayText(day, detailText, dayFontSize, detailFontSize * detailTextScale)

    Text(
        text = text,
        color = textColor,
        style = MaterialTheme.typography.labelMedium.copy(
            fontWeight = FontWeight.W700,
            textAlign = TextAlign.Center,
            shadow = Shadow(
                color = THEME.shadow.copy(alpha = 0.45f),
                offset = Offset(0f, 1f),
                blurRadius = 2f
            )
        ),
        maxLines = 4,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
        onTextLayout = { result ->
            if (
                detailText == "Praznik" &&
                result.lineCount > expectedCalendarDayTextLines(detailText)
            ) {
                val newScale = (detailTextScale * CALENDAR_DAY_TEXT_SCALE_STEP)
                    .coerceAtLeast(MIN_CALENDAR_DETAIL_TEXT_SCALE)
                if (newScale < detailTextScale) {
                    detailTextScale = newScale
                }
            }
        }
    )
}

private fun buildDayText(
    day: Int,
    detail: String,
    dayFontSize: TextUnit,
    detailFontSize: TextUnit
): AnnotatedString {
    return buildAnnotatedString {
        withStyle(SpanStyle(fontWeight = FontWeight.W700, fontSize = dayFontSize)) {
            append(day.toString())
        }
        append("\n")
        withStyle(SpanStyle(fontSize = detailFontSize, fontWeight = FontWeight.W700)) {
            append(detail)
        }
    }
}

private const val CALENDAR_DAY_TEXT_SCALE_STEP = 0.9f
private const val MIN_CALENDAR_DETAIL_TEXT_SCALE = 0.75f

private fun expectedCalendarDayTextLines(detail: String): Int {
    return 2 + detail.count { it == '\n' }
}

internal fun calendarDayBackgroundColor(
    isHolidayDay: Boolean,
    workedHoliday: Boolean,
    defaultBackground: Color
): Color {
    return if (isHolidayDay || workedHoliday) THEME.warning else defaultBackground
}

internal fun calendarDayTextColor(
    isHolidayDay: Boolean,
    workedHoliday: Boolean,
    defaultTextColor: Color
): Color {
    return if (isHolidayDay || workedHoliday) THEME.textDark else defaultTextColor
}

internal fun calendarDayDetailText(
    record: DayRecord?,
    isHolidayDay: Boolean,
    workedHoliday: Boolean
): String {
    if (record == null) return if (isHolidayDay || workedHoliday) "Praznik" else "-"

    return when (record.shift) {
        Shift.OFF -> when {
            isHolidayDay || workedHoliday -> "Praznik"
            record.weekday.isWorkday -> "0h"
            else -> "OFF"
        }
        Shift.SICK -> if (record.isBloodDonationLeave) "Krv" else "BOL"
        Shift.VACATION -> "GO"
        else -> {
            val shiftLabel = if (record.isDoubleShift && record.shift2 != null) {
                "${record.shift.icon} + ${record.shift2.icon}"
            } else {
                record.shift.icon
            }
            val detail = if (record.isDoubleShift) {
                "$shiftLabel\n${formatHours(record.totalHours)}"
            } else {
                "$shiftLabel\n${formatHours(record.workHours)}"
            }
            detail
        }
    }
}
