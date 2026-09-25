package com.clearline.sponsors

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import com.clearline.core.SponsorConfiguration
import kotlinx.serialization.json.Json

internal class AndroidCredentialKeySource(private val namespace: String = "com.clearline.sponsors") : CredentialKeySource {
    private val store by lazy { KeyStore.getInstance("AndroidKeyStore").apply { load(null) } }
    private fun alias(service: String): String {
        require(service in setOf("nimble", "rawtree"))
        require(namespace.matches(Regex("[A-Za-z0-9_.-]{1,120}")))
        return "$namespace.$service.v1"
    }

    override fun encryptionKey(service: String): SecretKey {
        val alias = alias(service)
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build())
        }.generateKey()
    }

    override fun decryptionKey(service: String): SecretKey =
        store.getKey(alias(service), null) as? SecretKey ?: error("Credential encryption key is unavailable")

    override fun delete(service: String) = store.deleteEntry(alias(service))
}

internal class AndroidSponsorConfigurationStore(context: Context) : SponsorConfigurationStore {
    private val path = File(context.applicationContext.noBackupFilesDir, "sponsor_settings.json")
    override fun read(): SponsorConfiguration = if (!path.exists() && !File(path.path + ".bak").exists()) SponsorConfiguration() else {
        AtomicFile(path).openRead().use { Json.decodeFromString<SponsorConfiguration>(it.readBytesBounded(4096).toString(Charsets.UTF_8)) }
    }
    override fun write(configuration: SponsorConfiguration) {
        val atomic = AtomicFile(path)
        val stream = atomic.startWrite()
        try {
            stream.write(Json.encodeToString(SponsorConfiguration.serializer(), configuration).toByteArray())
            atomic.finishWrite(stream)
        } catch (error: Exception) { atomic.failWrite(stream); throw error }
    }
}

/** Ciphertext resides only under Context.noBackupFilesDir, never preferences/assets. */
internal class AndroidCredentialBlobStore(context: Context, namespace: String = "sponsor_credentials") : CredentialBlobStore {
    init { require(namespace.matches(Regex("[A-Za-z0-9_-]{1,100}"))) }
    private val directory = File(context.applicationContext.noBackupFilesDir, namespace)

    private fun file(service: String): AtomicFile {
        require(service in setOf("nimble", "rawtree"))
        check(directory.isDirectory || directory.mkdirs())
        return AtomicFile(File(directory, "$service.enc"))
    }

    override fun read(service: String): ByteArray? {
        val file = file(service)
        return try {
            file.openRead().use { stream ->
                val bytes = stream.readBytesBounded(CredentialCipher.MAX_ENVELOPE_BYTES)
                require(bytes.isNotEmpty())
                bytes
            }
        } catch (error: java.io.FileNotFoundException) {
            val base = file.baseFile
            if (base.exists() || File(base.path + ".bak").exists() || File(base.path + ".new").exists()) throw error
            null
        }
    }

    override fun write(service: String, envelope: ByteArray) {
        require(envelope.size <= CredentialCipher.MAX_ENVELOPE_BYTES)
        val file = file(service)
        val stream = file.startWrite()
        try {
            stream.write(envelope)
            file.finishWrite(stream)
        } catch (failure: Exception) {
            file.failWrite(stream)
            throw failure
        }
    }

    override fun delete(service: String) = file(service).delete()
}
