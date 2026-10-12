package com.nuvio.tv.core.image

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ImageRevalidationSchedulerTest {
    @Test fun burstSkipsExcessRefreshesAndKeepsDuplicateUrlsOut() = runTest {
        val scheduler = ImageRevalidationScheduler(backgroundScope, maxConcurrent = 2)
        val finish = CompletableDeferred<Unit>()
        var started = 0
        val refresh: suspend () -> Unit = { started++; finish.await() }
        assertTrue(scheduler.schedule("a", refresh))
        assertFalse(scheduler.schedule("a", refresh))
        assertTrue(scheduler.schedule("b", refresh))
        assertFalse(scheduler.schedule("c", refresh))
        runCurrent()
        assertEquals(2, started)
        finish.complete(Unit)
        runCurrent()
        // Skipped work was not queued; a future cache read may retry it.
        assertEquals(2, started)
        assertTrue(scheduler.schedule("c", refresh))
        runCurrent()
        assertEquals(3, started)
    }

    @Test fun cancelledRefreshReleasesItsUrlAndSlot() = runTest {
        val scheduler = ImageRevalidationScheduler(backgroundScope, maxConcurrent = 1)
        assertTrue(scheduler.schedule("a") { throw kotlinx.coroutines.CancellationException() })
        runCurrent()
        assertTrue(scheduler.schedule("a") {})
        runCurrent()
    }

    @Test fun cancellationBeforeExecutionDoesNotLeakPermit() = runTest {
        val scope = kotlinx.coroutines.CoroutineScope(backgroundScope.coroutineContext + kotlinx.coroutines.Job())
        val scheduler = ImageRevalidationScheduler(scope, maxConcurrent = 1)
        var started = false
        assertTrue(scheduler.schedule("a") { started = true })
        scope.cancel()
        runCurrent()
        assertFalse(started)
        // Even a job cancelled before execution must release its registered URL/slot.
        assertTrue(scheduler.schedule("a") { fail("Cancelled scope must not execute work") })
        runCurrent()
    }
}
