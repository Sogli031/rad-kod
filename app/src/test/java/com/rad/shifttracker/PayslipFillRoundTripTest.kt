package com.rad.shifttracker

import java.time.LocalDate
import java.time.YearMonth
import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Slučajni „pravi“ meseci (GO, bolovanje, plaćeno, izostanci, kraći i produženi dani, zamena smene,
 * dupla smena, rad vikendom i praznikom) upisani u aplikaciju; lista se sastavi iz njenih sati, pa
 * „Popuni iz liste“ mora da nađe raspored koji se posle upisa tačno slaže sa listom.
 */
class PayslipFillRoundTripTest {

    private val kinds = listOf("vac", "sick", "leave", "absent", "short", "ext", "swap", "wkend", "holwork", "double")

    private fun isHoliday(date: LocalDate) = isSerbianHoliday(date.year, date.monthValue, date.dayOfMonth)

    @Suppress("CyclomaticComplexMethod", "LongMethod")
    private fun randomChanges(year: Int, month: Int, phase: Shift, chosen: Set<String>, rnd: Random): List<TruthDay> {
        val changes = LinkedHashMap<Int, TruthDay>()
        val days = (1..YearMonth.of(year, month).lengthOfMonth()).map { LocalDate.of(year, month, it) }
        val free = days.filter { it.dayOfWeek.value <= 5 && !isHoliday(it) }.map { it.dayOfMonth }.toMutableList()
        val weekend = days.filter { it.dayOfWeek.value > 5 }.map { it.dayOfMonth }
        val holidayWeekdays = days.filter { it.dayOfWeek.value <= 5 && isHoliday(it) }.map { it.dayOfMonth }
        fun shiftOf(day: Int) = rotationShiftFor(LocalDate.of(year, month, day), phase)
        fun otherShift(than: Shift) = Shift.workShifts().filter { it != than }.let { it[rnd.nextInt(it.size)] }
        fun anyShift() = Shift.workShifts().let { it[rnd.nextInt(it.size)] }
        fun block(size: Int): List<Int> {
            if (free.size < size) return emptyList()
            val start = rnd.nextInt(free.size - size + 1)
            val taken = free.subList(start, start + size).toList()
            free.removeAll(taken.toSet())
            return taken
        }
        fun one(): Int? = if (free.isEmpty()) null else free.removeAt(rnd.nextInt(free.size))

        if ("vac" in chosen) block(1 + rnd.nextInt(10)).forEach { changes[it] = TruthDay(it, Shift.VACATION) }
        if ("sick" in chosen) block(1 + rnd.nextInt(8)).forEach { changes[it] = TruthDay(it, Shift.SICK, sickRate = 0.65) }
        if ("leave" in chosen) {
            block(1 + rnd.nextInt(3)).forEach { changes[it] = TruthDay(it, Shift.SICK, source = DayRecordSource.PAID_LEAVE) }
        }
        if ("absent" in chosen) repeat(1 + rnd.nextInt(5)) { one()?.let { changes[it] = TruthDay(it, Shift.OFF, 0, exempt = true) } }
        if ("short" in chosen) repeat(1 + rnd.nextInt(4)) {
            one()?.let { changes[it] = TruthDay(it, shiftOf(it), 1 + rnd.nextInt(7), exempt = true) }
        }
        if ("ext" in chosen) repeat(1 + rnd.nextInt(4)) { one()?.let { changes[it] = TruthDay(it, shiftOf(it), 9 + rnd.nextInt(4)) } }
        if ("swap" in chosen) repeat(1 + rnd.nextInt(3)) { one()?.let { changes[it] = TruthDay(it, otherShift(shiftOf(it))) } }
        if ("double" in chosen) repeat(1 + rnd.nextInt(3)) {
            one()?.let { changes[it] = TruthDay(it, shiftOf(it), 8, shift2 = otherShift(shiftOf(it)), hours2 = 2 + rnd.nextInt(5)) }
        }
        if ("wkend" in chosen && weekend.isNotEmpty()) {
            weekend.shuffled(rnd).take(1 + rnd.nextInt(minOf(4, weekend.size))).forEach {
                changes[it] = TruthDay(it, anyShift(), if (rnd.nextInt(3) == 0) 4 + rnd.nextInt(9) else 8)
            }
        }
        if ("holwork" in chosen) holidayWeekdays.forEach {
            if (rnd.nextBoolean()) {
                val hours = 8 - rnd.nextInt(3) + rnd.nextInt(3)
                changes[it] = TruthDay(it, anyShift(), hours, exempt = hours < 8)
            }
        }
        return changes.values.toList()
    }

    @Test
    fun randomMonthsAreFilledExactlyAndWithoutInventingExtraDays() {
        val random = Random(20260930)
        var cases = 0
        var filled = 0
        var moreSpecialThanTruth = 0
        val failures = mutableListOf<String>()
        repeat(240) { index ->
            val year = if (random.nextBoolean()) 2026 else 2027
            val month = 1 + random.nextInt(12)
            val phase = TEST_ROTATION_CYCLE[random.nextInt(3)]
            val chosen = kinds.filter { random.nextInt(100) < 30 }.toSet().ifEmpty { setOf(kinds[random.nextInt(kinds.size)]) }
            val changes = randomChanges(year, month, phase, chosen, random)
            val truth = truthMonth(year, month, phase, changes)
            val slip = payslipFromRepo(truth, year, month)
            // Lista iz podataka aplikacije mora da se slaže sa njima; inače to nije greška popune.
            if (hourMismatches(truth, slip).isNotEmpty()) return@repeat
            cases++
            val repo = templateRepoBefore(year, month, phase)
            val name = "#$index $year-$month $phase $chosen"
            when (val result = planMonthFromPayslip(repo, slip)) {
                is MonthFillResult.Failure -> failures += "$name: ${result.message}"
                is MonthFillResult.Success -> {
                    filled++
                    applyMonthFill(repo, result)
                    val mismatches = hourMismatches(repo, slip)
                    assertTrue("$name posle upisa: $mismatches", mismatches.isEmpty())
                    val filledSpecial = result.days.count { it.isSpecial() && it.kind != FillDayKind.HOLIDAY_OFF }
                    val truthSpecial = changes.size
                    if (filledSpecial > truthSpecial) moreSpecialThanTruth++
                }
            }
        }
        println("slučajeva=$cases popunjeno=$filled višeNeobičnihNegoPravo=$moreSpecialThanTruth pali=${failures.take(5)}")
        assertTrue("premalo slučajeva: $cases", cases >= 150)
        assertTrue("uspelo $filled/$cases; palo: ${failures.take(8)}", filled >= cases * 97 / 100)
        // Popuna ne sme da izmišlja neobične dane kad ih lista ne traži (retko, kad sati druge kombinacije daju jeftinije).
        assertTrue("više neobičnih dana nego pravo: $moreSpecialThanTruth/$filled", moreSpecialThanTruth * 100 <= filled * 8)
        assertEquals(0, failures.count { it.contains("neočekivana") })
    }
}
