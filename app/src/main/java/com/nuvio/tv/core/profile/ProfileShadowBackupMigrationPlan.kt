package com.nuvio.tv.core.profile

import java.io.File
import java.nio.file.Files

/**
 * Non-mutating audit for DataStore shadow copies during profile-ID migration.
 *
 * The existing copy transaction intentionally rejects .bak files. This plan
 * documents exactly which backups require a future copy transaction; it does
 * NOT authorize the current migration coordinator to skip those files.
 *
 * Sources remain untouched and every target must be absent.
 */
internal object ProfileShadowBackupMigrationPlan {
    data class Plan(
        val backupsToCopy: Map<String, String>,
        val primaryFilesWithoutBackups: List<String>
    )

    fun inspect(
        directory: File,
        sourceId: Int,
        targetId: Int,
        primaryManifest: Map<String, String>
    ): Plan {
        require(sourceId in 2..6 && targetId in 2..6 && sourceId != targetId) {
            "Unsupported local profile remap"
        }
        require(directory.isDirectory && !Files.isSymbolicLink(directory.toPath())) {
            "Unsafe profile storage directory"
        }
        require(primaryManifest.isNotEmpty()) { "Missing primary DataStore manifest" }
        val sourceSuffix = "_p$sourceId.preferences_pb"
        val targetSuffix = "_p$targetId.preferences_pb"
        val safeName = Regex("[A-Za-z0-9_-]+\\.preferences_pb")
        require(primaryManifest.all { (from, to) ->
            safeName.matches(from) && safeName.matches(to) &&
                from.endsWith(sourceSuffix) &&
                to == from.removeSuffix(sourceSuffix) + targetSuffix
        }) { "Unsafe or incorrect primary file mapping" }

        val listed = directory.listFiles() ?: error("Cannot inspect profile storage")
        val sourceBackups = listed.filter {
            it.name.endsWith("$sourceSuffix.bak")
        }.associateBy { it.name }
        val targetBackups = listed.filter {
            it.name.endsWith("$targetSuffix.bak")
        }
        require(targetBackups.isEmpty()) {
            "Existing destination shadow backups must never be overwritten"
        }
        val expectedBackupNames = primaryManifest.keys.map { "$it.bak" }.toSet()
        require(sourceBackups.keys.all { it in expectedBackupNames }) {
            "Orphan source backup has no corresponding primary store"
        }
        require(sourceBackups.values.all { it.isFile && it.length() > 0L &&
            !Files.isSymbolicLink(it.toPath())
        }) { "Unreadable, empty or unsafe shadow backup" }

        val backups = linkedMapOf<String, String>()
        for ((from, to) in primaryManifest.toSortedMap()) {
            val sourceBackup = "$from.bak"
            if (sourceBackups.containsKey(sourceBackup)) {
                val destinationBackup = "$to.bak"
                require(!File(directory, destinationBackup).exists() &&
                    !Files.isSymbolicLink(File(directory, destinationBackup).toPath())
                ) { "Shadow backup destination is occupied" }
                backups[sourceBackup] = destinationBackup
            }
        }
        return Plan(
            backupsToCopy = backups,
            primaryFilesWithoutBackups = primaryManifest.keys.filter {
                "$it.bak" !in sourceBackups
            }.sorted()
        )
    }
}
