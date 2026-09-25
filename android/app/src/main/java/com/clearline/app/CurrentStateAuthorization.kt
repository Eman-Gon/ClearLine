package com.clearline.app

import com.clearline.core.*
import com.clearline.sponsors.SponsorAuthorization
import kotlinx.coroutines.flow.first

/** Reads current local state immediately before each sponsor dispatch; never grants consent. */
internal class CurrentStateAuthorization(private val store: WorkflowStore) : SponsorAuthorization {
    private fun deny(message: String): Nothing = throw ClearLineException(AppError(ErrorCode.CONSENT_REQUIRED, message))
    override suspend fun requireResearch(request: ApprovedResourceRequest) {
        val session = store.getSession(request.sessionId) ?: deny("This check-in was deleted.")
        if (session.approvedResources != request || session.researchRevision != request.inputRevision || session.phase == Phase.PAUSED || session.pauseRequested) deny("Approve the current search before it can leave the phone.")
        request.queryDraft?.let { if (it.transcriptHash != CallInsights.transcriptHash(CallInsights.transcriptFor(session))) deny("The transcript changed. Review and approve a new search.") }
    }
    override suspend fun requireSource(source: ApprovedSource) {
        requireResearch(source.request)
        val session = store.getSession(source.request.sessionId) ?: deny("This check-in was deleted.")
        if (source.candidate !in session.resources) deny("Only a current approved search result can be inspected.")
    }
    override suspend fun requireExport(event: ApprovedExport) {
        val session = store.getSession(event.sessionId) ?: deny("This check-in was deleted.")
        if (session.profileId != event.profileId || session.dataOrigin != event.dataOrigin || session.inputRevision != event.inputRevision || session.consent.exportRevision != event.consentRevision || event.requiredField !in session.consent.exportFields) deny("Export consent or input has changed.")
        val measurements = event.projection as? ExportProjection.Measurements
        if (measurements != null && (measurements.transcriptSnippet != null || measurements.topKeyword != null) && ExportField.TRANSCRIPT_SNIPPET !in session.consent.exportFields) deny("Transcript snippet export requires its own approval.")
        if (measurements != null && (measurements.transcriptSnippet != session.consent.reviewedTranscriptSnippet || measurements.topKeyword != session.consent.reviewedKeyword) && (measurements.transcriptSnippet != null || measurements.topKeyword != null)) deny("Only the exact reviewed snippet and keyword may be exported.")
        if (measurements != null) {
            val summary = store.observeHistory(session.profileId).first().firstOrNull { it.sessionId == session.sessionId && it.version == measurements.summaryVersion }
                ?: deny("Only a committed local summary can be exported.")
            if (measurements.metrics != summary.metrics || measurements.metrics != session.metrics || measurements.summaryVersion != session.summaryVersion || measurements.sessionCreatedAtMs != session.createdAtMs || measurements.completedAtMs != summary.completedAtMs || measurements.task != session.task) deny("The approved measurement projection no longer matches this check-in.")
        }
        val publicResource = event.projection as? ExportProjection.PublicResource
        if (publicResource != null && publicResource.evidence !in session.sources) deny("Only a committed public source can be exported.")
        // The summary read above may suspend. A revocation or correction during it must still win.
        val latest = store.getSession(event.sessionId) ?: deny("This check-in was deleted.")
        if (latest.inputRevision != session.inputRevision || latest.consent != session.consent) deny("Export approval changed before dispatch.")
    }
    override suspend fun requireHistory(request: BoundedHistoryQuery) {
        val session = store.getSession(request.sessionId) ?: deny("This check-in was deleted.")
        if (request.field == ExportField.TRANSCRIPT_SNIPPET && ExportField.TRANSCRIPT_SNIPPET !in session.consent.exportFields) deny("Reading approved snippets requires the current text selection.")
    }
    override suspend fun requireMemory(query: MemoryQuery) {
        val session = store.getSession(query.currentSessionId) ?: deny("This check-in was deleted.")
        if (session.profileId != query.profileId || session.dataOrigin != query.dataOrigin || session.task != query.task) deny("Exported history must match this local profile.")
        if (query.beforeCreatedAtMs != session.createdAtMs || query.measurementVersion != session.metrics?.measurementVersion || query.lexicalVersion != session.metrics?.lexicalVersion) deny("Exported history must match this check-in’s methods and time window.")
    }
}
