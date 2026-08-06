package com.rad.shifttracker

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import com.rad.shifttracker.ui.components.AutoSizeText
import kotlin.math.roundToInt

internal const val WORK_HOURS_DIALOG_TITLE = "Sati rada"

private const val WORK_HOURS_DIALOG_WIDTH_RATIO = 0.85f
private const val WORK_HOURS_MINUTES_PER_HOUR = 60
private const val WORK_HOURS_TABLE_LABEL_WEIGHT = 1.15f
private const val WORK_HOURS_TABLE_HOURS_WEIGHT = 0.55f
private const val WORK_HOURS_TABLE_AMOUNT_WEIGHT = 1.3f

@Composable
fun WorkHoursDialog(
    summary: MonthSummary,
    payBreakdown: MonthlyNetPayBreakdown,
    month: MonthKey,
    payDetails: MonthlyPayDetails,
    deficitNetLoss: Double,
    onDismiss: () -> Unit
) {
    PopupDialog(
        title = WORK_HOURS_DIALOG_TITLE,
        onDismiss = onDismiss,
        widthRatio = WORK_HOURS_DIALOG_WIDTH_RATIO,
        scrollable = true,
        accent = THEME.textPrimary
    ) {
        PopupCard(bgColor = THEME.bgCardElevated, borderColor = THEME.textPrimary) {
            SectionLabel(text = monthYearText(month))
        }

        WorkHoursSection("Pregled", overviewRows(summary, payBreakdown, payDetails))
        WorkHoursTableSection("Dodaci", addOnRows(payBreakdown, payDetails))
        WorkHoursTableSection("Izostanci i naknade", absenceAndLeaveRows(payBreakdown, payDetails, deficitNetLoss))
        WorkHoursSection("Ostalo u obračunu", otherPayrollRows(payBreakdown, payDetails))
    }
}

@Composable
private fun WorkHoursSection(title: String, rows: List<WorkHoursBreakdownLine>) {
    if (rows.isEmpty()) return
    PopupCard {
        SectionLabel(text = title)
        rows.forEach { row ->
            WorkHoursRow(row)
        }
    }
}

@Composable
private fun WorkHoursTableSection(title: String, rows: List<WorkHoursTableLine>) {
    if (rows.isEmpty()) return
    PopupCard {
        SectionLabel(text = title)
        WorkHoursTableHeader()
        rows.forEach { row ->
            WorkHoursTableRow(row)
        }
    }
}

@Composable
private fun WorkHoursRow(row: WorkHoursBreakdownLine) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        AutoSizeText(
            text = row.label,
            color = THEME.textSecondary,
            style = MaterialTheme.typography.bodyMedium,
            maxFontSize = MaterialTheme.typography.bodyMedium.fontSize,
            minFontSize = MaterialTheme.typography.bodySmall.fontSize,
            maxLines = 1,
            modifier = Modifier.weight(1.15f)
        )
        AutoSizeText(
            text = row.value,
            color = row.tone.color,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.End
            ),
            maxFontSize = MaterialTheme.typography.bodyMedium.fontSize,
            minFontSize = MaterialTheme.typography.bodySmall.fontSize,
            maxLines = 1,
            modifier = Modifier.weight(1.0f)
        )
    }
}

@Composable
private fun WorkHoursTableHeader() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        WorkHoursTableText(
            text = "Tip",
            color = THEME.textMuted,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Start,
            weight = WORK_HOURS_TABLE_LABEL_WEIGHT
        )
        WorkHoursTableText(
            text = "Sati",
            color = THEME.textMuted,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.End,
            weight = WORK_HOURS_TABLE_HOURS_WEIGHT
        )
        WorkHoursTableText(
            text = "Iznos",
            color = THEME.textMuted,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.End,
            weight = WORK_HOURS_TABLE_AMOUNT_WEIGHT
        )
    }
}

@Composable
private fun WorkHoursTableRow(row: WorkHoursTableLine) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        WorkHoursTableText(
            text = row.label,
            color = THEME.textSecondary,
            fontWeight = FontWeight.Normal,
            textAlign = TextAlign.Start,
            weight = WORK_HOURS_TABLE_LABEL_WEIGHT
        )
        WorkHoursTableText(
            text = row.hours,
            color = row.tone.color,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.End,
            weight = WORK_HOURS_TABLE_HOURS_WEIGHT
        )
        WorkHoursTableText(
            text = row.amount,
            color = row.tone.color,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.End,
            weight = WORK_HOURS_TABLE_AMOUNT_WEIGHT
        )
    }
}

