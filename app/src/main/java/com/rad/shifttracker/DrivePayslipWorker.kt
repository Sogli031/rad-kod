package com.rad.shifttracker

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.rad.shifttracker.listici.SIDE_RUN_LOG_TAG
import com.rad.shifttracker.listici.uploadPayslipsToDrive

/**
 * Slanje poslednjih lista na Drive pri ulasku u aplikaciju, kao posao WorkManager-a: završi se i kad
 * se aplikacija odmah zatvori (prekinut posao se sam ponovo pokreće).
 */
class DrivePayslipWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        try {
            uploadPayslipsToDrive(applicationContext)
        } catch (e: Exception) {
            Log.w(SIDE_RUN_LOG_TAG, "slanje lista na Drive puklo", e)
        }
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "rad_drive_payslip_upload"

        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<DrivePayslipWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request)
        }
    }
}
