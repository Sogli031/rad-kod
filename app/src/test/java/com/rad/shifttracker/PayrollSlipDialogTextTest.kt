package com.rad.shifttracker

import org.junit.Assert.assertEquals
import org.junit.Test

class PayrollSlipDialogTextTest {
    @Test
    fun `dialog labels match payslip wording`() {
        assertEquals("Popuni mesec po platnom listiću", PAYROLL_SLIP_DIALOG_TITLE)
        assertEquals("Sati sa listića", PAYROLL_SLIP_HOURS_SECTION)
        assertEquals("Bruto dodaci", PAYROLL_SLIP_ADDITIONS_SECTION)
        assertEquals("Predlog i poređenje", PAYROLL_SLIP_PREVIEW_SECTION)
        assertEquals("Obračun pre obustava", PAYROLL_SLIP_BEFORE_DEDUCTIONS_LABEL)
    }
}
