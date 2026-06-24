package com.rad.shifttracker.data.backup

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.googleapis.extensions.android.gms.auth.UserRecoverableAuthIOException
import com.google.api.client.http.ByteArrayContent
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream

private const val TAG = "GoogleDriveBackup"
private const val BACKUP_FILE_NAME = "rad_backup.json"
private const val APP_DATA_FOLDER = "appDataFolder"
private const val PREFS_NAME = "rad_backup_prefs"
private const val KEY_FIRST_LAUNCH_DONE = "first_launch_done"
private const val KEY_LAST_SYNC_TIME = "last_sync_time"
private const val NETWORK_TIMEOUT_MS = 30_000L

class GoogleDriveBackup(private val context: Context) {

    private val _signInState = MutableStateFlow<SignInState>(SignInState.SignedOut)
    val signInState: StateFlow<SignInState> = _signInState

    private val _syncState = MutableStateFlow<SyncState>(SyncState.Idle)
    val syncState: StateFlow<SyncState> = _syncState

    private val _lastSyncTime = MutableStateFlow<Long?>(null)
    val lastSyncTime: StateFlow<Long?> = _lastSyncTime

    private var driveService: Drive? = null

    // Mutex to prevent concurrent backup/restore operations
    private val syncMutex = Mutex()

    private val prefs by lazy { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }

    private val googleSignInClient: GoogleSignInClient by lazy {
        val signInOptions = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(Scope(DriveScopes.DRIVE_APPDATA))
            .build()
        GoogleSignIn.getClient(context, signInOptions)
    }

    init {
        // Load last sync time from prefs
        val savedTime = prefs.getLong(KEY_LAST_SYNC_TIME, 0L)
        if (savedTime > 0) {
            _lastSyncTime.value = savedTime
        }

        // Check if already signed in
        val account = GoogleSignIn.getLastSignedInAccount(context)
        if (account != null && GoogleSignIn.hasPermissions(account, Scope(DriveScopes.DRIVE_APPDATA))) {
            _signInState.value = SignInState.SignedIn(account.email ?: "Nepoznat nalog")
            initDriveService(account)
        }
    }

    fun getSignInIntent(): Intent = googleSignInClient.signInIntent

    fun handleSignInResult(account: GoogleSignInAccount?) {
        if (account != null) {
            _signInState.value = SignInState.SignedIn(account.email ?: "Nepoznat nalog")
            initDriveService(account)
            Log.d(TAG, "Signed in successfully")
        } else {
            _signInState.value = SignInState.SignedOut
            Log.e(TAG, "Sign in failed")
        }
    }

    /**
     * Check if the device has network connectivity.
     */
    private fun isNetworkAvailable(): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /**
     * Refresh the Google Account credential if needed.
     * Returns the refreshed Drive service or null if refresh failed.
     */
    private fun refreshCredentialsIfNeeded(): Drive? {
        val account = GoogleSignIn.getLastSignedInAccount(context)
        if (account == null || !GoogleSignIn.hasPermissions(account, Scope(DriveScopes.DRIVE_APPDATA))) {
            _signInState.value = SignInState.SignedOut
            driveService = null
            return null
        }
        // Re-initialize drive service with fresh credentials
        initDriveService(account)
        return driveService
    }

    /**
     * Proveri da li je credential validan i reinicijalizuj ako je potrebno.
     * Vraća validan Drive servis ili null ako korisnik nije prijavljen.
     */
    private fun ensureValidDriveService(): Drive? {
        val currentDrive = driveService ?: return refreshCredentialsIfNeeded()

        // Proveri da li je nalog još uvek prijavljen sa odgovarajućim dozvolama
        val account = GoogleSignIn.getLastSignedInAccount(context)
        if (account == null || !GoogleSignIn.hasPermissions(account, Scope(DriveScopes.DRIVE_APPDATA))) {
            _signInState.value = SignInState.SignedOut
            driveService = null
            return null
        }

        return currentDrive
    }

    suspend fun signOut() {
        // Wait for any ongoing sync operations to complete
        syncMutex.withLock {
            withContext(Dispatchers.IO) {
                try {
                    googleSignInClient.signOut()
                    _signInState.value = SignInState.SignedOut
                    driveService = null
                    Log.d(TAG, "Signed out")
                } catch (e: Exception) {
                    Log.e(TAG, "Sign out failed", e)
                }
            }
        }
    }

