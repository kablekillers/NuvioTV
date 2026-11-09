package com.nuvio.tv.data.local

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileRemapStoreAccessGateTest {
    @Test fun blocksBothIdsButAllowsMainAndUnrelatedProfile() {
        val gate = ProfileRemapStoreAccessGate()
        gate.begin(2, 5, emptySet())
        assertTrue(gate.isActive())
        assertTrue(runCatching { gate.checkAccess(2) }.isFailure)
        assertTrue(runCatching { gate.checkAccess(5) }.isFailure)
        gate.checkAccess(1)
        gate.checkAccess(3)
        gate.end(2, 5)
        assertFalse(gate.isActive())
        gate.checkAccess(2)
        gate.checkAccess(5)
    }

    @Test fun refusesAlreadyCachedSourceOrDestination() {
        val gate = ProfileRemapStoreAccessGate()
        assertTrue(runCatching { gate.begin(2, 5, setOf(2)) }.isFailure)
        assertTrue(runCatching { gate.begin(2, 5, setOf(5)) }.isFailure)
        assertFalse(gate.isActive())
    }

    @Test fun overlappingBarriersCannotReplaceExistingOne() {
        val gate = ProfileRemapStoreAccessGate()
        gate.begin(2, 5, emptySet())
        assertTrue(runCatching { gate.begin(3, 6, emptySet()) }.isFailure)
        assertTrue(runCatching { gate.end(3, 6) }.isFailure)
        assertTrue(runCatching { gate.checkAccess(2) }.isFailure)
        gate.end(2, 5)
        gate.begin(3, 6, emptySet())
        gate.end(3, 6)
    }

    @Test fun rejectsMainAndUnsupportedIds() {
        val gate = ProfileRemapStoreAccessGate()
        assertTrue(runCatching { gate.begin(1, 3, emptySet()) }.isFailure)
        assertTrue(runCatching { gate.begin(2, 7, emptySet()) }.isFailure)
        assertTrue(runCatching { gate.begin(2, 2, emptySet()) }.isFailure)
        assertFalse(gate.isActive())
    }
    @Test fun activeCleanupMustFinishBeforeMigrationCanBlockAccess() {
        val gate = ProfileRemapStoreAccessGate()
        gate.beginMutation(2)
        assertTrue(runCatching { gate.begin(2, 5, emptySet()) }.isFailure)
        gate.endMutation()
        gate.begin(2, 5, emptySet())
        assertTrue(gate.isActive())
        gate.end(2, 5)
    }

    @Test fun multipleInFlightMutationsMustAllFinish() {
        val gate = ProfileRemapStoreAccessGate()
        gate.beginMutation(2)
        gate.beginMutation(3)
        gate.endMutation()
        assertTrue(runCatching { gate.begin(2, 5, emptySet()) }.isFailure)
        gate.endMutation()
        gate.begin(2, 5, emptySet())
        gate.end(2, 5)
        assertTrue(runCatching { gate.endMutation() }.isFailure)
    }

    @Test fun lateCleanupAndShadowWritesCannotStartForBlockedProfiles() {
        val gate = ProfileRemapStoreAccessGate()
        gate.begin(2, 5, emptySet())
        assertTrue(runCatching { gate.beginMutation(2) }.isFailure)
        assertTrue(runCatching { gate.beginMutation(5) }.isFailure)
        assertTrue(runCatching { gate.beginMutation() }.isFailure)
        // The primary profile is unaffected by a secondary-ID remap.
        gate.beginMutation(1)
        gate.endMutation()
        gate.end(2, 5)
        gate.beginMutation(2)
        gate.endMutation()
    }

}
