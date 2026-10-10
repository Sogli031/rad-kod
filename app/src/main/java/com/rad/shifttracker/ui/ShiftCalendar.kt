package com.rad.shifttracker.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rad.shifttracker.ui.components.AutoSizeText
import com.rad.shifttracker.ui.components.DayIndicatorGroup
import com.rad.shifttracker.ui.components.DayIndicators
import java.time.LocalDate
import com.rad.shifttracker.osnova.MONTH_NAMES
import com.rad.shifttracker.osnova.UI
import com.rad.shifttracker.osnova.WEEKDAY_NAMES
import com.rad.shifttracker.osnova.formatHours
import com.rad.shifttracker.repo.ShiftRepository
import com.rad.shifttracker.repo.brigadeTypeValue
import com.rad.shifttracker.smene.BrigadeType
import com.rad.shifttracker.smene.DayRecord
import com.rad.shifttracker.smene.isSerbianHoliday
import com.rad.shifttracker.smene.Shift

/** Alfa tonirane pozadine ćelije i njenog okvira (boja smene). */
private const val DAY_TINT_ALPHA = 0.18f
private const val DAY_TINT_BORDER_ALPHA = 0.35f
/** Tekst ćelije je 11sp (M3 `labelSmall`) i ne smanjuje se; što ne stane skraćuje se, pun opis ima čitač ekrana. */
private const val CALENDAR_DETAIL_FONT_SIZE = 11f

// Duži naziv („Plaćeno odsustvo“) se smanjuje do ove veličine pre nego što se skrati tačkicama.
private const val CALENDAR_DETAIL_MIN_FONT_SIZE = 9f
/** Najmanja dodirna visina ćelije (M3 minimum). */
internal const val CALENDAR_MIN_CELL_HEIGHT = 48f
/**
 * Ćelija je široka oko 44dp, pa se font u njoj prati do ovoga; ostatak ekrana prati ceo sistemski font.
 * Veći font ne lomi ćelije nego ekran skroluje.
 */
internal const val CALENDAR_CELL_MAX_FONT_SCALE = 1.15f
/** Visina tri reda teksta ćelije (dan, smena, sati) pri fontu 1.0, sa malo vazduha. */
private const val CALENDAR_CELL_CONTENT_HEIGHT = 44f
private const val CALENDAR_CELL_VERTICAL_PADDING = 8f
private const val CALENDAR_PADDING = 6f

/** Font u ćeliji i zaglavlju dana: sistemski, ali najviše koliko staje u širinu ćelije. */
internal fun calendarCellFontScale(systemFontScale: Float): Float =
    minOf(systemFontScale, CALENDAR_CELL_MAX_FONT_SCALE)

/** Visina ćelije prati font u njoj; nikad ispod podrazumevane (58dp) ni M3 minimuma (48dp). */
internal fun calendarDayHeight(cellFontScale: Float): Float =
    maxOf(
        UI.dayButtonHeight.toFloat(),
        CALENDAR_MIN_CELL_HEIGHT,
        CALENDAR_CELL_CONTENT_HEIGHT * cellFontScale + CALENDAR_CELL_VERTICAL_PADDING
    )

@Composable
internal fun HeaderSection(month: Int, year: Int, onPrev: () -> Unit, onNext: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(UI.headerHeight.dp)
            .padding(horizontal = Spacing.xxs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        HeaderChevron(icon = Icons.Filled.ChevronLeft, label = "Prethodni mesec", onClick = onPrev)
        // Naslov ekrana: čitač ekrana ga najavljuje kao naslov („septembar 2026“) i do njega se skače.
        Row(
            modifier = Modifier.semantics(mergeDescendants = true) { heading() },
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            AutoSizeText(
                text = MONTH_NAMES[month],
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleLargeEmphasized,
                maxFontSize = MaterialTheme.typography.titleLarge.fontSize,
                minFontSize = MaterialTheme.typography.titleMedium.fontSize,
                maxLines = 1,
                modifier = Modifier.alignByBaseline()
            )
            Text(
                text = year.toString(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.alignByBaseline()
            )
        }
        HeaderChevron(icon = Icons.Filled.ChevronRight, label = "Sledeći mesec", onClick = onNext)
    }
}

/** Strelica za mesec: vidljiv krug 40dp u dodirnoj površini od 48dp, sa ripple-om i opisom. */
@Composable
internal fun HeaderChevron(icon: ImageVector, label: String, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .size(UI.iconButtonSize.dp)
            .clip(CircleShape)
            .radClickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(CHEVRON_VISUAL_SIZE_DP.dp)
                .background(scheme.surfaceContainer, CircleShape)
                .border(1.dp, scheme.outlineVariant.copy(alpha = CARD_BORDER_ALPHA), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = scheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp)
            )
        }
    }
}

