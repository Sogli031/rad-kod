package com.rad.shifttracker.data.backup

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.rad.shifttracker.BuildConfig
import com.rad.shifttracker.repo.buildCloudBackup
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

        // U pozadinskom procesu container možda još ne postoji (MainActivity nije
        // pokrenut); container() ga napravi jednom i učita lokalno stanje.
        val container = app.container()
        val repo = container.shiftRepository
        val backup = container.googleDriveBackup

        // Dok traje prvo pokretanje stanje je polu-prazno (podešavanja se tek unose);
        // backup tada bi u oblak upisao verziju sa praznim globalnim podacima.
        if (backup.isFirstLaunch()) {
            Log.d(TAG, "Preskačem auto-backup: prvo pokretanje nije završeno")
            return Result.success()
        }

        if (!backup.isSignedIn()) {
            Log.d(TAG, "Preskačem auto-backup: korisnik nije prijavljen")
            return Result.success()
        }

        // Nova instalacija ne sme sama da pošalje svoje (sveže) stanje preko
        // postojećeg backup-a — prvo korisnik bira Restore ili „zadrži lokalno".
        when (backup.autoBackupGate()) {
            AutoBackupGate.ALLOWED -> Unit
            AutoBackupGate.NEEDS_USER_DECISION -> {
                Log.d(TAG, "Preskačem auto-backup: oblak ima backup koji ovaj uređaj nije preuzeo")
                return Result.success()
            }
            AutoBackupGate.UNKNOWN -> return Result.retry()
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
