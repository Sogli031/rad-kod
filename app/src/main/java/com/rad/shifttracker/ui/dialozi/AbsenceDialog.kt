package com.rad.shifttracker.ui.dialozi

import com.rad.shifttracker.ui.DataActions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.rad.shifttracker.ui.components.ResponsiveInfoRow
import java.time.YearMonth
import kotlin.math.abs
import com.rad.shifttracker.osnova.CONFIG
import com.rad.shifttracker.osnova.MONTH_NAMES
import com.rad.shifttracker.osnova.formatCurrency
import com.rad.shifttracker.repo.ShiftRepository
import com.rad.shifttracker.repo.previewAbsenceRangeNetImpact
import com.rad.shifttracker.smene.Shift
import com.rad.shifttracker.smene.ShiftScheduler
import com.rad.shifttracker.ui.FormErrorText
import com.rad.shifttracker.ui.GlowButton
import com.rad.shifttracker.ui.HeaderChevron
import com.rad.shifttracker.ui.PopupCard
import com.rad.shifttracker.ui.PopupDialog
import com.rad.shifttracker.ui.SectionLabel
import com.rad.shifttracker.ui.extendedColors
import com.rad.shifttracker.ui.titleMediumEmphasized
import com.rad.shifttracker.godisnji.VacationRangePreview
import com.rad.shifttracker.repo.AbsenceRangeNetImpact
import com.rad.shifttracker.ui.ModernTextField
import com.rad.shifttracker.ui.Spacing
import com.rad.shifttracker.ui.bodyMediumEmphasized
import com.rad.shifttracker.repo.previewVacationRange

@Composable
internal fun SickVacationDialog(
    initialYear: Int,
    initialMonth: Int,
    scheduler: ShiftScheduler,
    repo: ShiftRepository,
    actions: DataActions,
    onDismiss: () -> Unit
) {
    // Mesec se menja strelicama u dijalogu, bez vraćanja na kalendar.
    var shownMonth by remember { mutableStateOf(YearMonth.of(initialYear, initialMonth)) }
    val year = shownMonth.year
    val month = shownMonth.monthValue
    var absenceType by remember { mutableStateOf(AbsenceType.SICK) }
    var sickPayRate by remember { mutableDoubleStateOf(CONFIG.pay.sickPayRate) }
    var startText by remember { mutableStateOf("") }
    var endText by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // Get max days in this month for validation
    val maxDays = shownMonth.lengthOfMonth()
    val previewImpact by remember(year, month, maxDays, absenceType, sickPayRate, startText, endText, repo) {
        derivedStateOf {
            absenceDayRangeOrNull(startText, endText, maxDays)?.let { range ->
                repo.previewAbsenceRangeNetImpact(
                    year = year,
                    month = month,
                    startDay = range.first,
                    endDay = range.last,
                    absenceShift = if (absenceType == AbsenceType.SICK) Shift.SICK else Shift.VACATION,
                    sickPayRate = if (absenceType == AbsenceType.SICK) sickPayRate else null
                )
            }
        }
    }
    val vacationPreview by remember(year, month, maxDays, absenceType, startText, endText, repo) {
        derivedStateOf {
            absenceDayRangeOrNull(startText, endText, maxDays)
                ?.takeIf { absenceType == AbsenceType.VACATION }
                ?.let { range -> scheduler.previewVacationRange(repo, year, month, range.first, range.last) }
        }
    }

    PopupDialog(
        title = "Bolovanje / Godi\u0161nji",
        onDismiss = onDismiss,
        scrollable = true
    ) {
        AbsenceMonthCard(
            year = year,
            month = month,
            onPrevious = {
                shownMonth = shownMonth.minusMonths(1)
                errorMessage = null
            },
            onNext = {
                shownMonth = shownMonth.plusMonths(1)
                errorMessage = null
            }
        )

        AbsenceDayRangeCard(
            startText = startText,
            endText = endText,
            onStartChange = {
                startText = it.filter(Char::isDigit)
                errorMessage = null
            },
            onEndChange = {
                endText = it.filter(Char::isDigit)
                errorMessage = null
            }
        )

        AbsenceTypeCard(
            absenceType = absenceType,
            sickPayRate = sickPayRate,
            onAbsenceTypeSelected = {
                absenceType = it
                errorMessage = null
            },
            onSickPayRateSelected = { sickPayRate = it }
        )

        AbsenceNetImpactCard(previewImpact)
        vacationPreview?.let { preview ->
            VacationRangePreviewCard(preview)
        }

        errorMessage?.let { message ->
            FormErrorText(message = message)
        }

        GlowButton(
            text = "Upi\u0161i",
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp),
            textStyle = MaterialTheme.typography.labelLarge,
            onClick = {
                absenceDayRangeError(startText, endText, maxDays)?.let { message ->
                    errorMessage = message
                    return@GlowButton
                }
                val range = absenceDayRangeOrNull(startText, endText, maxDays) ?: return@GlowButton
                val start = range.first
                val end = range.last

                if (absenceType == AbsenceType.SICK) {
                    actions.applySickRange(scheduler, year, month, start, end, sickPayRate)
                } else {
                    val preview = vacationPreview
                    if (preview != null && !preview.requestValidation.isAllowed) {
                        errorMessage = preview.requestValidation.message
                        return@GlowButton
                    }
                    actions.applyVacationRange(scheduler, year, month, start, end)
                }
                onDismiss()
            }
        )
    }
}

