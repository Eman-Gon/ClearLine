package com.clearline.audio

import java.io.File
import java.io.InputStream
import java.io.IOException
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** Pure file operation, separate from authorization/URI opening. Never exposes an unverified target. */
internal class AtomicModelInstaller {
    fun install(input: InputStream, target: File, expectedBytes: Long, expectedSha256: String, progress: (Long) -> Unit = {}): File {
        require(expectedBytes > 0 && expectedSha256.matches(Regex("[0-9a-f]{64}")))
        check(target.parentFile!!.mkdirs() || target.parentFile!!.isDirectory)
        val part = File(target.parentFile, "${target.name}.part")
        try {
            if (target.parentFile!!.usableSpace < expectedBytes + 16L * 1024 * 1024) throw IOException("Insufficient model storage")
            val digest = MessageDigest.getInstance("SHA-256")
            var total = 0L
            FileOutputStream(part).use { output ->
                val buffer = ByteArray(65536)
                while (true) {
                    val count = input.read(buffer); if (count == -1) break
                    total += count
                    if (total > expectedBytes) throw IOException("Model exceeds pinned size")
                    digest.update(buffer, 0, count); output.write(buffer, 0, count); progress(total)
                }
                output.fd.sync()
            }
            val sha = digest.digest().joinToString("") { "%02x".format(it) }
            if (total != expectedBytes || sha != expectedSha256) throw IOException("Model integrity check failed")
            Files.move(part.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            return target
        } finally { part.delete() }
    }
    fun verified(target: File, expectedBytes: Long, expectedSha256: String) = target.isFile && target.length() == expectedBytes && PcmWave.sha256(target) == expectedSha256
}
