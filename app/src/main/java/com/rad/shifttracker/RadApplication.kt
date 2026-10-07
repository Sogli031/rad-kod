package com.rad.shifttracker

import android.app.Application
import android.os.StrictMode
import com.rad.shifttracker.osnova.CrashLogStorage

class RadApplication : Application() {
    @Volatile
    private var containerInstance: AppContainer? = null

    /** Container ako je već napravljen; receiver bez njega čita fajl sam, worker-i ga prave. */
    val containerOrNull: AppContainer?
        get() = containerInstance

    companion object {
        const val CRASH_LOG_FILE = CrashLogStorage.CRASH_LOG_FILE
    }

    override fun onCreate() {
        super.onCreate()
        CrashLogStorage.install(this, appName = "Rad")
        if (BuildConfig.DEBUG) enableStrictMode()
        deleteSharedPreferences("gps_shift_state")
        cleanUpRemovedFeatures(this)
    }

    /**
     * Container sa repozitorijumom i backup servisom; pravi se pri prvom pozivu (čita lokalni fajl,
     * pa ne na glavnoj niti). Poziva se iz MainActivity POSLE provere crash loga, ali i iz
     * worker-a (WorkManager nit) — zato je sinhronizovano, inače dva poziva mogu da naprave dva
     * repozitorijuma pa autosave pregazi fajl sa starijim stanjem.
     */
    @Synchronized
    fun container(): AppContainer =
        containerInstance ?: AppContainer(this).also { containerInstance = it }

    /** Samo u debug build-u: upis i čitanje na glavnoj niti i propuštena zatvaranja idu u logcat (bez pada aplikacije). */
    private fun enableStrictMode() {
        StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.Builder().detectAll().penaltyLog().build())
        StrictMode.setVmPolicy(StrictMode.VmPolicy.Builder().detectLeakedClosableObjects().detectLeakedSqlLiteObjects().penaltyLog().build())
    }
}
