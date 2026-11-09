package com.nuvio.tv.core.profile

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption

/**
 * Development-only copy phase for a profile's explicitly enumerated stores.
 * Does not activate profiles, mutate cloud state, delete sources, or mark a
 * migration complete. A failed batch leaves any copies for manual recovery.
 */
internal object ProfileMigrationBatchCopy {
    data class Item(val source: File, val destination: File)
    data class Result(val verifiedFiles: Int, val allVerified: Boolean)

    fun copyAll(items: List<Item>): Result {
        preflight(items)
        var verified = 0
        for (item in items) {
            if (!ProfileMigrationCopyVerifier.copyAndVerify(item.source, item.destination)) {
                return Result(verified, false)
            }
            verified++
        }
        return Result(verified, true)
    }

    fun preflight(items: List<Item>) {
        require(items.isNotEmpty()) { "Migration manifest must not be empty" }
        val destinations = items.map { it.destination.toPath().toAbsolutePath().normalize() }
        val sources = items.map { it.source.toPath().toAbsolutePath().normalize() }
        require(destinations.none { it in sources }) {
            "Migration destinations must not overlap any source in the batch"
        }
        require(destinations.distinct().size == destinations.size) {
            "Migration manifest contains duplicate destinations"
        }
        // Preflight every file before any write; a missing or occupied path
        // must not leave a partially copied batch.
        items.forEach { item ->
            require(Files.isRegularFile(item.source.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                "Migration source is missing or unsafe"
            }
            require(!Files.exists(item.destination.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                "Migration destination is already occupied"
            }
            val parent = item.destination.toPath().parent
            require(parent != null && Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS) &&
                !Files.isSymbolicLink(parent)) {
                "Migration destination directory is unsafe"
            }
            require(item.source.toPath().toAbsolutePath().normalize() !=
                item.destination.toPath().toAbsolutePath().normalize()) {
                "Migration source and destination must differ"
            }
        }
    }
}
