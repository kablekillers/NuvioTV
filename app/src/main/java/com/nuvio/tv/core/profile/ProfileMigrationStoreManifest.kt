package com.nuvio.tv.core.profile

import java.io.File

/**
 * Converts the actual ProfileDataStoreFactory preview (primary preferences
 * and shadow backups) to a copy-only batch. This is not a complete account
 * migration: non-DataStore personal state must be inventoried separately.
 */
internal object ProfileMigrationStoreManifest {
    fun fromFactoryPreview(
        datastoreDirectory: File,
        preview: Map<String, String>,
        sourceId: Int,
        targetId: Int
    ): List<ProfileMigrationBatchCopy.Item> {
        require(datastoreDirectory.isDirectory) { "DataStore directory is unavailable" }
        require(sourceId in 2..6 && targetId in 2..6 && sourceId != targetId) { "Invalid profile remap" }
        require(preview.isNotEmpty()) { "Profile DataStore preview is empty" }
        val root = datastoreDirectory.canonicalFile.toPath()
        val items = preview.entries.sortedBy { it.key }.map { (sourceName, targetName) ->
            require(sourceName != targetName) { "Profile store cannot map to itself" }
            require(sourceName == File(sourceName).name &&
                targetName == File(targetName).name &&
                sourceName.isNotBlank() && targetName.isNotBlank()) {
                "Profile store names must be plain file names"
            }
            val backupSuffix = if (sourceName.endsWith(".bak")) ".bak" else ""
            val sourceSuffix = "_p$sourceId.preferences_pb" + backupSuffix
            val targetSuffix = "_p$targetId.preferences_pb" + backupSuffix
            require(sourceName.matches(Regex("[A-Za-z0-9_-]+[.]preferences_pb([.]bak)?")) &&
                targetName.matches(Regex("[A-Za-z0-9_-]+[.]preferences_pb([.]bak)?")) &&
                sourceName.endsWith(sourceSuffix) &&
                targetName == sourceName.removeSuffix(sourceSuffix) + targetSuffix) {
                "Profile store mapping does not match source and target IDs"
            }
            val source = File(datastoreDirectory, sourceName)
            val destination = File(datastoreDirectory, targetName)
            require(source.canonicalFile.toPath().parent == root &&
                destination.canonicalFile.toPath().parent == root) {
                "Profile store paths must remain inside DataStore directory"
            }
            ProfileMigrationBatchCopy.Item(source, destination)
        }
        ProfileMigrationBatchCopy.preflight(items)
        return items
    }
}
