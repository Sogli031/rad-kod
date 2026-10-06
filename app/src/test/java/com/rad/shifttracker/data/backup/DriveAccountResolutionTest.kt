package com.rad.shifttracker.data.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class DriveAccountResolutionTest {

    @Test
    fun `sacuvan nalog ima prednost i stari se ne cita`() {
        val result = resolveDriveAccount("a@gmail.com", legacyChecked = false) { fail("ne sme da čita stari nalog"); null }
        assertEquals(DriveAccountResolution("a@gmail.com", persist = false), result)
    }

    @Test
    fun `prvo pokretanje posle prelaska preuzima stari nalog`() {
        val result = resolveDriveAccount(null, legacyChecked = false) { "stari@gmail.com" }
        assertEquals(DriveAccountResolution("stari@gmail.com", persist = true), result)
    }

    @Test
    fun `bez starog naloga se ipak pamti da je provereno`() {
        val result = resolveDriveAccount(null, legacyChecked = false) { null }
        assertEquals(DriveAccountResolution(null, persist = true), result)
    }

    @Test
    fun `posle odjave stari nalog ne ozivljava`() {
        val result = resolveDriveAccount(null, legacyChecked = true) { fail("ne sme da čita stari nalog"); "stari@gmail.com" }
        assertEquals(DriveAccountResolution(null, persist = false), result)
    }

    @Test
    fun `prazan sacuvan mejl se tretira kao da ga nema`() {
        val result = resolveDriveAccount("", legacyChecked = true) { null }
        assertEquals(DriveAccountResolution(null, persist = false), result)
    }
}
