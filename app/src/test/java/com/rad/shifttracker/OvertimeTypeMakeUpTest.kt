package com.rad.shifttracker

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/** Automatska odrada (višak radnim danom odrađuje cele neradne dane) i vrste prekovremenog. */
class OvertimeTypeMakeUpTest {
    @Test
    fun `stara oznaka odrada se cita kao placeno`() {
        // „Odrada" (2) je uklonjena iz izbora; stari bekapi je čitaju kao „Plaćeno".
        val json = JSONObject(mapOf("shift" to 2, "hours" to 8, "shift2" to 3, "hours2" to 2, "overtime_type" to 2))
        assertEquals(OvertimeType.PAID, DayRecord.fromJson(2026, 6, 3, json).overtimeType)

        assertEquals(OvertimeType.PAID, OvertimeType.fromValue(0))
        assertEquals(OvertimeType.COMP_TIME, OvertimeType.fromValue(1))
        assertEquals(OvertimeType.PAID, OvertimeType.fromValue(7))
        assertEquals(listOf(OvertimeType.PAID, OvertimeType.COMP_TIME), OvertimeType.entries.toList())
    }

    @Test
    fun `jun 2026 visak automatski odradjuje neradne dane kao platni listic`() {
        // Listić 06/2026: 6h prekovremenog (01.06. 14h), +2h 03.06. i +3h 25.06. su
        // režija sa noćnim dodatkom jer odrađuju neradne dane 08, 10. i 17.06.
        val repo = testRepository()
        repo.set(2026, 6, 1, Shift.SECOND, 8, shift2 = Shift.THIRD, hours2 = 6)
        repo.set(2026, 6, 3, Shift.SECOND, 8, shift2 = Shift.THIRD, hours2 = 2)
        repo.set(2026, 6, 25, Shift.SECOND, 8, shift2 = Shift.THIRD, hours2 = 3)
        listOf(2, 4, 11, 12).forEach { day -> repo.set(2026, 6, day, Shift.FIRST, 8) }
        listOf(5).forEach { day -> repo.set(2026, 6, day, Shift.FIRST, 8) }
        listOf(15, 16, 18, 19).forEach { day -> repo.set(2026, 6, day, Shift.THIRD, 8) }
        listOf(8, 10, 17).forEach { day -> repo.set(2026, 6, day, Shift.OFF, 0) }
        repo.set(2026, 6, 9, Shift.FIRST, 7)
        repo.set(2026, 6, 22, Shift.SECOND, 5)
        repo.set(2026, 6, 23, Shift.SECOND, 5)
        repo.set(2026, 6, 24, Shift.SECOND, 4)
        repo.set(2026, 6, 26, Shift.SECOND, 4)
        repo.set(2026, 6, 29, Shift.FIRST, 7)
        repo.set(2026, 6, 30, Shift.FIRST, 5)

        val details = repo.getMonthlyPayDetails(2026, 6)

        assertEquals(6 * 60, details.overtimeMinutes)
        assertEquals(138 * 60, details.regularWorkedBaseMinutes)
        assertEquals(43 * 60, details.nightMinutes)
    }

    @Test
    fun `jul 2026 placeni visak i manjak poslednjeg dana prate listic`() {
        // Listić 07/2026: 17h prekovremenog (3h 01.07. + 10h subota + 4h nedelja),
        // režija 84,5h zaokruženo na 85h uz „Izostanci 1", 3h izostanaka.
        val repo = testRepository()
        repo.set(2026, 7, 1, Shift.FIRST, 8, shift2 = Shift.SECOND, hours2 = 3)
        listOf(2, 3).forEach { day -> repo.set(2026, 7, day, Shift.FIRST, 8) }
        repo.set(2026, 7, 4, Shift.FIRST, 8, shift2 = Shift.SECOND, hours2 = 2)
        repo.set(2026, 7, 5, Shift.FIRST, 4)
        listOf(6, 7, 8, 9, 28, 29, 30).forEach { day -> repo.set(2026, 7, day, Shift.THIRD, 8) }
        listOf(10, 13, 14, 15, 16, 17, 20, 21, 22, 23, 24, 27).forEach { day ->
            repo.set(2026, 7, day, Shift.VACATION, 8)
        }
        repo.set(2026, 7, 31, Shift.THIRD, 4, regularShortageMinutes = 30)

        val details = repo.getMonthlyPayDetails(2026, 7)

        assertEquals(17 * 60, details.overtimeMinutes)
        assertEquals(84 * 60 + 30, details.regularWorkedBaseMinutes)
        assertEquals(96 * 60, details.vacationMinutes)
        assertEquals(4 * 60, details.sundayMinutes)
        // Listić: 60h noćnog — 31.07. (4h30 u trećoj) nosi samo 4 puna sata.
        assertEquals(60 * 60, details.nightMinutes)
        // Fond 184h − (84,5h režije + 96h GO) = 3,5h; listić to knjiži kao 3h + 30 min kroz šifru 96.
        assertEquals(3 * 60 + 30, details.fundAbsenceMinutes(184))
    }

    @Test
    fun `za sate ne ide u prekovremeni ni u odradu`() {
        val record = DayRecord(
            2026, 6, 3, Shift.SECOND, 8,
            shift2 = Shift.THIRD,
            hours2 = 2,
            overtimeType = OvertimeType.COMP_TIME
        )

        assertEquals(8 * 60, record.paidWorkMinutes)
        assertEquals(2 * 60, record.compTimeEarnedMinutes)
    }
}
