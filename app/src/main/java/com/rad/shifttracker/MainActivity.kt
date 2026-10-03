package com.rad.shifttracker

import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.rad.shifttracker.data.backup.GoogleDriveBackup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : ComponentActivity() {
    private lateinit var repo: ShiftRepository
    private lateinit var backup: GoogleDriveBackup
    internal companion object {
        /** Jedna provera obračuna u isto vreme, i kad se otvori nova instanca aktivnosti. */
        val advanceCheckRunning = AtomicBoolean(false)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        @Suppress("DEPRECATION")
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        @Suppress("DEPRECATION")
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }

        // Proveri da li postoji crash log od prethodnog pokretanja
        val crashFile = File(filesDir, RadApplication.CRASH_LOG_FILE)
        if (crashFile.exists()) {
            val crashLog = crashFile.readText()
            crashFile.delete()
            setContent {
                RadTheme {
                    CrashLogScreen(crashLog)
                }
            }
            return
        }

        // Normalna inicijalizacija wrappovana u try-catch
        try {
            initializeApp()
        } catch (e: Exception) {
            Log.e("MainActivity", "Inicijalizacija pukla", e)
            setContent {
                RadTheme {
                    CrashLogScreen(Log.getStackTraceString(e))
                }
            }
        }
    }

    private fun initializeApp() {
        val app = application as RadApplication
        app.initializeServices()
        repo = app.shiftRepository
        backup = app.googleDriveBackup

        setContent {
            RadApp(repo, backup)
        }
    }

    override fun onResume() {
        super.onResume()
        val app = application as? RadApplication
        if (app?.consumePersistenceFailure() == true) {
            Toast.makeText(
                this,
                "Poslednja izmena nije mogla da se sačuva. Proveri slobodan prostor i pokušaj ponovo.",
                Toast.LENGTH_LONG
            ).show()
        }
        if (::repo.isInitialized) {
            BloodDonationReminderScheduler.reschedule(this, repo)
            checkSideRunsOnEntry()
        }
    }

    private fun checkSideRunsOnEntry() {
        // Uvoz iz Gmail-a (onboarding, „Proveri sve po listama“) već upisuje isto; provera čeka sledeći ulazak.
        if (GmailImportRunner.isRunning) return
        if (!advanceCheckRunning.compareAndSet(false, true)) return
        val context = applicationContext
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                try {
                    runSideRunCheckOnEntry(context, repo)
                } catch (e: Exception) {
                    Log.w(SIDE_RUN_LOG_TAG, "provera pukla", e)
                }
                try {
                    runFinalPayslipCheckOnEntry(context, repo)
                } catch (e: Exception) {
                    Log.w(SIDE_RUN_LOG_TAG, "provera konačne liste pukla", e)
                }
            } finally {
                advanceCheckRunning.set(false)
            }
        }
    }

    override fun onStop() {
        if (::repo.isInitialized) {
            (application as? RadApplication)?.flushPendingPersistence()
        }
        super.onStop()
    }
}