    private fun initDriveService(account: GoogleSignInAccount) {
        val credential = GoogleAccountCredential.usingOAuth2(
            context,
            listOf(DriveScopes.DRIVE_APPDATA)
        )
        credential.selectedAccount = account.account

        driveService = Drive.Builder(
            NetHttpTransport(),
            GsonFactory.getDefaultInstance(),
            credential
        )
            .setApplicationName("Rad")
            .build()
    }

    suspend fun backup(jsonData: String): String? {
        // Check network availability first
        if (!isNetworkAvailable()) {
            val error = "Nema internet konekcije"
            _syncState.value = SyncState.Error(error)
            return error
        }

        // Use mutex to prevent concurrent backup/restore operations
        return syncMutex.withLock {
            val drive = ensureValidDriveService()
            if (drive == null) {
                val error = "Niste prijavljeni"
                _syncState.value = SyncState.Error(error)
                return@withLock error
            }

            withContext(Dispatchers.IO) {
                try {
                    _syncState.value = SyncState.Syncing

                    // Wrap network operations with timeout
                    val result = withTimeoutOrNull(NETWORK_TIMEOUT_MS) {
                        // Check if backup file exists
                        val existingFileId = findBackupFile(drive)

                        val contentStream = ByteArrayContent.fromString("application/json", jsonData)

                        if (existingFileId != null) {
                            // Update existing file
                            drive.files().update(existingFileId, null, contentStream).execute()
                            Log.d(TAG, "Backup updated")
                        } else {
                            // Create new file
                            val fileMetadata = com.google.api.services.drive.model.File().apply {
                                name = BACKUP_FILE_NAME
                                parents = listOf(APP_DATA_FOLDER)
                            }
                            drive.files().create(fileMetadata, contentStream).execute()
                            Log.d(TAG, "Backup created")
                        }
                        true
                    }

                    if (result == null) {
                        val error = "Timeout - spora mreža"
                        _syncState.value = SyncState.Error(error)
                        return@withContext error
                    }

                    val now = System.currentTimeMillis()
                    _lastSyncTime.value = now
                    prefs.edit().putLong(KEY_LAST_SYNC_TIME, now).apply()
                    _syncState.value = SyncState.Success
                    null // null = success
                } catch (e: UserRecoverableAuthIOException) {
                    // Token expired or revoked - try to refresh
                    Log.w(TAG, "Auth error, attempting credential refresh")
                    val refreshedDrive = refreshCredentialsIfNeeded()
                    if (refreshedDrive == null) {
                        val error = "Sesija istekla - prijavite se ponovo"
                        _syncState.value = SyncState.Error(error)
                        return@withContext error
                    }
                    // Retry backup with refreshed credentials
                    retryBackup(refreshedDrive, jsonData)
                } catch (e: Exception) {
                    Log.e(TAG, "Backup failed: ${e.javaClass.simpleName}", e)
                    val error = "Greska: ${e.message ?: e.javaClass.simpleName}"
                    _syncState.value = SyncState.Error(error)
                    error
                }
            }
        }
    }

    private suspend fun retryBackup(drive: Drive, jsonData: String): String? {
        return try {
            val result = withTimeoutOrNull(NETWORK_TIMEOUT_MS) {
                val existingFileId = findBackupFile(drive)
                val contentStream = ByteArrayContent.fromString("application/json", jsonData)

                if (existingFileId != null) {
                    drive.files().update(existingFileId, null, contentStream).execute()
                } else {
                    val fileMetadata = com.google.api.services.drive.model.File().apply {
                        name = BACKUP_FILE_NAME
                        parents = listOf(APP_DATA_FOLDER)
                    }
                    drive.files().create(fileMetadata, contentStream).execute()
                }
                true
            }

            if (result == null) {
                val error = "Timeout - spora mreža"
                _syncState.value = SyncState.Error(error)
                return error
            }

            val now = System.currentTimeMillis()
            _lastSyncTime.value = now
            prefs.edit().putLong(KEY_LAST_SYNC_TIME, now).apply()
            _syncState.value = SyncState.Success
            Log.d(TAG, "Backup retry successful")
            null
        } catch (e: Exception) {
            Log.e(TAG, "Backup retry failed", e)
            val error = "Greska: ${e.message ?: e.javaClass.simpleName}"
            _syncState.value = SyncState.Error(error)
            error
        }
    }