@Composable
private fun AbsenceMonthCard(year: Int, month: Int, onPrevious: () -> Unit, onNext: () -> Unit) {
    PopupCard {
        SectionLabel(text = "Mesec odsustva")
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            HeaderChevron(icon = Icons.Filled.ChevronLeft, label = "Prethodni mesec", onClick = onPrevious)
            Text(
                text = "${MONTH_NAMES[month]} $year",
                color = MaterialTheme.extendedColors.textSecondary,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            HeaderChevron(icon = Icons.Filled.ChevronRight, label = "Sledeći mesec", onClick = onNext)
        }
    }
}

@Composable
private fun AbsenceDayRangeCard(
    startText: String,
    endText: String,
    onStartChange: (String) -> Unit,
    onEndChange: (String) -> Unit
) {
    PopupCard {
        SectionLabel(text = "Opseg dana")
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            ModernTextField(
                value = startText,
                onValueChange = onStartChange,
                hintText = "Početak",
                label = "Od",
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                modifier = Modifier.weight(1f)
            )
            ModernTextField(
                value = endText,
                onValueChange = onEndChange,
                hintText = "Kraj",
                label = "Do",
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun AbsenceTypeCard(
    absenceType: AbsenceType,
    sickPayRate: Double,
    onAbsenceTypeSelected: (AbsenceType) -> Unit,
    onSickPayRateSelected: (Double) -> Unit
) {
    // Isti izgled izbora kao u prozoru dana (DayEditChoiceButton).
    PopupCard {
        SectionLabel(text = "Tip odsustva")
        val isSick = absenceType == AbsenceType.SICK
        Row(
            modifier = Modifier.fillMaxWidth().selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            DayEditChoiceButton(
                text = "Bolovanje",
                selected = isSick,
                minHeight = DAY_TYPE_CHOICE_HEIGHT,
                onClick = { onAbsenceTypeSelected(AbsenceType.SICK) }
            )
            DayEditChoiceButton(
                text = "Godišnji",
                selected = !isSick,
                minHeight = DAY_TYPE_CHOICE_HEIGHT,
                onClick = { onAbsenceTypeSelected(AbsenceType.VACATION) }
            )
        }
        DayEditChoiceHeader(text = "Isplata bolovanja")
        Row(
            modifier = Modifier.fillMaxWidth().selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            for (rate in listOf(CONFIG.pay.sickPayRate to "${(CONFIG.pay.sickPayRate * 100).toInt()}%", 1.0 to "100%")) {
                DayEditChoiceButton(
                    text = rate.second,
                    // Za godišnji isplata bolovanja ne važi, pa nijedan procenat nije označen.
                    selected = isSick && abs(sickPayRate - rate.first) < 0.01,
                    minHeight = OVERTIME_TYPE_CHOICE_HEIGHT,
                    enabled = isSick,
                    onClick = { onSickPayRateSelected(rate.first) }
                )
            }
        }
    }
}

/** Opseg dana odsustva iz unosa, ili null dok unos nije potpun i ispravan za mesec od [maxDays] dana. */
internal fun absenceDayRangeOrNull(startText: String, endText: String, maxDays: Int): IntRange? {
    val start = startText.toIntOrNull() ?: return null
    val end = endText.toIntOrNull() ?: return null
    if (start !in 1..maxDays || end !in 1..maxDays || start > end) return null
    return start..end
}

/** Poruka za neispravan opseg dana (prva greška redom: početak, kraj, granice, redosled), ili null. */
internal fun absenceDayRangeError(startText: String, endText: String, maxDays: Int): String? {
    val start = startText.toIntOrNull()
    val end = endText.toIntOrNull()
    return when {
        start == null -> "Unesite početni dan."
        end == null -> "Unesite krajnji dan."
        start < 1 || start > maxDays -> "Početni dan mora biti između 1 i $maxDays."
        end < 1 || end > maxDays -> "Krajnji dan mora biti između 1 i $maxDays."
        start > end -> "Početni dan mora biti manji ili jednak krajnjem."
        else -> null
    }
}

@Composable
private fun VacationRangePreviewCard(preview: VacationRangePreview) {
    PopupCard(
        spacing = Spacing.xs
    ) {
        SectionLabel(text = "Godišnji")
        Text(
            text = vacationRangePreviewText(preview),
            color = MaterialTheme.extendedColors.textSecondary,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis
        )
        for (warning in preview.warnings) {
            Text(
                text = warning,
                color = MaterialTheme.extendedColors.warning,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun AbsenceNetImpactCard(impact: AbsenceRangeNetImpact?) {
    val difference = impact?.netDifference ?: 0.0
    val differenceColor = absenceNetDifferenceColor(difference)
    PopupCard(
        spacing = Spacing.xs
    ) {
        SectionLabel(text = "Neto promena")
        if (impact == null) {
            Text(
                text = "Unesi opseg dana za procenu.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            return@PopupCard
        }

        if (impact.affectedDays == 0) {
            Text(
                text = "U opsegu nema radnih dana za upis.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            return@PopupCard
        }

        ResponsiveInfoRow(
            label = "Sada:",
            value = "${formatCurrency(impact.currentNetPay)} ${CONFIG.pay.currency}",
            labelWeight = 0.42f,
            valueWeight = 0.58f,
            labelStyle = MaterialTheme.typography.bodySmall,
            valueStyle = MaterialTheme.typography.bodyMediumEmphasized,
            labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
            valueColor = MaterialTheme.extendedColors.textSecondary,
            
        )
        ResponsiveInfoRow(
            label = "Posle upisa:",
            value = "${formatCurrency(impact.previewNetPay)} ${CONFIG.pay.currency}",
            labelWeight = 0.42f,
            valueWeight = 0.58f,
            labelStyle = MaterialTheme.typography.bodySmall,
            valueStyle = MaterialTheme.typography.bodyMediumEmphasized,
            labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
            valueColor = MaterialTheme.extendedColors.textSecondary,
            
        )
        ResponsiveInfoRow(
            label = "Razlika:",
            value = formatAbsenceNetDifference(difference),
            labelWeight = 0.42f,
            valueWeight = 0.58f,
            labelStyle = MaterialTheme.typography.bodyMedium,
            valueStyle = MaterialTheme.typography.titleMediumEmphasized,
            labelColor = MaterialTheme.extendedColors.textSecondary,
            valueColor = differenceColor,
            
        )

        Text(
            text = absenceReplacementText(impact),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

private enum class AbsenceType {
    SICK,
    VACATION
}
