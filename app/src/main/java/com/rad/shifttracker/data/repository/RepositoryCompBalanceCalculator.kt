package com.rad.shifttracker.data.repository

import com.rad.shifttracker.CONFIG
import com.rad.shifttracker.MonthKey
import com.rad.shifttracker.shortageMinutesCoveredByExtraWork

private const val MINUTES_PER_HOUR = 60

internal class RepositoryCompBalanceCalculator(
    private val payState: RepositoryPayState,
    private val recordStore: ShiftRecordStore
) {
    fun hoursAtMonthStart(year: Int, month: Int): Int {
        return minutesAtMonthStart(year, month) / MINUTES_PER_HOUR
    }

    fun minutesAtMonthStart(year: Int, month: Int): Int {
        val target = MonthKey(year, month)
        payState.compBalanceCache[target]?.let { return it }

        val priorMonths = recordStore.getAllMonthsSorted().filter { it < target }
        if (priorMonths.isEmpty()) {
            payState.compBalanceCache[target] = payState.initialCompBalanceMinutes
            return payState.initialCompBalanceMinutes
        }

        var balance = payState.initialCompBalanceMinutes
        var startIndex = 0
        for (i in priorMonths.indices.reversed()) {
            val cached = payState.compBalanceCache[priorMonths[i]]
            if (cached != null) {
                balance = cached
                startIndex = i
                break
            }
        }

        for (i in startIndex until priorMonths.size) {
            balance += monthNetChangeMinutes(priorMonths[i])
            if (balance < 0) balance = 0
        }

        payState.compBalanceCache[target] = balance
        return balance
    }

    /** Isto kao MonthSummary.compTimeNetMinutes: manjak pokriven „Odradom" ne skida saldo. */
    private fun monthNetChangeMinutes(month: MonthKey): Int {
        val monthRecords = recordStore.getMonthRecords(month)
        return monthRecords.sumOf { it.netCompChangeMinutesFor(payState.brigadeType) } +
            shortageMinutesCoveredByExtraWork(
                records = monthRecords,
                year = month.year,
                month = month.month,
                brigadeType = payState.brigadeType,
                defaultHours = CONFIG.pay.defaultHours
            )
    }
}