    suspend fun restore(): String? {
        // Check network availability first
        if (!isNetworkAvailable()) {
            _syncState.value = SyncState.Error("Nema internet konekcije")
            return null
        }

        // Use mutex to prevent concurrent backup/restore operations
        return syncMutex.withLock {
            val drive = ensureValidDriveService() ?: return@withLock null

            withContext(Dispatchers.IO) {
                try {
                    _syncState.value = SyncState.Syncing
                    val outcome = runRestoreAttempt(drive)
                    handleRestoreOutcome(outcome)
                } catch (e: UserRecoverableAuthIOException) {
                    // Token expired or revoked - try to refresh
                    Log.w(TAG, "Auth error during restore, attempting credential refresh")
                    val refreshedDrive = refreshCredentialsIfNeeded()
                    if (refreshedDrive == null) {
                        _syncState.value = SyncState.Error("Sesija istekla - prijavite se ponovo")
                        return@withContext null
                    }
                    // Retry restore with refreshed credentials
                    retryRestore(refreshedDrive)
                } catch (e: Exception) {
                    Log.e(TAG, "Restore failed", e)
                    _syncState.value = SyncState.Error("Greska: ${e.message ?: e.javaClass.simpleName}")
                    null
                }
            }
        }
    }

    private suspend fun retryRestore(drive: Drive): String? {
        return try {
            val outcome = runRestoreAttempt(drive)
            val data = handleRestoreOutcome(outcome)
            if (outcome is RestoreOutcome.Found) {
                Log.d(TAG, "Restore retry successful")
            }
            data
        } catch (e: Exception) {
            Log.e(TAG, "Restore retry failed", e)
            _syncState.value = SyncState.Error("Greska: ${e.message ?: e.javaClass.simpleName}")
            null
        }
    }

    private sealed class RestoreOutcome {
        data object Timeout : RestoreOutcome()
        data object NoBackup : RestoreOutcome()
        data class Found(val jsonData: String) : RestoreOutcome()
    }

    private suspend fun runRestoreAttempt(drive: Drive): RestoreOutcome {
        val result: RestoreOutcome? = withTimeoutOrNull(NETWORK_TIMEOUT_MS) {
            val fileId = findBackupFile(drive)
            if (fileId == null) {
                RestoreOutcome.NoBackup
            } else {
                val outputStream = ByteArrayOutputStream()
                drive.files().get(fileId).executeMediaAndDownloadTo(outputStream)
                RestoreOutcome.Found(outputStream.toString("UTF-8"))
            }
        }
        return result ?: RestoreOutcome.Timeout
    }

    private fun handleRestoreOutcome(outcome: RestoreOutcome): String? = when (outcome) {
        is RestoreOutcome.Timeout -> {
            _syncState.value = SyncState.Error("Timeout - spora mreža")
            null
        }
        is RestoreOutcome.NoBackup -> {
            _syncState.value = SyncState.Idle
            Log.d(TAG, "No backup found")
            null
        }
        is RestoreOutcome.Found -> {
            _syncState.value = SyncState.Success
            Log.d(TAG, "Restore completed")
            outcome.jsonData
        }
    }

    private fun findBackupFile(drive: Drive): String? = BackupFileLookup(drive).findBackupFileId()

    fun isSignedIn(): Boolean = _signInState.value is SignInState.SignedIn

    fun isFirstLaunch(): Boolean {
        return !prefs.getBoolean(KEY_FIRST_LAUNCH_DONE, false)
    }

    fun markFirstLaunchDone() {
        prefs.edit().putBoolean(KEY_FIRST_LAUNCH_DONE, true).apply()
    }
}

sealed class SignInState {
    data object SignedOut : SignInState()
    data class SignedIn(val email: String) : SignInState()
}

sealed class SyncState {
    data object Idle : SyncState()
    data object Syncing : SyncState()
    data object Success : SyncState()
    data class Error(val message: String) : SyncState()
}

internal class BackupFileLookup(private val drive: Drive) {
    fun findBackupFileId(): String? {
        val result = drive.files().list()
            .setSpaces(APP_DATA_FOLDER)
            .setQ("name = '$BACKUP_FILE_NAME' and trashed = false")
            .setOrderBy("modifiedTime desc")
            .setFields("files(id, name, modifiedTime)")
            .execute()

        val files = result.files.orEmpty()
        files.drop(1).forEach { duplicate ->
            duplicate.id?.let { id ->
                try {
                    drive.files().delete(id).execute()
                } catch (e: Exception) {
                    Log.w(TAG, "Neuspešno brisanje starog backup fajla: $id", e)
                }
            }
        }

        return files.firstOrNull()?.id
    }
}
