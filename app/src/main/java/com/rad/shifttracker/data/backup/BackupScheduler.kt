package com.rad.shifttracker.data.backup

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkRequest
import java.util.concurrent.TimeUnit

/**
 * Zakazuje pozadinske backup-ove preko WorkManager-a:
 *  - [scheduleDebounced]: posle SVAKE izmene podataka; jedinstven posao sa kratkim
 *    odlaganjem i politikom REPLACE, pa se niz brzih izmena (npr. popuna meseca)
 *    spoji u jedan backup, a pojedinačna izmena stigne u oblak za par sekundi.
 *    Broj verzija na Drive-u drži [BackupRetentionPolicy].
 *  - [schedulePeriodic]: dnevni „safety-net" ako korisnik dugo ne menja ništa.
 *
 * Oba traže mrežu i koriste eksponencijalni backoff pri neuspehu. Worker sam
 * preskače posao ako korisnik nije prijavljen.
 */
object BackupScheduler {

    private const val DEBOUNCED_WORK = "rad_cloud_backup_debounced"
    private const val PERIODIC_WORK = "rad_cloud_backup_periodic"
    private const val DEBOUNCE_DELAY_SECONDS = 5L
    private const val PERIODIC_INTERVAL_HOURS = 24L

    private fun networkConstraints(): Constraints =
        Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

    fun scheduleDebounced(context: Context) {
        val request = OneTimeWorkRequestBuilder<BackupWorker>()
            .setInitialDelay(DEBOUNCE_DELAY_SECONDS, TimeUnit.SECONDS)
            .setConstraints(networkConstraints())
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                WorkRequest.MIN_BACKOFF_MILLIS,
                TimeUnit.MILLISECONDS
            )
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(DEBOUNCED_WORK, ExistingWorkPolicy.REPLACE, request)
    }

    fun schedulePeriodic(context: Context) {
        val request = PeriodicWorkRequestBuilder<BackupWorker>(PERIODIC_INTERVAL_HOURS, TimeUnit.HOURS)
            .setConstraints(networkConstraints())
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                WorkRequest.MIN_BACKOFF_MILLIS,
                TimeUnit.MILLISECONDS
            )
            .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(PERIODIC_WORK, ExistingPeriodicWorkPolicy.KEEP, request)
    }
}
