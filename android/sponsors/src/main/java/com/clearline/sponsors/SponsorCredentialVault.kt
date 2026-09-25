package com.clearline.sponsors

import com.clearline.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong

internal interface SponsorConfigurationStore {
    fun read(): SponsorConfiguration
    fun write(configuration: SponsorConfiguration)
}

/** No token-reading method is exposed through SponsorCredentialSettings. */
internal class SponsorCredentialVault(
    private val blobs: CredentialBlobStore,
    private val cipher: CredentialCipher,
    private val configurations: SponsorConfigurationStore,
) : SponsorCredentialSettings, CredentialProvider {
    private val mutex = Mutex()
    private val credentialVersions = Sponsor.entries.associateWith { AtomicLong() }
    private var initialized = false
    private val statuses = MutableStateFlow(Sponsor.entries.map { CredentialStatus(it, CredentialState.MISSING) })
    private val configurationState = MutableStateFlow(SponsorConfiguration())

    override fun observeStatus(): Flow<List<CredentialStatus>> = flow { initialize(); emitAll(statuses) }
    override fun observeConfiguration(): Flow<SponsorConfiguration> = flow { initialize(); emitAll(configurationState) }

    suspend fun configuration(): SponsorConfiguration { initialize(); return configurationState.value }

    private suspend fun initialize() = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (initialized) return@withLock
            try { configurationState.value = configurations.read() }
            catch (_: Exception) {
                // Corrupt configuration disables all network operations.
                configurationState.value = SponsorConfiguration()
                statuses.value = Sponsor.entries.map { CredentialStatus(it, CredentialState.ERROR, vaultError()) }
                initialized = true
                return@withLock
            }
            statuses.value = Sponsor.entries.map { service ->
                try {
                    val bytes = blobs.read(service.id())
                    if (bytes == null) CredentialStatus(service, CredentialState.MISSING)
                    else {
                        val clear = cipher.decrypt(service.id(), bytes)
                        try { validateToken(clear); CredentialStatus(service, CredentialState.CONFIGURED) }
                        finally { clear.fill(0) }
                    }
                } catch (_: Exception) { CredentialStatus(service, CredentialState.ERROR, vaultError()) }
            }
            initialized = true
        }
    }

    override suspend fun setCredential(service: Sponsor, value: SecretValue) {
        initialize()
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val bytes = value.useSecret { chars ->
                    if (chars.size !in 1..CredentialCipher.MAX_TOKEN_BYTES || chars.any { it.code !in 33..126 })
                        sponsorFailure(ErrorCode.INVALID_INPUT, "Enter a bounded sponsor token without whitespace.")
                    ByteArray(chars.size) { chars[it].code.toByte() }
                }
                try {
                    credentialVersions.getValue(service).incrementAndGet()
                    blobs.write(service.id(), cipher.encrypt(service.id(), bytes))
                    setStatus(CredentialStatus(service, CredentialState.CONFIGURED))
                } catch (error: Exception) {
                    setStatus(CredentialStatus(service, CredentialState.ERROR, vaultError()))
                    sponsorFailure(ErrorCode.UNAVAILABLE, "Sponsor credential could not be stored.")
                } finally { bytes.fill(0) }
            }
        }
    }

    override suspend fun clearCredential(service: Sponsor) {
        initialize()
        withContext(Dispatchers.IO) {
            mutex.withLock {
                try {
                    credentialVersions.getValue(service).incrementAndGet()
                    // Deleting the encryption key also invalidates any leftover ciphertext.
                    cipher.deleteKey(service.id())
                    blobs.delete(service.id())
                    setStatus(CredentialStatus(service, CredentialState.MISSING))
                } catch (_: Exception) {
                    setStatus(CredentialStatus(service, CredentialState.ERROR, vaultError()))
                    sponsorFailure(ErrorCode.UNAVAILABLE, "Sponsor credential could not be cleared.")
                }
            }
        }
    }

    override suspend fun setConfiguration(configuration: SponsorConfiguration) {
        initialize()
        if (configuration.rawTreeEnabled && configuration.rawTreeDatabase == null)
            sponsorFailure(ErrorCode.INVALID_INPUT, "Select a RawTree database before enabling export.")
        withContext(Dispatchers.IO) {
            mutex.withLock {
                try {
                    val old = configurationState.value
                    if (old.nimbleEnabled != configuration.nimbleEnabled) credentialVersions.getValue(Sponsor.NIMBLE).incrementAndGet()
                    if (old.rawTreeEnabled != configuration.rawTreeEnabled || old.rawTreeDatabase != configuration.rawTreeDatabase)
                        credentialVersions.getValue(Sponsor.RAWTREE).incrementAndGet()
                    configurations.write(configuration)
                    configurationState.value = configuration
                }
                catch (_: Exception) { sponsorFailure(ErrorCode.UNAVAILABLE, "Sponsor configuration could not be stored.") }
            }
        }
    }

    override suspend fun <T> withCredential(service: Sponsor, block: suspend (ByteArray, () -> Unit) -> T): T {
        initialize()
        // Keep the reference outside withContext so prompt cancellation when
        // returning from IO also wipes a successfully decrypted temporary copy.
        var token: ByteArray? = null
        try {
            val version = withContext(Dispatchers.IO) {
                mutex.withLock {
                    val config = configurationState.value
                    if ((service == Sponsor.NIMBLE && !config.nimbleEnabled) || (service == Sponsor.RAWTREE && !config.rawTreeEnabled))
                        sponsorFailure(ErrorCode.CONSENT_REQUIRED, "This sponsor is disabled in settings.")
                    try {
                        val encrypted = blobs.read(service.id()) ?: sponsorFailure(ErrorCode.UNAVAILABLE, "Sponsor credential is missing.")
                        val decrypted = cipher.decrypt(service.id(), encrypted)
                        try { validateToken(decrypted); token = decrypted; credentialVersions.getValue(service).get() }
                        catch (error: Exception) { decrypted.fill(0); throw error }
                    } catch (error: ClearLineException) { throw error }
                    catch (_: Exception) {
                        setStatus(CredentialStatus(service, CredentialState.ERROR, vaultError()))
                        sponsorFailure(ErrorCode.UNAVAILABLE, "Sponsor credential cannot be read. Clear and re-enter it.")
                    }
                }
            }
            return block(checkNotNull(token)) {
                if (credentialVersions.getValue(service).get() != version)
                    sponsorFailure(ErrorCode.STALE_REVISION, "Sponsor settings or credential changed before dispatch.")
            }
        } finally { token?.fill(0) }
    }

    private fun setStatus(status: CredentialStatus) { statuses.value = statuses.value.map { if (it.sponsor == status.sponsor) status else it } }
    private fun validateToken(bytes: ByteArray) {
        require(bytes.size in 1..CredentialCipher.MAX_TOKEN_BYTES && bytes.all { it.toInt() in 33..126 })
    }
    private fun vaultError() = AppError(ErrorCode.UNAVAILABLE, "Credential storage needs attention; clear and re-enter the token.")
    private fun Sponsor.id() = name.lowercase(java.util.Locale.ROOT)
}
