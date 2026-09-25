package com.clearline.inference

import com.clearline.core.ApprovedModelArtifact
import com.clearline.core.ClearLineException
import com.clearline.core.ErrorCode
import com.clearline.core.ModelPhase
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Tiny fixture bytes exercise integrity/failure behavior; they are never a runnable model. */
class PrivateModelInstallerTest {
    @get:Rule val temporary = TemporaryFolder()
    private val bytes = "GGUF test fixture only".toByteArray()
    private val identity = LiquidModelCatalog.DEFAULT.copy(
        sizeBytes = bytes.size.toLong(),
        sha256 = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) },
    )
    private val artifact = ApprovedModelArtifact(identity, "content://test/model", 1)
    private val root get() = temporary.root.canonicalFile
    private fun installer(
        data: () -> InputStream = { ByteArrayInputStream(bytes) },
        space: Long = Long.MAX_VALUE,
    ) = PrivateModelInstaller(root, { data() }, listOf(identity), { space })

    @Test fun importRequiresDigestAndManifestThenSurvivesInstallerRecreation() = runBlocking {
        val phases = mutableListOf<ModelPhase>()
        val file = installer().install(artifact) { phases += it.phase }
        assertArrayEquals(bytes, file.readBytes())
        assertTrue(phases.contains(ModelPhase.INSTALLING))
        assertTrue(phases.contains(ModelPhase.VERIFYING))
        assertEquals(ModelPhase.INSTALLED, phases.last())
        assertFalse(phases.contains(ModelPhase.READY))
        assertEquals(file, installer().verifiedFile(identity.modelId))
        assertFalse(root.listFiles()!!.any { it.name.endsWith(".part") })
    }

    @Test fun sameSizeTamperingIsNotInstalled() = runBlocking {
        val file = installer().install(artifact)
        file.writeBytes(ByteArray(bytes.size) { 1 })
        assertNull(installer().verifiedFile(identity.modelId))
    }

    @Test fun finalFileWithoutCompletionManifestIsNotInstalled() = runBlocking {
        File(root, identity.filename).writeBytes(bytes)
        assertNull(installer().verifiedFile(identity.modelId))
    }

    @Test fun manipulatedCompletionManifestIsNotInstalled() = runBlocking {
        installer().install(artifact)
        File(root, "${identity.filename}.verified").appendText("changed")
        assertNull(installer().verifiedFile(identity.modelId))
    }

    @Test fun bothShortAndOversizedStreamsAreRejectedWithoutFinalFile() = runBlocking {
        for (input in listOf(bytes.dropLast(1).toByteArray(), bytes + byteArrayOf(1))) {
            assertFailure(ErrorCode.INVALID_INPUT) { installer({ ByteArrayInputStream(input) }).install(artifact) }
            assertNull(installer().verifiedFile(identity.modelId))
            assertTrue(root.listFiles()!!.isEmpty())
        }
    }

    @Test fun wrongDigestIsRejectedWithoutFinalFile() = runBlocking {
        assertFailure(ErrorCode.INVALID_INPUT) { installer({ ByteArrayInputStream(ByteArray(bytes.size)) }).install(artifact) }
        assertTrue(root.listFiles()!!.isEmpty())
    }

    @Test fun lowSpaceRejectsBeforeOpeningSource() = runBlocking {
        var opened = false
        assertFailure(ErrorCode.STORAGE_FULL) {
            installer({ opened = true; ByteArrayInputStream(bytes) }, bytes.size + PrivateModelInstaller.SPACE_RESERVE_BYTES - 1).install(artifact)
        }
        assertFalse(opened)
    }

    @Test fun invalidApprovalOrChangedIdentityCannotOpenSource() = runBlocking {
        var opened = false
        val installer = installer({ opened = true; ByteArrayInputStream(bytes) })
        assertFailure(ErrorCode.CONSENT_REQUIRED) { installer.install(artifact.copy(approvedAtMs = 0)) }
        assertFailure(ErrorCode.INVALID_INPUT) { installer.install(artifact.copy(identity = identity.copy(quantization = "Q8_0"))) }
        assertFalse(opened)
    }

    @Test fun unapprovedSourcesCannotOpenDownloadOrContentResolver() = runBlocking {
        var opened = false
        val installer = PrivateModelInstaller(root, { opened = true; ByteArrayInputStream(bytes) }, listOf(identity),
            { Long.MAX_VALUE }, { opened = true; ByteArrayInputStream(bytes) })
        for (source in listOf("file:///sdcard/model.gguf", "http://huggingface.co/model", "https://example.com/model.gguf",
            LiquidModelCatalog.downloadUrl(identity).replace(identity.revision, "main"), "content:/missing-authority")) {
            assertFailure(ErrorCode.INVALID_INPUT) { installer.install(artifact.copy(sourceUri = source)) }
        }
        assertFalse(opened)
    }

    @Test fun exactPinnedDownloadIsAcceptedAndVerified() = runBlocking {
        var actual = ""
        val installer = PrivateModelInstaller(root, approvedCatalog = listOf(identity), usableSpace = { Long.MAX_VALUE },
            download = { actual = it; ByteArrayInputStream(bytes) })
        installer.install(artifact.copy(sourceUri = LiquidModelCatalog.downloadUrl(identity)))
        assertEquals(LiquidModelCatalog.downloadUrl(identity), actual)
        assertNotNull(installer.verifiedFile(identity.modelId))
    }

    @Test fun interruptedStreamIsCleanedAndHasSafeError() = runBlocking {
        assertFailure(ErrorCode.MODEL_FAILED) {
            installer({ object : InputStream() { override fun read(): Int = throw IOException("sensitive source details") } }).install(artifact)
        }
        assertTrue(root.listFiles()!!.isEmpty())
    }

    @Test fun cancellationPreservedAndPartialFileRemoved() = runBlocking {
        try {
            installer({ object : InputStream() { override fun read(): Int = throw CancellationException("stop") } }).install(artifact)
            fail("Expected cancellation")
        } catch (_: CancellationException) { }
        assertTrue(root.listFiles()!!.isEmpty())
    }

    @Test fun orphanPartialsAreCleanedAndNeverCountAsInstalled() = runBlocking {
        File(root, "${identity.filename}.part").writeBytes(bytes)
        File(root, "${identity.filename}.verified.part").writeText("unfinished")
        assertNull(installer().verifiedFile(identity.modelId))
        assertTrue(root.listFiles()!!.isEmpty())
    }

    @Test fun redirectsStayOnDeclaredHttpsHosts() {
        ModelArtifactHttps.validateRedirect(URI("https://us.aws.cdn.hf.co/path?signature=public-model-download"))
        for (url in listOf("http://huggingface.co/path", "https://huggingface.co.evil.example/path", "https://127.0.0.1/path",
            "https://user@huggingface.co/path", "https://huggingface.co:8443/path", "https://huggingface.co/path#fragment")) {
            try { ModelArtifactHttps.validateRedirect(URI(url)); fail("Unexpected allowed redirect") }
            catch (error: ClearLineException) { assertEquals(ErrorCode.INVALID_INPUT, error.error.code) }
        }
    }

    private suspend fun assertFailure(expected: ErrorCode, block: suspend () -> Unit) {
        try { block(); fail("Expected $expected") }
        catch (error: ClearLineException) {
            assertEquals(expected, error.error.code)
            assertFalse(error.message!!.contains("sensitive source details"))
        }
    }
}
