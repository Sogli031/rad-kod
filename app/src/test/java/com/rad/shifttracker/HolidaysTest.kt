package com.rad.shifttracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class HolidaysTest {

    // ============= Orthodox Easter Meeus algoritam =============

    @Test
    fun `orthodoxEasterSunday 2025 je 20 april`() {
        // Poznat datum: Pravoslavni Uskrs 2025. je 20. april
        val easter = orthodoxEasterSunday(2025)
        assertEquals(LocalDate.of(2025, 4, 20), easter)
    }

    @Test
    fun `orthodoxEasterSunday 2024 je 5 maj`() {
        // Poznat datum: Pravoslavni Uskrs 2024. je 5. maj
        val easter = orthodoxEasterSunday(2024)
        assertEquals(LocalDate.of(2024, 5, 5), easter)
    }

    @Test
    fun `orthodoxEasterSunday 2023 je 16 april`() {
        val easter = orthodoxEasterSunday(2023)
        assertEquals(LocalDate.of(2023, 4, 16), easter)
    }

    @Test
    fun `orthodoxEasterSunday 2000 je 30 april`() {
        val easter = orthodoxEasterSunday(2000)
        assertEquals(LocalDate.of(2000, 4, 30), easter)
    }

    @Test
    fun `orthodoxEasterSunday 1900 granična godina`() {
        // 1900: Meeus algoritam treba da vrati validan datum
        val easter = orthodoxEasterSunday(1900)
        assertNotNull(easter)
        // Pravoslavni Uskrs 1900. je bio 22. april (gregorijanski)
        assertEquals(LocalDate.of(1900, 4, 22), easter)
    }

    @Test
    fun `orthodoxEasterSunday 2100 granična godina sa promenom ofseta`() {
        // Posle 2100. se menja Julian-Gregorian ofset (sa 13 na 14 dana)
        val easter = orthodoxEasterSunday(2100)
        assertNotNull(easter)
        // Provera da je datum razuman (april ili maj)
        assertTrue(
            "Uskrs mora biti u aprilu ili maju, a dobili smo ${easter.monthValue}",
            easter.monthValue in 3..6
        )
    }

    @Test
    fun `orthodoxEasterSunday 2200 daleka budućnost`() {
        val easter = orthodoxEasterSunday(2200)
        assertNotNull(easter)
        assertTrue(
            "Uskrs mora biti u razumnom opsegu meseci",
            easter.monthValue in 3..6
        )
    }

    @Test
    fun `orthodoxEasterSunday prestupna godina 2028`() {
        // 2028 je prestupna godina
        val easter = orthodoxEasterSunday(2028)
        assertNotNull(easter)
        // Pravoslavni Uskrs 2028. je 16. april
        assertEquals(LocalDate.of(2028, 4, 16), easter)
    }

    @Test
    fun `orthodoxEasterSunday prestupna godina 2000`() {
        // 2000 je prestupna godina (deljiva sa 400)
        val easter = orthodoxEasterSunday(2000)
        assertEquals(LocalDate.of(2000, 4, 30), easter)
    }

    // ============= Fiksni srpski praznici =============

    @Test
    fun `isSerbianHoliday Nova godina 1 januar`() {
        assertTrue(isSerbianHoliday(2025, 1, 1))
    }

    @Test
    fun `isSerbianHoliday Nova godina 2 januar`() {
        assertTrue(isSerbianHoliday(2025, 1, 2))
    }

    @Test
    fun `isSerbianHoliday Božić 7 januar`() {
        assertTrue(isSerbianHoliday(2025, 1, 7))
    }

    @Test
    fun `isSerbianHoliday Dan državnosti 15 februar`() {
        assertTrue(isSerbianHoliday(2025, 2, 15))
    }

    @Test
    fun `isSerbianHoliday Dan državnosti 16 februar`() {
        assertTrue(isSerbianHoliday(2025, 2, 16))
    }

    @Test
    fun `isSerbianHoliday Dan državnosti preneti dan 2025`() {
        assertTrue(isSerbianHoliday(2025, 2, 17))
    }

    @Test
    fun `isSerbianHoliday Praznik rada 1 maj`() {
        assertTrue(isSerbianHoliday(2025, 5, 1))
    }

    @Test
    fun `isSerbianHoliday Praznik rada 2 maj`() {
        assertTrue(isSerbianHoliday(2025, 5, 2))
    }

    @Test
    fun `isSerbianHoliday Dan primirja 11 novembar`() {
        assertTrue(isSerbianHoliday(2025, 11, 11))
    }

    @Test
    fun `isSerbianHoliday normalan dan nije praznik`() {
        // 10. mart 2025. nije praznik
        assertFalse(isSerbianHoliday(2025, 3, 10))
    }

    // ============= Uskršnji praznici =============

    @Test
    fun `isSerbianHoliday Uskrs 2025`() {
        // Uskrs 2025 = 20. april
        assertTrue(isSerbianHoliday(2025, 4, 20))
    }

    @Test
    fun `isSerbianHoliday Veliki petak 2025`() {
        // Uskrs 2025 = 20. april, Veliki petak = 18. april
        assertTrue(isSerbianHoliday(2025, 4, 18))
    }

    @Test
    fun `isSerbianHoliday Velika subota 2025`() {
        // Velika subota = 19. april
        assertTrue(isSerbianHoliday(2025, 4, 19))
    }

    @Test
    fun `isSerbianHoliday Uskršnji ponedeljak 2025`() {
        // Uskršnji ponedeljak = 21. april
        assertTrue(isSerbianHoliday(2025, 4, 21))
    }

    // ============= getHolidayName testovi =============

    @Test
    fun `getHolidayName vraca ime za poznati praznik`() {
        val name = getHolidayName(2025, 1, 7)
        assertNotNull(name)
        assertTrue(name!!.contains("Božić"))
    }

    @Test
    fun `getHolidayName vraca null za obican dan`() {
        val name = getHolidayName(2025, 3, 10)
        assertNull(name)
    }

    @Test
    fun `getHolidayName vraca ime za Vaskrs`() {
        val name = getHolidayName(2025, 4, 20)
        assertNotNull(name)
        assertTrue(name!!.contains("Vaskrs"))
    }

    // ============= getSerbianHolidays lista testovi =============

    @Test
    fun `getSerbianHolidays vraca sve praznike za godinu`() {
        val holidays = getSerbianHolidays(2025)
        // Minimum: 9 fiksnih + 4 Uskršnja = 13 (može manje ako se neki poklope)
        assertTrue(
            "Očekivano bar 10 praznika, dobijeno ${holidays.size}",
            holidays.size >= 10
        )
    }

    @Test
    fun `getSerbianHolidays sortirana po datumu`() {
        val holidays = getSerbianHolidays(2025)
        for (i in 0 until holidays.size - 1) {
            val current = holidays[i]
            val next = holidays[i + 1]
            val currentDate = current.month * 100 + current.day
            val nextDate = next.month * 100 + next.day
            assertTrue(
                "Praznici nisu sortirani: ${current.name} ($currentDate) > ${next.name} ($nextDate)",
                currentDate <= nextDate
            )
        }
    }

    @Test
    fun `getSerbianHolidays keš vraća isti rezultat`() {
        // Prvi poziv puni keš
        val first = getSerbianHolidays(2030)
        // Drugi poziv čita iz keša
        val second = getSerbianHolidays(2030)
        // Moraju biti identični (ista referenca zbog keša)
        assertTrue("Keš mora vratiti isti objekat", first === second)
    }

    @Test
    fun `getSerbianHolidays različite godine daju različite Vaskrse`() {
        val holidays2025 = getSerbianHolidays(2025)
        val holidays2026 = getSerbianHolidays(2026)

        val vaskrs2025 = holidays2025.firstOrNull { it.name.contains("Vaskrs") && !it.name.contains("ponedeljak") }
        val vaskrs2026 = holidays2026.firstOrNull { it.name.contains("Vaskrs") && !it.name.contains("ponedeljak") }

        assertNotNull(vaskrs2025)
        assertNotNull(vaskrs2026)
        // Vaskrs pada na različite datume u različitim godinama
        assertTrue(
            "Vaskrs 2025 i 2026 moraju biti na različitim datumima",
            vaskrs2025!!.month != vaskrs2026!!.month || vaskrs2025.day != vaskrs2026.day
        )
    }

    @Test
    fun `getSerbianHolidays koristi ugradjenu listu za prenete dane i poklopljene praznike`() {
        assertFalse(isSerbianHoliday(2027, 2, 17))
        assertTrue(isSerbianHoliday(2027, 5, 4))
        assertEquals("Praznik rada + Velika subota", getHolidayName(2027, 5, 1))
        assertEquals("Praznik rada + Vaskrs", getHolidayName(2027, 5, 2))
    }

    @Test
    fun `getSerbianHolidays cuva kategoriju i oznaku prenetog dana`() {
        val transferred = getSerbianHolidays(2028).first { it.month == 1 && it.day == 3 }

        assertEquals(HolidayCategory.STATE_SUBSTITUTE, transferred.category)
        assertTrue(transferred.isSubstitute)
        assertEquals("Nova godina (preneti neradni dan)", transferred.name)
    }

    @Test
    fun `isSerbianHoliday ne oznacava radne drzavne praznike kao neradne`() {
        assertFalse(isSerbianHoliday(2026, 5, 9))
    }

    // ============= Merge praznika koji se poklapaju =============

    @Test
    fun `getSerbianHolidays spaja praznike na istom datumu`() {
        // Ako Vaskrs padne na 1. maj, imena se spajaju
        // Ovo je redak slučaj, ali testiramo da nema duplikata datuma
        val holidays = getSerbianHolidays(2025)
        val dateSet = mutableSetOf<Pair<Int, Int>>()
        for (h in holidays) {
            val key = h.month to h.day
            assertFalse(
                "Duplikat datuma pronađen: ${h.month}/${h.day}",
                dateSet.contains(key)
            )
            dateSet.add(key)
        }
    }
}
