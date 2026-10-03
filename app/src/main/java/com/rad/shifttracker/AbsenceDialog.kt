package com.rad.shifttracker

import androidx.compose.ui.text.input.ImeAction
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.rad.shifttracker.ui.components.ResponsiveInfoRow
import java.time.YearMonth
import kotlin.math.abs


// Existing dialog flow is intentionally grouped; detekt stays active for newly added methods.
@Suppress("LongMethod", "CyclomaticComplexMethod")
@Composable
fun SickVacationDialog(
    initialYear: Int,
    initialMonth: Int,
    scheduler: ShiftScheduler,
    repo: ShiftRepository,
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
            val start = startText.toIntOrNull()
            val end = endText.toIntOrNull()
            if (
                start == null ||
                end == null ||
                start !in 1..maxDays ||
                end !in 1..maxDays ||
                start > end
            ) {
                null
            } else {
                repo.previewAbsenceRangeNetImpact(
                    year = year,
                    month = month,
                    startDay = start,
                    endDay = end,
                    absenceShift = if (absenceType == AbsenceType.SICK) Shift.SICK else Shift.VACATION,
                    sickPayRate = if (absenceType == AbsenceType.SICK) sickPayRate else null
                )
            }
        }
    }
    val vacationPreview by remember(year, month, maxDays, absenceType, startText, endText, repo) {
        derivedStateOf {
            val start = startText.toIntOrNull()
            val end = endText.toIntOrNull()
            if (
                absenceType != AbsenceType.VACATION ||
                start == null ||
                end == null ||
                start !in 1..maxDays ||
                end !in 1..maxDays ||
                start > end
            ) {
                null
            } else {
                scheduler.previewVacationRange(repo, year, month, start, end)
            }
        }
    }

    PopupDialog(
        title = "Bolovanje / Godi\u0161nji",
        onDismiss = onDismiss,
        scrollable = true
    ) {
        PopupCard {
            SectionLabel(text = "Mesec odsustva")
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                HeaderChevron(icon = Icons.Filled.ChevronLeft, label = "Prethodni mesec") {
                    shownMonth = shownMonth.minusMonths(1)
                    errorMessage = null
                }
                Text(
                    text = "${MONTH_NAMES[month]} $year",
                    color = THEME.textSecondary,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                HeaderChevron(icon = Icons.Filled.ChevronRight, label = "Sledeći mesec") {
                    shownMonth = shownMonth.plusMonths(1)
                    errorMessage = null
                }
            }
        }

        PopupCard {
            SectionLabel(text = "Opseg dana")
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ModernTextField(
                    value = startText,
                    onValueChange = {
                        startText = it.filter(Char::isDigit)
                        errorMessage = null
                    },
                    hintText = "Početak",
                    label = "Od",
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                    modifier = Modifier.weight(1f)
                )
                ModernTextField(
                    value = endText,
                    onValueChange = {
                        endText = it.filter(Char::isDigit)
                        errorMessage = null
                    },
                    hintText = "Kraj",
                    label = "Do",
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                    modifier = Modifier.weight(1f)
                )
            }
        }

        // Isti izgled izbora kao u prozoru dana (DayEditChoiceButton).
        PopupCard {
            SectionLabel(text = "Tip odsustva")
            val isSick = absenceType == AbsenceType.SICK
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                DayEditChoiceButton(
                    text = "Bolovanje",
                    selected = isSick,
                    minHeight = DAY_TYPE_CHOICE_HEIGHT,
                    onClick = {
                        absenceType = AbsenceType.SICK
                        errorMessage = null
                    }
                )
                DayEditChoiceButton(
                    text = "Godišnji",
                    selected = !isSick,
                    minHeight = DAY_TYPE_CHOICE_HEIGHT,
                    onClick = {
                        absenceType = AbsenceType.VACATION
                        errorMessage = null
                    }
                )
            }
            DayEditChoiceHeader(text = "Isplata bolovanja")
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                for (rate in listOf(CONFIG.pay.sickPayRate to "${(CONFIG.pay.sickPayRate * 100).toInt()}%", 1.0 to "100%")) {
                    DayEditChoiceButton(
                        text = rate.second,
                        // Za godišnji isplata bolovanja ne važi, pa nijedan procenat nije označen.
                        selected = isSick && abs(sickPayRate - rate.first) < 0.01,
                        minHeight = OVERTIME_TYPE_CHOICE_HEIGHT,
                        enabled = isSick,
                        onClick = { sickPayRate = rate.first }
                    )
                }
            }
        }

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
                val start = startText.toIntOrNull()
                val end = endText.toIntOrNull()

                // Validate inputs
                when {
                    start == null -> {
                        errorMessage = "Unesite početni dan."
                        return@GlowButton
                    }
                    end == null -> {
                        errorMessage = "Unesite krajnji dan."
                        return@GlowButton
                    }
                    start < 1 || start > maxDays -> {
                        errorMessage = "Početni dan mora biti između 1 i $maxDays."
                        return@GlowButton
                    }
                    end < 1 || end > maxDays -> {
                        errorMessage = "Krajnji dan mora biti između 1 i $maxDays."
                        return@GlowButton
                    }
                    start > end -> {
                        errorMessage = "Početni dan mora biti manji ili jednak krajnjem."
                        return@GlowButton
                    }
                }

                if (absenceType == AbsenceType.SICK) {
                    scheduler.applySickRange(repo, year, month, start, end, sickPayRate)
                } else {
                    val preview = vacationPreview
                    if (preview != null && !preview.requestValidation.isAllowed) {
                        errorMessage = preview.requestValidation.message
                        return@GlowButton
                    }
                    scheduler.applyVacationRange(repo, year, month, start, end)
                }
                onDismiss()
            }
        )
    }
}

