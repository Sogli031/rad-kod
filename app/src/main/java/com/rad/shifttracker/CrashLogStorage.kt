package com.rad.shifttracker

import android.Manifest
import android.annotation.TargetApi
import android.app.Activity
import android.app.Application
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import kotlin.system.exitProcess

object CrashLogStorage {
    const val CRASH_LOG_FILE = "crash_log.txt"

    private const val STORAGE_PERMISSION_REQUEST_CODE = 7021
    private const val MIME_TYPE = "text/plain"

    fun install(application: Application, appName: String) {
        requestPublicDocumentsPermissionWhenNeeded(application)

        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            saveCrashLog(application, appName, thread, throwable)

            if (defaultHandler != null) {
                defaultHandler.uncaughtException(thread, throwable)
            } else {
                android.os.Process.killProcess(android.os.Process.myPid())
                exitProcess(10)
            }
        }
    }

    private fun saveCrashLog(
        context: Context,
        appName: String,
        thread: Thread,
        throwable: Throwable
    ) {
        val logText = CrashLogFormatter.format(
            appName = appName,
            packageName = context.packageName,
            androidVersion = Build.VERSION.RELEASE
                .takeIf { it.isNotBlank() }
                ?: Build.VERSION.SDK_INT.toString(),
            deviceName = listOf(Build.MANUFACTURER, Build.MODEL)
                .filter { it.isNotBlank() }
                .joinToString(" "),
            threadName = thread.name,
            throwable = throwable
        )

        runCatching {
            File(context.filesDir, CRASH_LOG_FILE).writeText(logText, Charsets.UTF_8)
        }
        runCatching {
            appendPublicDocumentsLog(context, logText)
        }
    }

    private fun appendPublicDocumentsLog(context: Context, logText: String) {
        val entry = buildString {
            appendLine("-----")
            appendLine(logText.trimEnd())
            appendLine()
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appendPublicDocumentsLogWithMediaStore(context, entry)
        } else {
            appendPublicDocumentsLogDirectly(context, entry)
        }
    }

    @TargetApi(Build.VERSION_CODES.Q)
    private fun appendPublicDocumentsLogWithMediaStore(context: Context, entry: String) {
        val resolver = context.contentResolver
        val existingUri = findPublicDocumentsLog(context)
        val uri = existingUri ?: createPublicDocumentsLog(context) ?: return
        val mode = if (existingUri == null) "w" else "wa"

        resolver.openOutputStream(uri, mode)?.use { stream ->
            stream.write(entry.toByteArray(Charsets.UTF_8))
        }
    }

    // Kolekcija „Files“, ne „Downloads“: Downloads dozvoljava samo putanju Download/, pa je upis u
    // Documents/ bio odbijan i javni log nikad nije nastajao.
    @TargetApi(Build.VERSION_CODES.Q)
    private fun findPublicDocumentsLog(context: Context): Uri? {
        val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val relativePath = "${Environment.DIRECTORY_DOCUMENTS}/"
        val projection = arrayOf(MediaStore.MediaColumns._ID)
        val selection = "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND " +
            "${MediaStore.MediaColumns.RELATIVE_PATH} = ?"
        val selectionArgs = arrayOf(CRASH_LOG_FILE, relativePath)

        context.contentResolver.query(
            collection,
            projection,
            selection,
            selectionArgs,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                return ContentUris.withAppendedId(collection, cursor.getLong(idColumn))
            }
        }

        return null
    }

    @TargetApi(Build.VERSION_CODES.Q)
    private fun createPublicDocumentsLog(context: Context): Uri? {
        val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, CRASH_LOG_FILE)
            put(MediaStore.MediaColumns.MIME_TYPE, MIME_TYPE)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOCUMENTS}/")
        }

        return context.contentResolver.insert(collection, values)
    }

    @Suppress("DEPRECATION")
    private fun appendPublicDocumentsLogDirectly(context: Context, entry: String) {
        if (!hasLegacyStoragePermission(context)) return

        val documentsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
        if (!documentsDir.exists() && !documentsDir.mkdirs()) return

        File(documentsDir, CRASH_LOG_FILE).appendText(entry, Charsets.UTF_8)
    }

    private fun requestPublicDocumentsPermissionWhenNeeded(application: Application) {
        if (Build.VERSION.SDK_INT !in Build.VERSION_CODES.M..Build.VERSION_CODES.P) return

        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            private var handled = false

            override fun onActivityStarted(activity: Activity) {
                if (handled) return
                handled = true

                if (!hasLegacyStoragePermission(activity)) {
                    activity.requestPermissions(
                        arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),
                        STORAGE_PERMISSION_REQUEST_CODE
                    )
                }
                application.unregisterActivityLifecycleCallbacks(this)
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }

    private fun hasLegacyStoragePermission(context: Context): Boolean {
        return context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED
    }
}
