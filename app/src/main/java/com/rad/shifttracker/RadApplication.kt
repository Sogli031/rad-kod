package com.rad.shifttracker

import android.app.Application
import com.rad.shifttracker.data.backup.BackupScheduler
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

        // Pri svakoj promeni: lokalni save, podsetnici i zakazivanje pozadinskog
        // cloud backup-a (debounce — niz brzih izmena se spoji u jedan backup;
        // worker preskače posao ako korisnik nije prijavljen).
        shiftRepository.onChange {
            shiftRepository.saveToLocal(this@RadApplication)
            FoodReceiptReminderScheduler.reschedule(this@RadApplication, shiftRepository)
            BloodDonationReminderScheduler.reschedule(this@RadApplication, shiftRepository)
            BackupScheduler.scheduleDebounced(this@RadApplication)
        }
        FoodReceiptReminderScheduler.reschedule(this, shiftRepository)
        BloodDonationReminderScheduler.reschedule(this, shiftRepository)
        // Dnevni safety-net backup ako korisnik dugo ne menja podatke.
        BackupScheduler.schedulePeriodic(this)
    }
}
