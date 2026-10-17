package com.rad.shifttracker.repo

import java.time.YearMonth
import com.rad.shifttracker.obracun.YearOverview
import com.rad.shifttracker.obracun.YearOverviewMonth

/**
 * Godišnji pregled za [year] (i prošlu godinu za poređenje) iz postojećih mesečnih obračuna: neto je
 * ista „Neto plata“ kao ispod kalendara (isplata narednog meseca), sati iz zbira meseca. Meseci posle
 * [today] i meseci bez upisanih dana se preskaču.
 */
internal fun ShiftRepository.getYearOverview(year: Int, today: YearMonth): YearOverview =
    YearOverview.build(year, yearOverviewMonths(year, today), yearOverviewMonths(year - 1, today))

private fun ShiftRepository.yearOverviewMonths(year: Int, today: YearMonth): List<YearOverviewMonth> =
    (1..12).mapNotNull { month ->
        if (YearMonth.of(year, month).isAfter(today)) return@mapNotNull null
        if (getMonthRecords(year, month).isEmpty()) return@mapNotNull null
        val summary = getSummary(year, month)
        val payment = YearMonth.of(year, month).plusMonths(1)
        val receipt = getMonthlyReceipt(payment.year, payment.monthValue)
        YearOverviewMonth(
            month = month,
            net = receipt.workSalaryPay.takeIf { receipt.isSalaryAvailable && it > 0.0 },
            workHours = summary.workHours,
            overtimeHours = summary.overtimeHours,
            nightHours = summary.nightHours,
            sundayHours = summary.sundayHours,
            sickHours = summary.sickHours,
            vacationHours = summary.vacationHours
        )
    }
