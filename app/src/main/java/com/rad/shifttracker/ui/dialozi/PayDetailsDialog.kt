package com.rad.shifttracker.ui.dialozi

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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.rad.shifttracker.ui.components.AutoSizeText
import com.rad.shifttracker.osnova.CONFIG
import com.rad.shifttracker.osnova.formatCurrency
import com.rad.shifttracker.obracun.ADVANCE_NOT_PAID_TEXT
import com.rad.shifttracker.obracun.ADVANCE_NOT_PAID_TEXT
import com.rad.shifttracker.obracun.FINAL_PAYMENT_ADVANCE_LABEL
import com.rad.shifttracker.obracun.FINAL_PAYMENT_ADVANCE_WARNING_TEXT
import com.rad.shifttracker.obracun.FINAL_PAYMENT_DEDUCTIONS_LABEL
import com.rad.shifttracker.obracun.FINAL_PAYMENT_REMAINING_20_LABEL
import com.rad.shifttracker.obracun.MonthlyFinalPaymentBreakdown
import com.rad.shifttracker.obracun.MonthlyPayDetails
import com.rad.shifttracker.obracun.MonthlyReceipt
import com.rad.shifttracker.repo.ShiftRepository
import com.rad.shifttracker.ui.PopupCard
import com.rad.shifttracker.ui.SectionLabel
import com.rad.shifttracker.ui.Spacing
import com.rad.shifttracker.ui.bodyMediumEmphasized
import com.rad.shifttracker.ui.extendedColors
import com.rad.shifttracker.ui.headlineMediumEmphasized
import com.rad.shifttracker.ui.PayAccuracyWarningsCard
import com.rad.shifttracker.obracun.payAccuracyWarningTexts
import com.rad.shifttracker.repo.isSeniorityConfiguredValue
import com.rad.shifttracker.ui.mainPayCardAmount
import com.rad.shifttracker.ui.rememberRepoValue
import com.rad.shifttracker.ui.TOTAL_CARD_UNAVAILABLE_TEXT

@Composable
internal fun PayDetailsDialog(
    monthlyReceipt: MonthlyReceipt,
    monthlyFinalPaymentBreakdown: MonthlyFinalPaymentBreakdown,
    payDetails: MonthlyPayDetails,
    repo: ShiftRepository,
    onDismiss: () -> Unit
) {
    val receiptAvailable = monthlyReceipt.isSalaryAvailable
    val receiptText = if (receiptAvailable) {
        "${formatCurrency(payDetailsTotalAfterDeductions(monthlyReceipt, monthlyFinalPaymentBreakdown))} ${CONFIG.pay.currency}"
    } else {
        TOTAL_CARD_UNAVAILABLE_TEXT
    }
    val receiptDescription = payDetailsReceiptDescriptionText(monthlyReceipt)
    val safeDrawingPadding = WindowInsets.safeDrawing.asPaddingValues()
    val navigationBarBottomPadding = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val bottomScrollPadding = maxOf(
        navigationBarBottomPadding,
        PAY_DETAILS_MIN_BOTTOM_SAFE_PADDING_DP.dp
    ) + Spacing.sm

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
                    .padding(Spacing.lg),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {
                PayDetailsHeroCard(
                    paymentTitle = paymentMonthTitleText(monthlyReceipt),
                    payrollTitle = payrollMonthTitleText(monthlyReceipt),
                    receiptText = receiptText,
                    receiptAvailable = receiptAvailable,
                    receiptDescription = receiptDescription
                )

                PayAccuracyWarningsCard(
                    payAccuracyWarningTexts(payDetails, rememberRepoValue(repo) { isSeniorityConfiguredValue })
                )

                PayDetailsSectionCard(title = "Isplata") {
                    PaymentRows(
                        monthlyReceipt = monthlyReceipt,
                        finalPayment = monthlyFinalPaymentBreakdown,
                        repo = repo
                    )
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
            style = MaterialTheme.typography.headlineMediumEmphasized.copy(
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
private fun AdvancePaymentRow(
    advancePaymentNet: Double,
    monthlyReceipt: MonthlyReceipt,
    repo: ShiftRepository
) {
    if (advancePaymentNet > 0.0) {
        CompactMoneyRow(FINAL_PAYMENT_ADVANCE_LABEL, advancePaymentNet, MaterialTheme.extendedColors.success)
    } else {
        CompactInfoRow(FINAL_PAYMENT_ADVANCE_LABEL, ADVANCE_NOT_PAID_TEXT, MaterialTheme.colorScheme.onSurfaceVariant)
    }
    AdvanceMinimumText(monthlyReceipt = monthlyReceipt, repo = repo)
}

/**
 * Jedan spisak isplate: neto plata, akontacija (deo plate, ne sabira se), regres, obustave
 * i ukupno posle obustava. „Preostalo 20.“ se vidi tek kad je akontacija uplaćena.
 */
@Composable
private fun PaymentRows(
    monthlyReceipt: MonthlyReceipt,
    finalPayment: MonthlyFinalPaymentBreakdown,
    repo: ShiftRepository
) {
    CompactMoneyRow(payDetailsNetSalaryLabel(monthlyReceipt), monthlyReceipt.salaryNetPay, MaterialTheme.colorScheme.primary)
    AdvancePaymentRow(
        advancePaymentNet = finalPayment.advancePaymentNet,
        monthlyReceipt = monthlyReceipt,
        repo = repo
    )
    CompactMoneyRowIf(
        monthlyReceipt.regressNetPay > 0.0,
        payDetailsRegressLabel(monthlyReceipt),
        monthlyReceipt.regressNetPay,
        MaterialTheme.extendedColors.success
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
        payDetailsTotalLabel(monthlyReceipt),
        payDetailsTotalAfterDeductions(monthlyReceipt, finalPayment),
        MaterialTheme.colorScheme.primary
    )
    if (finalPayment.isNetPayAvailable && finalPayment.advancePaymentNet > 0.0) {
        CompactMoneyRow(
            FINAL_PAYMENT_REMAINING_20_LABEL,
            finalPayment.finalRemainingNetPay,
            MaterialTheme.colorScheme.primary
        )
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
}

/** Ukupno u ovom prozoru: neto plata i regres minus obustave sa listića, isto kao na glavnom ekranu. */
internal fun payDetailsTotalAfterDeductions(
    monthlyReceipt: MonthlyReceipt,
    finalPayment: MonthlyFinalPaymentBreakdown
): Double = mainPayCardAmount(monthlyReceipt, finalPayment.deductionsNet)

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
                .padding(end = Spacing.sm)
        )
        // Iznos se nikad ne skraćuje; kad nema mesta, smanjuje se naziv levo.
        Text(
            text = value,
            color = color,
            style = MaterialTheme.typography.bodyMediumEmphasized,
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
        modifier = Modifier.padding(start = Spacing.sm)
    )
}
