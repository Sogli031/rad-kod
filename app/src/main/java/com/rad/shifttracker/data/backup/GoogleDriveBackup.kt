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

    fun isSignedIn(): Boolean = _signInState.value is SignInState.SignedIn

    fun isFirstLaunch(): Boolean {
        return !prefs.getBoolean(KEY_FIRST_LAUNCH_DONE, false)
    }

    fun markFirstLaunchDone() {
        prefs.edit().putBoolean(KEY_FIRST_LAUNCH_DONE, true).apply()
    }

    // ---------------------------------------------------------------------------
    // Verzionisani backup: svaka promena ide u NOV fajl rad_backup_<ts>.json sa
    // metapodacima u appProperties, uz detekciju anomalija i deda-otac-sin
    // retenciju. Tako se stare verzije čuvaju i nikad se dobra kopija ne pregazi.
    // ---------------------------------------------------------------------------

    /** Lista svih verzija u oblaku (najnovija prva), bez skidanja sadržaja. */
    suspend fun listVersions(): List<BackupVersion> {
        if (!isNetworkAvailable()) return emptyList()
        return syncMutex.withLock {
            val drive = ensureValidDriveService() ?: return@withLock emptyList()
            withContext(Dispatchers.IO) {
                try {
                    withTimeoutOrNull(NETWORK_TIMEOUT_MS) { listVersionsInternal(drive) }.orEmpty()
                } catch (e: UserRecoverableAuthIOException) {
                    val refreshed = refreshCredentialsIfNeeded() ?: return@withContext emptyList()
                    withTimeoutOrNull(NETWORK_TIMEOUT_MS) { listVersionsInternal(refreshed) }.orEmpty()
                } catch (e: Exception) {
                    Log.e(TAG, "List versions failed: ${e.javaClass.simpleName}", e)
                    emptyList()
                }
            }
        }
    }

    /**
     * Napravi novu verziju backup-a. [build] dobija sadržaj prethodne (najsvežije)
     * arhive iz oblaka i vraća sadržaj + otisak koji treba upisati. Ako prethodna
     * verzija postoji ali se ne može pročitati, operacija se PREKIDA bez upisa —
     * da se puna istorija ne pregazi orezanim podacima.
     */
    suspend fun backupVersioned(
        appVersion: String?,
        build: (existingJson: String?) -> CloudBackupContent
    ): BackupOutcome {
        if (!isNetworkAvailable()) {
            _syncState.value = SyncState.Error("Nema internet konekcije")
            return BackupOutcome.Failure("Nema internet konekcije")
        }
        return syncMutex.withLock {
            val drive = ensureValidDriveService()
            if (drive == null) {
                _syncState.value = SyncState.Error("Niste prijavljeni")
                return@withLock BackupOutcome.Failure("Niste prijavljeni")
            }
            withContext(Dispatchers.IO) {
                try {
                    _syncState.value = SyncState.Syncing
                    runVersionedBackup(drive, appVersion, build)
                } catch (e: UserRecoverableAuthIOException) {
                    Log.w(TAG, "Auth error during versioned backup, refreshing")
                    val refreshed = refreshCredentialsIfNeeded()
                    if (refreshed == null) {
                        _syncState.value = SyncState.Error("Sesija istekla - prijavite se ponovo")
                        return@withContext BackupOutcome.Failure("Sesija istekla - prijavite se ponovo")
                    }
                    runVersionedBackup(refreshed, appVersion, build)
                } catch (e: Exception) {
                    Log.e(TAG, "Versioned backup failed: ${e.javaClass.simpleName}", e)
                    val error = "Greska: ${e.message ?: e.javaClass.simpleName}"
                    _syncState.value = SyncState.Error(error)
                    BackupOutcome.Failure(error)
                }
            }
        }
    }

    private sealed class VersionedRunResult {
        data object ArchiveUnreadable : VersionedRunResult()
        data class Done(val flagged: Boolean, val reasons: List<String>) : VersionedRunResult()
    }

    private suspend fun runVersionedBackup(
        drive: Drive,
        appVersion: String?,
        build: (existingJson: String?) -> CloudBackupContent
    ): BackupOutcome {
        val nowMillis = System.currentTimeMillis()
        val result: VersionedRunResult? = withTimeoutOrNull(NETWORK_TIMEOUT_MS) {
            val versions = listVersionsInternal(drive)
            val latest = VersionedBackupPlanner.latest(versions)
            val existingJson = latest?.let { downloadContent(drive, it.fileId) }
            if (latest != null && existingJson == null) {
                return@withTimeoutOrNull VersionedRunResult.ArchiveUnreadable
            }

            val content = build(existingJson)
            val baseline = VersionedBackupPlanner.baselineStats(versions)
            val verdict = BackupAnomalyGuard.assess(baseline, content.stats)
            val flagged = verdict.isSuspicious

            val props = BackupMetadata.toProperties(nowMillis, content.stats, flagged, appVersion)
            val newId = createVersionFile(drive, nowMillis, content.json, props)
            val newVersion = BackupMetadata.buildVersion(
                fileId = newId,
                fileName = BackupMetadata.fileName(nowMillis),
                driveCreatedAtMillis = nowMillis,
                props = props
            )
            pruneOldVersions(drive, versions + newVersion)

            val reasons = (verdict as? BackupAnomalyGuard.Verdict.Suspicious)?.reasons.orEmpty()
            VersionedRunResult.Done(flagged, reasons)
        }

        return when (result) {
            null -> {
                _syncState.value = SyncState.Error("Timeout - spora mreža")
                BackupOutcome.Failure("Timeout - spora mreža")
            }
            VersionedRunResult.ArchiveUnreadable -> {
                val error = "Ne mogu da pročitam postojeći backup — prekinuto radi zaštite"
                _syncState.value = SyncState.Error(error)
                BackupOutcome.Failure(error)
            }
            is VersionedRunResult.Done -> {
                _lastSyncTime.value = nowMillis
                prefs.edit().putLong(KEY_LAST_SYNC_TIME, nowMillis).apply()
                _syncState.value = SyncState.Success
                Log.d(TAG, "Versioned backup created (flagged=${result.flagged})")
                BackupOutcome.Success(result.flagged, result.reasons)
            }
        }
    }

    /** Vrati sadržaj najnovije verzije (za standardni „Restore"). */
    suspend fun restoreLatest(): String? {
        val versions = listVersions()
        val latest = VersionedBackupPlanner.latest(versions) ?: return null
        return restoreVersion(latest.fileId)
    }

    /** Vrati sadržaj konkretne verzije po Drive fileId. */
    suspend fun restoreVersion(fileId: String): String? {
        if (!isNetworkAvailable()) {
            _syncState.value = SyncState.Error("Nema internet konekcije")
            return null
        }
        return syncMutex.withLock {
            val drive = ensureValidDriveService() ?: return@withLock null
            withContext(Dispatchers.IO) {
                try {
                    _syncState.value = SyncState.Syncing
                    val json = withTimeoutOrNull(NETWORK_TIMEOUT_MS) { downloadContent(drive, fileId) }
                    finishRestore(json)
                } catch (e: UserRecoverableAuthIOException) {
                    val refreshed = refreshCredentialsIfNeeded()
                    if (refreshed == null) {
                        _syncState.value = SyncState.Error("Sesija istekla - prijavite se ponovo")
                        return@withContext null
                    }
                    val json = withTimeoutOrNull(NETWORK_TIMEOUT_MS) { downloadContent(refreshed, fileId) }
                    finishRestore(json)
                } catch (e: Exception) {
                    Log.e(TAG, "Restore version failed", e)
                    _syncState.value = SyncState.Error("Greska: ${e.message ?: e.javaClass.simpleName}")
                    null
                }
            }
        }
    }

    private fun finishRestore(json: String?): String? {
        if (json == null) {
            _syncState.value = SyncState.Error("Ne mogu da pročitam backup")
            return null
        }
        _syncState.value = SyncState.Success
        return json
    }

    private fun listVersionsInternal(drive: Drive): List<BackupVersion> {
        val versions = mutableListOf<BackupVersion>()
        var pageToken: String? = null
        do {
            val response = drive.files().list()
                .setSpaces(APP_DATA_FOLDER)
                .setQ("trashed = false")
                .setOrderBy("createdTime desc")
                .setFields("nextPageToken, files(id, name, createdTime, appProperties)")
                .setPageToken(pageToken)
                .execute()
            for (file in response.files.orEmpty()) {
                val id = file.id ?: continue
                val name = file.name ?: continue
                if (!isOurBackupFile(name)) continue
                versions += BackupMetadata.buildVersion(
                    fileId = id,
                    fileName = name,
                    driveCreatedAtMillis = file.createdTime?.value,
                    props = file.appProperties
                )
            }
            pageToken = response.nextPageToken
        } while (pageToken != null)
        return versions
    }

    private fun isOurBackupFile(name: String): Boolean =
        BackupMetadata.isLegacyName(name) || BackupMetadata.parseCreatedAt(name) != null

    private fun downloadContent(drive: Drive, fileId: String): String? = try {
        val outputStream = ByteArrayOutputStream()
        drive.files().get(fileId).executeMediaAndDownloadTo(outputStream)
        outputStream.toString("UTF-8")
    } catch (e: Exception) {
        Log.w(TAG, "Neuspešno čitanje verzije $fileId", e)
        null
    }

    private fun createVersionFile(
        drive: Drive,
        nowMillis: Long,
        json: String,
        props: Map<String, String>
    ): String {
        val metadata = com.google.api.services.drive.model.File().apply {
            name = BackupMetadata.fileName(nowMillis)
            parents = listOf(APP_DATA_FOLDER)
            appProperties = HashMap(props)
        }
        val content = ByteArrayContent.fromString("application/json", json)
        val created = drive.files().create(metadata, content).setFields("id").execute()
        return created.id ?: error("Drive nije vratio id nove verzije")
    }

    private fun pruneOldVersions(drive: Drive, allVersions: List<BackupVersion>) {
        val toDelete = BackupRetentionPolicy.selectForDeletion(allVersions)
        for (version in toDelete) {
            try {
                drive.files().delete(version.fileId).execute()
            } catch (e: Exception) {
                Log.w(TAG, "Neuspešno orezivanje verzije ${version.fileId}", e)
            }
        }
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

/** Sadržaj jedne verzije backup-a + njen otisak (za metapodatke i anomalije). */
data class CloudBackupContent(val json: String, val stats: BackupStats)

/** Ishod verzionisanog backup-a. */
sealed class BackupOutcome {
    /**
     * @param flagged true ako [BackupAnomalyGuard] sumnja na masovno brisanje —
     *   verzija je svejedno sačuvana, ali korisnika treba upozoriti.
     */
    data class Success(val flagged: Boolean, val reasons: List<String>) : BackupOutcome()
    data class Failure(val message: String) : BackupOutcome()
}