@Composable
private fun RowScope.WorkHoursTableText(
    text: String,
    color: Color,
    fontWeight: FontWeight,
    textAlign: TextAlign,
    weight: Float
) {
    AutoSizeText(
        text = text,
        color = color,
        style = MaterialTheme.typography.bodyMedium.copy(
            fontWeight = fontWeight,
            textAlign = textAlign
        ),
        maxFontSize = MaterialTheme.typography.bodyMedium.fontSize,
        minFontSize = MaterialTheme.typography.labelSmall.fontSize,
        maxLines = 1,
        modifier = Modifier.weight(weight)
    )
}

private fun overviewRows(
    summary: MonthSummary,
    payBreakdown: MonthlyNetPayBreakdown,
    payDetails: MonthlyPayDetails
): List<WorkHoursBreakdownLine> {
    return buildList {
        add(WorkHoursBreakdownLine("Ukupan fond sati", "${payBreakdown.monthlyFundHours}h", WorkHoursTone.Muted))
        add(
            WorkHoursBreakdownLine(
                "Obični sati",
                hoursAndMoneyText(
                    formatPayDetailMinutes(payDetails.paidWorkMinutes),
                    netMoneyText(payDetails.grossBaseRegular, payDetails)
                ),
                WorkHoursTone.Primary
            )
        )
        addIf(summary.workDays > 0, "Radni dani", summary.workDays.toString(), WorkHoursTone.Muted)
    }
}

private fun addOnRows(
    payBreakdown: MonthlyNetPayBreakdown,
    payDetails: MonthlyPayDetails
): List<WorkHoursTableLine> {
    return buildList {
        addGrossRow(
            label = "Treća smena",
            minutes = payDetails.nightMinutes,
            gross = payDetails.grossNightPremium,
            payDetails = payDetails
        )
        addGrossRow(
            label = "Prekovremeno",
            minutes = payDetails.overtimeMinutes,
            gross = payDetails.grossOvertimePremium,
            payDetails = payDetails
        )
        addGrossRow(
            label = "Nedelja",
            minutes = payDetails.sundayMinutes,
            gross = payDetails.grossSundayPremium,
            payDetails = payDetails
        )
        addGrossRow(
            label = "Rad na praznik",
            minutes = holidayWorkMinutes(payBreakdown, payDetails),
            gross = payDetails.grossHolidayPremium,
            payDetails = payDetails
        )
        addGrossRow(
            label = "Otežani uslovi",
            minutes = payDetails.paidWorkMinutes,
            gross = payDetails.grossDifficultWorkConditions,
            payDetails = payDetails
        )
    }
}

private fun absenceAndLeaveRows(
    payBreakdown: MonthlyNetPayBreakdown,
    payDetails: MonthlyPayDetails,
    deficitNetLoss: Double
): List<WorkHoursTableLine> {
    return buildList {
        addGrossRow("Bolovanje", payDetails.sickMinutes, payDetails.grossSickPay, payDetails)
        addGrossRow(
            "Plaćeno odsustvo – dobrovoljno davanje krvi",
            payDetails.paidLeaveMinutes,
            payDetails.grossPaidLeave,
            payDetails
        )
        addGrossRow("Godišnji odmor", payDetails.vacationMinutes, payDetails.grossVacationPay, payDetails)
        addGrossRow("Plaćen praznik", payDetails.paidHolidayMinutes, payDetails.grossPaidHoliday, payDetails)
        addGrossRow(
            "Pokriven manjak sati",
            payDetails.coveredDeficitMinutes,
            payDetails.grossCoveredDeficitPay,
            payDetails
        )
        val uncoveredLoss = uncoveredDeficitNetLoss(payDetails, deficitNetLoss)
        if (uncoveredLoss > 0.0 || payDetails.grossUncoveredDeficitLoss > 0.0) {
            add(
                WorkHoursTableLine(
                    "Izostanci",
                    formatHoursAndMinutes(-uncoveredDeficitMinutes(payBreakdown, payDetails)),
                    deductionMoneyText(uncoveredLoss),
                    WorkHoursTone.Danger
                )
            )
        }
    }
}

