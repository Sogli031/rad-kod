package com.rad.shifttracker.data.repository

import com.rad.shifttracker.AppConfig
import com.rad.shifttracker.BatchRecord
import com.rad.shifttracker.DayRecord
import com.rad.shifttracker.MonthKey
import com.rad.shifttracker.Shift

internal data class DeleteMonthOutcome(
    val recordsDeletedCount: Int,
    val removedMonthlyPayBases: Boolean,
    val removedMonthlyAdditions: Boolean,
    val removedMonthlyAdjustments: Boolean
) {
    val shouldNotify: Boolean
        get() = recordsDeletedCount > 0 ||
            removedMonthlyPayBases ||
            removedMonthlyAdditions ||
            removedMonthlyAdjustments
}

internal data class DeleteAllOutcome(
    val recordsDeletedCount: Int,
    val hadMonthlyPayBases: Boolean,
    val hadMonthlyAdditions: Boolean,
    val hadMonthlyNetAdjustments: Boolean
) {
    val shouldNotify: Boolean
        get() = recordsDeletedCount > 0 ||
            hadMonthlyPayBases ||
            hadMonthlyAdditions ||
            hadMonthlyNetAdjustments
}

internal class RepositoryBulkOperations(
    private val config: AppConfig,
    private val payState: RepositoryPayState,
    private val recordStore: ShiftRecordStore,
    private val invalidateSummaryAndRollingDependents: (MonthKey) -> Unit
) {
    fun deleteMonth(monthKey: MonthKey): DeleteMonthOutcome {
        val recordsToDelete = recordStore.getMonthRecords(monthKey)
        for (record in recordsToDelete) {
            recordStore.delete(record.year, record.month, record.day)
        }
        val removedMonthlyPayBases = payState.monthlyPayBases.remove(monthKey) != null
        val removedMonthlyAdditions = payState.monthlyGrossAdditions.remove(monthKey) != null
        val removedMonthlyAdjustments = payState.monthlyNetAdjustments.remove(monthKey) != null
        if (removedMonthlyPayBases || removedMonthlyAdditions || removedMonthlyAdjustments) {
            invalidateSummaryAndRollingDependents(monthKey)
        }
        return DeleteMonthOutcome(
            recordsDeletedCount = recordsToDelete.size,
            removedMonthlyPayBases = removedMonthlyPayBases,
            removedMonthlyAdditions = removedMonthlyAdditions,
            removedMonthlyAdjustments = removedMonthlyAdjustments
        )
    }

    fun deleteAllData(): DeleteAllOutcome {
        val count = recordStore.allRecords.size
        val hadMonthlyPayBases = payState.monthlyPayBases.isNotEmpty()
        val hadMonthlyAdditions = payState.monthlyGrossAdditions.isNotEmpty()
        val hadMonthlyNetAdjustments = payState.monthlyNetAdjustments.isNotEmpty()
        recordStore.clear()
        payState.monthlyPayBases.clear()
        payState.monthlyGrossAdditions.clear()
        payState.monthlyNetAdjustments.clear()
        payState.dirtyMonths.clear()
        payState.invalidateSummariesRollingAndCompBalance()
        return DeleteAllOutcome(
            recordsDeletedCount = count,
            hadMonthlyPayBases = hadMonthlyPayBases,
            hadMonthlyAdditions = hadMonthlyAdditions,
            hadMonthlyNetAdjustments = hadMonthlyNetAdjustments
        )
    }

    fun setBatch(batchRecords: List<BatchRecord>) {
        for (rec in batchRecords) {
            recordStore.delete(rec.year, rec.month, rec.day)
            val finalSickRate = if (rec.shift != Shift.SICK) {
                null
            } else {
                rec.sickPayRate ?: config.pay.sickPayRate
            }
            val record = DayRecord(
                year = rec.year,
                month = rec.month,
                day = rec.day,
                shift = rec.shift,
                hours = rec.hours,
                balanceExempt = rec.balanceExempt,
                sickPayRate = finalSickRate,
                source = rec.source
            )
            recordStore.set(record)
        }
    }
}
