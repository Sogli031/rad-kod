package com.rad.shifttracker.ui.dialozi

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.rad.shifttracker.ui.components.AutoSizeText
import com.rad.shifttracker.obracun.ADVANCE_NOT_PAID_TEXT
import com.rad.shifttracker.obracun.FINAL_PAYMENT_ADVANCE_WARNING_TEXT
import com.rad.shifttracker.obracun.FINAL_PAYMENT_DEDUCTIONS_LABEL
import com.rad.shifttracker.obracun.MonthlyFinalPaymentBreakdown
import com.rad.shifttracker.obracun.MonthlyPayDetails
import com.rad.shifttracker.obracun.MonthlyReceipt
import com.rad.shifttracker.obracun.AdvancePaymentPrediction
import com.rad.shifttracker.repo.ShiftRepository
import com.rad.shifttracker.ui.PopupCard
import com.rad.shifttracker.ui.SectionLabel
import com.rad.shifttracker.ui.Spacing
import com.rad.shifttracker.ui.bodyMediumEmphasized
import com.rad.shifttracker.ui.extendedColors
import com.rad.shifttracker.ui.PayAccuracyWarningsCard
import com.rad.shifttracker.obracun.payAccuracyWarningTexts
import com.rad.shifttracker.repo.isSeniorityConfiguredValue
import com.rad.shifttracker.ui.rememberRepoValue
import com.rad.shifttracker.ui.TOTAL_CARD_UNAVAILABLE_TEXT

/**
 * Ispod kalendara: isplata za izabrani mesec (neto plata, akontacija, obustave, pa „Ukupno za
 * isplatu 20.“ kad akontacija stigne) i obračun plate tog meseca. Regres i prevoz se ovde ne
 * prikazuju: kolege porede samo akontaciju i završnu isplatu 20.
 */
