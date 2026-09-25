package com.clearline.sponsors

import org.junit.Assert.*
import org.junit.Test
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

class CredentialCipherTest {
    private class MemoryKeys : CredentialKeySource {
        val keys = mutableMapOf<String, SecretKey>()
        override fun encryptionKey(service: String) = keys.getOrPut(service) {
            KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        }
        override fun decryptionKey(service: String) = checkNotNull(keys[service])
        override fun delete(service: String) { keys.remove(service) }
    }

    @Test fun repeatedWritesUseDistinctNoncesAndRoundTrip() {
        val cipher = CredentialCipher(MemoryKeys())
        val token = "synthetic-test-key".toByteArray()
        val first = cipher.encrypt("nimble", token)
        val second = cipher.encrypt("nimble", token)
        assertFalse(first.contentEquals(second))
        assertFalse(String(first).contains("synthetic-test-key"))
        assertArrayEquals(token, cipher.decrypt("nimble", first))
        assertArrayEquals(token, cipher.decrypt("nimble", second))
    }

    @Test fun tamperingAndCrossServiceBlobSwapsFailAuthentication() {
        val keys = MemoryKeys()
        val cipher = CredentialCipher(keys)
        val blob = cipher.encrypt("nimble", "synthetic-key".toByteArray())
        keys.keys["rawtree"] = keys.keys.getValue("nimble") // Isolate AAD protection.
        assertThrows(Exception::class.java) { cipher.decrypt("rawtree", blob) }
        blob[blob.lastIndex] = (blob.last().toInt() xor 1).toByte()
        assertThrows(Exception::class.java) { cipher.decrypt("nimble", blob) }
    }

    @Test fun deletingKeyMakesExistingCiphertextUnreadable() {
        val cipher = CredentialCipher(MemoryKeys())
        val blob = cipher.encrypt("nimble", "synthetic-key".toByteArray())
        cipher.deleteKey("nimble")
        assertThrows(Exception::class.java) { cipher.decrypt("nimble", blob) }
    }

    @Test fun oversizeAndUnsupportedEnvelopeRejected() {
        val cipher = CredentialCipher(MemoryKeys())
        assertThrows(IllegalArgumentException::class.java) { cipher.encrypt("nimble", ByteArray(4097)) }
        val blob = cipher.encrypt("nimble", byteArrayOf(1))
        blob[0] = 99
        assertThrows(IllegalArgumentException::class.java) { cipher.decrypt("nimble", blob) }
    }
}
