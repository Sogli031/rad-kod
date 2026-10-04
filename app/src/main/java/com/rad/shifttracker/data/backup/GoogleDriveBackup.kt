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

private const val TAG = "GoogleDriveBackup"
private const val PREFS_NAME = "rad_backup_prefs"
private const val KEY_FIRST_LAUNCH_DONE = "first_launch_done"
private const val KEY_LAST_SYNC_TIME = "last_sync_time"
private const val KEY_CLOUD_ADOPTED = "cloud_adopted"
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
    // „Usvojen oblak": uređaj sme AUTOMATSKI da šalje backup tek kad je njegovo
    // stanje nastavak onoga što je u oblaku — ili je sam napravio prvi backup, ili
    // je uradio Restore, ili je korisnik izričito rekao da zadrži lokalne podatke.
    // Bez ovoga nova instalacija (prazna podešavanja + sveža auto-popuna smena)
    // posle prijave pregazi tekuće mesece i podešavanja u najnovijoj verziji.
    // ---------------------------------------------------------------------------

    fun isCloudAdopted(): Boolean {
        if (!prefs.contains(KEY_CLOUD_ADOPTED)) {
            // Migracija: uređaj koji je već uspešno slao backup je nastavak oblaka.
            // Android auto-backup je isključen, pa prefs ne preživljavaju reinstalaciju.
            val adopted = prefs.getLong(KEY_LAST_SYNC_TIME, 0L) > 0L
            prefs.edit().putBoolean(KEY_CLOUD_ADOPTED, adopted).apply()
        }
        return prefs.getBoolean(KEY_CLOUD_ADOPTED, false)
    }

    fun markCloudAdopted() {
        prefs.edit().putBoolean(KEY_CLOUD_ADOPTED, true).apply()
    }

    /**
     * Odluka za pozadinski backup. Ako uređaj još nije usvojio oblak, a u oblaku
     * već postoji redovna verzija, auto-backup se preskače dok korisnik ne odluči
     * (Restore ili „zadrži lokalno"). Prazan oblak se usvaja odmah.
     */
    suspend fun autoBackupGate(): AutoBackupGate {
        if (isCloudAdopted()) return AutoBackupGate.ALLOWED
        val gate = VersionedBackupWriter.gate(adopted = false, cloudVersions = cloudVersionsOrNull())
        if (gate == AutoBackupGate.ALLOWED) markCloudAdopted()
        return gate
    }

    /** Kao [listVersions], ali vraća null kad lista nije mogla da se pročita. */
    suspend fun cloudVersionsOrNull(): List<BackupVersion>? {
        if (!isNetworkAvailable()) return null
        return syncMutex.withLock {
            val drive = ensureValidDriveService() ?: return@withLock null
            withContext(Dispatchers.IO) {
                try {
                    withTimeoutOrNull(NETWORK_TIMEOUT_MS) { DriveBackupFileStore(drive).listVersions() }
                } catch (e: Exception) {
                    Log.e(TAG, "Cloud versions check failed: ${e.javaClass.simpleName}", e)
                    null
                }
            }
        }
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
                    try {
                        withTimeoutOrNull(NETWORK_TIMEOUT_MS) { DriveBackupFileStore(drive).listVersions() }.orEmpty()
                    } catch (e: UserRecoverableAuthIOException) {
                        val refreshed = refreshCredentialsIfNeeded() ?: return@withContext emptyList()
                        withTimeoutOrNull(NETWORK_TIMEOUT_MS) { DriveBackupFileStore(refreshed).listVersions() }.orEmpty()
                    }
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
     *
     * @param safety true za sigurnosnu kopiju pre vraćanja: obeležava se u
     *   metapodacima, ne postaje „najnovija verzija" i ne usvaja oblak.
     */
    suspend fun backupVersioned(
        appVersion: String?,
        safety: Boolean = false,
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
                    // Ponovni pokušaj posle osvežene prijave je unutar istog try-a: greška drugog
                    // pokušaja mora da stigne do catch-a ispod, inače izlazi iz backupVersioned
                    // (posao puca, a stanje ostaje „Syncing").
                    try {
                        runVersionedBackup(drive, appVersion, safety, build)
                    } catch (e: UserRecoverableAuthIOException) {
                        Log.w(TAG, "Auth error during versioned backup, refreshing")
                        val refreshed = refreshCredentialsIfNeeded()
                        if (refreshed == null) {
                            _syncState.value = SyncState.Error("Sesija istekla - prijavite se ponovo")
                            return@withContext BackupOutcome.Failure("Sesija istekla - prijavite se ponovo")
                        }
                        runVersionedBackup(refreshed, appVersion, safety, build)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Versioned backup failed: ${e.javaClass.simpleName}", e)
                    val error = "Greska: ${e.message ?: e.javaClass.simpleName}"
                    _syncState.value = SyncState.Error(error)
                    BackupOutcome.Failure(error)
                }
            }
        }
    }

    private suspend fun runVersionedBackup(
        drive: Drive,
        appVersion: String?,
        safety: Boolean,
        build: (existingJson: String?) -> CloudBackupContent
    ): BackupOutcome {
        val nowMillis = System.currentTimeMillis()
        val result = VersionedBackupWriter.write(DriveBackupFileStore(drive), nowMillis, appVersion, safety, NETWORK_TIMEOUT_MS, build)
        val created = when (result) {
            VersionedWriteResult.ReadTimeout -> return syncFailure("Timeout - spora mreža")
            VersionedWriteResult.LatestUnreadable ->
                return syncFailure("Ne mogu da pročitam postojeći backup — prekinuto radi zaštite")
            is VersionedWriteResult.Created -> result
        }
        if (!safety) {
            _lastSyncTime.value = nowMillis
            prefs.edit().putLong(KEY_LAST_SYNC_TIME, nowMillis).apply()
            markCloudAdopted()
        }
        _syncState.value = SyncState.Success
        Log.d(TAG, "Versioned backup created (flagged=${created.flagged})")
        return BackupOutcome.Success(created.flagged, created.reasons)
    }

    private fun syncFailure(error: String): BackupOutcome {
        _syncState.value = SyncState.Error(error)
        return BackupOutcome.Failure(error)
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
                    val json = withTimeoutOrNull(NETWORK_TIMEOUT_MS) { DriveBackupFileStore(drive).download(fileId) }
                    finishRestore(json)
                } catch (e: UserRecoverableAuthIOException) {
                    val refreshed = refreshCredentialsIfNeeded()
                    if (refreshed == null) {
                        _syncState.value = SyncState.Error("Sesija istekla - prijavite se ponovo")
                        return@withContext null
                    }
                    val json = withTimeoutOrNull(NETWORK_TIMEOUT_MS) { DriveBackupFileStore(refreshed).download(fileId) }
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

/** Da li pozadinski backup sme da krene (vidi [GoogleDriveBackup.autoBackupGate]). */
enum class AutoBackupGate {
    ALLOWED,

    /** U oblaku već postoji backup, a ovaj uređaj ga nije preuzeo — čeka se odluka korisnika. */
    NEEDS_USER_DECISION,

    /** Stanje oblaka nije moglo da se proveri (mreža/sesija) — probaj kasnije. */
    UNKNOWN
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
