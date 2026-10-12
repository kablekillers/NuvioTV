package com.nuvio.tv.core.image

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore

/** Best-effort image refreshes must never build an unbounded queue behind visible loads. */
internal class ImageRevalidationScheduler(
    private val scope: CoroutineScope,
    maxConcurrent: Int
) {
    private val permits = Semaphore(maxConcurrent)
    private val activeUrls = ConcurrentHashMap.newKeySet<String>()

    fun schedule(url: String, refresh: suspend () -> Unit): Boolean {
        if (!permits.tryAcquire()) return false
        if (!activeUrls.add(url)) {
            permits.release()
            return false
        }
        val job = scope.launch { refresh() }
        // Completion also runs if the scope is cancelled before the coroutine starts.
        job.invokeOnCompletion {
            activeUrls.remove(url)
            permits.release()
        }
        return true
    }
}
