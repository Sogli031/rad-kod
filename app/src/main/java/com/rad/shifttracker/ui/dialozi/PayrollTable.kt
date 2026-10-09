package com.rad.shifttracker.ui.dialozi

import kotlin.math.abs
import kotlin.math.roundToInt
import com.rad.shifttracker.osnova.CONFIG
import com.rad.shifttracker.obracun.MonthlyNetPayBreakdown
import com.rad.shifttracker.obracun.MonthlyPayDetails

private const val MINUTES_PER_HOUR = 60
private const val ROUNDING_VISIBLE_FROM = 0.01

/** Boja reda u tabeli obračuna. */
internal enum class PayrollTone { Plain, Positive, Negative, Muted }

/**
 * Jedan red tabele. [amount] je neto iznos koji se sabira; [note] je sitan tekst ispod
 * (npr. koliko je bolovanje manje nego rad) i ne ulazi u zbir.
 */
internal data class PayrollRow(
    val label: String,
    val minutes: Int?,
    val amount: Double,
    val tone: PayrollTone,
    val note: String? = null,
    val noteTone: PayrollTone = PayrollTone.Muted
)

/**
 * Obračun plate meseca kao tabela koja se sabira: rad + naknade + ostalo + zaokruženje = neto plata.
 * [notPaid] su izostanci koji su već izostavljeni iz plate; samo informacija, ne ulaze u zbir.
 */
internal data class PayrollTable(
    val work: List<PayrollRow>,
    val compensation: List<PayrollRow>,
    val other: List<PayrollRow>,
    val rounding: Double,
    val totalMinutes: Int,
    val netSalary: Double,
    val notPaid: PayrollRow?
) {
    val showRounding: Boolean
        get() = abs(rounding) >= ROUNDING_VISIBLE_FROM
}

/**
 * Svaka bruto stavka meseca ide u neto istim odnosom neto/bruto kao ceo mesec, minuli rad je već neto,
 * a topli obrok, „Izostanci 1“ i obustave u % idu kao na listiću. Ostatak do [netSalary] (porez i
 * doprinosi se računaju na ceo mesec, ne po stavci) je red „Zaokruženje poreza“, pa zbir uvek
 * odgovara neto plati iz isplate.
 */
internal fun payrollTable(
    payDetails: MonthlyPayDetails,
    payBreakdown: MonthlyNetPayBreakdown,
    netSalary: Double,
    sickLeaveNetDifference: Double,
    uncoveredDeficitNetLoss: Double
): PayrollTable {
    val ratio = payDetails.netRatioBeforeSeniority
    fun net(gross: Double) = gross * ratio

    val work = buildList {
        addRow("Obični sati", payDetails.paidWorkMinutes, net(payDetails.grossBaseRegular), PayrollTone.Plain, always = true)
        addRow("Treća smena", payDetails.nightMinutes, net(payDetails.grossNightPremium), PayrollTone.Positive)
        addRow("Prekovremeno", payDetails.overtimeMinutes, net(payDetails.grossOvertimePremium), PayrollTone.Positive)
        addRow("Nedelja", payDetails.sundayMinutes, net(payDetails.grossSundayPremium), PayrollTone.Positive)
        addRow(
            "Rad na praznik",
            holidayWorkMinutes(payBreakdown, payDetails),
            net(payDetails.grossHolidayPremium),
            PayrollTone.Positive
        )
        addRow("Otežani uslovi", null, net(payDetails.grossDifficultWorkConditions), PayrollTone.Positive)
        addRow("Minuli rad", null, payDetails.netSeniorityBonus, PayrollTone.Positive)
    }
    val compensation = buildList {
        if (payDetails.grossSickPay > 0.0) {
            add(
                PayrollRow(
                    label = "Bolovanje",
                    minutes = payDetails.sickMinutes,
                    amount = net(payDetails.grossSickPay),
                    tone = PayrollTone.Plain,
                    note = sickLeaveNote(sickLeaveNetDifference),
                    noteTone = if (sickLeaveNetDifference < 0.0) PayrollTone.Negative else PayrollTone.Positive
                )
            )
        }
        addRow("Davanje krvi", payDetails.paidLeaveMinutes, net(payDetails.grossPaidLeave), PayrollTone.Plain)
        addRow("Godišnji odmor", payDetails.vacationMinutes, net(payDetails.grossVacationPay), PayrollTone.Plain)
        addRow("Plaćen praznik", payDetails.paidHolidayMinutes, net(payDetails.grossPaidHoliday), PayrollTone.Plain)
        addRow("Pokriven manjak sati", payDetails.coveredDeficitMinutes, net(payDetails.grossCoveredDeficitPay), PayrollTone.Plain)
    }
    val other = buildList {
        // Topli obrok: dodatak u bruto, pa odbitak pojedenog u neto; ovde jedan red sa razlikom.
        val mealNet = net(payDetails.grossMealAddition) - payBreakdown.mealNetDeduction
        if (payDetails.grossMealAddition > 0.0 || payBreakdown.mealNetDeduction > 0.0) {
            addSigned("Topli obrok", mealNet)
        }
        addRow("Bonus", null, net(payDetails.grossBonusAddition), PayrollTone.Positive)
        val absencePenalty = net(payDetails.absencePenaltyGross.toDouble())
        if (absencePenalty > 0.0) add(PayrollRow("Izostanci 1 (ostatak minuta)", null, -absencePenalty, PayrollTone.Negative))
        if (payBreakdown.unionDuesNet > 0.0) {
            add(PayrollRow("Sindikat i fond (%)", null, -payBreakdown.unionDuesNet, PayrollTone.Negative))
        }
    }
    val rowsTotal = (work + compensation + other).sumOf { it.amount }
    val notPaid = uncoveredDeficitMinutes(payBreakdown, payDetails).takeIf { it > 0 }?.let { minutes ->
        PayrollRow("Izostanci", minutes, uncoveredDeficitNetLoss.coerceAtLeast(0.0), PayrollTone.Muted)
    }
    return PayrollTable(
        work = work,
        compensation = compensation,
        other = other,
        rounding = netSalary - rowsTotal,
        totalMinutes = payDetails.paidWorkMinutes + payDetails.sickMinutes + payDetails.paidLeaveMinutes +
            payDetails.vacationMinutes + payDetails.paidHolidayMinutes + payDetails.coveredDeficitMinutes,
        netSalary = netSalary,
        notPaid = notPaid
    )
}

