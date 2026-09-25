package com.clearline.agent

import com.clearline.core.*
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** One foreground coordinator. Observations belong to WorkflowStore and never call this worker. */
class OnDeviceCoordinator(
    private val store: WorkflowStore,
    private val audio: LocalAudioProcessor,
    private val agent: LocalAgent,
    private val resources: PublicResourceClient,
    private val history: ApprovedHistoryClient,
    private val scope: CoroutineScope,
    private val cancelInference: () -> Unit = {},
    private val cancelAudio: () -> Unit = {},
    private val now: () -> Long = System::currentTimeMillis,
) : CheckInCommands {
    private val commands = Mutex()
    private val execution = Mutex()
    private val active = ConcurrentHashMap<SessionId, Job>()
    private val memoryWork = ConcurrentHashMap<SessionId, Deferred<Unit>>()
    @Volatile private var foreground = false
    @Volatile private var executingSession: SessionId? = null
    private var initialized = false

    suspend fun initialize(): RecoveryReport = commands.withLock {
        if (initialized) return@withLock RecoveryReport(emptyList(), emptyList(), emptyList())
        val report = store.recoverInterrupted(now())
        cleanupFiles()
        initialized = true
        report // Explicit Resume is required; opening/observing does not execute tools.
    }

    fun onForeground() { foreground = true }
    /** Synchronous lifecycle boundary; persistence/cancellation then drains in onBackground. */
    fun markBackground() { foreground = false }

    suspend fun onBackground() {
        markBackground()
        (active.keys + memoryWork.keys).toSet().forEach { pause(it) }
    }

    suspend fun close() {
        onBackground()
        active.values.toList().joinAll()
        memoryWork.values.toList().joinAll()
    }

    override suspend fun createSession(input: CreateSession): SessionId = commands.withLock {
        requireInput(input.recordingConsent, "Recording consent is required.")
        val profile = store.getProfile(input.profileId) ?: fail(ErrorCode.NOT_FOUND, "The local profile was not found.")
        requireInput(profile.dataOrigin == input.dataOrigin, "The profile and check-in provenance must match.")
        val timestamp = now()
        val snapshot = SessionSnapshot(sessionId = SessionId.new(), profileId = input.profileId,
            phase = Phase.RECORDING, executionMode = input.executionMode, dataOrigin = input.dataOrigin,
            task = input.task, createdAtMs = timestamp, updatedAtMs = timestamp,
            consent = ConsentState(recording = true, updatedAtMs = timestamp))
        store.applyCommand(CommandMutation(null, null, snapshot, checkpoint(snapshot, WorkflowScope.COMPARISON)))
        snapshot.sessionId
    }

    override suspend fun acceptClip(input: CompletedLocalClip): ClipReceipt {
        val receipt = commands.withLock {
            val current = session(input.sessionId)
            current.clips.firstOrNull { it.clip.clipId == input.clipId }?.let { prior ->
                requireInput(prior.clip.sha256 == input.sha256, "This clip identity already belongs to other content.")
                return@withLock prior.receipt.copy(duplicate = true)
            }
            requireInput(current.consent.recording && current.dataOrigin == input.dataOrigin, "Recording approval or provenance does not match.")
            requireInput(current.clips.size < 20 || current.clips.any { it.clip.clipId == input.clipId }, "This check-in has reached its clip limit.")
            // The phone-private completed WAV is rechecked before transactional admission.
            withContext(Dispatchers.IO) {
                val file = File(input.privatePath)
                requireInput(file.isFile && !file.name.endsWith(".part"), "A finalized local recording is required.")
                val digest = MessageDigest.getInstance("SHA-256")
                file.inputStream().use { stream -> val buffer = ByteArray(65536); while (true) { val count = stream.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) } }
                requireInput(digest.digest().joinToString("") { "%02x".format(it) } == input.sha256, "The recording checksum changed.")
            }
            store.admitClip(input, now())
        }
        launchSession(input.sessionId)
        return receipt
    }

    override suspend fun finishCapture(sessionId: SessionId) {
        commands.withLock {
            val old = session(sessionId)
            if (old.captureFinished) return@withLock
            var next = changed(old).copy(captureFinished = true, phase = if (old.phase == Phase.PAUSED) Phase.PAUSED else Phase.PROCESSING)
            val completed = accepted(next)
            val summary = if (next.clips.none { it.result == null && it.supersededBy == null } && completed.isNotEmpty()) {
                next = next.copy(metrics = LocalMeasurements.aggregate(completed), summaryVersion = old.summaryVersion + 1, phase = if (old.phase == Phase.PAUSED) Phase.PAUSED else Phase.COMPARING)
                summary(next)
            } else null
            val jobs = if (summary != null) listOf(workflowJob(next, WorkflowScope.COMPARISON)) else emptyList()
            save(old, next, WorkflowScope.COMPARISON, jobs, summary = summary)
        }
        launchSession(sessionId)
    }

    override suspend fun requestResources(input: ApprovedResourceRequest) {
        commands.withLock {
            val old = session(input.sessionId)
            requireRevision(old, input.inputRevision)
            requireInput(input.approvedAtMs > 0, "Approve the public request preview first.")
            val revision = old.inputRevision + 1
            val researchRevision = old.researchRevision + 1
            input.queryDraft?.let { draft ->
                requireInput(draft.transcriptHash == CallInsights.transcriptHash(CallInsights.transcriptFor(old)) && draft.city == input.city && draft.category == input.category, "Review the current transcript-derived search preview.")
                requireInput(draft == CallInsights.buildQuery(CallInsights.transcriptFor(old), input.city, input.category, old.comparison).copy(query = draft.query), "Review the current query rationale before approving the exact edited query.")
            }
            val next = changed(old).copy(inputRevision = revision, researchRevision = researchRevision,
                approvedResources = input.copy(inputRevision = researchRevision), resources = emptyList(), sources = emptyList(),
                pendingInput = null, pendingAction = null, errors = emptyList(),
                phase = if (old.phase == Phase.PAUSED) Phase.PAUSED else Phase.RESEARCHING)
            save(old, next, WorkflowScope.RESEARCH, listOf(workflowJob(next, WorkflowScope.RESEARCH)), invalidate = setOf(WorkflowScope.RESEARCH))
        }
        launchSession(input.sessionId)
    }

    override suspend fun applyInput(input: RevisionedUserInput) {
        commands.withLock {
            val old = session(input.sessionId)
            requireRevision(old, input.inputRevision)
            when (val change = input.input) {
                is UserInput.TranscriptCorrection -> {
                    val clip = old.clips.find { it.clip.clipId == change.clipId && it.result != null && it.supersededBy == null }
                        ?: fail(ErrorCode.INVALID_INPUT, "Choose a completed recording to correct.")
                    val revision = old.inputRevision + 1
                    val corrected = clip.copy(result = LocalMeasurements.correct(clip.result!!, change.transcript, now()))
                    var next = changed(old).copy(inputRevision = revision, comparisonRevision = old.comparisonRevision + 1,
                        clips = old.clips.map { if (it.clip.clipId == change.clipId) corrected else it }, comparison = null,
                        summaryVersion = old.summaryVersion + 1, pendingInput = null, pendingAction = null, errors = emptyList(),
                        // A transcript-derived public query must be previewed and approved again.
                        researchRevision = old.researchRevision + 1, approvedResources = null, resources = emptyList(), sources = emptyList(),
                        consent = withoutReviewedText(old.consent), rawTreeMemory = null,
                        phase = if (old.phase == Phase.PAUSED) Phase.PAUSED else Phase.COMPARING)
                    next = next.copy(metrics = LocalMeasurements.aggregate(accepted(next)))
                    save(old, next, WorkflowScope.COMPARISON, listOf(workflowJob(next, WorkflowScope.COMPARISON)),
                        invalidate = setOf(WorkflowScope.COMPARISON, WorkflowScope.RESEARCH), summary = summary(next))
                }
                is UserInput.Answer -> {
                    requireInput(old.pendingInput != null, "No question is currently awaiting an answer.")
                    val prior = store.getCheckpoint(old.sessionId)
                    val workflowScope = prior?.workflowScope ?: WorkflowScope.COMPARISON
                    requireInput(old.activeActions(workflowScope).size < 12, "The action budget is exhausted. Correct the transcript or approve a new resource request.")
                    val next = changed(old).copy(inputRevision = old.inputRevision + 1, pendingInput = null, errors = emptyList(),
                        phase = if (old.phase == Phase.PAUSED) Phase.PAUSED else phase(workflowScope))
                    val base = checkpoint(next, workflowScope, prior)
                    val userMessage = ChatMessage(ChatRole.USER, safeJson(Json.encodeToString(change.text)))
                    save(old, next, workflowScope, listOf(workflowJob(next, workflowScope)),
                        checkpointOverride = base.copy(exchanges = (base.exchanges.takeLast(2) + userMessage)))
                }
            }
        }
        launchSession(input.sessionId)
    }

    override suspend fun pause(sessionId: SessionId) {
        commands.withLock {
            val old = store.getSession(sessionId) ?: return@withLock
            if (old.phase == Phase.PAUSED && !old.pauseRequested) return@withLock
            val running = active[sessionId]?.isCompleted == false
            val next = changed(old).copy(pauseRequested = running, phase = if (running) old.phase else Phase.PAUSED)
            val prior = store.getCheckpoint(sessionId)
            save(old, next, prior?.workflowScope ?: WorkflowScope.COMPARISON)
        }
        if (executingSession == sessionId) { cancelInference(); cancelAudio() }
        active[sessionId]?.cancelAndJoin()
        memoryWork[sessionId]?.cancelAndJoin()
        settlePause(sessionId)
    }

    override suspend fun resume(sessionId: SessionId) {
        commands.withLock {
            val old = session(sessionId)
            if (active[sessionId]?.isCompleted == false) return@withLock
            requireInput(foreground, "Open the app before resuming local work.")
            if (old.phase == Phase.READY || old.phase == Phase.AWAITING_USER_CHOICE || old.phase == Phase.RECORDING) return@withLock
            if (old.pendingInput != null && old.errors.none { it.code in setOf(ErrorCode.MISSING_MODEL, ErrorCode.MODEL_FAILED) }) return@withLock
            val prior = store.getCheckpoint(sessionId)
            val workflowScope = store.getPendingPlan(sessionId)?.scope ?: prior?.workflowScope ?: WorkflowScope.COMPARISON
            val next = changed(old).copy(pauseRequested = false, errors = emptyList(), pendingInput = null,
                phase = if (old.clips.any { it.result == null && it.supersededBy == null }) Phase.PROCESSING else phase(workflowScope))
            save(old, next, workflowScope, resume = true)
        }
        launchSession(sessionId)
    }

    override suspend fun setExportConsent(input: ExportConsentChange) {
      commands.withLock {
        val old = session(input.sessionId)
        requireInput(old.consent.exportRevision == input.expectedConsentRevision, "Export approval changed; review it again.")
        val textSelected = ExportField.TRANSCRIPT_SNIPPET in input.selectedFields
        requireInput(!textSelected || ExportField.MEASUREMENTS in input.selectedFields, "Select measurements with the reviewed text export.")
        requireInput(textSelected || (input.reviewedTranscriptSnippet == null && input.reviewedKeyword == null), "Select text export before approving snippet or keyword fields.")
        requireInput(listOfNotNull(input.reviewedTranscriptSnippet, input.reviewedKeyword).all { it.isNotBlank() && it.none(Char::isISOControl) }, "Review a nonempty snippet and keyword without control characters, or omit them.")
        requireInput(!textSelected || CallInsights.transcriptFor(old).isNotBlank(), "Accepted transcript text is required before reviewing a text export.")
        val currentSummary = if (ExportField.MEASUREMENTS in input.selectedFields) store.observeHistory(old.profileId).first()
            .firstOrNull { it.sessionId == old.sessionId && it.version == old.summaryVersion && it.metrics == old.metrics } else null
        // Cancel old projections; this explicit approval may export only the displayed current summary.
        val revoked = store.revokeExportConsent(old.sessionId, input.expectedConsentRevision, now())
        if (input.selectedFields.isNotEmpty()) {
            val next = changed(revoked).copy(consent = revoked.consent.copy(exportFields = input.selectedFields, exportRevision = revoked.consent.exportRevision + 1, updatedAtMs = now(),
                reviewedTranscriptSnippet = input.reviewedTranscriptSnippet, reviewedKeyword = input.reviewedKeyword), rawTreeMemory = null)
            save(revoked, next, store.getCheckpoint(old.sessionId)?.workflowScope ?: WorkflowScope.COMPARISON,
                extraProjections = currentSummary?.let { listOf(measurementProjection(next, it)) } ?: emptyList())
        }
      }
      launchSession(input.sessionId)
    }

    override suspend fun refreshExportedMemory(sessionId: SessionId) {
        val work = commands.withLock {
            requireInput(foreground, "Open the app before refreshing exported history.")
            memoryWork[sessionId]?.takeUnless { it.isCompleted } ?: scope.async(start = CoroutineStart.LAZY) {
                execution.withLock {
                    if (foreground) { flushOutbox(); refreshMemory(sessionId) }
                }
            }.also { memoryWork[sessionId] = it; it.start() }
        }
        try { work.await() }
        catch (cancelled: CancellationException) { work.cancelAndJoin(); throw cancelled }
        finally { memoryWork.remove(sessionId, work) }
    }

    private suspend fun refreshMemory(sessionId: SessionId) {
        if (!foreground) return
        val before = store.getSession(sessionId) ?: return
        val metrics = before.metrics ?: return
        val query = MemoryQuery(before.profileId, before.sessionId, before.dataOrigin, before.task,
            metrics.measurementVersion, metrics.lexicalVersion, before.createdAtMs)
        val remote = try { withTimeout(30_000) { history.memory(query) } }
        catch (timeout: TimeoutCancellationException) { RawTreeMemoryMath.unavailable(query, now(), RawTreeMemoryDiagnostics(error = ErrorCode.TIMEOUT)) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Throwable) { RawTreeMemoryMath.unavailable(query, now(),
            RawTreeMemoryDiagnostics(error = (error as? ClearLineException)?.error?.code ?: ErrorCode.UNAVAILABLE)) }
        requireInput(remote.profileId == before.profileId && remote.currentSessionId == before.sessionId,
            "Exported history response has the wrong identity.")
        commands.withLock {
            val old = store.getSession(sessionId) ?: return@withLock
            if (!foreground || old.inputRevision != before.inputRevision || old.consent.exportRevision != before.consent.exportRevision) return@withLock
            val projection = if (ExportField.TRANSCRIPT_SNIPPET in old.consent.exportFields) remote else remote.copy(
                previousSessions = remote.previousSessions.map { it.copy(transcriptSnippet = null, topKeyword = null) },
                currentSession = remote.currentSession?.copy(transcriptSnippet = null, topKeyword = null))
            val next = changed(old).copy(rawTreeMemory = projection)
            val prior = store.getCheckpoint(sessionId)
            // This cache is optional evidence, never a replacement baseline/checkpoint.
            store.applyCommand(CommandMutation(old.stateVersion, old.inputRevision, next,
                checkpoint(next, prior?.workflowScope ?: WorkflowScope.COMPARISON, prior)))
        }
    }

    override suspend fun deleteSession(sessionId: SessionId) {
        stopSession(sessionId)
        commands.withLock { store.deleteSession(sessionId, now()); cleanupFiles() }
    }

    override suspend fun deleteProfile(profileId: ProfileId) {
        (active.keys + memoryWork.keys).filter { store.getSession(it)?.profileId == profileId }.forEach { stopSession(it) }
        commands.withLock { store.deleteProfile(profileId, now()); cleanupFiles() }
    }

    private suspend fun stopSession(id: SessionId) {
        if (executingSession == id) { cancelInference(); cancelAudio() }
        active[id]?.cancelAndJoin()
        memoryWork[id]?.cancelAndJoin()
    }

    private fun launchSession(id: SessionId) {
        if (!foreground) return
        synchronized(active) {
            if (active[id]?.isCompleted == false) return
            lateinit var job: Job
            job = scope.launch(start = CoroutineStart.LAZY) {
                try { execution.withLock { executingSession = id; runSession(id) } }
                finally { if (executingSession == id) executingSession = null; withContext(NonCancellable) { settlePause(id) }; active.remove(id, job) }
            }
            active[id] = job
            job.start()
        }
    }

    /** Useful to application tests; ordinary UI reads must never call this. */
    suspend fun awaitIdle() { active.values.toList().joinAll() }

    private suspend fun runSession(id: SessionId) {
        while (foreground && currentCoroutineContext().isActive) {
            val claim = commands.withLock {
                val current = store.getSession(id) ?: return
                if (current.phase in setOf(Phase.PAUSED, Phase.AWAITING_INPUT, Phase.AGENT_UNAVAILABLE, Phase.WAITING_NETWORK, Phase.WAITING_RETRY) || current.pauseRequested) return@withLock null
                store.claimJob(id, now())
            } ?: break
            try {
                if (claim.job.kind == JobKind.PROCESS_AUDIO) processAudio(claim) else processAction(claim)
            } catch (timeout: TimeoutCancellationException) {
                if (!claimIsCurrent(claim)) continue
                recordFailure(claim, AppError(ErrorCode.TIMEOUT, "The operation timed out. Resume to retry with its saved identity.", true), Phase.WAITING_RETRY)
                return
            } catch (cancelled: CancellationException) {
                withContext(NonCancellable) { recordFailure(claim, AppError(ErrorCode.CANCELLED, "Work paused at a durable boundary.", true), Phase.PAUSED) }
                throw cancelled
            } catch (error: Throwable) {
                if (!claimIsCurrent(claim)) continue // Newer revision jobs remain eligible in this runner.
                val safe = (error as? ClearLineException)?.error ?: AppError(ErrorCode.UNAVAILABLE, "The local step could not complete.")
                val phase = when (safe.code) {
                    ErrorCode.NETWORK_UNAVAILABLE -> Phase.WAITING_NETWORK
                    ErrorCode.TIMEOUT, ErrorCode.RATE_LIMITED -> Phase.WAITING_RETRY
                    ErrorCode.INVALID_AUDIO, ErrorCode.SILENT_AUDIO, ErrorCode.NO_SPEECH, ErrorCode.MISSING_MODEL -> Phase.AWAITING_INPUT
                    else -> Phase.AGENT_UNAVAILABLE
                }
                recordFailure(claim, safe, phase)
                return
            }
            flushOutbox()
        }
        flushOutbox()
    }

    private suspend fun processAudio(claim: JobClaim) {
        val initial = session(claim.job.sessionId)
        val clip = initial.clips.find { it.clip.clipId == claim.job.clipId } ?: fail(ErrorCode.NOT_FOUND, "The queued recording was not found.")
        val result = withTimeout(120_000) { audio.process(clip.clip) }
        commands.withLock {
            val old = session(claim.job.sessionId)
            ensureCurrent(claim, old)
            requireInput(result.clipId == clip.clip.clipId && result.metrics.dataOrigin == old.dataOrigin, "Audio result identity changed.")
            val completedClip = old.clips.first { it.clip.clipId == result.clipId }.copy(result = result, audioDeleted = true)
            val updatedClips = old.clips.map {
                when { it.clip.clipId == result.clipId -> completedClip
                    it.clip.clipId == clip.clip.supersedesClipId -> it.copy(supersededBy = result.clipId)
                    else -> it }
            }
            var next = changed(old).copy(clips = updatedClips, errors = emptyList(), pendingInput = null,
                phase = if (old.captureFinished) Phase.PROCESSING else Phase.RECORDING)
            val ready = next.captureFinished && next.clips.none { it.result == null && it.supersededBy == null }
            var summary: SessionSummary? = null
            if (ready) {
                next = next.copy(metrics = LocalMeasurements.aggregate(accepted(next)), summaryVersion = old.summaryVersion + 1, phase = Phase.COMPARING)
                summary = summary(next)
            }
            val jobs = if (ready) listOf(workflowJob(next, WorkflowScope.COMPARISON)) else emptyList()
            val mutation = mutation(old, next, WorkflowScope.COMPARISON, jobs, summary = summary)
            store.commitAudioSuccess(AudioSuccessCommit(claim, result, mutation))
        }
        // DB says terminal media can be removed. Startup can repeat cleanup after a kill here.
        withContext(Dispatchers.IO) { File(clip.clip.privatePath).delete() }
        cleanupFiles()
    }

    private suspend fun processAction(claim: JobClaim) {
        var current = session(claim.job.sessionId)
        ensureCurrent(claim, current)
        var plan = store.getPendingPlan(current.sessionId)?.takeIf { it.jobId == claim.job.jobId && it.scopeRevision == current.revision(it.scope) }
        if (plan == null) {
            val checkpoint = checkpoint(current, claim.job.scope, store.getCheckpoint(current.sessionId))
            val count = current.activeActions(claim.job.scope).size
            if (count >= 12) fail(ErrorCode.RETRY_EXHAUSTED, "The local action budget is exhausted. Refine the request.")
            val local = agent as? ToolCallingLocalAgent ?: fail(ErrorCode.AGENT_UNAVAILABLE, "A real tool-calling model adapter is required.")
            val proposed = withTimeout(120_000) { local.proposeTurn(checkpoint) }
            commands.withLock {
                current = session(current.sessionId)
                ensureCurrent(claim, current)
                WorkflowPolicy.validate(proposed.action, checkpoint, current)
                val id = ActionId.new()
                val callId = "call_${id.value}"
                plan = store.persistPlan(claim, ActionRecord(id, claim.job.jobId, current.sessionId,
                    current.inputRevision, count + 1, proposed.action, proposed.assistantMessage.copy(toolCallId = callId),
                    callId, createdAtMs = now(), scope = claim.job.scope, scopeRevision = claim.job.scopeRevision))
            }
        }
        val recoveredPlan = plan!!
        if (recoveredPlan.status == ActionStatus.UNKNOWN) commands.withLock {
            plan = store.persistPlan(claim, recoveredPlan)
        }
        val action = plan!!
        current = session(current.sessionId)
        ensureCurrent(claim, current)
        val checkpoint = checkpoint(current, action.scope, store.getCheckpoint(current.sessionId))
        WorkflowPolicy.validate(action.proposal, checkpoint, current)
        val result = action.result?.takeIf { action.status == ActionStatus.SUCCEEDED } ?: execute(action.proposal, checkpoint, current)
        commands.withLock {
            val old = session(current.sessionId)
            ensureCurrent(claim, old)
            WorkflowPolicy.validate(action.proposal, checkpoint(old, action.scope, store.getCheckpoint(old.sessionId)), old)
            var next = changed(old).copy(pendingAction = null, pendingInput = null, errors = emptyList(), phase = phase(action.scope))
            when (result) {
                is ActionResult.Baseline -> Unit
                is ActionResult.Comparison -> next = next.copy(comparison = result.value)
                is ActionResult.Search -> {
                    requireInput(result.value.approvalId == old.approvedResources?.approvalId && result.value.inputRevision == old.researchRevision, "Search approval changed.")
                    next = next.copy(resources = result.value.candidates)
                }
                is ActionResult.Extract -> {
                    requireInput(result.value.approvalId == old.approvedResources?.approvalId && result.value.inputRevision == old.researchRevision, "Source approval changed.")
                    next = next.copy(sources = (old.sources.filterNot { it.sourceUrl == result.value.sourceUrl } + result.value).takeLast(36))
                }
                is ActionResult.InputRequested -> next = next.copy(phase = Phase.AWAITING_INPUT, pendingInput = result.value)
                ActionResult.Finished -> next = next.copy(phase = if (action.scope == WorkflowScope.RESEARCH) Phase.READY else Phase.AWAITING_USER_CHOICE)
            }
            val toolMessage = resultMessage(result, action.toolCallId)
            val finished = action.copy(status = ActionStatus.SUCCEEDED, result = result, toolMessage = toolMessage, completedAtMs = now())
            next = next.copy(actions = (old.actions.filterNot { it.actionId == action.actionId } + finished).takeLast(144))
            val terminal = result is ActionResult.InputRequested || result == ActionResult.Finished
            if (!terminal && next.activeActions(action.scope).size >= 12) next = budgetExhausted(next)
            val nextCheckpoint = checkpoint(next, action.scope).copy(exchanges = listOf(action.assistantMessage, toolMessage),
                baseline = (result as? ActionResult.Baseline)?.value ?: checkpoint.baseline)
            val jobs = if (terminal || next.activeActions(action.scope).size >= 12) emptyList() else listOf(workflowJob(next, action.scope))
            val exports = if (result is ActionResult.Extract) listOf(ExportProjection.PublicResource(result.value)) else emptyList()
            val mutation = mutation(old, next, action.scope, jobs, checkpointOverride = nextCheckpoint, extraProjections = exports)
            store.commitSuccess(ActionSuccessCommit(claim, action.actionId, result, toolMessage, mutation, now()))
        }
    }

    private suspend fun execute(action: ProposedAction, checkpoint: AgentCheckpoint, state: SessionSnapshot): ActionResult = when (action) {
        ProposedAction.GetBaselineSummary -> {
            val metrics = state.metrics ?: fail(ErrorCode.INVALID_INPUT, "Accepted measurements are required.")
            ActionResult.Baseline(store.readBaseline(BaselineQuery(state.profileId, state.sessionId, state.task,
                state.dataOrigin, metrics.measurementVersion, metrics.lexicalVersion, state.createdAtMs)))
        }
        ProposedAction.CompareRecordingMetrics -> ActionResult.Comparison(LocalMeasurements.compare(state.metrics!!, checkpoint.baseline!!))
        ProposedAction.SearchPublicResources -> ActionResult.Search(withTimeout(45_000) { resources.search(state.approvedResources!!) })
        is ProposedAction.ExtractPublicPage -> ActionResult.Extract(withTimeout(45_000) { resources.extract(ApprovedSource(state.approvedResources!!, state.resources.first { it.candidateId == action.candidateId })) })
        is ProposedAction.RequestUserInput -> ActionResult.InputRequested(action.input)
        ProposedAction.FinishTask -> ActionResult.Finished
    }

    private suspend fun recordFailure(claim: JobClaim, error: AppError, blockedPhase: Phase) = commands.withLock {
        val old = store.getSession(claim.job.sessionId) ?: return@withLock
        if (old.revision(claim.job.scope) != claim.job.scopeRevision) return@withLock // obsolete results never restore state
        val plan = store.getPendingPlan(old.sessionId)?.takeIf { it.jobId == claim.job.jobId }
        val retrySameAction = plan == null || error.code in setOf(ErrorCode.CANCELLED, ErrorCode.TIMEOUT)
        val toolMessage = plan?.takeIf { !retrySameAction }?.let { ChatMessage(ChatRole.TOOL, safeJson(Json.encodeToString(error)), it.toolCallId) }
        var next = changed(old).copy(phase = blockedPhase, pauseRequested = false, errors = listOf(error),
            pendingInput = if (blockedPhase == Phase.AWAITING_INPUT) PendingInput(error.code.name.lowercase(), if (error.code == ErrorCode.MISSING_MODEL) "Install and load the missing local model, then Resume." else "Please record a replacement clip.") else old.pendingInput)
        if (plan != null) {
            val failed = plan.copy(status = if (retrySameAction) ActionStatus.UNKNOWN else ActionStatus.FAILED,
                error = error, toolMessage = toolMessage, completedAtMs = if (retrySameAction) null else now())
            next = next.copy(actions = (old.actions.filterNot { it.actionId == plan.actionId } + failed).takeLast(144), pendingAction = if (retrySameAction) failed else null)
        }
        if (!retrySameAction && next.activeActions(claim.job.scope).size >= 12) next = budgetExhausted(next)
        if (plan == null && claim.job.attempt >= 3 && error.code !in setOf(ErrorCode.CANCELLED, ErrorCode.MISSING_MODEL, ErrorCode.MODEL_FAILED)) {
            next = budgetExhausted(next)
        }
        val base = checkpoint(next, claim.job.scope, store.getCheckpoint(old.sessionId))
        val exchanges = if (plan != null && toolMessage != null) listOf(plan.assistantMessage, toolMessage) else base.exchanges
        val jobs = if (!retrySameAction && next.activeActions(claim.job.scope).size < 12) listOf(workflowJob(next, claim.job.scope)) else emptyList()
        store.recordFailure(FailureCommit(claim, plan?.actionId, error,
            mutation(old, next, claim.job.scope, jobs, checkpointOverride = base.copy(exchanges = exchanges)), now(), retrySameAction))
    }

    private suspend fun settlePause(id: SessionId) = commands.withLock {
        val old = store.getSession(id) ?: return@withLock
        if (old.pauseRequested) save(old, changed(old).copy(phase = Phase.PAUSED, pauseRequested = false), store.getCheckpoint(id)?.workflowScope ?: WorkflowScope.COMPARISON)
    }

    private suspend fun flushOutbox() {
        repeat(4) {
            if (!foreground) return
            val claim = store.claimOutbox(now()) ?: return
            if (!store.isExportStillApproved(claim)) return@repeat
            try {
                store.acknowledgeOutbox(claim, withTimeout(45_000) { history.append(claim.export) })
            }
            catch (timeout: TimeoutCancellationException) {
                store.failOutbox(claim, AppError(ErrorCode.TIMEOUT, "Export acknowledgement timed out; its saved identity can be retried.", true), now())
                return
            }
            catch (cancelled: CancellationException) {
                withContext(NonCancellable) { store.failOutbox(claim, AppError(ErrorCode.CANCELLED, "Export paused; delivery may be retried with its saved identity.", true), now()) }
                throw cancelled
            }
            catch (error: Throwable) {
                store.failOutbox(claim, (error as? ClearLineException)?.error ?: AppError(ErrorCode.UNAVAILABLE, "Approved export is pending."), now())
                return
            }
            if (claim.export.projection is ExportProjection.Measurements) refreshMemory(claim.export.sessionId)
        }
    }

    private suspend fun cleanupFiles() = withContext(Dispatchers.IO) {
        store.pendingFileCleanup().forEach { path -> if (!File(path).exists() || File(path).delete()) store.acknowledgeFileCleanup(path) }
    }

    private fun checkpoint(state: SessionSnapshot, workflowScope: WorkflowScope, prior: AgentCheckpoint? = null): AgentCheckpoint {
        val relevant = state.activeActions(workflowScope)
        val previous = prior?.takeIf { it.workflowScope == workflowScope && it.scopeRevision == state.revision(workflowScope) }
        val baseline = relevant.mapNotNull { (it.result as? ActionResult.Baseline)?.value }.lastOrNull() ?: previous?.baseline
        val obligations = if (workflowScope == WorkflowScope.RESEARCH) buildSet {
            if (relevant.none { it.result is ActionResult.Search && it.status == ActionStatus.SUCCEEDED }) add(Requirement.PUBLIC_SEARCH_COMPLETE)
            if (state.sources.none { it.approvalId == state.approvedResources?.approvalId && it.inputRevision == state.researchRevision }) add(Requirement.PUBLIC_EXTRACTION_COMPLETE)
        } else buildSet {
            if (state.metrics == null) add(Requirement.AUDIO_ACCEPTED)
            if (baseline == null) add(Requirement.BASELINE_READ)
            if (state.comparison == null) add(Requirement.COMPARISON_COMPLETE)
        }
        val latest = relevant.lastOrNull { it.result != null && it.status == ActionStatus.SUCCEEDED }
        val exchange = previous?.exchanges ?: latest?.let { listOf(it.assistantMessage, resultMessage(it.result!!, it.toolCallId)) } ?: emptyList()
        return AgentCheckpoint(sessionId = state.sessionId, profileId = state.profileId, inputRevision = state.inputRevision,
            stateVersion = state.stateVersion, phase = state.phase, task = state.task, dataOrigin = state.dataOrigin,
            metrics = state.metrics, comparison = state.comparison, approvedResources = state.approvedResources,
            pendingInput = state.pendingInput, completedActionIds = relevant.filter { it.status == ActionStatus.SUCCEEDED }.takeLast(8).map { it.actionId },
            pendingActionId = state.pendingAction?.actionId, exchanges = exchange.takeLast(3), sourceIds = state.sources.map { it.evidenceId }.takeLast(8),
            baseline = baseline, candidateSources = state.resources, workflowScope = workflowScope, scopeRevision = state.revision(workflowScope), unresolvedRequirements = obligations)
    }

    private suspend fun save(old: SessionSnapshot, next: SessionSnapshot, workflowScope: WorkflowScope,
        jobs: List<JobRecord> = emptyList(), invalidate: Set<WorkflowScope> = emptySet(), summary: SessionSummary? = null,
        checkpointOverride: AgentCheckpoint? = null, resume: Boolean = false, extraProjections: List<ExportProjection> = emptyList()): SessionSnapshot {
        val preserved = checkpointOverride ?: checkpoint(next, workflowScope, store.getCheckpoint(old.sessionId))
        return store.applyCommand(mutation(old, next, workflowScope, jobs, invalidate, summary, preserved, extraProjections, resume))
    }

    private fun mutation(old: SessionSnapshot, next: SessionSnapshot, workflowScope: WorkflowScope,
        jobs: List<JobRecord> = emptyList(), invalidate: Set<WorkflowScope> = emptySet(), summary: SessionSummary? = null,
        checkpointOverride: AgentCheckpoint? = null, extraProjections: List<ExportProjection> = emptyList(), resume: Boolean = false): CommandMutation {
        val projections = mutableListOf<ExportProjection>()
        projections += ExportProjection.WorkflowCounts(next.phase, next.actions.count { it.status == ActionStatus.SUCCEEDED }, jobs.size)
        if (summary != null) projections += measurementProjection(next, summary)
        projections += extraProjections
        val exports = projections.map { projection -> ApprovedExport(ExportId.new(), next.profileId, next.sessionId,
            next.inputRevision, next.consent.exportRevision, next.dataOrigin, now(), projection) }
            .filter { it.requiredField in next.consent.exportFields }
        return CommandMutation(old.stateVersion, old.inputRevision, next, checkpointOverride ?: checkpoint(next, workflowScope),
            enqueueJobs = jobs, invalidateScopes = invalidate, summary = summary, exports = exports, resumeEligibleJobs = resume)
    }

    private fun workflowJob(state: SessionSnapshot, workflowScope: WorkflowScope): JobRecord {
        val ordinal = state.activeActions(workflowScope).size + 1
        val revision = state.revision(workflowScope)
        val key = "${state.sessionId.value}:$workflowScope:$revision:$ordinal"
        return JobRecord(JobId(UUID.nameUUIDFromBytes(key.toByteArray()).toString()), state.sessionId, state.inputRevision,
            JobKind.ADVANCE_WORKFLOW, createdAtMs = now(), updatedAtMs = now(), scope = workflowScope, scopeRevision = revision)
            .copy(stepOrdinal = ordinal)
    }

    private fun measurementProjection(state: SessionSnapshot, summary: SessionSummary): ExportProjection.Measurements {
        val includeText = ExportField.TRANSCRIPT_SNIPPET in state.consent.exportFields
        return ExportProjection.Measurements(summary.version, summary.metrics,
            transcriptSnippet = state.consent.reviewedTranscriptSnippet.takeIf { includeText },
            topKeyword = state.consent.reviewedKeyword.takeIf { includeText },
            sessionCreatedAtMs = state.createdAtMs, completedAtMs = summary.completedAtMs, task = state.task)
    }

    private fun withoutReviewedText(consent: ConsentState): ConsentState = if (ExportField.TRANSCRIPT_SNIPPET !in consent.exportFields && consent.reviewedTranscriptSnippet == null && consent.reviewedKeyword == null) consent else
        consent.copy(exportFields = consent.exportFields - ExportField.TRANSCRIPT_SNIPPET, exportRevision = consent.exportRevision + 1,
            updatedAtMs = now(), reviewedTranscriptSnippet = null, reviewedKeyword = null)

    private fun budgetExhausted(state: SessionSnapshot) = state.copy(phase = Phase.AWAITING_INPUT,
        pendingInput = PendingInput("action_budget", "Correct the transcript or approve a new resource request to begin a new workflow revision."),
        errors = listOf(AppError(ErrorCode.RETRY_EXHAUSTED, "The bounded local workflow could not complete.")))

    private suspend fun claimIsCurrent(claim: JobClaim) = store.getSession(claim.job.sessionId)?.revision(claim.job.scope) == claim.job.scopeRevision

    private fun summary(state: SessionSnapshot) = SessionSummary(state.sessionId, state.profileId, state.summaryVersion,
        state.inputRevision, state.task, state.dataOrigin, state.metrics ?: fail(ErrorCode.INVALID_INPUT, "No accepted metrics are available."),
        now(), state.clips.filter { it.result != null && it.supersededBy == null }.map { it.clip.clipId })
    private fun accepted(state: SessionSnapshot) = state.clips.filter { it.supersededBy == null }.mapNotNull { it.result }
    private fun changed(state: SessionSnapshot) = state.copy(stateVersion = state.stateVersion + 1, updatedAtMs = now())
    private suspend fun session(id: SessionId) = store.getSession(id) ?: fail(ErrorCode.NOT_FOUND, "This local session no longer exists.")
    private fun requireRevision(state: SessionSnapshot, revision: Long) { if (state.inputRevision != revision) fail(ErrorCode.STALE_REVISION, "This input changed. Review the current check-in.") }
    private fun ensureCurrent(claim: JobClaim, state: SessionSnapshot) {
        if (state.revision(claim.job.scope) != claim.job.scopeRevision) fail(ErrorCode.STALE_REVISION, "This workflow changed before the result could be accepted.")
        if (state.pauseRequested || state.phase == Phase.PAUSED || !foreground) throw CancellationException("Foreground work paused")
    }
    private fun requireInput(condition: Boolean, message: String) { if (!condition) fail(ErrorCode.INVALID_INPUT, message) }
    private fun fail(code: ErrorCode, message: String): Nothing = throw ClearLineException(AppError(code, message))
    private fun phase(scope: WorkflowScope) = if (scope == WorkflowScope.RESEARCH) Phase.RESEARCHING else Phase.COMPARING
}
