package com.nuvio.tv.core.profile

import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

/**
 * Standalone copy-only migration primitive. This is deliberately not wired to
 * profile activation: callers must first quiesce all profile stores, verify
 * account ownership and persist a recoverable migration journal.
 *
 * Source files are never deleted. On failure, only destinations created by
 * this invocation are removed. Existing destinations always block the copy.
 */
internal object ProfileFileCopyTransaction {
    data class Result(val copiedFileNames: List<String>)

    fun copy(directory: File, manifest: Map<String, String>): Result {
        require(manifest.isNotEmpty()) { "Empty migration manifest" }
        require(manifest.values.size == manifest.values.toSet().size) {
            "Duplicate migration destinations"
        }
        val canonicalDirectory = directory.canonicalFile
        require(canonicalDirectory.isDirectory) { "DataStore directory does not exist" }
        val pairs = manifest.map { (from, to) ->
            require(from.matches(Regex("[A-Za-z0-9_-]+\\.preferences_pb")) &&
                to.matches(Regex("[A-Za-z0-9_-]+\\.preferences_pb"))) {
                "Unsafe DataStore file name"
            }
            val source = File(canonicalDirectory, from)
            val destination = File(canonicalDirectory, to)
            require(source.canonicalFile.parentFile == canonicalDirectory &&
                destination.canonicalFile.parentFile == canonicalDirectory) {
                "Profile store path escapes the DataStore directory"
            }
            source to destination
        }
        require(pairs.all { (source, destination) ->
            source.isFile && source.length() > 0L && !destination.exists() &&
                source.name != destination.name
        }) { "Missing source, self-copy, or occupied destination" }
        val sourceDigests = pairs.associate { (source, _) ->
            source.name to digest(source)
        }

        val created = mutableListOf<File>()
        try {
            for ((source, destination) in pairs) {
                // CREATE_NEW prevents clobbering files that appear after preflight.
                java.nio.file.Files.newOutputStream(
                    destination.toPath(),
                    java.nio.file.StandardOpenOption.CREATE_NEW,
                    java.nio.file.StandardOpenOption.WRITE
                ).use { output ->
                    created.add(destination)
                    FileInputStream(source).use { input -> input.copyTo(output) }
                    output.flush()
                }
                require(source.length() == destination.length() &&
                    sourceDigests.getValue(source.name).contentEquals(digest(destination)) &&
                    sourceDigests.getValue(source.name).contentEquals(digest(source))) {
                    "Profile copy verification failed or source changed during transfer"
                }
                // A copy that survives process death must have durable file contents.
                java.io.RandomAccessFile(destination, "rw").use { it.fd.sync() }
            }
            return Result(created.map { it.name })
        } catch (failure: Exception) {
            created.asReversed().forEach { it.delete() }
            throw failure
        }
    }

    private fun digest(file: File): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                md.update(buffer, 0, read)
            }
        }
        return md.digest()
    }
}
