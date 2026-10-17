package com.rad.shifttracker

import android.os.Bundle
import android.util.Log
import android.widget.Toast
import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import com.rad.shifttracker.data.backup.GoogleDriveBackup
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import com.rad.shifttracker.krv.BloodDonationReminderScheduler
import com.rad.shifttracker.uvoz.GmailImportRunner
import com.rad.shifttracker.listici.SIDE_RUN_LOG_TAG
import com.rad.shifttracker.listici.uploadPayslipsToDrive
import com.rad.shifttracker.uvoz.runFinalPayslipCheckOnEntry
import com.rad.shifttracker.uvoz.runSideRunCheckOnEntry
import com.rad.shifttracker.repo.ShiftRepository
import com.rad.shifttracker.ui.CrashLogScreen
import com.rad.shifttracker.ui.RadApp
import com.rad.shifttracker.ui.RadTheme
import com.rad.shifttracker.repo.reschedule

class MainActivity : ComponentActivity() {
    private lateinit var repo: ShiftRepository
    private lateinit var backup: GoogleDriveBackup
    internal companion object {
        /** Jedna provera obračuna u isto vreme, i kad se otvori nova instanca aktivnosti. */
        val advanceCheckRunning = AtomicBoolean(false)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Aplikacija je uvek tamna: providne trake sa svetlim ikonama.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
        )
        super.onCreate(savedInstanceState)

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

        // Do učitanih podataka samo pozadina; čitanje i parsiranje lokalnog fajla ide van glavne niti.
        setContent { RadTheme { Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) } }
        lifecycleScope.launch {
            try {
                val app = application as RadApplication
                val container = withContext(Dispatchers.IO) { app.container() }
                repo = container.shiftRepository
                backup = container.googleDriveBackup
                setContent { RadApp(repo, backup) }
                if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) onDataReady()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("MainActivity", "Inicijalizacija pukla", e)
                setContent {
                    RadTheme {
                        CrashLogScreen(Log.getStackTraceString(e))
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val container = (application as? RadApplication)?.containerOrNull
        if (container?.consumePersistenceFailure() == true) {
            Toast.makeText(
                this,
                "Poslednja izmena nije mogla da se sačuva. Proveri slobodan prostor i pokušaj ponovo.",
                Toast.LENGTH_LONG
            ).show()
        }
        if (::repo.isInitialized) onDataReady()
    }

    private fun onDataReady() {
        BloodDonationReminderScheduler.reschedule(this, repo)
        checkSideRunsOnEntry()
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
                try {
                    uploadPayslipsToDrive(context)
                } catch (e: Exception) {
                    Log.w(SIDE_RUN_LOG_TAG, "slanje lista na Drive puklo", e)
                }
            } finally {
                advanceCheckRunning.set(false)
            }
        }
    }

    override fun onStop() {
        (application as? RadApplication)?.containerOrNull?.flushPendingPersistence()
        super.onStop()
    }
}
