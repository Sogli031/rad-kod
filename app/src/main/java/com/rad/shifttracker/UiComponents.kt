package com.rad.shifttracker

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxColors
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchColors
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rad.shifttracker.ui.components.AutoSizeText
import java.time.LocalDate
import java.util.Locale

/** Najmanja dodirna površina kontrole (Material i WCAG 2.5.8): 48dp. */
internal val MIN_TOUCH_TARGET: Dp = 48.dp

/** Alfa tanke linije oko kartica — ukrasna ivica (outlineVariant), ne nosi informaciju. */
internal const val CARD_BORDER_ALPHA = 0.6f

/** Boje M3 `Button`-a iz prosleđenih boja; onemogućeno stanje prati M3 (container 12%, sadržaj 38%). */
@Composable
private fun radButtonColors(container: Color, content: Color): ButtonColors {
    val scheme = MaterialTheme.colorScheme
    return ButtonDefaults.buttonColors(
        containerColor = container,
        contentColor = content,
        disabledContainerColor = scheme.onSurface.copy(alpha = 0.12f),
        disabledContentColor = scheme.onSurface.copy(alpha = 0.38f)
    )
}

/** Tekst dugmeta koji se sam smanjuje da stane u jedan red (deli ga RoundedButton i GlowButton). */
@Composable
private fun RadButtonLabel(text: String, style: TextStyle, contentDescription: String? = null) {
    AutoSizeText(
        text = text,
        color = LocalContentColor.current,
        style = style.copy(textAlign = TextAlign.Center),
        maxFontSize = style.fontSize,
        minFontSize = MaterialTheme.typography.labelSmall.fontSize,
        maxLines = 1,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.xs, vertical = Spacing.xxs)
            .then(
                // Dugme sa jednim znakom (X, +, -) čitač izgovara kroz opis, ne kroz sam znak.
                if (contentDescription == null) {
                    Modifier
                } else {
                    Modifier.clearAndSetSemantics { this.contentDescription = contentDescription }
                }
            )
    )
}

@Composable
fun BackgroundLayer(modifier: Modifier = Modifier) {
    val background = MaterialTheme.colorScheme.background
    // Ravna pozadina; gornja traka i linija iz stare verzije su uklonjene.
    Canvas(modifier = modifier.fillMaxSize()) {
        drawRect(color = background)
    }
}

/**
 * Kartica kao M3 outlined card: površina `surfaceContainer`, tanka linija `outlineVariant`,
 * oblik `shapes.medium`. Boje se prosleđuju samo kad kartica sme da odstupa od teme.
 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    bgColor: Color = MaterialTheme.colorScheme.surfaceContainer,
    borderColor: Color = MaterialTheme.colorScheme.outlineVariant,
    shape: Shape = MaterialTheme.shapes.medium,
    content: @Composable BoxScope.() -> Unit
) {
    OutlinedCard(
        modifier = modifier,
        shape = shape,
        colors = CardDefaults.outlinedCardColors(containerColor = bgColor),
        border = BorderStroke(1.dp, borderColor.copy(alpha = CARD_BORDER_ALPHA))
    ) {
        Box { content() }
    }
}

/**
 * Klik sa M3 ripple-om i ulogom dugmeta (TalkBack ga najavljuje kao dugme).
 * Klip oblika mora da stoji PRE ovog modifiera da bi ripple bio ograničen.
 */
fun Modifier.radClickable(
    enabled: Boolean = true,
    onClickLabel: String? = null,
    onClick: () -> Unit
): Modifier = clickable(enabled = enabled, onClickLabel = onClickLabel, role = Role.Button, onClick = onClick)

/**
 * Dugme (M3 `Button`) sa prosleđenim bojama; koristi se i za izbor vrednosti (izabrano = akcenat).
 * Oblik je `full`, dodirna površina najmanje 48dp, ripple i stanja su iz M3.
 * [contentDescription] zadaj kad je tekst samo znak (X, +, -).
 */
@Composable
fun RoundedButton(
    text: String,
    modifier: Modifier = Modifier,
    bgColor: Color,
    textColor: Color,
    borderColor: Color = MaterialTheme.colorScheme.outlineVariant,
    textStyle: TextStyle = MaterialTheme.typography.labelMedium,
    bold: Boolean = false,
    enabled: Boolean = true,
    contentDescription: String? = null,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        // Spoljni minimum pobeđuje i kad pozivalac zada nižu visinu: dodirna površina je uvek >= 48dp.
        modifier = Modifier.heightIn(min = MIN_TOUCH_TARGET).then(modifier),
        enabled = enabled,
        shape = CircleShape,
        colors = radButtonColors(container = bgColor, content = textColor),
        border = BorderStroke(1.dp, borderColor.copy(alpha = 0.5f)),
        contentPadding = PaddingValues(horizontal = Spacing.sm, vertical = 0.dp)
    ) {
        RadButtonLabel(
            text = text,
            style = if (bold) textStyle.emphasized() else textStyle,
            contentDescription = contentDescription
        )
    }
}

