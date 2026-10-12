package com.nuvio.tv

import java.io.File

/** Only AFR scratch files belong here. Never traverse the rest of the app cache. */
internal object ManagedProbeFiles {
    private val active = mutableSetOf<File>()
    private val probeName = Regex("afr_probe_-?[0-9]+\\.tmp")

    // Creation/registration and cleanup share a lock so cleanup cannot race a new probe.
    @Synchronized
    fun create(cacheDirectory: File): File =
        File.createTempFile("afr_probe_", ".tmp", cacheDirectory).also {
            active.add(it.canonicalFile)
        }

    @Synchronized
    fun release(file: File) {
        try {
            file.delete()
        } finally {
            active.remove(file.canonicalFile)
        }
    }

    @Synchronized
    fun clean(cacheDirectory: File, cutoffMillis: Long): Boolean {
        if (!cacheDirectory.exists()) return true
        val root = cacheDirectory.canonicalFile
        val files = root.listFiles() ?: return false
        var success = true
        for (file in files) {
            if (!probeName.matches(file.name)) continue
            // Reject symlinks, directories, and anything outside this exact cache root.
            val canonical = file.canonicalFile
            if (canonical != file.absoluteFile || canonical.parentFile != root) continue
            if (!file.isFile || canonical in active || file.lastModified() >= cutoffMillis) continue
            if (!file.delete() && file.exists()) success = false
        }
        return success
    }
}
