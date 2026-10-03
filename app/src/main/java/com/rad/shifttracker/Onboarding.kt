package com.rad.shifttracker

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Prvo pokretanje posle instalacije: „Popuni smene“, pa Gmail sa listićima ([GmailOnboardingDialog] —
 * upisuje koeficijent, minuli rad, doprinose, olakšicu, porez, otežane uslove, prevoz i akontaciju).
 */
fun requiredOnboardingPermissions(context: Context): Array<String> {
    fun missing(permission: String) =
        ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED
    val permissions = mutableListOf<String>()
    if (Build.VERSION.SDK_INT in Build.VERSION_CODES.M..Build.VERSION_CODES.P &&
        missing(Manifest.permission.WRITE_EXTERNAL_STORAGE)
    ) {
        permissions += Manifest.permission.WRITE_EXTERNAL_STORAGE
    }
    return permissions.toTypedArray()
}
