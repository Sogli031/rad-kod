package com.rad.shifttracker

import android.app.Application
import com.rad.shifttracker.data.backup.BackupScheduler
import com.rad.shifttracker.data.backup.GoogleDriveBackup
import com.rad.shifttracker.data.backup.GoogleDriveBackupProvider

class RadApplication : Application() {
    @Volatile
    lateinit var shiftRepository: ShiftRepository
        private set

    @Volatile
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
     * Poziva se iz MainActivity POSLE provere crash loga, ali i iz
     * BackupWorker-a (WorkManager nit) — zato mora biti sinhronizovano,
     * inače dva poziva mogu da naprave dva repozitorijuma pa autosave
     * pregazi fajl sa starijim stanjem.
     */
    @Synchronized
    fun initializeServices() {
        if (::shiftRepository.isInitialized) return

        // Inicijalizuj repozitorijum (podaci samo u memoriji)
        val repository = ShiftRepository()

        // Inicijalizuj Google Drive backup
        googleDriveBackup = GoogleDriveBackupProvider.get(this)

        // Učitaj iz lokalne memorije ODMAH (sinhrono, brzo)
        repository.loadFromLocal(this)

        // Pri svakoj promeni: lokalni save, podsetnici i zakazivanje pozadinskog
        // cloud backup-a (debounce — niz brzih izmena se spoji u jedan backup;
        // worker preskače posao ako korisnik nije prijavljen).
        // Callback hvata lokalnu referencu, ne property, da nikad ne snimi
        // stanje neke druge instance.
        repository.onChange {
            repository.saveToLocal(this@RadApplication)
            FoodReceiptReminderScheduler.reschedule(this@RadApplication, repository)
            BloodDonationReminderScheduler.reschedule(this@RadApplication, repository)
            BackupScheduler.scheduleDebounced(this@RadApplication)
        }
        // Objavi tek kad je potpuno učitan i povezan.
        shiftRepository = repository
        FoodReceiptReminderScheduler.reschedule(this, repository)
        BloodDonationReminderScheduler.reschedule(this, repository)
        // Dnevni safety-net backup ako korisnik dugo ne menja podatke.
        BackupScheduler.schedulePeriodic(this)
    }
}
