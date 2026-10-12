
package com.nuvio.tv

import android.content.Context
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

object CacheCleaner {
    private const val TEMP_FOLDER = "kable_killers_temp"
    private const val MAX_AGE_DAYS = 7L

    private val executor = Executors.newSingleThreadExecutor()

    private fun temporaryDirectory(context: Context): File =
        File(context.cacheDir, TEMP_FOLDER)

    fun runStartupCleanup(context: Context) {
        val appContext = context.applicationContext

        executor.execute {
            if (CachePreferences.autoClearOnStartup(appContext)) {
                clearManagedCache(appContext)
            } else if (CachePreferences.smartCleanupEnabled(appContext)) {
                clearOldFiles(appContext)
            }
        }
    }

    fun clearNow(context: Context, onComplete: (Boolean) -> Unit) {
        val appContext = context.applicationContext

        executor.execute {
            val success = clearManagedCache(appContext)
            android.os.Handler(android.os.Looper.getMainLooper())
                .post { onComplete(success) }
        }
    }

    private fun clearManagedCache(context: Context): Boolean {
        val directory = temporaryDirectory(context)
        if (!directory.exists()) return true

        return directory.listFiles()
            ?.map { it.deleteRecursively() }
            ?.all { it } ?: false
    }

    private fun clearOldFiles(context: Context) {
        val directory = temporaryDirectory(context)
        if (!directory.exists()) return

        val cutoff = System.currentTimeMillis() -
            TimeUnit.DAYS.toMillis(MAX_AGE_DAYS)

        directory.listFiles()?.forEach { file ->
            if (file.lastModified() < cutoff) {
                file.deleteRecursively()
            }
        }
    }
}
