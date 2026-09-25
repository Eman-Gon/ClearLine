package com.clearline.core

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

@Serializable enum class ResourceCategory { CAREGIVER_SUPPORT, RESPITE_CARE, CAREGIVER_EDUCATION }
@Serializable data class ApprovedResourceRequest(val sessionId: SessionId, val inputRevision: Long, val approvalId: ApprovalId, val category: ResourceCategory, val city: String, val approvedAtMs: Long, val queryDraft: CallQueryDraft? = null) {
    init { require(inputRevision >= 0); require(city.length in 1..120 && city == city.trim() && city.none { it.isISOControl() }) }
}
@Serializable data class ResourceCandidate(val candidateId: String, val title: String, val url: String, val description: String?, val requestId: String?, val retrievedAtMs: Long) {
    init { require(candidateId.length in 1..128); require(title.length in 1..500); require(url.length in 1..4096); require(description == null || description.length <= 4000); require(requestId == null || requestId.length <= 256) }
}
@Serializable data class SearchResult(val approvalId: ApprovalId, val inputRevision: Long, val candidates: List<ResourceCandidate>, val retrievedAtMs: Long, val requestId: String?) { init { require(candidates.size <= 3) } }
@Serializable data class ApprovedSource(val request: ApprovedResourceRequest, val candidate: ResourceCandidate)
@Serializable data class SupportingPassage(val passageId: String, val text: String) { init { require(passageId.length in 1..128 && text.length in 1..4000) } }
@Serializable data class SourcedFact(val value: String, val passageIds: List<String>) { init { require(value.length in 1..1000 && passageIds.size in 1..8) } }
@Serializable data class PublicFacts(val phone: SourcedFact? = null, val address: SourcedFact? = null, val hours: SourcedFact? = null)
@Serializable enum class VerificationStatus { EXTRACTED, CACHED, SIMULATED }
@Serializable data class SourceEvidence(val evidenceId: EvidenceId, val version: Int, val approvalId: ApprovalId, val inputRevision: Long, val title: String, val description: String?, val sourceUrl: String, val retrievedAtMs: Long, val contentHash: String, val passages: List<SupportingPassage>, val facts: PublicFacts = PublicFacts(), val verificationStatus: VerificationStatus = VerificationStatus.EXTRACTED, val requestId: String? = null, val taskId: String? = null, val searchRequestId: String? = null) {
    init { require(version > 0 && inputRevision >= 0); require(title.length in 1..500); require(description == null || description.length <= 4000); require(sourceUrl.length in 1..4096); require(contentHash.matches(Regex("[0-9a-f]{64}"))); require(passages.size <= 12 && passages.map { it.passageId }.distinct().size == passages.size); val ids = passages.map { it.passageId }.toSet(); listOfNotNull(facts.phone, facts.address, facts.hours).forEach { require(ids.containsAll(it.passageIds)) } }
}
interface PublicResourceClient { suspend fun search(request: ApprovedResourceRequest): SearchResult; suspend fun extract(source: ApprovedSource): SourceEvidence }

@Serializable enum class Sponsor { NIMBLE, RAWTREE }
@Serializable enum class CredentialState { MISSING, CONFIGURED, ERROR }
@Serializable data class CredentialStatus(val sponsor: Sponsor, val state: CredentialState, val error: AppError? = null)
/** Deliberately not serializable. No data-class generated toString or copy. */
class SecretValue(value: CharArray) : AutoCloseable {
    private val chars = value.copyOf()
    private var cleared = false
    init { require(chars.size in 1..8192) }
    fun <T> useSecret(block: (CharArray) -> T): T { check(!cleared); val temporary = chars.copyOf(); return try { block(temporary) } finally { temporary.fill('\u0000') } }
    override fun close() { chars.fill('\u0000'); cleared = true }
    override fun toString(): String = "SecretValue([REDACTED])"
}
@Serializable data class SponsorConfiguration(val nimbleEnabled: Boolean = false, val rawTreeEnabled: Boolean = false, val rawTreeDatabase: String? = null) { init { require(rawTreeDatabase == null || rawTreeDatabase.matches(Regex("[A-Za-z0-9_-]{1,128}"))) } }
interface SponsorCredentialSettings {
    fun observeStatus(): Flow<List<CredentialStatus>>
    fun observeConfiguration(): Flow<SponsorConfiguration>
    suspend fun setCredential(service: Sponsor, value: SecretValue)
    suspend fun clearCredential(service: Sponsor)
    suspend fun setConfiguration(configuration: SponsorConfiguration)
}

