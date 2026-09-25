package com.clearline.sponsors

import android.content.Context
import com.clearline.core.ApprovedHistoryClient
import com.clearline.core.PublicResourceClient
import com.clearline.core.SponsorCredentialSettings

/** Create once per application process; app/ supplies read-only current authorization. */
class SponsorServices private constructor(
    val credentials: SponsorCredentialSettings,
    val resources: PublicResourceClient,
    val history: ApprovedHistoryClient,
    private val transport: OkHttpSponsorTransport,
) : AutoCloseable {
    override fun close() = transport.close()

    companion object {
        fun create(context: Context, authorization: SponsorAuthorization = DenySponsorAuthorization): SponsorServices {
            val app = context.applicationContext
            val vault = SponsorCredentialVault(
                AndroidCredentialBlobStore(app), CredentialCipher(AndroidCredentialKeySource()),
                AndroidSponsorConfigurationStore(app),
            )
            val transport = OkHttpSponsorTransport(vault)
            return SponsorServices(
                vault,
                NimbleResourceClient(transport, authorization, AndroidPublicSourceDns),
                RawTreeHistoryClient(transport, authorization, vault::configuration),
                transport,
            )
        }
    }
}
