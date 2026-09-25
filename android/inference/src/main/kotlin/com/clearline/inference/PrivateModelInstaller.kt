package com.clearline.inference

import com.clearline.core.AppError
import com.clearline.core.ApprovedModelArtifact
import com.clearline.core.ClearLineException
import com.clearline.core.ErrorCode
import com.clearline.core.ModelId
import com.clearline.core.ModelIdentity
import com.clearline.core.ModelPhase
import com.clearline.core.ModelStatus
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Dedicated directory under Context.noBackupFilesDir; never external storage or APK assets.
 * The runtime serializes install/load/unload. This class also serializes its own file operations.
 * Content URIs are opened through ContentResolver by the composition root, never resolved as paths.
 */
class PrivateModelInstaller(
    private val privateDirectory: File,
    private val contentStream: (String) -> InputStream? = { null },
    private val approvedCatalog: List<ModelIdentity> = listOf(LiquidModelCatalog.DEFAULT),
    private val usableSpace: (File) -> Long = { it.usableSpace },
    private val download: (String) -> InputStream = { ModelArtifactHttps.open(it) },
) {
    private val mutex = Mutex()

    init {
        require(approvedCatalog.isNotEmpty())
        require(approvedCatalog.map { it.modelId }.distinct().size == approvedCatalog.size)
        require(approvedCatalog.map { it.filename }.distinct().size == approvedCatalog.size)
    }

    suspend fun install(
        artifact: ApprovedModelArtifact,
        onStatus: (ModelStatus) -> Unit = {},
    ): File = withContext(Dispatchers.IO) {
        mutex.withLock {
            val identity = approvedIdentity(artifact.identity)
            if (artifact.approvedAtMs <= 0) fail(ErrorCode.CONSENT_REQUIRED, "Model setup approval is required")
            validateSource(artifact)
            ensureDirectory()
            cleanPartialsUnlocked()
            verifiedFileUnlocked(identity, onStatus)?.let { return@withLock it }
            val target = target(identity)
            val part = File(privateDirectory, "${identity.filename}.part")
            val marker = marker(identity)
            val markerPart = File(privateDirectory, "${identity.filename}.verified.part")
            var promoted = false
            try {
                if (usableSpace(privateDirectory) < identity.sizeBytes + SPACE_RESERVE_BYTES) {
                    fail(ErrorCode.STORAGE_FULL, "Insufficient private storage for model setup")
                }
                onStatus(ModelStatus(identity.modelKind, ModelPhase.INSTALLING, identity))
                val digest = MessageDigest.getInstance("SHA-256")
                var written = 0L
                val source = if (URI(artifact.sourceUri).scheme == "content") {
                    contentStream(artifact.sourceUri) ?: fail(ErrorCode.INVALID_INPUT, "Cannot open the selected model file")
                } else download(artifact.sourceUri)
                source.use { input ->
                    FileOutputStream(part).use { output ->
                        val buffer = ByteArray(COPY_BUFFER_BYTES)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = input.read(buffer)
                            if (count == -1) break
                            if (count == 0) fail(ErrorCode.BAD_RESPONSE, "Model source did not make progress")
                            if (written > identity.sizeBytes - count) fail(ErrorCode.INVALID_INPUT, "Model file is larger than the approved artifact")
                            output.write(buffer, 0, count)
                            digest.update(buffer, 0, count)
                            written += count
                            onStatus(ModelStatus(identity.modelKind, ModelPhase.INSTALLING, identity, written))
                        }
                        output.fd.sync()
                    }
                }
                onStatus(ModelStatus(identity.modelKind, ModelPhase.VERIFYING, identity, written))
                currentCoroutineContext().ensureActive()
                if (written != identity.sizeBytes || digest.digest().hex() != identity.sha256) {
                    fail(ErrorCode.INVALID_INPUT, "Model size or SHA-256 does not match the approved artifact")
                }
                // Both files are on one filesystem. No fallback copy can expose a partial final file.
                FileOutputStream(markerPart).use { output ->
                    output.write(manifest(identity).toByteArray(Charsets.UTF_8))
                    output.fd.sync()
                }
                currentCoroutineContext().ensureActive()
                Files.move(part.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                promoted = true
                Files.move(markerPart.toPath(), marker.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                onStatus(ModelStatus(identity.modelKind, ModelPhase.INSTALLED, identity, written))
                target
            } catch (failure: Throwable) {
                // A completed model without its completion manifest is deliberately unavailable.
                if (promoted) { target.delete(); marker.delete() }
                if (failure is CancellationException) throw failure
                val safe = (failure as? ClearLineException)?.error
                    ?: AppError(ErrorCode.MODEL_FAILED, "Model setup failed; retry or choose a verified import", true)
                onStatus(ModelStatus(identity.modelKind, ModelPhase.FAILED, identity, error = safe))
                throw ClearLineException(safe)
            } finally {
                part.delete()
                markerPart.delete()
            }
        }
    }

    /** Re-hashes before every load. A previous completion marker is never enough by itself. */
    suspend fun verifiedFile(modelId: ModelId, onStatus: (ModelStatus) -> Unit = {}): File? =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                ensureDirectory()
                cleanPartialsUnlocked()
                val identity = approvedCatalog.singleOrNull { it.modelId == modelId }
                    ?: fail(ErrorCode.INVALID_INPUT, "Unapproved model identity")
                verifiedFileUnlocked(identity, onStatus)
            }
        }

    suspend fun cleanPartials() = withContext(Dispatchers.IO) {
        mutex.withLock { ensureDirectory(); cleanPartialsUnlocked() }
    }

    private suspend fun verifiedFileUnlocked(identity: ModelIdentity, onStatus: (ModelStatus) -> Unit): File? {
        val target = target(identity)
        val marker = marker(identity)
        if (!target.isFile || !marker.isFile || Files.isSymbolicLink(target.toPath()) || Files.isSymbolicLink(marker.toPath())) return null
        if (target.length() != identity.sizeBytes || marker.length() > MAX_MANIFEST_BYTES || marker.readText() != manifest(identity)) return null
        onStatus(ModelStatus(identity.modelKind, ModelPhase.VERIFYING, identity))
        val digest = MessageDigest.getInstance("SHA-256")
        var readBytes = 0L
        FileInputStream(target).use { input ->
            val buffer = ByteArray(COPY_BUFFER_BYTES)
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = input.read(buffer)
                if (count == -1) break
                if (count == 0 || readBytes > identity.sizeBytes - count) return null
                digest.update(buffer, 0, count)
                readBytes += count
            }
        }
        if (readBytes != identity.sizeBytes || digest.digest().hex() != identity.sha256) return null
        onStatus(ModelStatus(identity.modelKind, ModelPhase.INSTALLED, identity, identity.sizeBytes))
        return target
    }

    private fun approvedIdentity(identity: ModelIdentity): ModelIdentity =
        approvedCatalog.singleOrNull { it == identity }
            ?: fail(ErrorCode.INVALID_INPUT, "Model artifact is not in the approved catalog")

    private fun validateSource(artifact: ApprovedModelArtifact) {
        val uri = try { URI(artifact.sourceUri) } catch (_: Exception) {
            fail(ErrorCode.INVALID_INPUT, "Invalid model setup source")
        }
        val allowed = when (uri.scheme) {
            "content" -> !uri.authority.isNullOrBlank() && uri.userInfo == null && uri.fragment == null
            "https" -> artifact.sourceUri == LiquidModelCatalog.downloadUrl(artifact.identity)
            else -> false
        }
        if (!allowed) fail(ErrorCode.INVALID_INPUT, "Select a local document or the pinned HTTPS model download")
    }

    private fun ensureDirectory() {
        if ((!privateDirectory.isDirectory && !privateDirectory.mkdirs()) || Files.isSymbolicLink(privateDirectory.toPath())) {
            fail(ErrorCode.STORAGE_FULL, "Private model storage is unavailable")
        }
        // Caller must use noBackupFilesDir; canonical containment also rejects a substituted child path.
        if (privateDirectory.canonicalFile != privateDirectory.absoluteFile) {
            fail(ErrorCode.INVALID_INPUT, "Model directory must be an app-private canonical path")
        }
    }

    private fun cleanPartialsUnlocked() {
        privateDirectory.listFiles()?.filter { it.name.endsWith(".part") }?.forEach { file ->
            if (!file.delete()) fail(ErrorCode.STORAGE_FULL, "Interrupted model setup could not be cleaned up")
        }
    }

    private fun target(identity: ModelIdentity) = File(privateDirectory, identity.filename)
    private fun marker(identity: ModelIdentity) = File(privateDirectory, "${identity.filename}.verified")

    private fun manifest(identity: ModelIdentity) = listOf(
        "clearline-model-manifest-v1", identity.modelId.value, identity.repository, identity.revision,
        identity.filename, identity.sizeBytes.toString(), identity.sha256, identity.modelKind.name,
        identity.quantization, identity.templateIdentity, identity.language, identity.licenseReference,
    ).joinToString("\n", postfix = "\n")

    companion object {
        const val SPACE_RESERVE_BYTES = 64L * 1024 * 1024
        private const val COPY_BUFFER_BYTES = 256 * 1024
        private const val MAX_MANIFEST_BYTES = 8192
    }
}