@Composable
private fun VacationRangePreviewCard(preview: VacationRangePreview) {
    PopupCard(
        spacing = 5.dp
    ) {
        SectionLabel(text = "Godišnji")
        Text(
            text = vacationRangePreviewText(preview),
            color = THEME.textSecondary,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis
        )
        for (warning in preview.warnings) {
            Text(
                text = warning,
                color = THEME.warning,
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
        spacing = 5.dp
    ) {
        SectionLabel(text = "Neto promena")
        if (impact == null) {
            Text(
                text = "Unesi opseg dana za procenu.",
                color = THEME.textMuted,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            return@PopupCard
        }

        if (impact.affectedDays == 0) {
            Text(
                text = "U opsegu nema radnih dana za upis.",
                color = THEME.textMuted,
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
            valueStyle = MaterialTheme.typography.bodyMedium,
            labelColor = THEME.textMuted,
            valueColor = THEME.textSecondary,
            valueFontWeight = FontWeight.Bold
        )
        ResponsiveInfoRow(
            label = "Posle upisa:",
            value = "${formatCurrency(impact.previewNetPay)} ${CONFIG.pay.currency}",
            labelWeight = 0.42f,
            valueWeight = 0.58f,
            labelStyle = MaterialTheme.typography.bodySmall,
            valueStyle = MaterialTheme.typography.bodyMedium,
            labelColor = THEME.textMuted,
            valueColor = THEME.textSecondary,
            valueFontWeight = FontWeight.Bold
        )
        ResponsiveInfoRow(
            label = "Razlika:",
            value = formatAbsenceNetDifference(difference),
            labelWeight = 0.42f,
            valueWeight = 0.58f,
            labelStyle = MaterialTheme.typography.bodyMedium,
            valueStyle = MaterialTheme.typography.titleMedium,
            labelColor = THEME.textSecondary,
            valueColor = differenceColor,
            valueFontWeight = FontWeight.Bold
        )

        Text(
            text = absenceReplacementText(impact),
            color = THEME.textMuted,
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
