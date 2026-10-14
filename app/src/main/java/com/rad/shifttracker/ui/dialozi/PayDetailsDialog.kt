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
import com.rad.shifttracker.osnova.CONFIG
import com.rad.shifttracker.obracun.ADVANCE_NOT_PAID_TEXT
import com.rad.shifttracker.obracun.FINAL_PAYMENT_ADVANCE_LABEL
import com.rad.shifttracker.obracun.FINAL_PAYMENT_ADVANCE_WARNING_TEXT
import com.rad.shifttracker.obracun.FINAL_PAYMENT_DEDUCTIONS_LABEL
import com.rad.shifttracker.obracun.MonthlyFinalPaymentBreakdown
import com.rad.shifttracker.obracun.MonthlyPayDetails
import com.rad.shifttracker.obracun.MonthlyReceipt
import com.rad.shifttracker.repo.ShiftRepository
import com.rad.shifttracker.smene.Shift
import com.rad.shifttracker.ui.PopupCard
import com.rad.shifttracker.ui.SectionLabel
import com.rad.shifttracker.ui.Spacing
import com.rad.shifttracker.ui.bodyMediumEmphasized
import com.rad.shifttracker.ui.extendedColors
import com.rad.shifttracker.ui.PayAccuracyWarningsCard
import com.rad.shifttracker.obracun.payAccuracyWarningTexts
import com.rad.shifttracker.repo.isSeniorityConfiguredValue
import com.rad.shifttracker.ui.rememberRepoValue
import com.rad.shifttracker.repo.getNetPayBreakdown
import com.rad.shifttracker.repo.getMonthlyOvertimeNetImpact
import com.rad.shifttracker.repo.getSickLeaveNetDifference
import com.rad.shifttracker.repo.getPaidLeaveNetDifference
import com.rad.shifttracker.repo.getVacationNetDifference
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
    repo: ShiftRepository
) {
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

    if (monthlyReceipt.isSalaryAvailable) {
        PayrollMonthTable(monthlyReceipt = monthlyReceipt, payDetails = payDetails, repo = repo)
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
internal fun PayrollMonthTable(monthlyReceipt: MonthlyReceipt, payDetails: MonthlyPayDetails, repo: ShiftRepository) {
    val payrollMonth = monthlyReceipt.payrollMonth
    val year = payrollMonth.year
    val month = payrollMonth.month
    val payBreakdown = rememberRepoValue(repo, payrollMonth) { getNetPayBreakdown(year, month) }
    val deficitNetLoss = rememberRepoValue(repo, payrollMonth) { getMonthlyOvertimeNetImpact(year, month).deficitNetLoss }
    val sickLeaveNetDifference = rememberRepoValue(repo, payrollMonth) { getSickLeaveNetDifference(year, month) }
    val vacationNetDifference = rememberRepoValue(repo, payrollMonth) { getVacationNetDifference(year, month) }
    val paidLeaveNetDifference = rememberRepoValue(repo, payrollMonth) { getPaidLeaveNetDifference(year, month) }
    // Procenat bolovanja po danima (davanje krvi je plaćeno odsustvo, nije bolovanje).
    val sickPayRates = rememberRepoValue(repo, payrollMonth) {
        getMonthRecords(year, month)
            .filter { it.shift == Shift.SICK && !it.isPaidLeave && it.workHours > 0 }
            .map { it.sickPayRate ?: CONFIG.pay.sickPayRate }
    }
    val table = payrollTable(
        payDetails = payDetails,
        payBreakdown = payBreakdown,
        netSalary = monthlyReceipt.salaryNetPay,
        sickLeaveNetDifference = sickLeaveNetDifference,
        vacationNetDifference = vacationNetDifference,
        paidLeaveNetDifference = paidLeaveNetDifference,
        sickPayRates = sickPayRates,
        uncoveredDeficitNetLoss = uncoveredDeficitNetLoss(payDetails, deficitNetLoss)
    )
    PayrollTableCards(title = "Obračun plate za ${payrollMonthNameText(monthlyReceipt)}", table = table)
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
 * Isplata: neto plata, akontacija (deo plate) i obustave. „Ukupno za isplatu 20.“ se vidi tek kad je
 * akontacija uplaćena; tada je to neto plata − obustave − akontacija.
 */
@Composable
private fun PaymentRows(
    monthlyReceipt: MonthlyReceipt,
    finalPayment: MonthlyFinalPaymentBreakdown,
    repo: ShiftRepository
) {
    if (!monthlyReceipt.isSalaryAvailable) {
        CompactInfoRow(payDetailsNetSalaryLabel(monthlyReceipt), TOTAL_CARD_UNAVAILABLE_TEXT, MaterialTheme.extendedColors.warning)
        return
    }
    CompactMoneyRow(payDetailsNetSalaryLabel(monthlyReceipt), monthlyReceipt.salaryNetPay, MaterialTheme.colorScheme.primary)
    AdvancePaymentRow(
        advancePaymentNet = finalPayment.advancePaymentNet,
        monthlyReceipt = monthlyReceipt,
        repo = repo
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
    if (finalPayment.isNetPayAvailable && finalPayment.advancePaymentNet > 0.0) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
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

/** „Ukupno za isplatu 20.“: neto plata − obustave − već uplaćena akontacija (bez regresa i prevoza). */
internal fun payDetailsTotalOn20(
    monthlyReceipt: MonthlyReceipt,
    finalPayment: MonthlyFinalPaymentBreakdown
): Double = (
    monthlyReceipt.workSalaryPay - finalPayment.deductionsNet.coerceAtLeast(0.0) - finalPayment.advancePaymentNet
    ).coerceAtLeast(0.0)

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