@Composable
internal fun PayDetailsSections(
    monthlyReceipt: MonthlyReceipt,
    monthlyFinalPaymentBreakdown: MonthlyFinalPaymentBreakdown,
    payDetails: MonthlyPayDetails,
    repo: ShiftRepository,
    // Glavni ekran ih dobija gotove iz pozadinskog računanja meseca, da prevlačenje ne čeka obračune.
    extras: PayDetailsExtras? = rememberRepoValue(repo, monthlyReceipt.payrollMonth) { payDetailsExtras(monthlyReceipt) }
) {
    PayAccuracyWarningsCard(
        payAccuracyWarningTexts(payDetails, rememberRepoValue(repo) { isSeniorityConfiguredValue })
    )

    PayDetailsSectionCard(title = "Isplata") {
        PaymentRows(
            monthlyReceipt = monthlyReceipt,
            finalPayment = monthlyFinalPaymentBreakdown,
            advancePrediction = extras?.advancePrediction
        )
    }

    if (monthlyReceipt.isSalaryAvailable && extras != null) {
        PayrollMonthTable(monthlyReceipt = monthlyReceipt, payDetails = payDetails, inputs = extras.table)
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

/** Obračun plate meseca isplate kao tabela koja se sabira u neto platu (ispod isplate i u prozoru „Sati rada“). */
@Composable
internal fun PayrollMonthTable(monthlyReceipt: MonthlyReceipt, payDetails: MonthlyPayDetails, inputs: PayrollTableInputs) {
    val table = payrollTable(
        payDetails = payDetails,
        payBreakdown = inputs.payBreakdown,
        netSalary = monthlyReceipt.salaryNetPay,
        sickLeaveNetDifference = inputs.sickLeaveNetDifference,
        vacationNetDifference = inputs.vacationNetDifference,
        paidLeaveNetDifference = inputs.paidLeaveNetDifference,
        sickPayRates = inputs.sickPayRates,
        uncoveredDeficitNetLoss = uncoveredDeficitNetLoss(payDetails, inputs.deficitNetLoss)
    )
    PayrollTableCards(title = "Obračun plate za ${payrollMonthNameText(monthlyReceipt)}", table = table)
}

@Composable
private fun AdvancePaymentRow(
    label: String,
    advancePaymentNet: Double,
    advancePrediction: AdvancePaymentPrediction?
) {
    if (advancePaymentNet > 0.0) {
        CompactMoneyRow(label, advancePaymentNet, MaterialTheme.extendedColors.success)
    } else {
        CompactInfoRow(label, ADVANCE_NOT_PAID_TEXT, MaterialTheme.colorScheme.onSurfaceVariant)
    }
    AdvanceMinimumText(advancePrediction = advancePrediction)
}

/**
 * Isplata: neto plata, akontacija (deo plate), obustave i „Ukupno za isplatu u <mesecu>“ (neto plata −
 * obustave). „Ukupno za isplatu 20.“ se vidi tek kad je akontacija uplaćena: ukupno − akontacija.
 */
@Composable
private fun PaymentRows(
    monthlyReceipt: MonthlyReceipt,
    finalPayment: MonthlyFinalPaymentBreakdown,
    advancePrediction: AdvancePaymentPrediction?
) {
    if (!monthlyReceipt.isSalaryAvailable) {
        CompactInfoRow(payDetailsNetSalaryLabel(monthlyReceipt), TOTAL_CARD_UNAVAILABLE_TEXT, MaterialTheme.extendedColors.warning)
        return
    }
    CompactMoneyRow(payDetailsNetSalaryLabel(monthlyReceipt), monthlyReceipt.salaryNetPay, MaterialTheme.colorScheme.primary)
    AdvancePaymentRow(
        label = payDetailsAdvanceLabel(monthlyReceipt),
        advancePaymentNet = finalPayment.advancePaymentNet,
        advancePrediction = advancePrediction
    )
    if (finalPayment.deductionsNet > 0.0) {
        CompactInfoRow(
            FINAL_PAYMENT_DEDUCTIONS_LABEL,
            signedSrMoney(-finalPayment.deductionsNet),
            MaterialTheme.colorScheme.error
        )
        finalPayment.deductions.forEach { deduction ->
            CompactDeductionDetailRow(label = deduction.name, value = deduction.amountNet)
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    CompactInfoRow(
        payDetailsTotalLabel(monthlyReceipt),
        srMoney(payDetailsTotalAfterDeductions(monthlyReceipt, finalPayment)),
        MaterialTheme.colorScheme.primary,
        bold = true
    )
    if (finalPayment.isNetPayAvailable && finalPayment.advancePaymentNet > 0.0) {
        CompactInfoRow(
            payDetailsTotalOn20Label(monthlyReceipt),
            srMoney(payDetailsTotalOn20(monthlyReceipt, finalPayment)),
            MaterialTheme.colorScheme.primary,
            bold = true
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

/** „Ukupno za isplatu u <mesecu>“: neto plata − obustave, bez regresa i prevoza. */
internal fun payDetailsTotalAfterDeductions(
    monthlyReceipt: MonthlyReceipt,
    finalPayment: MonthlyFinalPaymentBreakdown
): Double = (monthlyReceipt.workSalaryPay - finalPayment.deductionsNet.coerceAtLeast(0.0)).coerceAtLeast(0.0)

/** „Ukupno za isplatu 20.“: ukupno za isplatu − već uplaćena akontacija. */
internal fun payDetailsTotalOn20(
    monthlyReceipt: MonthlyReceipt,
    finalPayment: MonthlyFinalPaymentBreakdown
): Double = (payDetailsTotalAfterDeductions(monthlyReceipt, finalPayment) - finalPayment.advancePaymentNet).coerceAtLeast(0.0)

@Composable
private fun CompactMoneyRow(
    label: String,
    value: Double,
    color: Color = MaterialTheme.colorScheme.onSurface
) {
    CompactInfoRow(label, srMoney(value), color)
}

@Composable
private fun CompactInfoRow(
    label: String,
    value: String,
    color: Color = MaterialTheme.colorScheme.onSurface,
    modifier: Modifier = Modifier,
    bold: Boolean = false
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        AutoSizeText(
            text = label,
            color = if (bold) MaterialTheme.colorScheme.onSurface else MaterialTheme.extendedColors.textSecondary,
            style = if (bold) MaterialTheme.typography.bodyMediumEmphasized else MaterialTheme.typography.bodyMedium,
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
        value = signedSrMoney(-value),
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.padding(start = Spacing.sm)
    )
}
