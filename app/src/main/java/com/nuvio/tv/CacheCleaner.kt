package com.nuvio.tv

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import coil3.SingletonImageLoader
import dagger.hilt.android.qualifiers.ApplicationContext
import okhttp3.OkHttpClient
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/** Cleanup is deliberately limited to disposable HTTP/images and leased AFR scratch files.
 * Never touch filesDir, preferences, databases, plugins, subtitles, VOD or torrent caches.
 */
@Singleton
class CacheCleaner @Inject constructor(
    @ApplicationContext private val context: Context,
    httpClient: OkHttpClient,
    @param:Named("addonPermissive") addonHttpClient: OkHttpClient
) {
    private val httpCaches = listOfNotNull(httpClient.cache, addonHttpClient.cache).distinct()
    private val executor = Executors.newSingleThreadExecutor()

    fun runStartupCleanup() {
        executor.execute {
            if (CachePreferences.autoClearOnStartup(context)) {
                clean(clearAll = true)
            } else if (CachePreferences.smartCleanupEnabled(context)) {
                clean(clearAll = false)
            }
        }
    }

    fun clearNow(onComplete: (Boolean) -> Unit) {
        executor.execute {
            val success = clean(clearAll = true)
            Handler(Looper.getMainLooper()).post { onComplete(success) }
        }
    }

    private fun clean(clearAll: Boolean): Boolean {
        var success = true
        fun attempt(block: () -> Unit) {
            try {
                block()
            } catch (e: Exception) {
                success = false
                Log.w("CacheCleaner", "Could not clean disposable cache", e)
            }
        }
        val cutoff = if (clearAll) Long.MAX_VALUE else
            System.currentTimeMillis() - TimeUnit.DAYS.toMillis(7L)
        attempt {
            if (!ManagedProbeFiles.clean(context.cacheDir, cutoff)) success = false
        }
        httpCaches.forEach { cache ->
            attempt {
                if (clearAll) cache.evictAll() else HttpCacheCleanup.removeOldResponses(cache, cutoff)
            }
        }
        if (clearAll) {
            // Coil handles its own open snapshots/editors; do not recursively delete image_cache.
            attempt {
                val loader = SingletonImageLoader.get(context)
                loader.diskCache?.clear()
                loader.memoryCache?.clear()
            }
        }
        return success
    }
}
