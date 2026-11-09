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
            val primaryManifest = manifest.filterKeys { !it.endsWith(".bak") }
            if (primaryManifest.isEmpty() ||
                allSourceFiles != primaryManifest.keys ||
                allTargetFiles != primaryManifest.values.toSet()
            ) return@runCatching false
            if (manifest.any { (source, target) ->
                    val backup = source.endsWith(".bak")
                    val suffix = sourceSuffix + if (backup) ".bak" else ""
                    val targetSuffixForFile = targetSuffix + if (backup) ".bak" else ""
                    !source.matches(Regex("[A-Za-z0-9_-]+\\.preferences_pb(\\.bak)?")) ||
                        !target.matches(Regex("[A-Za-z0-9_-]+\\.preferences_pb(\\.bak)?")) ||
                        !source.endsWith(suffix) ||
                        target != source.removeSuffix(suffix) + targetSuffixForFile
                }
            ) return@runCatching false
            // The read-only backup plan rejects unlisted sources and occupied
            // target backups; target backups created by this migration are
            // expected now, so compare both full inventories with the manifest.
            val sourceBackupSuffix = "$sourceSuffix.bak"
            val targetBackupSuffix = "$targetSuffix.bak"
            val listed = directory.listFiles() ?: return@runCatching false
            val sourceBackups = listed.filter { it.name.endsWith(sourceBackupSuffix) }
                .map { it.name }.toSet()
            val targetBackups = listed.filter { it.name.endsWith(targetBackupSuffix) }
                .map { it.name }.toSet()
            val backupManifest = manifest.filterKeys { it.endsWith(".bak") }
            if (sourceBackups != backupManifest.keys ||
                targetBackups != backupManifest.values.toSet() ||
                backupManifest.any { (from, to) ->
                    val primarySource = from.removeSuffix(".bak")
                    val primaryTarget = to.removeSuffix(".bak")
                    primaryManifest[primarySource] != primaryTarget
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
