package com.clearline.agent

import com.clearline.core.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal fun SessionSnapshot.revision(scope: WorkflowScope) = if (scope == WorkflowScope.RESEARCH) researchRevision else comparisonRevision
internal fun SessionSnapshot.activeActions(scope: WorkflowScope) = actions.filter { it.scope == scope && it.scopeRevision == revision(scope) && it.status != ActionStatus.INVALIDATED }
internal fun safeJson(value: String) = value.replace("<", "\\u003c").replace(">", "\\u003e")
internal fun resultMessage(result: ActionResult, toolCallId: String) = ChatMessage(ChatRole.TOOL, safeJson(Json.encodeToString(result)), toolCallId)

internal object WorkflowPolicy {
    fun validate(action: ProposedAction, checkpoint: AgentCheckpoint, snapshot: SessionSnapshot) {
        fun reject(message: String): Nothing = throw ClearLineException(AppError(ErrorCode.INVALID_INPUT, message))
        if (checkpoint.sessionId != snapshot.sessionId || checkpoint.profileId != snapshot.profileId || checkpoint.scopeRevision != snapshot.revision(checkpoint.workflowScope)) reject("The workflow revision changed.")
        if (!snapshot.consent.recording) reject("Recording consent is unavailable.")
        if (snapshot.phase == Phase.PAUSED || snapshot.pauseRequested) throw ClearLineException(AppError(ErrorCode.CANCELLED, "The workflow is paused.", true))
        val research = checkpoint.workflowScope == WorkflowScope.RESEARCH
        when (action) {
            ProposedAction.GetBaselineSummary -> if (research || snapshot.metrics == null || checkpoint.baseline != null) reject("Baseline retrieval is not permitted now.")
            ProposedAction.CompareRecordingMetrics -> if (research || snapshot.metrics == null || checkpoint.baseline == null || snapshot.comparison != null) reject("Comparison is not permitted now.")
            ProposedAction.SearchPublicResources -> {
                val request = snapshot.approvedResources ?: reject("Approve a resource request first.")
                if (!research || request.sessionId != snapshot.sessionId || request.inputRevision != snapshot.researchRevision || snapshot.resources.isNotEmpty()) reject("Search does not match the approved current request.")
            }
            is ProposedAction.ExtractPublicPage -> if (!research || snapshot.approvedResources?.inputRevision != snapshot.researchRevision || snapshot.resources.none { it.candidateId == action.candidateId }) reject("The source is not an approved search candidate.")
            is ProposedAction.RequestUserInput -> if (action.input.question.contains("<|") || action.input.question.any { it.isISOControl() }) reject("Invalid missing-input question.")
            ProposedAction.FinishTask -> {
                if (research) {
                    if (snapshot.sources.none { it.approvalId == snapshot.approvedResources?.approvalId && it.inputRevision == snapshot.researchRevision && it.verificationStatus != VerificationStatus.SIMULATED }) reject("Public source evidence is still required.")
                } else if (snapshot.comparison == null) reject("Local comparison is still required.")
                if (checkpoint.unresolvedRequirements.isNotEmpty()) reject("The current workflow still has unfinished requirements.")
            }
        }
    }
}
