package com.rad.shifttracker

import android.app.Application
import com.rad.shifttracker.data.backup.BackupScheduler
import com.rad.shifttracker.data.backup.GoogleDriveBackup
import com.rad.shifttracker.data.backup.GoogleDriveBackupProvider
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class RadApplication : Application() {
    @Volatile
    lateinit var shiftRepository: ShiftRepository
        private set

    @Volatile
    lateinit var googleDriveBackup: GoogleDriveBackup
        private set

    @Volatile
    private var persistenceScheduler: RepositoryPersistenceScheduler? = null

    private val persistenceFailure = AtomicBoolean(false)

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
        // Sve ide na jednu pozadinsku nit: pun JSON encode + upis na disk pri
        // svakoj izmeni je predugačak za main nit, a jedna nit garantuje da se
        // dva upisa u isti fajl nikad ne preklapaju. Callback hvata lokalnu
        // referencu, ne property, da nikad ne snimi stanje druge instance.
        val persistence = RepositoryPersistenceScheduler(
            work = {
                val saved = repository.saveToLocal(this@RadApplication)
                persistenceFailure.set(!saved)
                if (!saved) {
                    android.util.Log.e("RadApplication", "Lokalno čuvanje podataka nije uspelo")
                }
                FoodReceiptReminderScheduler.reschedule(this@RadApplication, repository)
                BloodDonationReminderScheduler.reschedule(this@RadApplication, repository)
                BackupScheduler.scheduleDebounced(this@RadApplication)
            },
            onFailure = { error ->
                persistenceFailure.set(true)
                android.util.Log.e("RadApplication", "Pozadinsko čuvanje podataka nije uspelo", error)
            }
        )
        persistenceScheduler = persistence
        repository.onChange {
            persistence.schedule()
        }
        // Objavi tek kad je potpuno učitan i povezan.
        shiftRepository = repository
        FoodReceiptReminderScheduler.reschedule(this, repository)
        BloodDonationReminderScheduler.reschedule(this, repository)
        // Dnevni safety-net backup ako korisnik dugo ne menja podatke.
        BackupScheduler.schedulePeriodic(this)
    }

    fun flushPendingPersistence(): Boolean {
        val flushed = persistenceScheduler?.flush() ?: true
        return flushed && !persistenceFailure.get()
    }

    fun consumePersistenceFailure(): Boolean = persistenceFailure.getAndSet(false)
}

/**
 * Koalescira uzastopne izmene u jedan posao na jednoj pozadinskoj niti.
 * Zastavica se spušta PRE izvršavanja posla, pa izmena koja stigne u toku
 * snimanja zakaže novo snimanje — ništa se ne gubi, a niz brzih izmena
 * (npr. batch popuna meseca) ne pravi po jedan upis na disk za svaku.
 */
internal class RepositoryPersistenceScheduler(
    private val work: () -> Unit,
    private val onFailure: (Throwable) -> Unit = {}
) {
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "rad-persistence")
    }
    private val pending = AtomicBoolean(false)

    fun schedule() {
        if (!pending.compareAndSet(false, true)) return
        executor.execute {
            pending.set(false)
            runWork()
        }
    }

    fun flush(timeoutMillis: Long = FLUSH_TIMEOUT_MILLIS): Boolean {
        return try {
            executor.submit(java.util.concurrent.Callable { runWork() })
                .get(timeoutMillis, TimeUnit.MILLISECONDS)
        } catch (error: Exception) {
            onFailure(error)
            false
        }
    }

    private fun runWork(): Boolean {
        return try {
            work()
            true
        } catch (error: Exception) {
            onFailure(error)
            false
        }
    }

    private companion object {
        const val FLUSH_TIMEOUT_MILLIS = 2_000L
    }
}
