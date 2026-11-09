package com.nuvio.tv.data.local

import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileDataStoreFactoryWriteGateTest {
    @Test fun existingProfileWriteBlocksMigrationUntilItFinishes() = runBlocking {
        val factory = ProfileDataStoreFactory(mockk())
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val writer = launch(Dispatchers.Default) {
            factory.withTrackedProfileStoreWrite("history_p2") {
                entered.complete(Unit)
                release.await()
            }
        }
        try {
            entered.await()
            assertTrue("A pending write must prevent migration",
                runCatching { factory.beginProfileRemapAccessBlock(2, 5) }.isFailure)
        } finally {
            release.complete(Unit)
            writer.join()
        }
        factory.beginProfileRemapAccessBlock(2, 5).close()
    }

    @Test fun barrierRejectsWritesEvenWhenCallerAlreadyHasStoreReference() = runBlocking {
        val factory = ProfileDataStoreFactory(mockk())
        val barrier = factory.beginProfileRemapAccessBlock(2, 5)
        var wrote = false
        try {
            assertTrue(runCatching {
                factory.withTrackedProfileStoreWrite("history_p2") { wrote = true }
            }.isFailure)
            assertTrue(runCatching {
                factory.withTrackedProfileStoreWrite("favorites_p5") { wrote = true }
            }.isFailure)
            assertTrue("Blocked callbacks must not run", !wrote)
            factory.withTrackedProfileStoreWrite("history_p3") { wrote = true }
            assertTrue(wrote)
        } finally { barrier.close() }
        var resumed = false
        factory.withTrackedProfileStoreWrite("history_p2") { resumed = true }
        assertTrue("Writes resume after the barrier closes", resumed)
    }

    @Test fun failingWriteAlwaysReleasesActiveMutation() = runBlocking {
        val factory = ProfileDataStoreFactory(mockk())
        assertTrue(runCatching {
            factory.withTrackedProfileStoreWrite("history_p2") {
                error("simulated interrupted write")
            }
        }.isFailure)
        factory.beginProfileRemapAccessBlock(2, 5).close()
    }
}
