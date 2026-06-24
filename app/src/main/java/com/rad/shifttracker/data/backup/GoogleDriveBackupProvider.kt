package com.rad.shifttracker.data.backup

import android.annotation.SuppressLint
import android.app.Application

object GoogleDriveBackupProvider {
    @SuppressLint("StaticFieldLeak")
    @Volatile
    private var instance: GoogleDriveBackup? = null

    fun get(app: Application): GoogleDriveBackup {
        return instance ?: synchronized(this) {
            instance ?: GoogleDriveBackup(app.applicationContext).also {
                instance = it
            }
        }
    }
}
