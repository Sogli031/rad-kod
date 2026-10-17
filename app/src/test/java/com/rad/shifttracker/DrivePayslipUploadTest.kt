package com.rad.shifttracker

import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import com.rad.shifttracker.listici.PayslipPayBaseResult
import com.rad.shifttracker.listici.PayslipParseResult
import com.rad.shifttracker.listici.PayslipSideRunKind
import com.rad.shifttracker.listici.drivePayslipFileName
import com.rad.shifttracker.smene.MonthKey

class DrivePayslipUploadTest {
    private fun sideRun(kind: PayslipSideRunKind, runLabel: String?) =
        PayslipParseResult.SideRun(kind, MonthKey(2026, 9), runLabel, BigDecimal("20000"))

    @Test
    fun `akontacija i prevoz dobijaju mesec rada i rb`() {
        assertEquals("2026-09 akontacija rb.1.pdf", drivePayslipFileName(sideRun(PayslipSideRunKind.ADVANCE, "rb.1")))
        assertEquals("2026-09 prevoz rb.4.pdf", drivePayslipFileName(sideRun(PayslipSideRunKind.TRANSPORT, "rb.4")))
    }

    @Test
    fun `konacna lista bez dodatka posle rb`() {
        assertEquals("2026-03 konačna rb.2.pdf", drivePayslipFileName(MonthKey(2026, 3), "konačna", "rb.2/konačna"))
        assertEquals("2026-11 konačna.pdf", drivePayslipFileName(MonthKey(2026, 11), "konačna", null))
    }

    @Test
    fun `regres, nepoznat obracun i pdf koji nije lista se ne salju`() {
        assertNull(drivePayslipFileName(sideRun(PayslipSideRunKind.REGRESS, "rb.1")))
        assertNull(drivePayslipFileName(sideRun(PayslipSideRunKind.OTHER, null)))
        assertNull(drivePayslipFileName(PayslipParseResult.Failure(PayslipPayBaseResult.InvalidPdf)))
    }
}
