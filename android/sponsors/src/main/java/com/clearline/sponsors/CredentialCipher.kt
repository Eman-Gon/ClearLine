package com.clearline.sponsors

import java.nio.ByteBuffer
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Key material is supplied by Android Keystore in production, never by app assets. */
internal interface CredentialKeySource {
    fun encryptionKey(service: String): SecretKey
    fun decryptionKey(service: String): SecretKey
    fun delete(service: String)
}

internal class CredentialCipher(private val keys: CredentialKeySource) {
    fun encrypt(service: String, plaintext: ByteArray): ByteArray {
        require(plaintext.size in 1..MAX_TOKEN_BYTES)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        // The provider generates a fresh nonce for each encryption.
        cipher.init(Cipher.ENCRYPT_MODE, keys.encryptionKey(service))
        require(cipher.iv.size == IV_BYTES)
        cipher.updateAAD(aad(service))
        val encrypted = cipher.doFinal(plaintext)
        return ByteBuffer.allocate(1 + IV_BYTES + encrypted.size)
            .put(VERSION).put(cipher.iv).put(encrypted).array()
    }

    fun decrypt(service: String, envelope: ByteArray): ByteArray {
        require(envelope.size in (1 + IV_BYTES + TAG_BYTES + 1)..MAX_ENVELOPE_BYTES)
        require(envelope[0] == VERSION)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, keys.decryptionKey(service), GCMParameterSpec(128, envelope.copyOfRange(1, 1 + IV_BYTES)))
        cipher.updateAAD(aad(service))
        return cipher.doFinal(envelope, 1 + IV_BYTES, envelope.size - 1 - IV_BYTES)
            .also { require(it.size in 1..MAX_TOKEN_BYTES) }
    }

    fun deleteKey(service: String) = keys.delete(service)

    private fun aad(service: String): ByteArray {
        require(service in setOf("nimble", "rawtree"))
        return "clearline.sponsors.credentials.v1:$service".toByteArray(Charsets.UTF_8)
    }

    companion object {
        const val MAX_TOKEN_BYTES = 4096
        const val MAX_ENVELOPE_BYTES = 1 + 12 + 16 + MAX_TOKEN_BYTES
        private const val IV_BYTES = 12
        private const val TAG_BYTES = 16
        private const val VERSION: Byte = 1
    }
}