/** Setup-only transport; no inference, credentials, private state, or automatic redirect trust. */
internal object ModelArtifactHttps {
    private val redirectHosts = setOf("huggingface.co", "us.aws.cdn.hf.co", "cas-bridge.xethub.hf.co")

    fun open(source: String): InputStream {
        var uri = URI(source)
        repeat(5) {
            validateRedirect(uri)
            val connection = uri.toURL().openConnection() as HttpsURLConnection
            try {
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 30_000
                connection.readTimeout = 30_000
                connection.setRequestProperty("Accept-Encoding", "identity")
                connection.setRequestProperty("User-Agent", "ClearLine-Android-Model-Setup/1")
                val code = connection.responseCode
                if (code in listOf(301, 302, 303, 307, 308)) {
                    val location = connection.getHeaderField("Location")
                        ?: fail(ErrorCode.BAD_RESPONSE, "Model download redirect is missing its destination")
                    uri = uri.resolve(location)
                    validateRedirect(uri)
                    connection.disconnect()
                } else {
                    if (code != 200) fail(ErrorCode.NETWORK_UNAVAILABLE, "Model download is unavailable", true)
                    return object : FilterInputStream(connection.inputStream) {
                        override fun close() { try { super.close() } finally { connection.disconnect() } }
                    }
                }
            } catch (failure: Throwable) {
                connection.disconnect()
                throw failure
            }
        }
        fail(ErrorCode.BAD_RESPONSE, "Too many model download redirects")
    }

    internal fun validateRedirect(uri: URI) {
        if (uri.scheme != "https" || uri.host !in redirectHosts || uri.userInfo != null ||
            uri.port !in listOf(-1, 443) || uri.fragment != null) {
            fail(ErrorCode.INVALID_INPUT, "Model download redirected outside the approved HTTPS hosts")
        }
    }
}

private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }
private fun fail(code: ErrorCode, message: String, retryable: Boolean = false): Nothing =
    throw ClearLineException(AppError(code, message, retryable))
