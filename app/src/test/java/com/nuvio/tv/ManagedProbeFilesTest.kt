package com.nuvio.tv

import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ManagedProbeFilesTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun manualCleanupPreservesActiveProbesAndOtherAppData() {
        val root = temporary.newFolder()
        val active = ManagedProbeFiles.create(root)
        val abandoned = File(root, "afr_probe_123.tmp").apply { writeText("probe") }
        val protected = listOf("subtitles", "nuvio-engine", "vod_cache", "image_cache", "plugin_code")
            .map { File(root, it).apply { mkdir(); resolve("afr_probe_456.tmp").writeText("keep") } }
        val unknown = File(root, "settings.tmp").apply { writeText("keep") }
        try {
            assertTrue(ManagedProbeFiles.clean(root, Long.MAX_VALUE))
            assertFalse(abandoned.exists())
            assertTrue(active.exists())
            assertTrue(unknown.exists())
            protected.forEach { assertTrue(it.resolve("afr_probe_456.tmp").exists()) }
        } finally {
            ManagedProbeFiles.release(active)
        }
        assertFalse(active.exists())
    }

    @Test fun smartCleanupKeepsRecentAndFutureFiles() {
        val root = temporary.newFolder()
        val cutoff = System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000
        val old = File(root, "afr_probe_1.tmp").apply { writeText("old"); assertTrue(setLastModified(cutoff - 1000)) }
        val recent = File(root, "afr_probe_2.tmp").apply { writeText("recent") }
        val future = File(root, "afr_probe_3.tmp").apply { writeText("future"); assertTrue(setLastModified(System.currentTimeMillis() + 60_000)) }
        assertTrue(ManagedProbeFiles.clean(root, cutoff))
        assertFalse(old.exists())
        assertTrue(recent.exists())
        assertTrue(future.exists())
    }

    @Test fun cleanupNeverFollowsSymlinksOrDeletesDirectories() {
        val root = temporary.newFolder()
        val outside = temporary.newFile().apply { writeText("keep") }
        val link = File(root, "afr_probe_1.tmp")
        Files.createSymbolicLink(link.toPath(), outside.toPath())
        val directory = File(root, "afr_probe_2.tmp").apply { mkdir(); resolve("keep").writeText("keep") }
        assertTrue(ManagedProbeFiles.clean(root, Long.MAX_VALUE))
        assertTrue(outside.exists())
        assertTrue(Files.isSymbolicLink(link.toPath()))
        assertTrue(directory.resolve("keep").exists())
    }
}