/**
 * Glavno dugme dijaloga („Sačuvaj“ i sl.): popunjeno `primary`; za destruktivne radnje
 * ([destructive]) `error`. Onemogućeno stanje i pritisak rešava M3 `Button`.
 */
@Composable
fun GlowButton(
    text: String,
    modifier: Modifier = Modifier,
    destructive: Boolean = false,
    textStyle: TextStyle = MaterialTheme.typography.labelLarge,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    Button(
        onClick = onClick,
        modifier = Modifier.heightIn(min = MIN_TOUCH_TARGET).then(modifier),
        enabled = enabled,
        shape = CircleShape,
        colors = radButtonColors(
            container = if (destructive) scheme.error else scheme.primary,
            content = if (destructive) scheme.onError else scheme.onPrimary
        ),
        contentPadding = PaddingValues(horizontal = Spacing.sm, vertical = 0.dp)
    ) {
        RadButtonLabel(text = text, style = textStyle.emphasized())
    }
}

/**
 * Red sa prekidačem kao jedna dodirna površina (>= 48dp): `toggleable` na celom redu daje čitaču ekrana
 * ime (naslov + opis), ulogu prekidača i stanje; sam [Switch] je samo slika (`onCheckedChange = null`).
 */
@Composable
fun LabeledSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    hint: String? = null,
    enabled: Boolean = true,
    titleStyle: TextStyle = MaterialTheme.typography.labelLarge,
    titleMaxLines: Int = 2,
    switchColors: SwitchColors = SwitchDefaults.colors(),
    contentPadding: PaddingValues = PaddingValues(0.dp)
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = MIN_TOUCH_TARGET)
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(contentPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md)
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs)
        ) {
            Text(
                text = title,
                color = MaterialTheme.colorScheme.onSurface,
                style = titleStyle,
                maxLines = titleMaxLines,
                overflow = TextOverflow.Ellipsis
            )
            hint?.let {
                Text(
                    text = it,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Switch(checked = checked, onCheckedChange = null, enabled = enabled, colors = switchColors)
    }
}

/**
 * Red sa potvrdnim poljem kao jedna dodirna površina (>= 48dp). Sadržaj reda ([content]) čini ime koje
 * čitač ekrana izgovara; [Checkbox] je samo slika (`onCheckedChange = null`) u polju od 48dp, kao pre.
 * Oblik i pozadinu reda zadaje pozivalac preko [modifier] (klip ide pre `toggleable`, da ripple bude ograničen).
 */
@Composable
fun LabeledCheckboxRow(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    checkboxColors: CheckboxColors = CheckboxDefaults.colors(),
    contentPadding: PaddingValues = PaddingValues(0.dp),
    content: @Composable RowScope.() -> Unit
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = MIN_TOUCH_TARGET)
            .toggleable(value = checked, enabled = enabled, role = Role.Checkbox, onValueChange = onCheckedChange)
            .padding(contentPadding),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(modifier = Modifier.size(MIN_TOUCH_TARGET), contentAlignment = Alignment.Center) {
            Checkbox(checked = checked, onCheckedChange = null, enabled = enabled, colors = checkboxColors)
        }
        content()
    }
}

/**
 * Greška u formi: čitač ekrana je izgovara čim se pojavi (`liveRegion` = Assertive) i prepoznaje je
 * kao grešku (`error`). Boja je `error` iz teme.
 */
@Composable
fun FormErrorText(
    message: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    maxLines: Int = 2
) {
    Text(
        text = message,
        color = MaterialTheme.colorScheme.error,
        style = style,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier.semantics {
            liveRegion = LiveRegionMode.Assertive
            error(message)
        }
    )
}


