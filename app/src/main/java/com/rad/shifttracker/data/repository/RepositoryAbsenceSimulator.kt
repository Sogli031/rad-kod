package com.rad.shifttracker.data.repository

import com.rad.shifttracker.AbsenceRangeNetImpact
import com.rad.shifttracker.AppConfig
import com.rad.shifttracker.DayRecord
import com.rad.shifttracker.MonthKey
import com.rad.shifttracker.Shift
import com.rad.shifttracker.Weekday
import java.time.LocalDate
import java.time.YearMonth

internal class RepositoryAbsenceSimulator(
    private val config: AppConfig,
    private val recordStore: ShiftRecordStore,
    private val payQueries: RepositoryPayQueries
) {
    fun previewAbsenceRangeNetImpact(
        year: Int,
        month: Int,
        startDay: Int,
        endDay: Int,
        absenceShift: Shift,
        sickPayRate: Double?
    ): AbsenceRangeNetImpact {
        val yearMonth = try {
            YearMonth.of(year, month)
        } catch (_: Exception) {
            return payQueries.emptyAbsenceRangeNetImpact()
        }
        val targetShift = when (absenceShift) {
            Shift.SICK -> Shift.SICK
            Shift.VACATION -> Shift.VACATION
            else -> return payQueries.emptyAbsenceRangeNetImpact()
        }
        val start = minOf(startDay, endDay).coerceIn(1, yearMonth.lengthOfMonth())
        val end = maxOf(startDay, endDay).coerceIn(1, yearMonth.lengthOfMonth())
        val monthKey = MonthKey(year, month)
        val currentRecords = recordStore.getMonthRecords(monthKey)
        val previewByDay = currentRecords.associateBy { it.day }.toMutableMap()
        var affectedDays = 0
        var replacedWorkHours = 0
        var replacedNightHours = 0

        for (day in start..end) {
            val date = LocalDate.of(year, month, day)
            if (!Weekday.from(date).isWorkday) continue

            val existing = previewByDay[day]
            if (existing != null && existing.shift in Shift.workShifts()) {
                replacedWorkHours += existing.totalHours
                replacedNightHours += payQueries.getNightHoursUnsafe(existing)
            }
            previewByDay[day] = DayRecord(
                year = year,
                month = month,
                day = day,
                shift = targetShift,
                hours = config.pay.defaultHours,
                sickPayRate = if (targetShift == Shift.SICK) {
                    sickPayRate ?: config.pay.sickPayRate
                } else {
                    null
                }
            )
            affectedDays += 1
        }

        val currentNet = payQueries.calculateAnnualMonthNetPayUnsafe(
            year = year,
            month = month,
            monthRecords = currentRecords,
            includeRegress = true
        ).displayPay
        val previewNet = payQueries.calculateAnnualMonthNetPayUnsafe(
            year = year,
            month = month,
            monthRecords = previewByDay.values.toList(),
            includeRegress = true
        ).displayPay

        return AbsenceRangeNetImpact(
            currentNetPay = currentNet,
            previewNetPay = previewNet,
            netDifference = previewNet - currentNet,
            affectedDays = affectedDays,
            absenceHours = affectedDays * config.pay.defaultHours,
            replacedWorkHours = replacedWorkHours,
            replacedNightHours = replacedNightHours
        )
    }
}
