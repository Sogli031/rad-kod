package com.rad.shifttracker

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.rad.shifttracker.ui.components.AutoSizeText
import com.rad.shifttracker.ui.components.ResponsiveInfoRow
import kotlin.math.abs

private const val ABSENCE_TYPE_CHOICE_HEIGHT = 58
private const val ABSENCE_RATE_CHOICE_HEIGHT = 58

// Existing dialog flow is intentionally grouped; detekt stays active for newly added methods.
@Suppress("LongMethod", "CyclomaticComplexMethod")
@Composable
fun SickVacationDialog(
    year: Int,
    month: Int,
    scheduler: ShiftScheduler,
    repo: ShiftRepository,
    onDismiss: () -> Unit
) {
    var absenceType by remember { mutableStateOf(AbsenceType.SICK) }
    var sickPayRate by remember { mutableDoubleStateOf(CONFIG.pay.sickPayRate) }
    var startText by remember { mutableStateOf("") }
    var endText by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // Get max days in this month for validation
    val maxDays = remember(year, month) {
        java.time.YearMonth.of(year, month).lengthOfMonth()
    }
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
        widthRatio = 0.85f,
        scrollable = true,
        accent = THEME.info
    ) {
        PopupCard(bgColor = THEME.bgCardElevated, borderColor = THEME.info) {
            AutoSizeText(
                text = "Unos odsustva",
                color = THEME.textPrimary,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                maxFontSize = MaterialTheme.typography.titleMedium.fontSize,
                minFontSize = MaterialTheme.typography.titleSmall.fontSize,
                maxLines = 1
            )
            Text(
                text = "${MONTH_NAMES[month]} $year",
                color = THEME.textSecondary,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        PopupCard {
            SectionLabel(text = "Tip odsustva")
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = ABSENCE_TYPE_CHOICE_HEIGHT.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val isSick = absenceType == AbsenceType.SICK
                RoundedButton(
                    text = "BOLOVANJE",
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = ABSENCE_TYPE_CHOICE_HEIGHT.dp),
                    bgColor = if (isSick) THEME.sick else THEME.bgCardElevated,
                    textColor = THEME.textPrimary,
                    radius = 14.dp,
                    textStyle = MaterialTheme.typography.labelLarge,
                    bold = true,
                    onClick = {
                        absenceType = AbsenceType.SICK
                        errorMessage = null
                    }
                )
                RoundedButton(
                    text = "GODI\u0160NJI",
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = ABSENCE_TYPE_CHOICE_HEIGHT.dp),
                    bgColor = if (!isSick) THEME.vacation else THEME.bgCardElevated,
                    textColor = THEME.textPrimary,
                    radius = 14.dp,
                    textStyle = MaterialTheme.typography.labelLarge,
                    bold = true,
                    onClick = {
                        absenceType = AbsenceType.VACATION
                        errorMessage = null
                    }
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val isSick = absenceType == AbsenceType.SICK
                Text(
                    text = "Isplata bolovanja",
                    color = if (isSick) THEME.textSecondary else THEME.textMuted,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = ABSENCE_RATE_CHOICE_HEIGHT.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    for (rate in listOf(CONFIG.pay.sickPayRate to "${(CONFIG.pay.sickPayRate * 100).toInt()}%", 1.0 to "100%")) {
                        val selected = kotlin.math.abs(sickPayRate - rate.first) < 0.01
                        val bgColor = if (isSick) {
                            if (selected) THEME.sick else THEME.bgCardElevated
                        } else {
                            THEME.bgCard
                        }
                        val textColor = if (isSick) THEME.textPrimary else THEME.textMuted
                        RoundedButton(
                            text = rate.second,
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = ABSENCE_RATE_CHOICE_HEIGHT.dp),
                            bgColor = bgColor,
                            textColor = textColor,
                            radius = 14.dp,
                            textStyle = MaterialTheme.typography.labelLarge,
                            bold = true,
                            enabled = isSick,
                            onClick = { sickPayRate = rate.first }
                        )
                    }
                }
            }
        }

        PopupCard {
            SectionLabel(text = "Opseg dana")
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(72.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "Od",
                        color = THEME.textSecondary,
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    ModernTextField(
                        value = startText,
                        onValueChange = {
                            startText = it.filter(Char::isDigit)
                            errorMessage = null
                        },
                        hintText = "Početak",
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "Do",
                        color = THEME.textSecondary,
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    ModernTextField(
                        value = endText,
                        onValueChange = {
                            endText = it.filter(Char::isDigit)
                            errorMessage = null
                        },
                        hintText = "Kraj",
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }

        AbsenceNetImpactCard(previewImpact)
        vacationPreview?.let { preview ->
            VacationRangePreviewCard(preview)
        }

        errorMessage?.let { message ->
            Text(
                text = message,
                color = THEME.danger,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }

        GlowButton(
            text = "Upi\u0161i",
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp),
            bgColor = THEME.info,
            textColor = THEME.textPrimary,
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
        bgColor = THEME.bgCardElevated,
        borderColor = if (preview.warnings.isEmpty()) THEME.vacation else THEME.warning,
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
        bgColor = THEME.bgCardElevated,
        borderColor = if (impact == null) THEME.glassBorder else differenceColor,
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
