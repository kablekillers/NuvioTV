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
        require(Files.isDirectory(destinationPath.parent, LinkOption.NOFOLLOW_LINKS)) {
            "Migration destination directory must exist"
        }
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
        return digest(source) contentEquals digest(destination)
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
