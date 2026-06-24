package com.rad.shifttracker.data.storage

import com.rad.shifttracker.BloodDonationLeaveBackup
import com.rad.shifttracker.BloodDonationLeaveState
import com.rad.shifttracker.DayRecord
import com.rad.shifttracker.bloodDonationLeaveDates
import org.json.JSONObject
import java.time.LocalDate
import java.util.TreeMap

internal object BloodDonationLeaveJsonCodec {
    private const val ENTRIES_KEY = "entries"
    private const val FIRST_DATE_KEY = "first_date"
    private const val PREVIOUS_RECORDS_KEY = "previous_records"

    fun build(leaves: List<BloodDonationLeaveState>): TreeMap<String, Any?> {
        if (leaves.size == 1) return buildEntry(leaves.single())
        val data = TreeMap<String, Any?>()
        data[ENTRIES_KEY] = leaves.sortedBy { it.firstDay }.map(::buildEntry)
        return data
    }

    fun parse(root: JSONObject?): List<BloodDonationLeaveState> {
        if (root == null) return emptyList()
        fun parseEntry(entry: JSONObject): BloodDonationLeaveState? {
            val firstDay = runCatching { LocalDate.parse(entry.optString(FIRST_DATE_KEY)) }.getOrNull() ?: return null
            val previousRecords = parsePreviousRecords(firstDay, entry.optJSONObject(PREVIOUS_RECORDS_KEY))
            return BloodDonationLeaveState(firstDay = firstDay, previousRecords = previousRecords)
        }

        val entries = root.optJSONArray(ENTRIES_KEY)
        if (entries != null) {
            return (0 until entries.length()).mapNotNull { index ->
                entries.optJSONObject(index)?.let(::parseEntry)
            }
        }
        return listOfNotNull(parseEntry(root))
    }

    private fun buildEntry(leave: BloodDonationLeaveState): TreeMap<String, Any?> {
        val data = TreeMap<String, Any?>()
        data[FIRST_DATE_KEY] = leave.firstDay.toString()
        val previousRecords = TreeMap<String, Any?>()
        for (backup in leave.previousRecords.sortedBy { it.date }) {
            previousRecords[backup.date.toString()] = backup.record?.toMap()
        }
        data[PREVIOUS_RECORDS_KEY] = previousRecords
        return data
    }

    private fun parsePreviousRecords(firstDay: LocalDate, root: JSONObject?): List<BloodDonationLeaveBackup> {
        val parsed = mutableMapOf<LocalDate, DayRecord?>()
        if (root != null) {
            val keys = root.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val date = runCatching { LocalDate.parse(key) }.getOrNull() ?: continue
                val recordData = root.optJSONObject(key)
                parsed[date] = recordData?.let {
                    DayRecord.fromJson(date.year, date.monthValue, date.dayOfMonth, it)
                }
            }
        }
        return bloodDonationLeaveDates(firstDay).map { date ->
            BloodDonationLeaveBackup(date = date, record = parsed[date])
        }
    }
}
