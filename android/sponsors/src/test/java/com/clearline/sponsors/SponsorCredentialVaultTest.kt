package com.clearline.sponsors

import com.clearline.core.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

class SponsorCredentialVaultTest {
    private class MemoryBlobs : CredentialBlobStore {
        val data = mutableMapOf<String, ByteArray>()
        override fun read(service: String) = data[service]?.copyOf()
        override fun write(service: String, envelope: ByteArray) { data[service] = envelope.copyOf() }
        override fun delete(service: String) { data.remove(service) }
    }
    private class MemoryKeys : CredentialKeySource {
        val keys = mutableMapOf<String, SecretKey>()
        override fun encryptionKey(service: String) = keys.getOrPut(service) { KeyGenerator.getInstance("AES").apply { init(256) }.generateKey() }
        override fun decryptionKey(service: String) = checkNotNull(keys[service])
        override fun delete(service: String) { keys.remove(service) }
    }
    private class MemoryConfig : SponsorConfigurationStore {
        var configuration = SponsorConfiguration()
        override fun read() = configuration
        override fun write(configuration: SponsorConfiguration) { this.configuration = configuration }
    }

    @Test fun credentialPersistsEncryptedAcrossVaultReconstructionAndClearsIndependently() = runTest {
        val blobs = MemoryBlobs(); val keys = MemoryKeys(); val config = MemoryConfig()
        val first = SponsorCredentialVault(blobs, CredentialCipher(keys), config)
        for (sponsor in Sponsor.entries) SecretValue("synthetic-${sponsor.name}-key".toCharArray()).use { first.setCredential(sponsor, it) }
        first.setConfiguration(SponsorConfiguration(nimbleEnabled = true, rawTreeEnabled = true, rawTreeDatabase = "default"))
        assertFalse(blobs.data.values.any { String(it).contains("synthetic-") })
        val restored = SponsorCredentialVault(blobs, CredentialCipher(keys), config)
        assertTrue(restored.observeStatus().first().all { it.state == CredentialState.CONFIGURED })
        var reference: ByteArray? = null
        restored.withCredential(Sponsor.NIMBLE) { bytes, current -> current(); reference = bytes; assertEquals("synthetic-NIMBLE-key", String(bytes)) }
        assertTrue(reference!!.all { it == 0.toByte() })
        restored.clearCredential(Sponsor.NIMBLE)
        assertEquals(CredentialState.MISSING, restored.observeStatus().first().first { it.sponsor == Sponsor.NIMBLE }.state)
        restored.withCredential(Sponsor.RAWTREE) { bytes, current -> current(); assertEquals("synthetic-RAWTREE-key", String(bytes)) }
        assertFalse(blobs.data.containsKey("nimble")); assertFalse(keys.keys.containsKey("nimble"))
    }

    @Test fun missingKeyAndCorruptTokenProduceRedactedErrorWithoutRegeneration() = runTest {
        val blobs = MemoryBlobs(); val keys = MemoryKeys(); val config = MemoryConfig()
        val first = SponsorCredentialVault(blobs, CredentialCipher(keys), config)
        SecretValue("never-log-this-token".toCharArray()).use { first.setCredential(Sponsor.NIMBLE, it) }
        keys.delete("nimble")
        val restored = SponsorCredentialVault(blobs, CredentialCipher(keys), config)
        val status = restored.observeStatus().first().first { it.sponsor == Sponsor.NIMBLE }
        assertEquals(CredentialState.ERROR, status.state)
        assertFalse(status.toString().contains("never-log"))
        assertFalse(keys.keys.containsKey("nimble"))
    }

    @Test fun disabledServiceCannotReadTokenAndCancellationWipesTemporaryCopy() = runTest {
        val vault = SponsorCredentialVault(MemoryBlobs(), CredentialCipher(MemoryKeys()), MemoryConfig())
        SecretValue("synthetic-secret".toCharArray()).use { vault.setCredential(Sponsor.NIMBLE, it) }
        try { vault.withCredential(Sponsor.NIMBLE) { _, _ -> fail("Disabled service must not dispatch") }; fail("Expected denial") }
        catch (error: ClearLineException) { assertEquals(ErrorCode.CONSENT_REQUIRED, error.error.code) }
        vault.setConfiguration(SponsorConfiguration(nimbleEnabled = true))
        var reference: ByteArray? = null
        try { vault.withCredential(Sponsor.NIMBLE) { bytes, _ -> reference = bytes; throw CancellationException("test") } }
        catch (_: CancellationException) { }
        assertTrue(reference!!.all { it == 0.toByte() })
    }

    @Test fun clearReplacementAndDisableInvalidatePendingCredentialCopies() = runTest {
        val vault = SponsorCredentialVault(MemoryBlobs(), CredentialCipher(MemoryKeys()), MemoryConfig())
        suspend fun configure() {
            SecretValue("synthetic-secret".toCharArray()).use { vault.setCredential(Sponsor.NIMBLE, it) }
            vault.setConfiguration(SponsorConfiguration(nimbleEnabled = true))
        }
        for (change in 0..2) {
            configure()
            var reference: ByteArray? = null
            try {
                vault.withCredential(Sponsor.NIMBLE) { bytes, current ->
                    reference = bytes
                    current()
                    when (change) {
                        0 -> vault.clearCredential(Sponsor.NIMBLE)
                        1 -> SecretValue("replacement".toCharArray()).use { vault.setCredential(Sponsor.NIMBLE, it) }
                        else -> vault.setConfiguration(SponsorConfiguration())
                    }
                    current()
                    fail("Changed credential/settings must invalidate the pending copy")
                }
                fail("Expected a stale credential denial")
            } catch (error: ClearLineException) { assertEquals(ErrorCode.STALE_REVISION, error.error.code) }
            assertTrue(reference!!.all { it == 0.toByte() })
        }
    }
}
