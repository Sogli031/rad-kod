package com.rad.shifttracker.ui.dialozi

import com.rad.shifttracker.osnova.CONFIG
import com.rad.shifttracker.obracun.AdvancePaymentPrediction
import com.rad.shifttracker.obracun.MonthlyNetPayBreakdown
import com.rad.shifttracker.obracun.MonthlyReceipt
import com.rad.shifttracker.repo.ShiftRepository
import com.rad.shifttracker.repo.getAdvancePaymentPrediction
import com.rad.shifttracker.repo.getMonthlyOvertimeNetImpact
import com.rad.shifttracker.repo.getNetPayBreakdown
import com.rad.shifttracker.repo.getPaidLeaveNetDifference
import com.rad.shifttracker.repo.getSickLeaveNetDifference
import com.rad.shifttracker.repo.getVacationNetDifference
import com.rad.shifttracker.smene.Shift

/** Obračuni iza tabele plate i akontacije (svaki je ceo obračun meseca, pa se računaju van kompozicije). */
internal data class PayrollTableInputs(
    val payBreakdown: MonthlyNetPayBreakdown,
    val deficitNetLoss: Double,
    val sickLeaveNetDifference: Double,
    val vacationNetDifference: Double,
    val paidLeaveNetDifference: Double,
    val sickPayRates: List<Double>
)

internal data class PayDetailsExtras(
    val table: PayrollTableInputs,
    val advancePrediction: AdvancePaymentPrediction
)

internal fun ShiftRepository.payrollTableInputs(receipt: MonthlyReceipt): PayrollTableInputs {
    val year = receipt.payrollMonth.year
    val month = receipt.payrollMonth.month
    return PayrollTableInputs(
        payBreakdown = getNetPayBreakdown(year, month),
        deficitNetLoss = getMonthlyOvertimeNetImpact(year, month).deficitNetLoss,
        sickLeaveNetDifference = getSickLeaveNetDifference(year, month),
        vacationNetDifference = getVacationNetDifference(year, month),
        paidLeaveNetDifference = getPaidLeaveNetDifference(year, month),
        // Procenat bolovanja po danima (davanje krvi je plaćeno odsustvo, nije bolovanje).
        sickPayRates = getMonthRecords(year, month)
            .filter { it.shift == Shift.SICK && !it.isPaidLeave && it.workHours > 0 }
            .map { it.sickPayRate ?: CONFIG.pay.sickPayRate }
    )
}

/** Null kad plata za mesec još nije dostupna (tada se ni tabela ni akontacija ne prikazuju). */
internal fun ShiftRepository.payDetailsExtras(receipt: MonthlyReceipt): PayDetailsExtras? {
    if (!receipt.isSalaryAvailable) return null
    return PayDetailsExtras(
        table = payrollTableInputs(receipt),
        advancePrediction = getAdvancePaymentPrediction(receipt.paymentMonth.year, receipt.paymentMonth.month)
    )
}
