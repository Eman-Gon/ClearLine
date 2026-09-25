package com.clearline.sponsors

import com.clearline.core.*

/**
 * Implement with current read-only workflow state. An Approved DTO is a request,
 * not proof that consent is still valid. Each callback must reject deleted or
 * stale sessions/revisions and revoked consent. Source checks also require exact
 * membership in the current committed Search. No callback grants consent.
 */
interface SponsorAuthorization {
    suspend fun requireResearch(request: ApprovedResourceRequest)
    suspend fun requireSource(source: ApprovedSource)
    suspend fun requireExport(event: ApprovedExport)
    suspend fun requireHistory(request: BoundedHistoryQuery)
}

/** Missing composition never grants implicit approval, including synthetic data. */
object DenySponsorAuthorization : SponsorAuthorization {
    private fun denied(): Nothing = sponsorFailure(ErrorCode.CONSENT_REQUIRED, "Sponsor operation requires current explicit approval.")
    override suspend fun requireResearch(request: ApprovedResourceRequest): Unit = denied()
    override suspend fun requireSource(source: ApprovedSource): Unit = denied()
    override suspend fun requireExport(event: ApprovedExport): Unit = denied()
    override suspend fun requireHistory(request: BoundedHistoryQuery): Unit = denied()
}
