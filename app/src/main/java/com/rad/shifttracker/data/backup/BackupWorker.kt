package com.rad.shifttracker.data.backup

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.rad.shifttracker.BuildConfig
import com.rad.shifttracker.RadApplication

/**
 * Pozadinski posao koji pravi verzionisani cloud backup. Pokreće ga
 * [BackupScheduler] (debounce na izmenu + dnevni safety-net) uz uslov da ima
 * mreže. Sav rad oko verzija, anomalija i retencije je u [GoogleDriveBackup];
 * worker samo obezbedi učitan repozitorijum i pozove backup ako je korisnik
 * prijavljen.
 */
class BackupWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as? RadApplication ?: return Result.success()

        // U pozadinskom procesu repo možda nije inicijalizovan (MainActivity nije
        // pokrenut); initializeServices je idempotentan i učita lokalno stanje.
        app.initializeServices()
        val repo = app.shiftRepositoryOrNull ?: return Result.success()
        val backup = app.googleDriveBackup

        if (!backup.isSignedIn()) {
            Log.d(TAG, "Preskačem auto-backup: korisnik nije prijavljen")
            return Result.success()
        }

        val outcome = backup.backupVersioned(appVersion = BuildConfig.VERSION_NAME) { existing ->
            repo.buildCloudBackup(existing)
        }
        return when (outcome) {
            is BackupOutcome.Success -> {
                Log.d(TAG, "Auto-backup uspešan (flagged=${outcome.flagged})")
                Result.success()
            }
            is BackupOutcome.Failure -> {
                Log.w(TAG, "Auto-backup neuspešan: ${outcome.message}")
                Result.retry()
            }
        }
    }

    companion object {
        private const val TAG = "BackupWorker"
    }
}
