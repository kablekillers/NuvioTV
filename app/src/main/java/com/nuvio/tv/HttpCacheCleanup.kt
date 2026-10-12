package com.nuvio.tv

import okhttp3.Cache
import okhttp3.CacheControl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

internal object HttpCacheCleanup {
    /** Use the live cache owner, never delete its journal/files behind its back. */
    fun removeOldResponses(cache: Cache, cutoffMillis: Long) {
        // No app interceptors, cookies, or credentials. only-if-cached prohibits network I/O.
        val reader = OkHttpClient.Builder().cache(cache).build()
        val cacheOnly = CacheControl.Builder()
            .onlyIfCached()
            .maxStale(Int.MAX_VALUE, TimeUnit.SECONDS)
            .build()
        val urls = cache.urls()
        while (urls.hasNext()) {
            val request = Request.Builder().url(urls.next()).cacheControl(cacheOnly).build()
            val old = reader.newCall(request).execute().use { response ->
                // A miss (504), Vary mismatch, or must-revalidate entry is left untouched.
                response.cacheResponse?.receivedResponseAtMillis?.let {
                    it > 0L && it < cutoffMillis
                } ?: false
            }
            if (old) urls.remove()
        }
    }
}
