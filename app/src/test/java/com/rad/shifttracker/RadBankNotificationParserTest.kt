package com.rad.shifttracker

import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RadBankNotificationParserTest {

    @Test
    fun `valjaonica income from second to tenth creates advance candidate`() {
        val candidate = parseRadAdvancePaymentNotification(
            """
            NLB mKlik
            Priliv
            Iznos: 54.321,00 RSD
            Datum: 05.08.2026 09:14
            Opis:
            VALJAONICA BAKRA SEVOJNO
            PRVOMAJSKA 43 SEVOJNO 378406
            """.trimIndent()
        )

        assertNotNull(candidate)
        requireNotNull(candidate)
        assertEquals(MonthKey(2026, 8), candidate.month)
        assertEquals(54_321.0, candidate.amount, 0.001)
        assertEquals(LocalDateTime.of(2026, 8, 5, 9, 14), candidate.dateTime)
        assertTrue(candidate.place.contains("VALJAONICA BAKRA SEVOJNO"))
    }

    @Test
    fun `valjaonica income on second and tenth is accepted`() {
        val secondCandidate = parseRadAdvancePaymentNotification(
            """
            Priliv
            Iznos: 54.321,00 RSD
            Datum: 02.08.2026 09:14
            Opis: VALJAONICA BAKRA SEVOJNO
            """.trimIndent()
        )
        val tenthCandidate = parseRadAdvancePaymentNotification(
            """
            Priliv
            Iznos: 54.321,00 RSD
            Datum: 10.08.2026 09:14
            Opis: VALJAONICA BAKRA SEVOJNO
            """.trimIndent()
        )

        assertEquals(MonthKey(2026, 8), secondCandidate?.month)
        assertEquals(MonthKey(2026, 8), tenthCandidate?.month)
    }

    @Test
    fun `valjaonica income outside second to tenth is ignored`() {
        val firstCandidate = parseRadAdvancePaymentNotification(
            """
            Priliv
            Iznos: 54.321,00 RSD
            Datum: 01.08.2026 09:14
            Opis: VALJAONICA BAKRA SEVOJNO
            """.trimIndent()
        )
        val eleventhCandidate = parseRadAdvancePaymentNotification(
            """
            Priliv
            Iznos: 54.321,00 RSD
            Datum: 11.08.2026 09:14
            Opis: VALJAONICA BAKRA SEVOJNO
            """.trimIndent()
        )

        assertNull(firstCandidate)
        assertNull(eleventhCandidate)
    }

    @Test
    fun `non valjaonica income is ignored`() {
        val candidate = parseRadAdvancePaymentNotification(
            """
            Priliv
            Iznos: 54.321,00 RSD
            Datum: 05.07.2026 09:14
            Opis: NEKA DRUGA FIRMA
            """.trimIndent()
        )

        assertNull(candidate)
    }

    @Test
    fun `expense from valjaonica is ignored`() {
        val candidate = parseRadAdvancePaymentNotification(
            """
            Odliv
            Iznos: 54.321,00 RSD
            Datum: 05.07.2026 09:14
            Opis: VALJAONICA BAKRA SEVOJNO
            """.trimIndent()
        )

        assertNull(candidate)
    }

    @Test
    fun `iznos samo sa tackom hiljada se cita kao hiljade`() {
        val candidate = parseRadAdvancePaymentNotification(
            """
            Priliv
            Iznos: 54.321 RSD
            Datum: 05.08.2026 09:14
            Opis: VALJAONICA BAKRA SEVOJNO
            """.trimIndent()
        )

        assertEquals(54_321.0, candidate?.amount ?: 0.0, 0.001)
    }

    @Test
    fun `iznos sa zarezom kao separatorom hiljada se cita kao hiljade`() {
        val candidate = parseRadAdvancePaymentNotification(
            """
            Priliv
            Iznos: 54,321.00 RSD
            Datum: 05.08.2026 09:14
            Opis: VALJAONICA BAKRA SEVOJNO
            """.trimIndent()
        )

        assertEquals(54_321.0, candidate?.amount ?: 0.0, 0.001)
    }

    @Test
    fun `decimalni iznos sa zarezom se i dalje cita kao decimala`() {
        val parsed = parseRadBankNotificationText(
            """
            Odliv
            Iznos: 123,45 RSD
            Datum: 05.08.2026 09:14
            Opis: PRODAVNICA
            """.trimIndent()
        )

        assertEquals(123.45, parsed?.amount ?: 0.0, 0.001)
    }

    @Test
    fun `jednocifren dan i mesec u datumu se parsira`() {
        val candidate = parseRadAdvancePaymentNotification(
            """
            Priliv
            Iznos: 54.321,00 RSD
            Datum: 5.8.2026 9:14
            Opis: VALJAONICA BAKRA SEVOJNO
            """.trimIndent()
        )

        assertNotNull(candidate)
        assertEquals(LocalDateTime.of(2026, 8, 5, 9, 14), candidate?.dateTime)
    }

    @Test
    fun `datum stanja na racunu ne otima datum transakcije`() {
        val candidate = parseRadAdvancePaymentNotification(
            """
            Priliv
            Raspolozivo stanje na dan 01.08.2026
            Iznos: 54.321,00 RSD
            Datum: 05.08.2026 09:14
            Opis: VALJAONICA BAKRA SEVOJNO
            """.trimIndent()
        )

        assertNotNull(candidate)
        assertEquals(LocalDateTime.of(2026, 8, 5, 9, 14), candidate?.dateTime)
    }

    @Test
    fun `bank package filter follows nlb notification imports`() {
        assertEquals(true, isRadBankNotificationPackage("com.nlb.mklik"))
        assertEquals(true, isRadBankNotificationPackage("rs.nlbklik.mobile"))
        assertEquals(true, isRadBankNotificationPackage("com.some.nlb.notifications"))
        assertEquals(false, isRadBankNotificationPackage("com.android.systemui"))
    }
}
