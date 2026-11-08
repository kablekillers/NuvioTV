package com.nuvio.tv.core.profile

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileMigrationRecoveryInspectorTest {
    @Test fun verifiedBatchIsNotReportedComplete() {
        val dir = Files.createTempDirectory("profile-recovery").toFile()
        try {
            ProfileMigrationJournal.write(dir, ProfileMigrationJournal.Entry(
                "account-a", 2, 3, ProfileMigrationJournal.Phase.PREPARED
            ))
            ProfileMigrationJournal.write(dir, ProfileMigrationJournal.Entry(
                "account-a", 2, 3, ProfileMigrationJournal.Phase.COPYING
            ))
            ProfileMigrationJournal.write(dir, ProfileMigrationJournal.Entry(
                "account-a", 2, 3, ProfileMigrationJournal.Phase.VERIFIED
            ))
            val report = ProfileMigrationRecoveryInspector.inspect(dir, "account-a")
            assertEquals(ProfileMigrationRecoveryInspector.State.VERIFIED_NOT_COMPLETE, report.state)
            assertTrue(report.accountMatches)
        } finally { dir.deleteRecursively() }
    }

    @Test fun otherAccountCannotClaimJournal() {
        val dir = Files.createTempDirectory("profile-recovery").toFile()
        try {
            ProfileMigrationJournal.write(dir, ProfileMigrationJournal.Entry(
                "account-a", 2, 3, ProfileMigrationJournal.Phase.PREPARED
            ))
            val report = ProfileMigrationRecoveryInspector.inspect(dir, "account-b")
            assertEquals(ProfileMigrationRecoveryInspector.State.UNRESOLVED, report.state)
            assertFalse(report.accountMatches)
        } finally { dir.deleteRecursively() }
    }

    @Test fun temporaryJournalBlocksRecovery() {
        val dir = Files.createTempDirectory("profile-recovery").toFile()
        try {
            dir.resolve("profile_migration_journal.tmp").writeText("partial")
            assertEquals(ProfileMigrationRecoveryInspector.State.INVALID_JOURNAL,
                ProfileMigrationRecoveryInspector.inspect(dir, "account-a").state)
        } finally { dir.deleteRecursively() }
    }
}