private const val CHEVRON_VISUAL_SIZE_DP = 40

@Composable
internal fun WeekdayHeader() {
    val density = LocalDensity.current
    // Sedam stubaca je uskih kao ćelije: isti najviši font kao u kalendaru.
    CompositionLocalProvider(LocalDensity provides Density(density.density, calendarCellFontScale(density.fontScale))) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(UI.weekdayHeaderHeight.dp)
                // Iste ivice i razmaci kao mreža dana u [CalendarSection]: svaki naziv je tačno iznad svoje kolone.
                .padding(horizontal = CALENDAR_PADDING.dp),
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
            verticalAlignment = Alignment.CenterVertically
        ) {
            WEEKDAY_NAMES.forEachIndexed { index, name ->
                val color = if (index >= 5) MaterialTheme.extendedColors.weekendOt.copy(alpha = 0.75f) else MaterialTheme.colorScheme.onSurfaceVariant
                Text(
                    text = name,
                    color = color,
                    style = MaterialTheme.typography.labelSmallEmphasized.copy(
                        letterSpacing = 0.6.sp,
                        textAlign = TextAlign.Center
                    ),
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
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
    refreshKey: Int,
    onDayClick: (Int, DayRecord?) -> Unit
) {
    val weeks = remember(year, month) { getMonthMatrix(year, month) }
    // Jedan upit za ceo mesec; ponovo se čita samo kad se podaci izmene (refreshKey).
    val recordsByDay = remember(year, month, refreshKey) { repo.getMonthRecords(year, month).associateBy { it.day } }
    val brigadeType = remember(refreshKey) { repo.brigadeTypeValue }
    val density = LocalDensity.current
    val cellFontScale = calendarCellFontScale(density.fontScale)
    val dayHeight = calendarDayHeight(cellFontScale)
    GlassCard(modifier = modifier.fillMaxWidth()) {
        CompositionLocalProvider(LocalDensity provides Density(density.density, cellFontScale)) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(CALENDAR_PADDING.dp),
                verticalArrangement = Arrangement.spacedBy(Spacing.xs)
            ) {
                for (week in weeks) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
                    ) {
                        for (day in week) {
                            val record = if (day != 0) recordsByDay[day] else null
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
                                height = dayHeight,
                                onClick = { onDayClick(day, record) }
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Ćelija dana: tonirana bojom smene (18%), broj dana u punoj boji smene,
 * naziv smene i sati ispod. Bez senke na tekstu. Današnji dan ima cijan okvir.
 */
@Composable
private fun RowScope.DayButton(
    day: Int,
    record: DayRecord?,
    isToday: Boolean,
    year: Int,
    month: Int,
    brigadeType: BrigadeType,
    height: Float,
    onClick: () -> Unit
) {
    if (day == 0) {
        Spacer(modifier = Modifier.height(height.dp).weight(1f))
        return
    }

    val isHolidayDay = record?.holidayOverride ?: isSerbianHoliday(year, month, day)
    val workedHoliday = record?.workedOnHoliday ?: false
    val tint = calendarDayTint(
        record,
        isHolidayDay,
        workedHoliday,
        MaterialTheme.colorScheme,
        MaterialTheme.extendedColors
    )
    val detailText = calendarDayDetailText(record, isHolidayDay, workedHoliday, brigadeType)
    val detailLines = detailText.split("\n")

    val doubleIndicatorColor = if (record?.isDoubleShift == true && record.shift2 != null) {
        if (record.isCompTimeOvertime) MaterialTheme.extendedColors.compTime else record.shift2.color
    } else {
        null
    }

    val compIndicatorColor = if (record != null) {
        when {
            record.hoursDeficitMinutesFor(brigadeType) > 0 -> MaterialTheme.extendedColors.compTimeDeficit
            record.compHoursUsed > 0 -> MaterialTheme.extendedColors.compTimeUsed
            record.compensatoryAdditionalWorkMinutes > 0 -> MaterialTheme.extendedColors.compTime
            else -> null
        }
    } else {
        null
    }

    val shape = MaterialTheme.shapes.small
    val borderColor = if (isToday) MaterialTheme.colorScheme.primary else tint.border
    val description = calendarDayDescription(
        day = day,
        month = month,
        record = record,
        isToday = isToday,
        isHolidayDay = isHolidayDay,
        workedHoliday = workedHoliday,
        brigadeType = brigadeType
    )
    Box(
        modifier = Modifier
            .height(height.dp)
            .weight(1f)
            .clip(shape)
            .background(tint.background)
            .border(1.dp, borderColor, shape)
            .radClickable(onClick = onClick)
            // Tekst u ćeliji je skraćen („Bolo-\nvanje“, „-“); čitač ekrana dobija pun opis dana.
            .clearAndSetSemantics { contentDescription = description }
    ) {
        DayIndicatorGroup(
            indicators = DayIndicators(
                doubleShift = doubleIndicatorColor,
                compTime = compIndicatorColor
            )
        )

        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxWidth()
                .padding(horizontal = Spacing.xs),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(1.dp)
        ) {
            Text(
                text = day.toString(),
                color = tint.dayNumber,
                style = MaterialTheme.typography.labelMediumEmphasized.copy(
                    fontSize = 13.sp,
                    lineHeight = 15.sp
                ),
                maxLines = 1
            )
            // Prvo smanji font (npr. "Praznik" u uskoj ćeliji), tek na minimumu skrati tačkicama.
            // Dve smene su rimski brojevi („II + III“), serifni da liče na prave.
            val romanShifts = record?.isDoubleShift == true
            val detailStyle = MaterialTheme.typography.labelSmall
            AutoSizeText(
                text = detailLines[0],
                color = tint.detail,
                style = detailStyle.copy(
                    lineHeight = 12.sp,
                    textAlign = TextAlign.Center,
                    fontFamily = if (romanShifts) FontFamily.Serif else detailStyle.fontFamily,
                    letterSpacing = if (romanShifts) 0.sp else detailStyle.letterSpacing
                ),
                maxFontSize = CALENDAR_DETAIL_FONT_SIZE.sp,
                minFontSize = CALENDAR_DETAIL_MIN_FONT_SIZE.sp,
                maxLines = 1,
                modifier = Modifier.fillMaxWidth()
            )
            if (detailLines.size > 1) {
                // Odsustvo u dva reda („Godišnji / odmor") je jedan naziv, pa je i drugi red iste boje;
                // kod smena je drugi red broj sati.
                val isLeaveLabel = record?.shift == Shift.VACATION || record?.shift == Shift.SICK
                AutoSizeText(
                    text = detailLines[1],
                    color = if (isLeaveLabel) tint.detail else MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall.copy(
                        lineHeight = 12.sp,
                        textAlign = TextAlign.Center
                    ),
                    maxFontSize = CALENDAR_DETAIL_FONT_SIZE.sp,
                    minFontSize = CALENDAR_DETAIL_MIN_FONT_SIZE.sp,
                    maxLines = 1,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

/**
 * Pun opis dana za čitač ekrana: datum, smena/odsustvo, sati i oznake koje se u ćeliji vide
 * samo bojom (dvostruka smena, manjak sati, kompenzacioni sati).
 */
internal fun calendarDayDescription(
    day: Int,
    month: Int,
    record: DayRecord?,
    isToday: Boolean,
    isHolidayDay: Boolean,
    workedHoliday: Boolean,
    brigadeType: BrigadeType
): String {
    val parts = mutableListOf("$day. ${MONTH_NAMES[month].lowercase()}")
    if (isToday) parts += "danas"
    if (isHolidayDay || workedHoliday) parts += "praznik"
    if (record == null) {
        parts += "nema unosa"
        return parts.joinToString(", ")
    }
    val shiftName = if (record.isDoubleShift && record.shift2 != null) {
        "${record.shift.fullName} i ${record.shift2.fullName.lowercase()}"
    } else {
        record.shift.fullName
    }
    parts += shiftName
    val regularShift = record.shift != Shift.OFF && record.shift != Shift.SICK && record.shift != Shift.VACATION
    if (regularShift) {
        val hours = if (record.isDoubleShift) record.totalHours else record.workHours
        parts += formatHours(hours)
    }
    if (record.hoursDeficitMinutesFor(brigadeType) > 0) parts += "manjak sati"
    if (record.compHoursUsed > 0) parts += "iskorišćeni kompenzacioni sati"
    if (record.compensatoryAdditionalWorkMinutes > 0) parts += "kompenzacioni sati"
    return parts.joinToString(", ")
}

/** Boje jedne ćelije: tonirana pozadina, okvir, broj dana i tekst detalja. */
internal data class CalendarDayTint(
    val background: Color,
    val border: Color,
    val dayNumber: Color,
    val detail: Color
)

private fun tintFor(color: Color, colors: ExtendedColors): CalendarDayTint = CalendarDayTint(
    background = color.copy(alpha = DAY_TINT_ALPHA),
    border = color.copy(alpha = DAY_TINT_BORDER_ALPHA),
    dayNumber = color,
    detail = colors.textSecondary
)

/** Prazan dan i slobodan dan su "tihi": bez pozadine, jedva vidljiv okvir. */
private fun quietTint(dayNumber: Color, detail: Color, scheme: ColorScheme): CalendarDayTint = CalendarDayTint(
    background = Color.Transparent,
    border = scheme.surfaceContainerHighest.copy(alpha = 0.7f),
    dayNumber = dayNumber,
    detail = detail
)

/** Boje ćelije iz teme; čista funkcija (bez kompozicije), pa je testabilna za svaku temu. */
internal fun calendarDayTint(
    record: DayRecord?,
    isHolidayDay: Boolean,
    workedHoliday: Boolean,
    scheme: ColorScheme,
    colors: ExtendedColors
): CalendarDayTint {
    if (isHolidayDay || workedHoliday) return tintFor(colors.warning, colors)
    if (record == null) return quietTint(dayNumber = scheme.onSurfaceVariant, detail = colors.textFaint, scheme = scheme)
    return if (record.shift == Shift.OFF) {
        quietTint(dayNumber = colors.textFaint, detail = colors.textFaint, scheme = scheme)
    } else {
        tintFor(record.shift.colorIn(colors), colors)
    }
}

/**
 * Rimski broj smene. Ćelija ga crta serifnim fontom ([FontFamily.Serif]), pa I dobija crtice gore i dole
 * kao pravi rimski broj; Unicode Ⅱ u Robotu izgleda kao obično II, a Space Grotesk ga nema.
 */
internal fun shiftRomanNumeral(shift: Shift): String = when (shift) {
    Shift.FIRST -> "I"
    Shift.SECOND -> "II"
    Shift.THIRD -> "III"
    else -> shift.icon
}

internal fun calendarDayDetailText(
    record: DayRecord?,
    isHolidayDay: Boolean,
    workedHoliday: Boolean,
    brigadeType: BrigadeType = BrigadeType.TROBRIGADA
): String {
    if (record == null) return if (isHolidayDay || workedHoliday) "Praznik" else "-"

    return when (record.shift) {
        Shift.OFF -> when {
            isHolidayDay || workedHoliday -> "Praznik"
            // Četvorobrigada: slobodan dan po ciklusu (balanceExempt) nije neodrađen radni dan.
            record.weekday.isWorkday && !(brigadeType == BrigadeType.CETVOROBRIGADA && record.balanceExempt) -> "0h"
            else -> "OFF"
        }
        Shift.SICK -> when {
            // Davanje krvi se na listiću vodi kao plaćeno odsustvo, pa se tako i prikazuje.
            record.isPaidLeave -> "Plaćeno\nodsustvo"
            // Ceo naziv ne staje u jedan red uske ćelije, pa se deli kao „Godišnji / odmor“.
            else -> "Bolo-\nvanje"
        }
        Shift.VACATION -> "Godišnji\nodmor"
        else -> {
            // Dve smene rečima („Druga + Treća“) ne staju u ćeliju, pa idu rimskim brojevima: „II + III“.
            val shiftLabel = if (record.isDoubleShift && record.shift2 != null) {
                "${shiftRomanNumeral(record.shift)} + ${shiftRomanNumeral(record.shift2)}"
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

/**
 * Panel izabranog dana na širokom ekranu (desna kolona): pun opis dana kao za čitač ekrana i dugme za izmenu.
 */
@Composable
internal fun SelectedDayCard(
    year: Int,
    month: Int,
    day: Int,
    today: LocalDate,
    record: DayRecord?,
    brigadeType: BrigadeType,
    onEdit: () -> Unit
) {
    val isHolidayDay = record?.holidayOverride ?: isSerbianHoliday(year, month, day)
    val description = calendarDayDescription(
        day = day,
        month = month,
        record = record,
        isToday = today == LocalDate.of(year, month, day),
        isHolidayDay = isHolidayDay,
        workedHoliday = record?.workedOnHoliday ?: false,
        brigadeType = brigadeType
    )
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            Text(
                text = "$day. ${MONTH_NAMES[month]}",
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleLargeEmphasized
            )
            Text(
                text = description.substringAfter(", "),
                color = MaterialTheme.extendedColors.textSecondary,
                style = MaterialTheme.typography.bodyMedium
            )
            GlowButton(text = "Izmeni", modifier = Modifier.fillMaxWidth(), onClick = onEdit)
        }
    }
}