/** Naslov sekcije: sitna verzalna slova sa razmakom, prigušena boja. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.labelSmallEmphasized.copy(
            letterSpacing = 0.9.sp
        ),
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
            .clip(MaterialTheme.shapes.medium)
            .radClickable(onClick = onClick)
    } else {
        modifier.height(UI.statCardHeight.dp)
    }
    GlassCard(modifier = cardModifier) {
        StatColumn(label = label, value = value, color = color, modifier = Modifier.fillMaxSize())
    }
}

/** Vrednost + sitna etiketa; deli se između StatCard i zajedničke kartice statistike. */
@Composable
fun StatColumn(
    label: String,
    value: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .heightIn(min = UI.statCardHeight.dp)
            .padding(horizontal = Spacing.xs, vertical = Spacing.xs),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        AutoSizeText(
            text = value,
            color = color,
            style = MaterialTheme.typography.titleMediumEmphasized,
            maxFontSize = MaterialTheme.typography.titleMedium.fontSize,
            minFontSize = MaterialTheme.typography.bodyMedium.fontSize,
            maxLines = 1
        )
        AutoSizeText(
            text = label.uppercase(),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelSmallEmphasized.copy(
                letterSpacing = 0.6.sp,
                textAlign = TextAlign.Center
            ),
            maxFontSize = MaterialTheme.typography.labelSmall.fontSize,
            minFontSize = MaterialTheme.typography.labelSmall.fontSize,
            maxLines = 1,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/** Kartica iznad kalendara: neutralna pozadina, obojena je samo vrednost. */
@Composable
private fun SummaryCard(
    label: String,
    value: String,
    subtitle: String,
    valueColor: Color,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null
) {
    val clickModifier = if (onClick != null) {
        Modifier.clip(MaterialTheme.shapes.medium).radClickable(onClick = onClick)
    } else {
        Modifier
    }
    GlassCard(
        modifier = modifier
            .heightIn(min = UI.compTimeBarHeight.dp)
            .fillMaxWidth()
            .then(clickModifier)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.lg, vertical = Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.xxs)
        ) {
            SectionLabel(text = label)
            AutoSizeText(
                text = value,
                color = valueColor,
                style = MaterialTheme.typography.titleLargeEmphasized,
                maxFontSize = MaterialTheme.typography.titleLarge.fontSize,
                minFontSize = MaterialTheme.typography.titleMedium.fontSize,
                maxLines = 1
            )
            AutoSizeText(
                text = subtitle,
                color = MaterialTheme.extendedColors.textFaint,
                style = MaterialTheme.typography.labelSmall,
                maxFontSize = MaterialTheme.typography.labelSmall.fontSize,
                minFontSize = 10.sp,
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
        balanceMinutes > 0 -> MaterialTheme.extendedColors.compTime
        balanceMinutes < 0 -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    SummaryCard(
        label = "Bilans sati",
        value = formatHoursAndMinutes(balanceMinutes, showPlusForPositive = true),
        subtitle = "kompenzacioni sati",
        valueColor = mainColor,
        modifier = modifier,
        onClick = onClick
    )
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

    val mainColor = if (exceeded) MaterialTheme.colorScheme.error else MaterialTheme.extendedColors.vacation
    val displayText = if (exceeded) {
        "Prekora\u010deno!"
    } else {
        vacationCardMainText(summary)
    }
    val subtitleText = if (summary.currentYearTotal > 0 || summary.carryoverInitial > 0) {
        vacationCardSubtitleText(summary)
    } else {
        "podesi fond"
    }
    SummaryCard(
        label = "Godi\u0161nji",
        value = displayText,
        subtitle = subtitleText,
        valueColor = mainColor,
        modifier = modifier,
        onClick = onClick
    )
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

/**
 * Polje za unos. [label] je ime polja koje ostaje vidljivo i kad se unese vrednost (placeholder nestaje);
 * [isError] boji ivicu greškom. Za dugme tastature zadaj `imeAction` u [keyboardOptions]
 * (Next prelazi na sledeće polje, Done zatvara tastaturu).
 */
@Composable
fun ModernTextField(
    value: String,
    onValueChange: (String) -> Unit,
    hintText: String,
    keyboardOptions: KeyboardOptions,
    modifier: Modifier = Modifier,
    label: String? = null,
    isError: Boolean = false,
    keyboardActions: KeyboardActions = KeyboardActions.Default
) {
    val scheme = MaterialTheme.colorScheme
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        singleLine = true,
        isError = isError,
        shape = MaterialTheme.shapes.small,
        textStyle = MaterialTheme.typography.bodyLarge,
        label = label?.let {
            { Text(text = it, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        },
        placeholder = {
            Text(
                text = hintText,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        colors = OutlinedTextFieldDefaults.colors(
            // Ivica polja nosi informaciju (gde se piše), pa je `outline` (>= 3:1), ne ukrasna linija.
            focusedBorderColor = scheme.primary,
            unfocusedBorderColor = scheme.outline,
            cursorColor = scheme.primary,
            focusedPlaceholderColor = scheme.onSurfaceVariant,
            unfocusedPlaceholderColor = scheme.onSurfaceVariant,
            focusedContainerColor = scheme.surfaceContainerLowest,
            unfocusedContainerColor = scheme.surfaceContainerLowest
        )
    )
}
