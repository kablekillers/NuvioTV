package com.nuvio.tv.core.profile

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.security.MessageDigest

/**
 * Copy-only building block for the future migration transaction.
 * Never overwrites a destination or deletes a source. Not wired to activation.
 */
internal object ProfileMigrationCopyVerifier {
    fun copyAndVerify(source: File, destination: File): Boolean {
        val sourcePath = source.toPath()
        val destinationPath = destination.toPath()
        require(Files.isRegularFile(sourcePath, LinkOption.NOFOLLOW_LINKS)) {
            "Migration source must be a regular file"
        }
        require(!Files.exists(destinationPath, LinkOption.NOFOLLOW_LINKS)) {
            "Migration destination must not already exist"
        }
        require(sourcePath.toAbsolutePath().normalize() != destinationPath.toAbsolutePath().normalize()) {
            "Migration source and destination must differ"
        }
        require(Files.isDirectory(destinationPath.parent, LinkOption.NOFOLLOW_LINKS)) {
            "Migration destination directory must exist"
        }
        require(!Files.isSymbolicLink(destinationPath.parent)) {
            "Migration destination directory must not be a symbolic link"
        }
        // Detect source changes while copying rather than validating a moving target.
        val sourceDigestBeforeCopy = digest(source)
        // CREATE_NEW refuses to replace a destination created by another writer.
        Files.newInputStream(sourcePath).use { input ->
            Files.newOutputStream(
                destinationPath,
                java.nio.file.StandardOpenOption.CREATE_NEW,
                java.nio.file.StandardOpenOption.WRITE
            ).use { output ->
                input.copyTo(output)
                output.flush()
            }
        }
        // Verify the bytes we copied. A failed comparison must never be
        // mistaken for a successful migration or trigger source deletion.
        val sourceDigestAfterCopy = digest(source)
        val verified = sourceDigestBeforeCopy.contentEquals(sourceDigestAfterCopy) &&
            sourceDigestBeforeCopy.contentEquals(digest(destination))
        if (verified) {
            // Persist copied bytes before the journal can advance to VERIFIED.
            java.io.RandomAccessFile(destination, "rw").use { it.fd.sync() }
        }
        return verified
    }

    private fun digest(file: File): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest()
    }
}
