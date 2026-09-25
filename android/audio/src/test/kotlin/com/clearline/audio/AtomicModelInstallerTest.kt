package com.clearline.audio

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Test

class AtomicModelInstallerTest {
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    @Test fun verifiedAtomicInstallRejectsBadHashAndPreservesPriorArtifact() {
        val root = Files.createTempDirectory("model-install").toFile()
        try {
            val file = File(root, "model.bin"); val original = "pinned-model".toByteArray(); val installer = AtomicModelInstaller()
            original.inputStream().use { installer.install(it, file, original.size.toLong(), hash(original)) }
            assertTrue(installer.verified(file, original.size.toLong(), hash(original)))
            val bad = "changed-data".toByteArray()
            assertThrows(IOException::class.java) { bad.inputStream().use { installer.install(it, file, original.size.toLong(), hash(original)) } }
            assertArrayEquals(original, file.readBytes()); assertFalse(File(root, "model.bin.part").exists())
        } finally { root.deleteRecursively() }
    }
    @Test fun truncatedAndOversizeArtifactsNeverBecomeInstalled() {
        val root = Files.createTempDirectory("model-install").toFile()
        try {
            val file = File(root, "model.bin"); val installer = AtomicModelInstaller(); val valid = byteArrayOf(1, 2)
            for (invalid in listOf(byteArrayOf(1), byteArrayOf(1, 2, 3))) {
                assertThrows(IOException::class.java) { invalid.inputStream().use { installer.install(it, file, 2, hash(valid)) } }
                assertFalse(file.exists()); assertFalse(File(root, "model.bin.part").exists())
            }
        } finally { root.deleteRecursively() }
    }
    @Test fun interruptedInstallRemovesPart() {
        val root = Files.createTempDirectory("model-install").toFile()
        try {
            val file = File(root, "model.bin"); val input = ByteArray(8)
            assertThrows(IllegalStateException::class.java) { input.inputStream().use { AtomicModelInstaller().install(it, file, 8, hash(input)) { throw IllegalStateException("cancelled") } } }
            assertFalse(file.exists()); assertFalse(File(root, "model.bin.part").exists())
        } finally { root.deleteRecursively() }
    }
}
