package com.rad.shifttracker

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.rad.shifttracker.ui.components.AutoSizeText

// Existing detail dialog slightly exceeds the new method length limit; keep pressure on new methods.
@Suppress("LongMethod")
@Composable
fun PayDetailsDialog(
    summary: MonthSummary,
    payBreakdown: MonthlyNetPayBreakdown,
    monthlyReceipt: MonthlyReceipt,
    monthlyFinalPaymentBreakdown: MonthlyFinalPaymentBreakdown,
    payDetails: MonthlyPayDetails,
    annualNetPayAverages: AnnualNetPayAverages,
    recentNetPayAverage: RecentNetPayAverage,
    repo: ShiftRepository,
    onDismiss: () -> Unit
) {
    val receiptAvailable = monthlyReceipt.isSalaryAvailable
    val receiptText = if (receiptAvailable) {
        "${formatCurrency(monthlyReceipt.totalReceivedThisMonth)} ${CONFIG.pay.currency}"
    } else {
        TOTAL_CARD_UNAVAILABLE_TEXT
    }
    val receiptDescription = payDetailsReceiptDescriptionText(monthlyReceipt)
    val safeDrawingPadding = WindowInsets.safeDrawing.asPaddingValues()
    val navigationBarBottomPadding = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val bottomScrollPadding = maxOf(
        navigationBarBottomPadding,
        PAY_DETAILS_MIN_BOTTOM_SAFE_PADDING_DP.dp
    ) + UI.largeSpacing.dp

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(safeDrawingPadding)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(UI.cardPadding.dp),
                verticalArrangement = Arrangement.spacedBy(UI.largeSpacing.dp)
            ) {
                PayDetailsHeroCard(
                    paymentTitle = paymentMonthTitleText(monthlyReceipt),
                    payrollTitle = payrollMonthTitleText(monthlyReceipt),
                    receiptText = receiptText,
                    receiptAvailable = receiptAvailable,
                    receiptDescription = receiptDescription
                )

                PayAccuracyWarningsCard(
                    payAccuracyWarningTexts(payDetails, repo.isSeniorityConfiguredValue)
                )

                PayDetailsSectionCard(title = "Isplata") {
                    CompactMoneyRow(payDetailsNetSalaryLabel(monthlyReceipt), monthlyReceipt.salaryNetPay, MaterialTheme.colorScheme.primary)
                    CompactMoneyRowIf(
                        monthlyReceipt.regressNetPay > 0.0,
                        payDetailsRegressLabel(monthlyReceipt),
                        monthlyReceipt.regressNetPay,
                        MaterialTheme.extendedColors.success
                    )
                    CompactInfoRow(
                        payDetailsTransportLabel(monthlyReceipt),
                        payDetailsTransportAmountText(monthlyReceipt),
                        if (monthlyReceipt.transportAmount > 0.0) MaterialTheme.extendedColors.success else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    CompactMoneyRow(
                        payDetailsTotalLabel(monthlyReceipt),
                        monthlyReceipt.totalReceivedThisMonth,
                        MaterialTheme.colorScheme.primary
                    )
                    FinalPaymentRows(
                        finalPayment = monthlyFinalPaymentBreakdown,
                        monthlyReceipt = monthlyReceipt,
                        repo = repo
                    )
                }

                PayDetailsSectionCard(title = "Osnovni podaci") {
                    payBreakdown.hourlyValue?.let { hourlyValue ->
                        CompactInfoRow(PAY_DETAILS_HOURLY_VALUE_LABEL, payDetailsHourlyValueText(hourlyValue))
                    }
                    CompactInfoRow("Bruto satnica", moneyText(payBreakdown.grossHourlyRate))
                    CompactInfoRow("Informativna neto satnica", moneyText(payBreakdown.informativeNetHourlyBase))
                    CompactInfoRow("Fond meseca", "${payBreakdown.monthlyFundHours}h")
                    CompactInfoRowIf(
                        payDetails.totalPaidHours > 0,
                        "Plaćeni sati",
                        formatHoursAndMinutes(payDetails.totalPaidMinutes)
                    )
                    CompactInfoRowIf(summary.workDays > 0, "Radni dani", summary.workDays.toString())
                }

                PayDetailsSectionCard(title = "Godišnji prosek neto plate") {
                    val labels = annualNetPayAverageLabels()
                    if (annualNetPayAverages.monthCount > 0) {
                        CompactMoneyRow(labels[0], annualNetPayAverages.averagePay, MaterialTheme.colorScheme.primary)
                        CompactMoneyRow(labels[1], annualNetPayAverages.averagePayWithoutRegress)
                        CompactMoneyRow(labels[2], annualNetPayAverages.averagePayWithoutThirdShift, MaterialTheme.extendedColors.shift2)
                    } else {
                        Text(
                            text = ANNUAL_NET_PAY_AVERAGE_EMPTY_TEXT,
                            color = MaterialTheme.extendedColors.warning,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Text(
                        text = ANNUAL_NET_PAY_AVERAGE_HINT_TEXT,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall
                    )
                    SectionLabel(text = RECENT_NET_PAY_AVERAGE_SECTION_LABEL)
                    val recentAverage = recentNetPayAverage.averageNetPay
                    if (recentAverage != null) {
                        CompactMoneyRow(RECENT_NET_PAY_AVERAGE_VALUE_LABEL, recentAverage, MaterialTheme.colorScheme.primary)
                    } else {
                        Text(
                            text = RECENT_NET_PAY_AVERAGE_UNAVAILABLE_TEXT,
                            color = MaterialTheme.extendedColors.warning,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    CompactTextBlock("Meseci", recentNetPayAverageMonthsText(recentNetPayAverage))
                }
                Spacer(
                    modifier = Modifier.height(bottomScrollPadding)
                )
            }
        }
    }
}

@Composable
private fun PayDetailsHeroCard(
    paymentTitle: String,
    payrollTitle: String,
    receiptText: String,
    receiptAvailable: Boolean,
    receiptDescription: String?
) {
    PopupCard {
        Text(
            text = paymentTitle,
            color = MaterialTheme.extendedColors.textSecondary,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = payrollTitle,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        AutoSizeText(
            text = receiptText,
            color = if (receiptAvailable) MaterialTheme.colorScheme.primary else MaterialTheme.extendedColors.warning,
            style = MaterialTheme.typography.headlineMedium.copy(
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Start
            ),
            maxFontSize = MaterialTheme.typography.headlineMedium.fontSize,
            minFontSize = MaterialTheme.typography.titleMedium.fontSize,
            maxLines = 2,
            modifier = Modifier.fillMaxWidth()
        )
        receiptDescription?.let {
            Text(
                text = it,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun PayDetailsSectionCard(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    PopupCard {
        SectionLabel(text = title)
        content()
    }
}

@Composable
private fun FinalPaymentRows(
    finalPayment: MonthlyFinalPaymentBreakdown,
    monthlyReceipt: MonthlyReceipt,
    repo: ShiftRepository
) {
    SectionLabel(text = FINAL_PAYMENT_REMAINING_LABEL)
    if (!finalPayment.isNetPayAvailable) {
        CompactTextBlock(FINAL_PAYMENT_REMAINING_LABEL, FINAL_PAYMENT_UNAVAILABLE_TEXT, MaterialTheme.extendedColors.warning)
    } else {
        CompactMoneyRow(FINAL_PAYMENT_NET_PAY_LABEL, finalPayment.netPayBeforeAdvance, MaterialTheme.colorScheme.primary)
        CompactInfoRow(
            FINAL_PAYMENT_ADVANCE_LABEL,
            deductionMoneyText(finalPayment.advancePaymentNet),
            if (finalPayment.advancePaymentNet > 0.0) MaterialTheme.extendedColors.warning else MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (finalPayment.deductionsNet > 0.0) {
            CompactInfoRow(
                FINAL_PAYMENT_DEDUCTIONS_LABEL,
                deductionMoneyText(finalPayment.deductionsNet),
                MaterialTheme.colorScheme.error
            )
            finalPayment.deductions.forEach { deduction ->
                CompactDeductionDetailRow(label = deduction.name, value = deduction.amountNet)
            }
        }
        CompactMoneyRow(
            FINAL_PAYMENT_REMAINING_20_LABEL,
            finalPayment.finalRemainingNetPay,
            MaterialTheme.colorScheme.primary
        )
        if (finalPayment.remainingRegressNetPay > 0.0) {
            CompactMoneyRow(
                FINAL_PAYMENT_REMAINING_REGRESS_LABEL,
                finalPayment.remainingRegressNetPay,
                MaterialTheme.extendedColors.success
            )
        }
        if (finalPayment.advanceExceedsCalculatedNetPay) {
            Text(
                text = FINAL_PAYMENT_ADVANCE_WARNING_TEXT,
                color = MaterialTheme.extendedColors.warning,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
    MonthlyPaymentInputRows(
        monthlyReceipt = monthlyReceipt,
        repo = repo
    )
}

@Composable
private fun CompactMoneyRow(
    label: String,
    value: Double,
    color: Color = MaterialTheme.colorScheme.onSurface
) {
    CompactInfoRow(label, moneyText(value), color)
}

@Composable
private fun CompactMoneyRowIf(
    visible: Boolean,
    label: String,
    value: Double,
    color: Color = MaterialTheme.colorScheme.onSurface
) {
    if (visible) {
        CompactMoneyRow(label, value, color)
    }
}

@Composable
private fun CompactInfoRow(
    label: String,
    value: String,
    color: Color = MaterialTheme.colorScheme.onSurface,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        AutoSizeText(
            text = label,
            color = MaterialTheme.extendedColors.textSecondary,
            style = MaterialTheme.typography.bodyMedium,
            maxFontSize = MaterialTheme.typography.bodyMedium.fontSize,
            minFontSize = MaterialTheme.typography.bodySmall.fontSize,
            maxLines = 1,
            modifier = Modifier
                .weight(1f)
                .padding(end = 8.dp)
        )
        // Iznos se nikad ne skraćuje; kad nema mesta, smanjuje se naziv levo.
        Text(
            text = value,
            color = color,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
            textAlign = TextAlign.End,
            maxLines = 1,
            softWrap = false
        )
    }
}

@Composable
private fun CompactDeductionDetailRow(
    label: String,
    value: Double
) {
    CompactInfoRow(
        label = label,
        value = deductionMoneyText(value),
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.padding(start = UI.largeSpacing.dp)
    )
}

@Composable
private fun CompactTextBlock(
    label: String,
    value: String,
    color: Color = MaterialTheme.colorScheme.onSurface
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = label,
            color = MaterialTheme.extendedColors.textSecondary,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = value,
            color = color,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
            maxLines = 3,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun CompactInfoRowIf(
    visible: Boolean,
    label: String,
    value: String,
    color: Color = MaterialTheme.colorScheme.onSurface
) {
    if (visible) {
        CompactInfoRow(label, value, color)
    }
}
