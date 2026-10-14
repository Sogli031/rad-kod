package com.rad.shifttracker

import java.time.DayOfWeek
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import com.rad.shifttracker.repo.BatchRecord
import com.rad.shifttracker.repo.ShiftRepository
import com.rad.shifttracker.repo.migrateFourBrigadeSaturdayRule
import com.rad.shifttracker.repo.populateMonthsAhead
import com.rad.shifttracker.smene.DayRecordSource
import com.rad.shifttracker.smene.Shift
import com.rad.shifttracker.smene.ShiftFillMode
import com.rad.shifttracker.smene.ShiftFillSettings
import com.rad.shifttracker.smene.ShiftScheduler

/** Četvorobrigada od 12.10.2026: prva smena ponedeljkom se radi, treća subotom ne. */
class FourBrigadeSaturdayRuleTest {
    private val scheduler = ShiftScheduler()

    @Test
    fun `cetvorobrigada od promene radi prvu ponedeljkom a trecu subotom ne`() {
        val repo = testRepository()
        fillLivnica(repo, LocalDate.of(2026, 11, 1))
        val november = (1..30).map { LocalDate.of(2026, 11, it) }
        val mondayFirst = november.filter { it.dayOfWeek == DayOfWeek.MONDAY && livnicaCycle(it) == Shift.FIRST }
        val saturdayThird = november.filter { it.dayOfWeek == DayOfWeek.SATURDAY && livnicaCycle(it) == Shift.THIRD }
        assertTrue(mondayFirst.isNotEmpty() && saturdayThird.isNotEmpty())
        mondayFirst.forEach { assertEquals(Shift.FIRST, repo.get(it.year, it.monthValue, it.dayOfMonth)!!.shift) }
        saturdayThird.forEach { assertEquals(Shift.OFF, repo.get(it.year, it.monthValue, it.dayOfMonth)!!.shift) }
    }

    @Test
    fun `ranija cetvorobrigadna popuna se presnimava po novom pravilu od danas`() {
        val repo = testRepository()
        fillLivnica(repo, LocalDate.of(2026, 10, 1))
        fillLivnica(repo, LocalDate.of(2026, 11, 1))
        val expected = repo.getMonthRecords(2026, 11).associate { it.date to it.shift }
        // Zapisi kao iz ranije verzije: ponedeljkom bez prve smene, subotom treća smena.
        val legacy = (1..31).map { LocalDate.of(2026, 10, it) } + (1..30).map { LocalDate.of(2026, 11, it) }
        repo.setBatch(
            legacy.mapNotNull { date ->
                val old = scheduler.legacyFourBrigadeShiftForDate(date, LocalDate.of(2026, 9, 25), 5)
                val current = repo.get(date.year, date.monthValue, date.dayOfMonth)!!
                if (old == current.shift || current.isHoliday) return@mapNotNull null
                BatchRecord(
                    date.year, date.monthValue, date.dayOfMonth, old,
                    if (old == Shift.OFF) 0 else 8,
                    balanceExempt = old == Shift.OFF,
                    source = DayRecordSource.AUTO_FILL
                )
            }
        )
        val pastBefore = repo.getMonthRecords(2026, 10).filter { it.day < 14 }.map { it.shift }
        // Ručni unos subotom sa trećom smenom ostaje.
        val manualSaturday = legacy.first {
            it.monthValue == 11 && it.dayOfWeek == DayOfWeek.SATURDAY && livnicaCycle(it) == Shift.THIRD
        }
        repo.setBatch(listOf(BatchRecord(2026, 11, manualSaturday.dayOfMonth, Shift.THIRD, 8)))

        assertTrue(repo.migrateFourBrigadeSaturdayRule(LocalDate.of(2026, 10, 14)) > 0)

        repo.getMonthRecords(2026, 11).filter { it.date != manualSaturday }.forEach {
            assertEquals(it.date.toString(), expected[it.date], it.shift)
        }
        assertEquals(Shift.THIRD, repo.get(2026, 11, manualSaturday.dayOfMonth)!!.shift)
        assertEquals(pastBefore, repo.getMonthRecords(2026, 10).filter { it.day < 14 }.map { it.shift })
        assertEquals(0, repo.migrateFourBrigadeSaturdayRule(LocalDate.of(2026, 10, 14)))
    }

    @Test
    fun `novo pravilo cetvorobrigade ne dira trobrigadu`() {
        val repo = testRepository()
        repo.setBatch(listOf(BatchRecord(2026, 11, 7, Shift.THIRD, 8, source = DayRecordSource.AUTO_FILL)))
        assertEquals(0, repo.migrateFourBrigadeSaturdayRule(LocalDate.of(2026, 10, 14)))
        assertEquals(Shift.THIRD, repo.get(2026, 11, 7)!!.shift)
    }

    private fun livnicaCycle(date: LocalDate): Shift =
        scheduler.fourBrigadeCycleShiftForDate(date, LocalDate.of(2026, 9, 25), 5)

    private fun fillLivnica(repo: ShiftRepository, month: LocalDate) {
        // Livnica, 25.09.2026. = drugi dan treće smene (indeks 5 u ciklusu 1-1-2-2-3-3-0-0).
        scheduler.populateMonthsAhead(
            repo = repo,
            referenceDate = month,
            settings = ShiftFillSettings(
                mode = ShiftFillMode.FOUR_BRIGADE,
                anchorDate = LocalDate.of(2026, 9, 25),
                selectedShift = Shift.THIRD,
                cycleDayIndex = 5
            ),
            monthsAhead = 0
        )
    }
}
