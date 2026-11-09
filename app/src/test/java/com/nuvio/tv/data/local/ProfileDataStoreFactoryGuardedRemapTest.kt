package com.nuvio.tv.data.local

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicReference

class ProfileDataStoreFactoryGuardedRemapTest {
    private fun withFactory(test: (ProfileDataStoreFactory, File) -> Unit) {
        val dir = Files.createTempDirectory("guarded-profile-remap").toFile()
        try {
            val store = File(dir, "datastore").apply { mkdirs() }
            File(store, "history_p2.preferences_pb").writeText("private history")
            File(store, "history_p2.preferences_pb.bak").writeText("recovery history")
            val context = mockk<Context>()
            every { context.filesDir } returns dir
            test(ProfileDataStoreFactory(context), store)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun suspendingWorkKeepsBothProfileIdsBlocked() = withFactory { factory, store ->
        runBlocking {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val work = async(Dispatchers.Default) {
                factory.withGuardedProfileRemap(
                    "verified-a", 2, 5, { "verified-a" }
                ) { manifest ->
                    assertEquals(mapOf(
                        "history_p2.preferences_pb" to "history_p5.preferences_pb",
                        "history_p2.preferences_pb.bak" to "history_p5.preferences_pb.bak"
                    ), manifest)
                    entered.complete(Unit)
                    release.await()
                    "verified"
                }
            }
            try {
                entered.await()
                assertTrue(runCatching { factory.get(2, "history") }.isFailure)
                assertTrue(runCatching { factory.get(5, "history") }.isFailure)
                assertTrue(runCatching {
                    factory.beginProfileRemapAccessBlock(3, 6)
                }.isFailure)
            } finally {
                release.complete(Unit)
            }
            assertEquals("verified", work.await())
            factory.beginProfileRemapAccessBlock(2, 5).close()
            assertEquals("private history",
                File(store, "history_p2.preferences_pb").readText())
            assertEquals("recovery history",
                File(store, "history_p2.preferences_pb.bak").readText())
        }
    }

    @Test fun accountSwitchDuringSuspendFailsClosedAndReleasesBarrier() =
        withFactory { factory, store ->
            runBlocking {
                val account = AtomicReference("verified-a")
                val entered = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                val work = async(Dispatchers.Default) {
                    runCatching {
                        factory.withGuardedProfileRemap(
                            "verified-a", 2, 5, { account.get() }
                        ) {
                            entered.complete(Unit)
                            release.await()
                        }
                    }.isFailure
                }
                try {
                    entered.await()
                    account.set("verified-b")
                } finally {
                    release.complete(Unit)
                }
                assertTrue(work.await())
                factory.beginProfileRemapAccessBlock(2, 5).close()
                assertEquals("private history",
                    File(store, "history_p2.preferences_pb").readText())
            }
        }

    @Test fun cancellationReleasesBarrierWithoutDeletingAnyFiles() =
        withFactory { factory, store ->
            runBlocking {
                val entered = CompletableDeferred<Unit>()
                val work = async(Dispatchers.Default) {
                    factory.withGuardedProfileRemap(
                        "verified-a", 2, 5, { "verified-a" }
                    ) {
                        entered.complete(Unit)
                        CompletableDeferred<Unit>().await()
                    }
                }
                entered.await()
                assertTrue(runCatching { factory.get(2, "history") }.isFailure)
                work.cancelAndJoin()
                factory.beginProfileRemapAccessBlock(2, 5).close()
                assertEquals("private history",
                    File(store, "history_p2.preferences_pb").readText())
                assertEquals("recovery history",
                    File(store, "history_p2.preferences_pb.bak").readText())
            }
        }

    @Test fun missingAccountIdentityCannotStartBarrier() =
        withFactory { factory, _ ->
            runBlocking {
                assertTrue(runCatching {
                    factory.withGuardedProfileRemap(
                        "verified-a", 2, 5, { null }
                    ) { error("Must never execute") }
                }.isFailure)
                factory.beginProfileRemapAccessBlock(2, 5).close()
            }
        }
}
