package com.rad.shifttracker

import android.app.Application
import com.rad.shifttracker.data.backup.GoogleDriveBackup
import com.rad.shifttracker.data.backup.GoogleDriveBackupProvider

class RadApplication : Application() {
    lateinit var shiftRepository: ShiftRepository
        private set

    lateinit var googleDriveBackup: GoogleDriveBackup
        private set

    val shiftRepositoryOrNull: ShiftRepository?
        get() = if (::shiftRepository.isInitialized) shiftRepository else null

    companion object {
        const val CRASH_LOG_FILE = CrashLogStorage.CRASH_LOG_FILE
    }

    override fun onCreate() {
        super.onCreate()
        CrashLogStorage.install(this, appName = "Rad")
        deleteSharedPreferences("gps_shift_state")
    }

    /**
     * Inicijalizuje repozitorijum i backup servis.
     * Poziva se iz MainActivity POSLE provere crash loga.
     */
    fun initializeServices() {
        if (::shiftRepository.isInitialized) return

        // Inicijalizuj repozitorijum (podaci samo u memoriji)
        shiftRepository = ShiftRepository()

        // Inicijalizuj Google Drive backup
        googleDriveBackup = GoogleDriveBackupProvider.get(this)

        // Učitaj iz lokalne memorije ODMAH (sinhrono, brzo)
        shiftRepository.loadFromLocal(this)

        // Auto-save SAMO lokalno pri svakoj promeni podataka
        // Cloud backup se radi isključivo ručno iz Settings ekrana
        shiftRepository.onChange {
            shiftRepository.saveToLocal(this@RadApplication)
            FoodReceiptReminderScheduler.reschedule(this@RadApplication, shiftRepository)
            BloodDonationReminderScheduler.reschedule(this@RadApplication, shiftRepository)
        }
        FoodReceiptReminderScheduler.reschedule(this, shiftRepository)
        BloodDonationReminderScheduler.reschedule(this, shiftRepository)
    }
}
