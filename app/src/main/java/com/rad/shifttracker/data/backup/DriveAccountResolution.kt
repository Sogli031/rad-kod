package com.rad.shifttracker.data.backup

/**
 * Koji nalog je prijavljen za backup pri pokretanju. Nalog sada pamti sama aplikacija; stari
 * GoogleSignIn ga je pamtio sam, pa se pri prvom pokretanju posle prelaska jednom preuzme odande.
 *
 * Preuzima se samo jednom ([legacyChecked]): posle odjave stari nalog ne sme ponovo da „oživi".
 */
internal data class DriveAccountResolution(
    /** Mejl prijavljenog naloga, ili null ako niko nije prijavljen. */
    val email: String?,
    /** true kad treba sačuvati [email] i obeležiti da je stari nalog proveren. */
    val persist: Boolean
)

internal fun resolveDriveAccount(
    savedEmail: String?,
    legacyChecked: Boolean,
    legacyEmail: () -> String?
): DriveAccountResolution {
    if (!savedEmail.isNullOrBlank()) return DriveAccountResolution(savedEmail, persist = false)
    if (legacyChecked) return DriveAccountResolution(null, persist = false)
    return DriveAccountResolution(legacyEmail()?.takeIf { it.isNotBlank() }, persist = true)
}