private fun otherPayrollRows(
    payBreakdown: MonthlyNetPayBreakdown,
    payDetails: MonthlyPayDetails
): List<WorkHoursBreakdownLine> {
    return buildList {
        addMoneyRowIf(payDetails.netSeniorityBonus > 0.0, "Minuli rad", payDetails.netSeniorityBonus)
        addMoneyRowIf(payBreakdown.netRegressAddition > 0.0, "Regres", payBreakdown.netRegressAddition)
        addMoneyRowIf(payBreakdown.netBonusAddition > 0.0, "Bonus", payBreakdown.netBonusAddition)
        if (payBreakdown.absencePenaltyGross.signum() > 0) {
            add(
                WorkHoursBreakdownLine(
                    "Izostanci 1",
                    deductionMoneyText(payBreakdown.absencePenaltyGross.toDouble()),
                    WorkHoursTone.Danger
                )
            )
        }
        val deductionsTotal = payBreakdown.netDeductionsTotal
        if (deductionsTotal > 0.0) {
            add(
                WorkHoursBreakdownLine(
                    "Odbici",
                    deductionMoneyText(deductionsTotal),
                    WorkHoursTone.Danger
                )
            )
        }
    }
}

private fun MutableList<WorkHoursTableLine>.addGrossRow(
    label: String,
    minutes: Int,
    gross: Double,
    payDetails: MonthlyPayDetails
) {
    if (gross <= 0.0) return
    add(
        WorkHoursTableLine(
            label = label,
            hours = formatPayDetailMinutes(minutes),
            amount = netMoneyText(gross, payDetails),
            tone = WorkHoursTone.Positive
        )
    )
}

private fun MutableList<WorkHoursBreakdownLine>.addIf(
    visible: Boolean,
    label: String,
    value: String,
    tone: WorkHoursTone = WorkHoursTone.Primary
) {
    if (visible) add(WorkHoursBreakdownLine(label, value, tone))
}

private fun MutableList<WorkHoursBreakdownLine>.addMoneyRowIf(
    visible: Boolean,
    label: String,
    value: Double,
    tone: WorkHoursTone = WorkHoursTone.Positive
) {
    if (visible) {
        add(WorkHoursBreakdownLine(label, moneyText(value), tone))
    }
}

private fun hoursAndMoneyText(hours: String, money: String): String {
    return "$hours · $money"
}

private fun formatPayDetailMinutes(minutes: Int): String {
    return formatHoursAndMinutes(minutes.coerceAtLeast(0))
}

private fun netMoneyText(gross: Double, payDetails: MonthlyPayDetails): String {
    return moneyText(gross * payDetails.netRatioBeforeSeniority)
}

private fun uncoveredDeficitNetLoss(payDetails: MonthlyPayDetails, deficitNetLoss: Double): Double {
    return if (payDetails.netUncoveredDeficitLoss > 0.0 && deficitNetLoss > 0.0) {
        deficitNetLoss
    } else {
        payDetails.netUncoveredDeficitLoss
    }
}

private fun holidayWorkMinutes(payBreakdown: MonthlyNetPayBreakdown, payDetails: MonthlyPayDetails): Int {
    val divisor = payBreakdown.grossHourlyRate * CONFIG.pay.holidayBonus
    if (divisor <= 0.0 || payDetails.grossHolidayPremium <= 0.0) return 0
    return (payDetails.grossHolidayPremium / divisor * WORK_HOURS_MINUTES_PER_HOUR)
        .roundToInt()
        .coerceAtLeast(0)
}

private fun uncoveredDeficitMinutes(
    payBreakdown: MonthlyNetPayBreakdown,
    payDetails: MonthlyPayDetails
): Int {
    val rate = payBreakdown.grossHourlyRate
    if (rate <= 0.0 || payDetails.grossUncoveredDeficitLoss <= 0.0) {
        return payDetails.uncoveredDeficitMinutes
    }
    return (payDetails.grossUncoveredDeficitLoss / rate * WORK_HOURS_MINUTES_PER_HOUR).roundToInt().coerceAtLeast(0)
}

private data class WorkHoursBreakdownLine(
    val label: String,
    val value: String,
    val tone: WorkHoursTone
)

private data class WorkHoursTableLine(
    val label: String,
    val hours: String,
    val amount: String,
    val tone: WorkHoursTone
)

private enum class WorkHoursTone(val color: Color) {
    Primary(THEME.textPrimary),
    Positive(THEME.success),
    Danger(THEME.danger),
    Muted(THEME.textMuted)
}
