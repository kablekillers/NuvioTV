package com.nuvio.tv.core.profile

import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files

/**
 * Cross-thread and cross-process advisory lock for future migration orchestration.
 *
 * Every migration step must be executed under the SAME lock. This cannot
 * protect against regular DataStore writers that do not cooperate; a caller
 * must quiesce them before migration. It does not enable migration on startup.
 */
internal object ProfileMigrationExclusiveLock {
    private const val LOCK_FILE = "profile_migration.lock"

    /**
     * Fail closed rather than waiting for another migration. Keep the lock
     * file after completion so another process cannot race inode replacement.
     */
    fun <T> tryWithLock(directory: File, operation: () -> T): T? {
        require(directory.isDirectory && !Files.isSymbolicLink(directory.toPath())) {
            "Migration directory must be a real directory"
        }
        val file = File(directory, LOCK_FILE)
        require(!Files.isSymbolicLink(file.toPath())) {
            "Migration lock file must not be a symbolic link"
        }
        // Never delete the lock file, even on failure.
        RandomAccessFile(file, "rw").use { handle ->
            val lock = try {
                handle.channel.tryLock()
            } catch (_: OverlappingFileLockException) {
                null
            }
            if (lock == null) return null
            lock.use {
                return operation()
            }
        }
    }
    /**
     * Holds the same OS file lock across suspending verification and journal
     * completion. Never run this concurrently with ordinary profile writers;
     * they are not participants in this advisory lock.
     */
    suspend fun <T> tryWithSuspendingLock(
        directory: File,
        operation: suspend () -> T
    ): T? {
        require(directory.isDirectory && !Files.isSymbolicLink(directory.toPath())) {
            "Migration directory must be a real directory"
        }
        val file = File(directory, LOCK_FILE)
        require(!Files.isSymbolicLink(file.toPath())) {
            "Migration lock file must not be a symbolic link"
        }
        RandomAccessFile(file, "rw").use { handle ->
            val lock = try {
                handle.channel.tryLock()
            } catch (_: OverlappingFileLockException) {
                null
            }
            if (lock == null) return null
            lock.use {
                return operation()
            }
        }
    }

}
