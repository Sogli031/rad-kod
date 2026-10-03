package com.rad.shifttracker

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Provera posebnih obračuna (akontacija, prevoz, regres) i konačne liste u pozadini, bez ulaska u
 * aplikaciju. Odluku da li ima šta da se traži donose iste provere kao pri ulasku
 * ([runSideRunCheckOnEntry], [runFinalPayslipCheckOnEntry]), pa se mreža ne troši bez potrebe.
 */
class SideRunWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as? RadApplication ?: return Result.success()
        app.initializeServices()
        val repo = app.shiftRepositoryOrNull ?: return Result.success()
        // Dok traje prvo pokretanje (uvoz iz Gmail-a) stanje je polu-prazno.
        if (app.googleDriveBackup.isFirstLaunch() || GmailImportRunner.isRunning) return Result.success()
        // Ako je aplikacija otvorena i već proverava, ne udvostručava se.
        if (!MainActivity.advanceCheckRunning.compareAndSet(false, true)) return Result.success()
        try {
            try {
                runSideRunCheckOnEntry(applicationContext, repo)
            } catch (e: Exception) {
                Log.w(SIDE_RUN_LOG_TAG, "pozadinska provera pukla", e)
            }
            try {
                runFinalPayslipCheckOnEntry(applicationContext, repo)
            } catch (e: Exception) {
                Log.w(SIDE_RUN_LOG_TAG, "pozadinska provera konačne liste pukla", e)
            }
        } finally {
            MainActivity.advanceCheckRunning.set(false)
        }
        return Result.success()
    }

    companion object {
        private const val PERIODIC_WORK = "rad_side_run_check_periodic"
        private const val INTERVAL_HOURS = 6L

        /** Na svakih ~6 sati uz mrežu; dnevno ograničenje i potvrde drže provere u [runSideRunCheckOnEntry]. */
        fun schedulePeriodic(context: Context) {
            val request = PeriodicWorkRequestBuilder<SideRunWorker>(INTERVAL_HOURS, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(PERIODIC_WORK, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
