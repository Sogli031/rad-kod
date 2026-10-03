package com.rad.shifttracker

import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PayslipPayBaseParserTest {

    private fun success(text: String): PayslipPayBaseResult.Success {
        val result = parsePayslipPayBase(text.trimIndent())
        assertTrue("očekivan uspeh, dobijeno $result", result is PayslipPayBaseResult.Success)
        return result as PayslipPayBaseResult.Success
    }

    @Test
    fun vacationHasPriorityOverPaidLeave() {
        val result = success(
            """
            OBRAČUN ZARADE I NAKNADE ZARADE ZA MESEC 08/2026 rb.3/konačna
            10 Godisnji odmor 16.0 15267.88
            11 Placeno odsustvo 16.0 15267.88
            """
        )
        assertEquals(MonthKey(2026, 8), result.month)
        assertEquals("rb.3/konačna", result.runLabel)
        assertEquals(PayslipPayBaseSource.VACATION, result.chosen.source)
        assertEquals(BigDecimal("954.24"), result.chosen.hourlyBase)
        assertFalse(result.hasMismatch)
        assertEquals(
            "Avgust 2026 · Godišnji odmor (10): 15.267,88 / 16 h = 954,24 RSD/h",
            payslipSummaryText(result)
        )
    }

    @Test
    fun vacationAndHolidayWithSameBaseHaveNoWarning() {
        val result = success(
            """
            OBRAČUN ZARADE I NAKNADE ZARADE ZA MESEC 01/2026 rb.2/konačna
            10 Godisnji odmor 40.0 36514.83
            12 Drzavni praznik 24.0 21908.9
            """
        )
        assertEquals(MonthKey(2026, 1), result.month)
        assertEquals(PayslipPayBaseSource.VACATION, result.chosen.source)
        assertEquals(BigDecimal("912.87"), result.chosen.hourlyBase)
        val holiday = result.candidates.single { it.source == PayslipPayBaseSource.STATE_HOLIDAY }
        assertEquals(BigDecimal("912.87"), holiday.hourlyBase)
        assertFalse(result.hasMismatch)
        assertNull(payslipMismatchWarningText(result))
    }

    @Test
    fun sickLeaveUsesPercentFromLineAndIgnoresSurroundingRows() {
        val result = success(
            """
            OBRAČUN ZARADE I NAKNADE ZARADE ZA MESEC 03/2026 rb.3/konačna
            Oznaka i vrsta rada ČASOVI % RADA BRUTO IZNOS
            95 Izostanci 2.0 0
            01 Rad u reziji 134.0 85400.88
            20 Nocni rad 78.0 41 20381.49
            24 Uvecanje za prekovremeni rad 3.0 41 783.9
            40 Bolovanje za tekuci mesec 40.0 65 23888.93
            UKUPNO 176.0 104,209.59
            PIO 10.00% 16,993.57 6,572.61 10,420.96
            """
        )
        assertEquals(MonthKey(2026, 3), result.month)
        assertEquals(PayslipPayBaseSource.SICK_LEAVE, result.chosen.source)
        assertEquals(BigDecimal("65"), result.chosen.percent)
        assertEquals(BigDecimal("918.81"), result.chosen.hourlyBase)
        assertEquals(1, result.candidates.size)
        assertEquals("23.888,93 / (40 h × 65%) = 918,81 RSD/h", payslipFormulaText(result.chosen))
    }

    @Test
    fun sickLeavePercentIsNotHardcoded() {
        val result = success(
            """
            ZA MESEC 03/2026 rb.3/konačna
            40 Bolovanje za tekuci mesec 40.0 100 36752.2
            """
        )
        assertEquals(BigDecimal("918.81"), result.chosen.hourlyBase)
    }

    @Test
    fun onlyRegularWorkReportsNoSource() {
        val result = parsePayslipPayBase(
            """
            OBRAČUN ZARADE I NAKNADE ZARADE ZA MESEC 06/2026 rb.4/konačna
            01 Rad u reziji 134.0 85400.88
            """.trimIndent()
        )
        assertEquals(PayslipPayBaseResult.NoSource(MonthKey(2026, 6)), result)
        assertEquals(
            "Na listi za 06/2026 nema godišnjeg odmora, plaćenog odsustva, državnog praznika ni bolovanja, " +
                "pa se osnovica ne može izračunati.",
            payslipImportErrorText(result)
        )
    }

    @Test
    fun paidLeaveAloneIsUsed() {
        val result = success(
            """
            ZA MESEC 05/2026 rb.3/konačna
            11 Placeno odsustvo 16.0 15209.29
            """
        )
        assertEquals(PayslipPayBaseSource.PAID_LEAVE, result.chosen.source)
        assertEquals(BigDecimal("950.58"), result.chosen.hourlyBase)
    }

    @Test
    fun stateHolidayAloneIsUsed() {
        val result = success(
            """
            ZA MESEC 04/2026 rb.3/konačna
            12 Drzavni praznik 16.0 15250.93
            """
        )
        assertEquals(PayslipPayBaseSource.STATE_HOLIDAY, result.chosen.source)
        assertEquals(BigDecimal("953.18"), result.chosen.hourlyBase)
    }

    @Test
    fun differentBasesAmongLeaveItemsWarnWithoutBlocking() {
        val result = success(
            """
            ZA MESEC 08/2026 rb.3/konačna
            10 Godisnji odmor 16.0 15267.88
            12 Drzavni praznik 8.0 7400
            """
        )
        assertEquals(PayslipPayBaseSource.VACATION, result.chosen.source)
        assertTrue(result.hasMismatch)
        assertTrue(payslipMismatchWarningText(result).orEmpty().startsWith("Upozorenje:"))
    }

    @Test
    fun zeroHoursOrAmountIsSkipped() {
        val result = success(
            """
            ZA MESEC 08/2026 rb.3/konačna
            10 Godisnji odmor 0.0 0
            11 Placeno odsustvo 16.0 15267.88
            """
        )
        assertEquals(PayslipPayBaseSource.PAID_LEAVE, result.chosen.source)
    }

    @Test
    fun missingMonthAndMissingTextAreErrors() {
        assertEquals(PayslipPayBaseResult.NoMonth, parsePayslipPayBase("10 Godisnji odmor 16.0 15267.88"))
        assertEquals(PayslipPayBaseResult.NoMonth, parsePayslipPayBase("ZA MESEC 13/2026 rb.1/konačna"))
        assertEquals(PayslipPayBaseResult.NoText, parsePayslipPayBase(listOf("", "  ")))
    }

    @Test
    fun glyphsAreGroupedByBaselineEvenWhenRowsAreClose() {
        // Dva reda tabele na 13 pt razmaka; redosled znakova u PDF-u je izmešan.
        fun word(x: Float, y: Float, text: String) = text.mapIndexed { i, c ->
            PdfGlyph(x = x + i * 4f, y = y, width = 4f, fontSize = 8f, text = c.toString())
        }
        val glyphs = word(330f, 302f, "16.0") + word(30f, 289f, "10") + word(50f, 302f, "Placeno") +
            word(30f, 302f, "11") + word(50f, 289f, "Godisnji") + word(330f, 289f, "16.0")
        assertEquals(listOf("10 Godisnji 16.0", "11 Placeno 16.0"), buildPdfTextLines(glyphs))
    }

    @Test
    fun serbianNumberFormat() {
        assertEquals("15.267,88", formatSerbianDecimal(BigDecimal("15267.88"), minFraction = 2))
        assertEquals("21.908,90", formatSerbianDecimal(BigDecimal("21908.9"), minFraction = 2))
        assertEquals("37,5", formatSerbianDecimal(BigDecimal("37.5")))
        assertEquals("16", formatSerbianDecimal(BigDecimal("16.0")))
    }
}