@Serializable enum class ExportField { EVENTS, MEASUREMENTS, WORKFLOW_COUNTS, PUBLIC_RESOURCES, TRANSCRIPT_SNIPPET }
@Serializable enum class ExportEventName { SESSION_CREATED, CLIP_ACCEPTED, AUDIO_PROCESSED, ACTION_COMPLETED, WORKFLOW_PAUSED, WORKFLOW_RESUMED, WORKFLOW_COMPLETED }
@Serializable enum class ExportEventStatus { COMPLETED, PENDING, PAUSED, FAILED }
@Serializable sealed interface ExportProjection {
    @Serializable data class Event(val eventId: String, val name: ExportEventName, val status: ExportEventStatus, val timestampMs: Long) : ExportProjection { init { require(eventId.length in 1..128) } }
    @Serializable data class Measurements(val summaryVersion: Int, val metrics: RecordingMetrics, val transcriptSnippet: String? = null, val topKeyword: String? = null, val sessionCreatedAtMs: Long? = null, val completedAtMs: Long? = null, val task: RecordingTask = RecordingTask.CHECK_IN) : ExportProjection { init { require(transcriptSnippet == null || transcriptSnippet.length <= 200); require(topKeyword == null || topKeyword.length <= 40) } }
    @Serializable data class WorkflowCounts(val phase: Phase, val completedCount: Int, val pendingCount: Int) : ExportProjection { init { require(completedCount >= 0 && pendingCount >= 0) } }
    @Serializable data class PublicResource(val evidence: SourceEvidence) : ExportProjection
}
@Serializable data class ApprovedExport(val exportId: ExportId, val profileId: ProfileId, val sessionId: SessionId, val inputRevision: Long, val consentRevision: Long, val dataOrigin: DataOrigin, val createdAtMs: Long, val projection: ExportProjection) {
    init { require(inputRevision >= 0 && consentRevision >= 0) }
    val requiredField: ExportField get() = when (projection) { is ExportProjection.Event -> ExportField.EVENTS; is ExportProjection.Measurements -> ExportField.MEASUREMENTS; is ExportProjection.WorkflowCounts -> ExportField.WORKFLOW_COUNTS; is ExportProjection.PublicResource -> ExportField.PUBLIC_RESOURCES }
}
@Serializable data class DeliveryReceipt(val exportId: ExportId, val deliveredAtMs: Long, val remoteReceipt: String? = null) { init { require(remoteReceipt == null || remoteReceipt.length <= 256) } }
@Serializable data class BoundedHistoryQuery(val sessionId: SessionId, val exportId: ExportId? = null, val limit: Int = 20, val field: ExportField = ExportField.EVENTS) { init { require(limit in 1..100) } }
@Serializable data class ExportedHistoryRecord(val exportId: ExportId, val sessionRef: String, val inputRevision: Long, val dataOrigin: DataOrigin, val createdAtMs: Long, val projection: ExportedHistoryProjection) { init { require(sessionRef.length in 1..128 && inputRevision >= 0) } }
@Serializable data class HistoryResult(val records: List<ExportedHistoryRecord>, val retrievedAtMs: Long) { init { require(records.size <= 100) } }
interface ApprovedHistoryClient { suspend fun append(event: ApprovedExport): DeliveryReceipt; suspend fun query(request: BoundedHistoryQuery): HistoryResult; suspend fun memory(query: MemoryQuery): RawTreeMemorySnapshot }

/** Cloud reads have no local approval/profile identity and cannot authorize local actions. */
@Serializable data class ExportedSourceEvidence(val evidenceId: EvidenceId, val version: Int, val title: String, val description: String?, val sourceUrl: String, val retrievedAtMs: Long, val contentHash: String, val passages: List<SupportingPassage>, val facts: PublicFacts, val verificationStatus: VerificationStatus)
@Serializable sealed interface ExportedHistoryProjection {
    @Serializable data class Event(val value: ExportProjection.Event) : ExportedHistoryProjection
    @Serializable data class Measurements(val value: ExportProjection.Measurements) : ExportedHistoryProjection
    @Serializable data class WorkflowCounts(val value: ExportProjection.WorkflowCounts) : ExportedHistoryProjection
    @Serializable data class PublicResource(val value: ExportedSourceEvidence) : ExportedHistoryProjection
}
