package com.nuvio.tv.core.profile

import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

/**
 * Read-only verification before a future migration coordinator may mark the
 * journal COMPLETED. Does not mutate profiles, files, or migration journals.
 *
 * Call only after the profile metadata edit has committed and all profile
 * DataStore writers have been stopped. Metadata must be checked separately
 * against the persisted profile list and selected profile ID.
 */
internal object ProfileMigrationRecoveryVerifier {
    fun verifyCopiedFiles(
        directory: File,
        verifiedAccountId: String,
        sourceProfileId: Int,
        targetProfileId: Int,
        manifest: Map<String, String>
    ): Boolean {
        if (verifiedAccountId.isBlank() || verifiedAccountId != verifiedAccountId.trim() ||
            sourceProfileId !in 2..6 || targetProfileId !in 2..6 ||
            sourceProfileId == targetProfileId || manifest.isEmpty()
        ) return false

        return runCatching {
            if (!directory.isDirectory || Files.isSymbolicLink(directory.toPath()) ||
                ProfileMigrationJournal.hasUnresolvedMigration(
                    directory, verifiedAccountId
                ).not()
            ) return@runCatching false
            val journal = ProfileMigrationJournal.read(directory)
                ?: return@runCatching false
            if (journal.accountId != verifiedAccountId ||
                journal.sourceId != sourceProfileId ||
                journal.targetId != targetProfileId ||
                journal.phase != ProfileMigrationJournal.Phase.VERIFIED
            ) return@runCatching false
            if (File(directory, "profile_migration_journal.tmp").exists()) {
                return@runCatching false
            }
            if (manifest.values.toSet().size != manifest.size ||
                manifest.keys.any { it in manifest.values }
            ) return@runCatching false

            val sourceSuffix = "_p$sourceProfileId.preferences_pb"
            val targetSuffix = "_p$targetProfileId.preferences_pb"
            // A subset of matching files is not enough. Every existing store
            // for this secondary profile must be copied, or a metadata remap
            // could silently strand its viewing progress or private settings.
            val allSourceFiles = directory.listFiles()
                ?.filter { it.isFile && it.name.endsWith(sourceSuffix) }
                ?.map { it.name }?.toSet() ?: return@runCatching false
            val allTargetFiles = directory.listFiles()
                ?.filter { it.isFile && it.name.endsWith(targetSuffix) }
                ?.map { it.name }?.toSet() ?: return@runCatching false
            if (allSourceFiles != manifest.keys ||
                allTargetFiles != manifest.values.toSet()
            ) return@runCatching false
            // A verified primary-file copy is not safe if a shadow recovery
            // backup remains under either profile ID without being migrated.
            val sourceBackupSuffix = "_p$sourceProfileId.preferences_pb.bak"
            val targetBackupSuffix = "_p$targetProfileId.preferences_pb.bak"
            if (directory.listFiles()?.any { file ->
                    file.name.endsWith(sourceBackupSuffix) ||
                        file.name.endsWith(targetBackupSuffix)
                } != false
            ) return@runCatching false
            if (manifest.any { (source, target) ->
                    !source.matches(Regex("[A-Za-z0-9_-]+\\.preferences_pb")) ||
                        !target.matches(Regex("[A-Za-z0-9_-]+\\.preferences_pb")) ||
                        !source.endsWith(sourceSuffix) ||
                        target != source.removeSuffix(sourceSuffix) + targetSuffix
                }
            ) return@runCatching false

            manifest.all { (sourceName, targetName) ->
                val source = File(directory, sourceName)
                val target = File(directory, targetName)
                source.isFile && target.isFile &&
                    source.length() > 0 && source.length() == target.length() &&
                    !Files.isSymbolicLink(source.toPath()) &&
                    !Files.isSymbolicLink(target.toPath()) &&
                    digest(source).contentEquals(digest(target))
            }
        }.getOrDefault(false)
    }

    private fun digest(file: File): ByteArray {
        val hash = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val bytes = ByteArray(8192)
            while (true) {
                val size = input.read(bytes)
                if (size < 0) break
                hash.update(bytes, 0, size)
            }
        }
        return hash.digest()
    }
}
