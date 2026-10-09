package com.rad.shifttracker.repo

import com.rad.shifttracker.osnova.AppConfig
import com.rad.shifttracker.smene.DayRecord
import com.rad.shifttracker.smene.MonthKey
import com.rad.shifttracker.smene.Shift
import com.rad.shifttracker.smene.Weekday
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

    /**
     * Neto razlika koju bolovanje u mesecu pravi u odnosu na platu da su ti radni dani odrađeni:
     * mesec kakav jeste − isti mesec sa danima bolovanja vraćenim na smenu iz rasporeda.
     * Isto poređenje kao pregled pri unosu bolovanja, samo unazad; negativno znači gubitak.
     */
    fun sickLeaveNetDifference(
        year: Int,
        month: Int,
        scheduledShift: (LocalDate) -> Shift?
    ): Double {
        val currentRecords = recordStore.getMonthRecords(MonthKey(year, month))
        var replaced = false
        val workedRecords = currentRecords.map { record ->
            if (record.shift != Shift.SICK || record.workHours <= 0 || !record.weekday.isWorkday) {
                return@map record
            }
            replaced = true
            val shift = scheduledShift(record.date)?.takeIf { it in Shift.workShifts() }
                ?: DayRecord(year, month, record.day, Shift.OFF, hours = 0).inferDeficitReplacementShift(currentRecords)
            DayRecord(
                year = year,
                month = month,
                day = record.day,
                shift = shift,
                hours = record.workHours,
                holidayOverride = record.holidayOverride
            )
        }
        if (!replaced) return 0.0

        val currentNet = payQueries.calculateAnnualMonthNetPayUnsafe(
            year = year,
            month = month,
            monthRecords = currentRecords,
            includeRegress = true
        ).displayPay
        val workedNet = payQueries.calculateAnnualMonthNetPayUnsafe(
            year = year,
            month = month,
            monthRecords = workedRecords,
            includeRegress = true
        ).displayPay
        return currentNet - workedNet
    }
}