/** Gubitak od nepokrivenog manjka sati: procena po danima ako postoji, inače iz obračuna meseca. */
internal fun uncoveredDeficitNetLoss(payDetails: MonthlyPayDetails, deficitNetLoss: Double): Double {
    return if (payDetails.netUncoveredDeficitLoss > 0.0 && deficitNetLoss > 0.0) {
        deficitNetLoss
    } else {
        payDetails.netUncoveredDeficitLoss
    }
}

private fun sickLeaveNote(difference: Double): String? {
    if (abs(difference) < ROUNDING_VISIBLE_FROM) return null
    val word = if (difference < 0.0) "manje" else "više"
    return "${srMoney(abs(difference))} $word nego da si radio"
}

private fun MutableList<PayrollRow>.addRow(
    label: String,
    minutes: Int?,
    amount: Double,
    tone: PayrollTone,
    always: Boolean = false
) {
    if (always || amount > 0.0) add(PayrollRow(label, minutes, amount, tone))
}

private fun MutableList<PayrollRow>.addSigned(label: String, amount: Double) {
    val tone = when {
        amount < 0.0 -> PayrollTone.Negative
        amount > 0.0 -> PayrollTone.Positive
        else -> PayrollTone.Muted
    }
    add(PayrollRow(label, null, amount, tone))
}

private fun holidayWorkMinutes(payBreakdown: MonthlyNetPayBreakdown, payDetails: MonthlyPayDetails): Int {
    val divisor = payBreakdown.grossHourlyRate * CONFIG.pay.holidayBonus
    if (divisor <= 0.0 || payDetails.grossHolidayPremium <= 0.0) return 0
    return (payDetails.grossHolidayPremium / divisor * MINUTES_PER_HOUR).roundToInt().coerceAtLeast(0)
}

private fun uncoveredDeficitMinutes(payBreakdown: MonthlyNetPayBreakdown, payDetails: MonthlyPayDetails): Int {
    val rate = payBreakdown.grossHourlyRate
    if (rate <= 0.0 || payDetails.grossUncoveredDeficitLoss <= 0.0) {
        return payDetails.uncoveredDeficitMinutes
    }
    return (payDetails.grossUncoveredDeficitLoss / rate * MINUTES_PER_HOUR).roundToInt().coerceAtLeast(0)
}
