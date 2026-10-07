package com.rad.shifttracker

import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import com.rad.shifttracker.listici.GmailPayslipCache
import com.rad.shifttracker.listici.PayslipParseResult
import com.rad.shifttracker.listici.PayslipPayBaseResult
import com.rad.shifttracker.listici.PayslipSideRunKind
import com.rad.shifttracker.uvoz.listCountText
import com.rad.shifttracker.listici.parsePayslip
import com.rad.shifttracker.listici.payslipToJson
import com.rad.shifttracker.listici.resultFromJson
import com.rad.shifttracker.listici.resultToJson
import com.rad.shifttracker.smene.MonthKey

class GmailPayslipCacheTest {
    private val transport = PayslipParseResult.SideRun(
        PayslipSideRunKind.TRANSPORT, MonthKey(2026, 8), "rb.1", BigDecimal("4320.00"), MonthKey(2026, 9), BigDecimal("240.00")
    )
    private val regress = PayslipParseResult.SideRun(PayslipSideRunKind.REGRESS, MonthKey(2026, 8), null, BigDecimal("20000.00"))

    @Test
    fun sideRunsAndListsSurviveTheRoundTrip() {
        assertEquals(transport, resultFromJson(resultToJson(transport)!!))
        assertEquals(regress, resultFromJson(resultToJson(regress)!!))
        val list = parsePayslip(requireNotNull(PAYSLIPS_2026[8])) as PayslipParseResult.Success
        val restored = resultFromJson(resultToJson(list)!!) as PayslipParseResult.Success
        assertEquals(payslipToJson(list.payslip), payslipToJson(restored.payslip))
    }

    @Test
    fun messageIsServedOnlyWhenAllItsPdfsAreRead() {
        val cache = GmailPayslipCache.inMemory()
        assertNull(cache.results("m1"))
        cache.rememberNames("m1", listOf("a.pdf", "b.pdf"))
        cache.remember("m1", "a.pdf", transport)
        assertNull(cache.results("m1"))
        cache.remember("m1", "b.pdf", regress)
        assertEquals(listOf("a.pdf" to transport, "b.pdf" to regress), cache.results("m1"))

        // PDF koji nije lista se ne pamti, pa se poruka čita ponovo.
        cache.rememberNames("m2", listOf("x.pdf"))
        cache.remember("m2", "x.pdf", PayslipParseResult.Failure(PayslipPayBaseResult.NoText))
        assertNull(cache.results("m2"))
        // Mejl bez PDF-a ne traži ništa.
        cache.rememberNames("m3", emptyList())
        assertEquals(emptyList<Pair<String, PayslipParseResult>>(), cache.results("m3"))
    }
}

class ListCountTextTest {
    @Test
    fun countAgreesWithNumber() {
        assertEquals(
            listOf("1 lista", "3 liste", "5 listi", "11 listi", "12 listi", "19 listi", "21 lista", "22 liste"),
            listOf(1, 3, 5, 11, 12, 19, 21, 22).map(::listCountText)
        )
    }
}
