package com.rad.shifttracker

import android.app.Application
import android.util.Log
import com.rad.shifttracker.data.backup.BackupScheduler
import com.rad.shifttracker.data.backup.GoogleDriveBackup
import com.rad.shifttracker.data.backup.GoogleDriveBackupProvider
import com.rad.shifttracker.krv.BloodDonationReminderScheduler
import com.rad.shifttracker.repo.ShiftRepository
import com.rad.shifttracker.repo.loadFromLocal
import com.rad.shifttracker.repo.reschedule
import com.rad.shifttracker.repo.saveToLocal
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Sve zavisnosti aplikacije na jednom mestu: repozitorijum (učitan iz lokalnog fajla), Google Drive
 * backup i pozadinsko snimanje posle svake izmene. Pravi ga samo [RadApplication.container], jednom
 * po procesu, pa aktivnost, worker-i i receiver dele isti repozitorijum.
 */
class AppContainer internal constructor(private val appContext: Application) {

    val shiftRepository: ShiftRepository = ShiftRepository()

    val googleDriveBackup: GoogleDriveBackup = GoogleDriveBackupProvider.get(appContext)

    private val persistenceFailure = AtomicBoolean(false)

    // Pri svakoj promeni: lokalni save, podsetnici i zakazivanje pozadinskog cloud backup-a
    // (debounce — niz brzih izmena se spoji u jedan backup; worker preskače posao ako korisnik
    // nije prijavljen). Sve ide na jednu pozadinsku nit: pun JSON encode + upis na disk pri svakoj
    // izmeni je predugačak za main nit, a jedna nit garantuje da se dva upisa u isti fajl nikad
    // ne preklapaju.
    private val persistence = RepositoryPersistenceScheduler(
        work = ::persist,
        onFailure = { error ->
            persistenceFailure.set(true)
            Log.e(TAG, "Pozadinsko čuvanje podataka nije uspelo", error)
        }
    )

    init {
        // Učitaj iz lokalne memorije ODMAH (sinhrono, brzo); container se objavljuje tek posle.
        shiftRepository.loadFromLocal(appContext)
        shiftRepository.onChange { persistence.schedule() }
        BloodDonationReminderScheduler.reschedule(appContext, shiftRepository)
        // Dnevni safety-net backup ako korisnik dugo ne menja podatke.
        BackupScheduler.schedulePeriodic(appContext)
        // Akontacija, prevoz i regres se traže i bez otvaranja aplikacije.
        SideRunWorker.schedulePeriodic(appContext)
    }

    private fun persist() {
        val saved = shiftRepository.saveToLocal(appContext)
        persistenceFailure.set(!saved)
        if (!saved) {
            Log.e(TAG, "Lokalno čuvanje podataka nije uspelo")
            persistence.markDirty()
        }
        BloodDonationReminderScheduler.reschedule(appContext, shiftRepository)
        BackupScheduler.scheduleDebounced(appContext)
    }

    fun flushPendingPersistence(): Boolean = persistence.flush() && !persistenceFailure.get()

    fun consumePersistenceFailure(): Boolean = persistenceFailure.getAndSet(false)

    private companion object {
        const val TAG = "AppContainer"
    }
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

    /** Ima izmena koje još nisu snimljene (ili je poslednje snimanje pokvareno). */
    private val dirty = AtomicBoolean(false)

    fun schedule() {
        dirty.set(true)
        if (!pending.compareAndSet(false, true)) return
        executor.execute {
            pending.set(false)
            runWork()
        }
    }

    /** Označi da stanje nije snimljeno, pa sledeći [flush] ponovo pokušava. */
    fun markDirty() {
        dirty.set(true)
    }

    /**
     * Čeka da se snimi ono što je na čekanju. Kad nema nesnimljenih izmena ne radi ništa —
     * inače bi svako gašenje ekrana ponovo snimalo i zakazivalo cloud backup bez ikakve izmene.
     */
    fun flush(timeoutMillis: Long = FLUSH_TIMEOUT_MILLIS): Boolean {
        return try {
            executor.submit(Callable { if (dirty.get()) runWork() else true })
                .get(timeoutMillis, TimeUnit.MILLISECONDS)
        } catch (error: Exception) {
            onFailure(error)
            false
        }
    }

    private fun runWork(): Boolean {
        // Spušta se PRE posla: izmena koja stigne u toku snimanja ostaje označena.
        dirty.set(false)
        return try {
            work()
            true
        } catch (error: Exception) {
            dirty.set(true)
            onFailure(error)
            false
        }
    }

    private companion object {
        const val FLUSH_TIMEOUT_MILLIS = 2_000L
    }
}
