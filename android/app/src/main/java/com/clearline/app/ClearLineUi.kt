package com.clearline.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.clearline.core.*
import java.text.DateFormat
import java.util.Date
import java.util.Locale

private fun String.words() = lowercase().replace('_', ' ').replaceFirstChar { it.titlecase() }
private fun Number?.display(digits: Int = 1) = if (this == null) "Unavailable" else String.format(Locale.getDefault(), "% .${digits}f", toDouble()).trim()
private fun timestamp(value: Long) = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(value))

/** Rendering and state collection dispatch no work. Every command below is a user gesture. */
@Composable fun ClearLineUi(state: AppUiState, onEvent: (UiEvent) -> Unit) {
    ClearLineTheme {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            bottomBar = {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                    listOf(Screen.HOME to Icons.Default.Home, Screen.CHECK_IN to Icons.Default.PlayArrow, Screen.SUMMARY to Icons.Default.List, Screen.FOLLOW_UP to Icons.Default.Search, Screen.SETUP to Icons.Default.Settings).forEach { (screen, icon) ->
                        NavigationBarItem(selected = state.screen == screen, onClick = { onEvent(UiEvent.Navigate(screen)) }, icon = { Icon(icon, null) }, label = { Text(when (screen) { Screen.CHECK_IN -> "Check-in"; Screen.FOLLOW_UP -> "Follow-up"; else -> screen.name.words() }, maxLines = 1) }, modifier = Modifier.testTag("nav-${screen.name}"))
                    }
                }
            },
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 22.dp, vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("ClearLine", style = MaterialTheme.typography.titleLarge)
                    Text("NATIVE PROTOTYPE", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                }
                Info("Voice and model processing are designed to run on this phone. Hardware acceptance is still being verified.")
                state.error?.let { Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.medium) { Text(it, Modifier.padding(16.dp).testTag("error"), color = MaterialTheme.colorScheme.onErrorContainer) } }
                state.message?.let { Info(it) }
                if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                when (state.screen) {
                    Screen.HOME -> HomeScreen(state, onEvent)
                    Screen.CHECK_IN -> CaptureScreen(state, onEvent)
                    Screen.SUMMARY -> SummaryScreen(state, onEvent)
                    Screen.FOLLOW_UP -> FollowUpScreen(state, onEvent)
                    Screen.SETUP -> SetupScreen(state, onEvent)
                    Screen.DIAGNOSTICS -> DiagnosticsScreen(state)
                }
                Text("Demo prototype · No health interpretation is provided.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

@Composable private fun Header(eyebrow: String, title: String, description: String) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(eyebrow.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
        Text(title, style = MaterialTheme.typography.headlineLarge)
        Text(description, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
@Composable private fun Panel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) { Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp), content = content) }
}
@Composable private fun Info(text: String) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.medium) { Text(text, Modifier.fillMaxWidth().padding(14.dp), style = MaterialTheme.typography.bodySmall) }
}
@Composable private fun Choice(checked: Boolean, onChecked: (Boolean) -> Unit, title: String, detail: String? = null, enabled: Boolean = true) {
    Row(Modifier.fillMaxWidth().clickable(enabled = enabled) { onChecked(!checked) }.padding(vertical = 4.dp), verticalAlignment = Alignment.Top) {
        Checkbox(checked, onCheckedChange = onChecked, enabled = enabled)
        Column(Modifier.weight(1f).padding(top = 10.dp)) { Text(title); detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
    }
}
@Composable private fun HomeScreen(state: AppUiState, onEvent: (UiEvent) -> Unit) {
    Header("A little space for you", "A moment to check in.\nA place to pick up.", "Your voice, local history, and the next step waiting where you left it.")
    state.profile?.let { Text("${it.label} · ${it.dataOrigin.name.words()}", style = MaterialTheme.typography.bodySmall) }
    Panel {
        Text("A small pause. In your own words.", style = MaterialTheme.typography.headlineMedium)
        Text("Take 20–30 seconds to share something simple about your day.")
        Button({ onEvent(UiEvent.NewCheckIn) }, Modifier.fillMaxWidth().testTag("new-check-in"), enabled = !state.busy && !state.capture.active && state.profile != null) { Text("Start a check-in") }
        Text("Microphone access starts only after your consent and a button press.", style = MaterialTheme.typography.bodySmall)
    }
    Text("Open follow-ups", style = MaterialTheme.typography.titleLarge)
    if (state.openFollowUps.isEmpty()) Info("Your unfinished tasks will appear here. Opening one only reads its saved state.")
    state.openFollowUps.forEach { session ->
        Panel(Modifier.clickable { onEvent(UiEvent.SelectSession(session.sessionId)) }.testTag("open-${session.sessionId.value}")) {
            Text(session.phase.name.words(), style = MaterialTheme.typography.titleMedium)
            Text(session.pendingInput?.question ?: session.pendingAction?.proposal?.let { "Next step: ${it.javaClass.simpleName}" } ?: "Saved check-in · ${timestamp(session.updatedAtMs)}")
            TextButton({ onEvent(UiEvent.SelectSession(session.sessionId)) }) { Text("Reopen saved task →") }
        }
    }
    Text("Your check-in history", style = MaterialTheme.typography.titleLarge)
    if (state.history.isEmpty()) Info("No completed local summaries yet. Export consent and sponsor keys are not needed for local history.")
    state.history.forEach { summary ->
        Panel(Modifier.clickable { onEvent(UiEvent.SelectSession(summary.sessionId)) }.testTag("history-${summary.sessionId.value}")) {
            Text(timestamp(summary.completedAtMs), style = MaterialTheme.typography.titleMedium)
            Text("${summary.metrics.durationSeconds.display()} seconds · ${summary.metrics.recordingWpm.display()} recording wpm")
            Text("${summary.dataOrigin.name.words()} · summary ${summary.version}", style = MaterialTheme.typography.bodySmall)
            TextButton({ onEvent(UiEvent.SelectSession(summary.sessionId)) }) { Text("View summary and follow-up →") }
        }
    }
    Panel { Text("On this phone", style = MaterialTheme.typography.titleMedium); Text("Liquid: ${state.liquid.phase.name.words()}"); Text("Transcription: ${state.asr.phase.name.words()}"); TextButton({ onEvent(UiEvent.Navigate(Screen.SETUP)) }) { Text("Model setup and data settings") } }
}

@Composable private fun CaptureScreen(state: AppUiState, onEvent: (UiEvent) -> Unit) {
    var recordingConsent by rememberSaveable(state.session?.sessionId?.value) { mutableStateOf(state.session?.consent?.recording ?: false) }
    var exportMetrics by rememberSaveable(state.session?.sessionId?.value) { mutableStateOf(false) }
    Header("A little time, in your own words", "How has your day been?", "English-language demo. Find a quiet spot and share something non-sensitive.")
    Panel {
        Text("Before you begin", style = MaterialTheme.typography.titleLarge)
        Text("Audio is recorded into private phone storage, processed with embedded Whisper, and removed after terminal processing. Transcripts and check-in state remain until you delete them.")
        Choice(recordingConsent, { recordingConsent = it }, "I consent to recording this demonstration.", "There is no background recording.", enabled = !state.capture.active && state.session == null)
        Choice(exportMetrics, { exportMetrics = it }, "Allow descriptive measurement export", "Optional: measurements and method versions go to RawTree. Audio and transcripts do not. Off by default.", enabled = !state.capture.active && state.session == null)
        if (state.session != null) Text("Consent is already saved for this session. Export choices can be changed in its summary.", style = MaterialTheme.typography.bodySmall)
    }
    Panel {
        Text(if (state.capture.active) "Recording on this phone" else "Microphone off", style = MaterialTheme.typography.labelLarge)
        val meterColor = MaterialTheme.colorScheme.secondary
        Canvas(Modifier.fillMaxWidth().height(88.dp).testTag("live-level")) {
            for (index in 0..30) {
                val amplitude = if (state.capture.active) state.capture.rms.coerceIn(0f, 1f) else 0f
                val height = 4.dp.toPx() + amplitude * size.height * (1f - kotlin.math.abs(index - 15) / 20f)
                val x = size.width * (index + 1) / 32f
                drawLine(meterColor, Offset(x, size.height / 2 - height / 2), Offset(x, size.height / 2 + height / 2), strokeWidth = 4.dp.toPx(), cap = StrokeCap.Round)
            }
        }
        Text("%02d:%02d".format(state.capture.elapsedMs / 60000, state.capture.elapsedMs / 1000 % 60), style = MaterialTheme.typography.headlineLarge)
        Text(state.capture.message ?: "Aim for 20–30 seconds. Recording stops at 30 seconds.")
        val canRecord = state.session == null || state.session.phase == Phase.RECORDING || (state.session.phase == Phase.AWAITING_INPUT && state.session.pendingInput?.reason == "replacement_recording")
        Button(onClick = { if (state.capture.active) onEvent(UiEvent.StopRecording) else onEvent(UiEvent.StartRecording(recordingConsent, if (exportMetrics) setOf(ExportField.MEASUREMENTS) else emptySet())) }, Modifier.fillMaxWidth().testTag("record"), enabled = state.capture.active || (!state.busy && recordingConsent && canRecord)) { Icon(if (state.capture.active) Icons.Default.Close else Icons.Default.PlayArrow, null); Spacer(Modifier.width(8.dp)); Text(if (state.capture.active) "Stop recording" else "Start recording") }
        if (state.pendingCaptureAdmission) OutlinedButton({ onEvent(UiEvent.RetryAdmission) }, enabled = !state.busy) { Text("Retry complete clip admission") }
        if (state.session?.clips?.isNotEmpty() == true && !state.session.captureFinished) OutlinedButton({ onEvent(UiEvent.FinishCapture) }, enabled = !state.busy && !state.capture.active) { Text("Finish accepted capture") }
        if (state.asr.phase !in setOf(ModelPhase.READY, ModelPhase.INSTALLED)) Info("Transcription model: ${state.asr.phase.name.words()}. A complete clip can wait privately for model setup.")
    }
    state.session?.let { MemoryCounts(it) }
}

@Composable private fun SummaryScreen(state: AppUiState, onEvent: (UiEvent) -> Unit) {
    Header("Your check-in, at a glance", "A moment, captured.", "Descriptive measurements of the recording. No health interpretation is provided.")
    val session = state.session
    if (session == null) { Info("Choose a check-in from history, or make a new recording."); return }
    SessionStatus(session, state.busy, onEvent)
    if (state.pendingCaptureAdmission) OutlinedButton({ onEvent(UiEvent.RetryAdmission) }, enabled = !state.busy) { Text("Retry complete clip admission") }
    val metrics = session.metrics
    Panel {
        Text("The recording · ${session.dataOrigin.name.words()}", style = MaterialTheme.typography.titleLarge)
        listOf("Full recording length" to "${metrics?.durationSeconds.display()} sec", "Words recorded" to (metrics?.wordCount?.toString() ?: "Unavailable"), "Words ÷ full recording duration" to "${metrics?.recordingWpm.display()} wpm", "Recording amplitude (RMS)" to metrics?.energyRms.display(4), "Pauses" to "Unavailable", "Average voiced pitch" to "Unavailable").forEach { (label, value) -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium); Text(value, Modifier.padding(start = 12.dp), style = MaterialTheme.typography.bodyMedium) } }
        Text("Recording setup affects amplitude. Unavailable measurements are never replaced with zero.", style = MaterialTheme.typography.bodySmall)
    }
    Panel {
        Text("A little context", style = MaterialTheme.typography.titleLarge)
        val comparison = session.comparison
        Text("Local history · ${comparison?.baseline?.previousSessions?.size ?: 0} eligible prior sessions")
        if (comparison == null || comparison.baseline.status == BaselineStatus.INSUFFICIENT_HISTORY) Text("Insufficient history. Two eligible prior completed check-ins are needed.")
        else { comparison.recordingWpm?.let { Text("Recording pace: current ${it.current.display()} · mean ${it.mean.display()} · difference ${it.delta.display()}") }; comparison.energyRms?.let { Text("Amplitude: current ${it.current.display(4)} · mean ${it.mean.display(4)} · difference ${it.delta.display(4)}") } }
        if (session.dataOrigin == DataOrigin.SYNTHETIC) Info("Synthetic profile. This history is not a real speaker’s personal baseline.")
    }
    session.clips.filter { it.result != null && it.supersededBy == null }.forEach { clip ->
        var edit by remember(session.sessionId, session.inputRevision, clip.clip.clipId) { mutableStateOf(clip.result!!.transcript) }
        val revision = session.inputRevision
        Panel {
            Text("Local transcript", style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(edit, { edit = it.take(20000) }, Modifier.fillMaxWidth().testTag("transcript"), label = { Text("Correct this clip’s words") }, minLines = 3)
            OutlinedButton({ onEvent(UiEvent.CorrectTranscript(clip.clip.clipId, edit, revision)) }, enabled = !state.busy && edit.isNotBlank() && edit != clip.result!!.transcript) { Text("Save correction") }
            Text("A correction versions word measurements and comparison; acoustic measurements remain unchanged.", style = MaterialTheme.typography.bodySmall)
        }
    }
    ExportChoices(session, state.busy, onEvent)
    RawTreeMemoryPanel(session, state.busy, onEvent)
    Button({ onEvent(UiEvent.Navigate(Screen.FOLLOW_UP)) }, Modifier.fillMaxWidth()) { Text("Choose an optional follow-up →") }
    DeleteControl("Delete this check-in", "Delete this session’s local audio, transcript, history and unfinished work? Unsent exports are cancelled. Previously delivered cloud records cannot be recalled.") { onEvent(UiEvent.DeleteSession) }
}

@Composable private fun ExportChoices(session: SessionSnapshot, busy: Boolean, onEvent: (UiEvent) -> Unit) {
    var fields by remember(session.sessionId, session.consent.exportRevision) { mutableStateOf(session.consent.exportFields) }
    val transcript = CallInsights.transcriptFor(session)
    var snippet by remember(session.sessionId, session.inputRevision, session.consent.exportRevision) { mutableStateOf(session.consent.reviewedTranscriptSnippet ?: CallInsights.memorySnippet(transcript).orEmpty()) }
    var keyword by remember(session.sessionId, session.inputRevision, session.consent.exportRevision) { mutableStateOf(session.consent.reviewedKeyword ?: CallInsights.topKeyword(transcript).orEmpty()) }
    var textReviewed by remember(session.sessionId, session.inputRevision, session.consent.exportRevision, snippet, keyword) { mutableStateOf(false) }
    val includesText = ExportField.TRANSCRIPT_SNIPPET in fields
    val outgoingSnippet = if (includesText) snippet.trim().ifBlank { null } else null
    val outgoingKeyword = if (includesText) keyword.trim().ifBlank { null } else null
    val changed = fields != session.consent.exportFields || outgoingSnippet != session.consent.reviewedTranscriptSnippet || outgoingKeyword != session.consent.reviewedKeyword
    Panel {
        Text("Your cloud-export choice", style = MaterialTheme.typography.titleLarge)
        Text("Off means local-only records. Saving approves the displayed current summary and future selected projections. Earlier sessions are not automatically backfilled.")
        ExportField.entries.forEach { field -> Choice(field in fields, { checked ->
            fields = if (checked) fields + field else fields - field
            if (field == ExportField.MEASUREMENTS && !checked) fields = fields - ExportField.TRANSCRIPT_SNIPPET
            if (field == ExportField.TRANSCRIPT_SNIPPET) textReviewed = false
        }, when (field) { ExportField.EVENTS -> "Minimal event IDs and status"; ExportField.MEASUREMENTS -> "Descriptive measurements"; ExportField.WORKFLOW_COUNTS -> "Phase and saved action counts"; ExportField.PUBLIC_RESOURCES -> "Public resource facts and citations"; ExportField.TRANSCRIPT_SNIPPET -> "Optional transcript snippet and keyword" }, enabled = !busy && (field != ExportField.TRANSCRIPT_SNIPPET || ExportField.MEASUREMENTS in fields)) }
        if (includesText) {
            Text("Review the exact text for RawTree. Remove names, contact details and private information yourself; automatic topic selection is not a privacy guarantee. Leave either field blank to omit it.")
            OutlinedTextField(snippet, { snippet = it.take(200) }, Modifier.fillMaxWidth().testTag("export-snippet"), label = { Text("Snippet · up to 200 characters") }, minLines = 2)
            OutlinedTextField(keyword, { keyword = it.take(40) }, Modifier.fillMaxWidth().testTag("export-keyword"), label = { Text("Keyword · up to 40 characters") }, singleLine = true)
            Choice(textReviewed, { textReviewed = it }, "I approve these exact optional text values for RawTree.")
        }
        OutlinedButton({ onEvent(UiEvent.ExportConsent(fields, session.consent.exportRevision, outgoingSnippet, outgoingKeyword, session.inputRevision)) }, modifier = Modifier.testTag("save-export"), enabled = !busy && changed && (!includesText || textReviewed)) { Text(if (fields.isEmpty()) "Save local-only choice" else "Approve current summary export choices") }
        Text("${session.cloudSync.delivered} delivered · ${session.cloudSync.pending} pending · ${session.cloudSync.failed} failed", style = MaterialTheme.typography.bodySmall)
        Text("Revocation cancels unsent exports. It cannot recall already delivered or in-flight requests.", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable private fun SessionStatus(session: SessionSnapshot, busy: Boolean, onEvent: (UiEvent) -> Unit) {
    Panel {
        Text(if (session.pauseRequested) "Pausing at a safe boundary" else session.phase.name.words(), style = MaterialTheme.typography.titleLarge)
        Text("${session.executionMode.name.words()} · state ${session.stateVersion}", style = MaterialTheme.typography.bodySmall)
        session.pendingInput?.let { Text(it.question) }
        session.errors.forEach { Text(it.message, color = MaterialTheme.colorScheme.error) }
        if (session.phase in setOf(Phase.PAUSED, Phase.WAITING_NETWORK, Phase.WAITING_RETRY, Phase.AGENT_UNAVAILABLE, Phase.AWAITING_INPUT)) Button({ onEvent(UiEvent.Resume) }, enabled = !busy, modifier = Modifier.testTag("resume")) { Text("Resume unfinished work") }
        else if (session.phase != Phase.READY) OutlinedButton({ onEvent(UiEvent.Pause) }, enabled = !busy && !session.pauseRequested, modifier = Modifier.testTag("pause")) { Text("Pause task") }
    }
}

@Composable private fun FollowUpScreen(state: AppUiState, onEvent: (UiEvent) -> Unit) {
    Header("One next step, when you’re ready", "A little support, nearby.", "Review the exact public search before it goes to Nimble. The proposed concern comes from words in this check-in.")
    val session = state.session
    if (session == null) { Info("Choose a saved check-in first."); return }
    var category by rememberSaveable(session.sessionId.value) { mutableStateOf(session.approvedResources?.category ?: ResourceCategory.CAREGIVER_SUPPORT) }
    var city by rememberSaveable(session.sessionId.value) { mutableStateOf(session.approvedResources?.city ?: "") }
    val transcript = CallInsights.transcriptFor(session)
    val proposed = remember(transcript, city, category, session.comparison) {
        if (city.isBlank()) null else runCatching { CallInsights.buildQuery(transcript, city, category, session.comparison) }.getOrNull()
    }
    var query by remember(proposed) { mutableStateOf(proposed?.query.orEmpty()) }
    var approved by remember(session.sessionId, session.inputRevision, proposed, query) { mutableStateOf(false) }
    Panel {
        Text("What would be helpful?", style = MaterialTheme.typography.titleLarge)
        ResourceCategory.entries.forEach { value -> Row(Modifier.fillMaxWidth().clickable { category = value; approved = false }, verticalAlignment = Alignment.CenterVertically) { RadioButton(category == value, { category = value; approved = false }); Text(value.name.words()) } }
        OutlinedTextField(city, { city = it.take(120); approved = false }, Modifier.fillMaxWidth().testTag("resource-city"), label = { Text("City") }, singleLine = true)
        proposed?.let { draft ->
            OutlinedTextField(query, { query = it.take(400).filterNot(Char::isISOControl); approved = false }, Modifier.fillMaxWidth().testTag("resource-query"), label = { Text("Exact query to send to Nimble") }, minLines = 2)
            Text("Based on", style = MaterialTheme.typography.titleMedium)
            if (draft.basis.fallback) Text("No supported concern phrase was found. This is a category-and-city search.")
            else {
                Text("A ${draft.basis.concern.name.words().lowercase()} topic mentioned in this check-in.")
                draft.basis.transcriptExcerpt?.let { Text("Local excerpt: “$it”") }
            }
            draft.basis.topMetric?.let { Text("Local comparison: ${it.words()} differs by ${draft.basis.deltaPercent.display()}% across ${draft.basis.baselineSessionCount} prior sessions. This is descriptive context, not a health finding.", style = MaterialTheme.typography.bodySmall) }
            Text("The excerpt and local comparison stay on this phone. The exact query and city reveal your search interest to Nimble; remove private details before approving.", style = MaterialTheme.typography.bodySmall)
        }
        Choice(approved, { approved = it }, "I approve this exact query and city for Nimble.", enabled = proposed != null && query.isNotBlank())
        Button({ proposed?.let { onEvent(UiEvent.RequestResources(it.copy(query = query.trim()), session.inputRevision)) }; approved = false }, Modifier.fillMaxWidth().testTag("search"), enabled = !state.busy && approved && proposed != null && query.isNotBlank() && session.metrics != null) { Text("Find public resources") }
    }
    session.approvedResources?.let { request ->
        val committed = session.actions.any { it.status == ActionStatus.SUCCEEDED && (it.result as? ActionResult.Search)?.value?.approvalId == request.approvalId }
        Panel {
            Text(if (committed) "Nimble searched" else "Approved search · not yet committed", style = MaterialTheme.typography.titleMedium)
            Text(request.queryDraft?.query ?: "${request.category.name.words()} near ${request.city}")
            Text("City: ${request.city}", style = MaterialTheme.typography.bodySmall)
            request.queryDraft?.basis?.let { basis ->
                Text(if (basis.fallback) "Based on the approved category and city; no supported concern phrase was found." else "Based on a ${basis.concern.name.words().lowercase()} topic in the approved transcript version.")
                basis.transcriptExcerpt?.let { Text("Local basis: “$it”", style = MaterialTheme.typography.bodySmall) }
                basis.topMetric?.let { Text("${it.words()}: ${basis.deltaPercent.display()}% difference across ${basis.baselineSessionCount} prior local sessions.", style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
    SessionStatus(session, state.busy, onEvent)
    session.pendingInput?.takeIf { session.phase == Phase.AWAITING_INPUT && it.reason !in setOf("replacement_recording", "missing_model", "audio_model_missing") }?.let { input ->
        var answer by remember(session.sessionId, session.inputRevision, input) { mutableStateOf("") }
        Panel { Text(input.question); OutlinedTextField(answer, { answer = it.take(1000) }, label = { Text("Your answer") }); Button({ onEvent(UiEvent.Answer(answer, session.inputRevision)) }, enabled = answer.isNotBlank() && !state.busy) { Text("Send answer") } }
    }
    Text("Sources to come back to", style = MaterialTheme.typography.titleLarge)
    if (session.sources.isEmpty() && session.resources.isEmpty()) Info("No sources have been retrieved. Missing keys or connectivity leave this work unfinished.")
    session.resources.filter { candidate -> session.sources.none { it.sourceUrl == candidate.url } }.forEach { candidate ->
        Panel { Text("SEARCH CANDIDATE · NOT VERIFIED", style = MaterialTheme.typography.labelSmall); Text(candidate.title, style = MaterialTheme.typography.titleLarge); Text(candidate.description ?: "Description unavailable"); Text("Retrieved ${timestamp(candidate.retrievedAtMs)}", style = MaterialTheme.typography.bodySmall); TextButton({ onEvent(UiEvent.OpenSource(candidate.url)) }) { Text("Open public source ↗") }; Text("Contact details and availability are unknown.") }
    }
    session.sources.forEach { source ->
        Panel {
            Text(source.verificationStatus.name.words(), style = MaterialTheme.typography.labelSmall)
            Text(source.title, style = MaterialTheme.typography.titleLarge)
            Text(source.description ?: "Description unavailable")
            Text("Retrieved ${timestamp(source.retrievedAtMs)} · version ${source.version}", style = MaterialTheme.typography.bodySmall)
            TextButton({ onEvent(UiEvent.OpenSource(source.sourceUrl)) }) { Text("Open source in browser ↗") }
            listOf("Phone" to source.facts.phone, "Address" to source.facts.address, "Hours" to source.facts.hours).forEach { (name, fact) -> Text("$name: ${fact?.value ?: "Unknown"}") }
            Text("Availability: unknown. A source statement does not independently establish availability.", style = MaterialTheme.typography.bodySmall)
            var expanded by remember(source.evidenceId) { mutableStateOf(false) }
            TextButton({ expanded = !expanded }) { Text(if (expanded) "Hide supporting passages" else "View supporting passages") }
            if (expanded) source.passages.forEach { passage -> Text(passage.text, style = MaterialTheme.typography.bodyMedium); Text(passage.passageId, style = MaterialTheme.typography.bodySmall) }
        }
    }
    Panel { Text("The steps so far", style = MaterialTheme.typography.titleLarge); if (session.actions.isEmpty()) Text("No committed agent actions yet."); session.actions.takeLast(8).forEach { action -> Text("${action.proposal.javaClass.simpleName} · ${action.status.name.words()}"); Text(timestamp(action.completedAtMs ?: action.createdAtMs), style = MaterialTheme.typography.bodySmall) } }
    RawTreeMemoryPanel(session, state.busy, onEvent)
}

@Composable private fun SetupScreen(state: AppUiState, onEvent: (UiEvent) -> Unit) {
    Header("On this phone", "Make room for a moment.", "Install models once, then process private check-ins locally. Sponsor connections are optional.")
    ModelPanel("Local voice transcription", state.asr, state.busy, onEvent)
    ModelPanel("Liquid local agent", state.liquid, state.busy, onEvent)
    Panel {
        Text("Local profiles", style = MaterialTheme.typography.titleLarge)
        state.profiles.forEach { profile -> TextButton({ onEvent(UiEvent.SelectProfile(profile.profileId)) }) { Text("${if (profile.profileId == state.profile?.profileId) "• " else ""}${profile.label} · ${profile.dataOrigin.name.words()}") } }
        var name by remember { mutableStateOf("") }
        OutlinedTextField(name, { name = it.take(80) }, label = { Text("New local profile label") }, modifier = Modifier.fillMaxWidth())
        OutlinedButton({ onEvent(UiEvent.CreateProfile(name.trim())); name = "" }, enabled = name.isNotBlank() && !state.busy) { Text("Create local profile") }
        if (state.profile != null) DeleteControl("Delete profile and local history", "Delete this profile, all its sessions, transcripts, audio, jobs, evidence and queued exports from this phone? Delivered cloud records are not deleted. This cannot be undone.") { onEvent(UiEvent.DeleteProfile) }
    }
    Panel {
        Text("Optional public services", style = MaterialTheme.typography.titleLarge)
        Text("Keys are encrypted in private phone storage with Android Keystore. They are never included in models, recordings or exports. No Liquid API key is needed.")
        Sponsor.entries.forEach { sponsor ->
            var secret by remember(sponsor) { mutableStateOf("") } // Intentionally not rememberSaveable.
            Text("${sponsor.name.words()} · ${state.credentials.find { it.sponsor == sponsor }?.state?.name?.words() ?: "Missing"}")
            OutlinedTextField(secret, { secret = it.take(8192) }, label = { Text("${sponsor.name.words()} key") }, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false), singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { Button({ val value = secret.toCharArray(); secret = ""; onEvent(UiEvent.SetCredential(sponsor, value)) }, enabled = secret.isNotBlank() && !state.busy) { Text("Save key") }; TextButton({ secret = ""; onEvent(UiEvent.ClearCredential(sponsor)) }, enabled = !state.busy) { Text("Clear key") } }
        }
        var database by remember(state.sponsorConfiguration.rawTreeDatabase) { mutableStateOf(state.sponsorConfiguration.rawTreeDatabase ?: "default") }
        var nimble by remember(state.sponsorConfiguration.nimbleEnabled) { mutableStateOf(state.sponsorConfiguration.nimbleEnabled) }
        var rawTree by remember(state.sponsorConfiguration.rawTreeEnabled) { mutableStateOf(state.sponsorConfiguration.rawTreeEnabled) }
        OutlinedTextField(database, { database = it.take(128) }, label = { Text("RawTree database") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Choice(nimble, { nimble = it }, "Enable Nimble public research", "Each exact query and city still need approval.")
        Choice(rawTree, { rawTree = it }, "Enable RawTree exports", "Per-session export choices remain off until separately approved.")
        OutlinedButton({ onEvent(UiEvent.SetSponsorConfiguration(SponsorConfiguration(nimble, rawTree, database.ifBlank { null }))) }, enabled = !state.busy && database.matches(Regex("[A-Za-z0-9_-]{1,128}"))) { Text("Save service settings") }
    }
    Panel { Text("What stays and what leaves", style = MaterialTheme.typography.titleLarge); Text("Audio, full transcripts, prompts, model output and full checkpoints stay in private phone storage. Audio is removed after terminal processing no longer needs it. Other local records remain until deletion."); Text("An exact approved concern query, city and source URLs go to Nimble. Only separately selected fields go to RawTree, including an optional exact snippet and keyword you review. Opening a source uses your external browser and leaves this app’s privacy boundary."); Text("Backups and device transfer are excluded by app policy; actual-device verification is still required. Deletion does not promise forensic erasure.", style = MaterialTheme.typography.bodySmall) }
    TextButton({ onEvent(UiEvent.Navigate(Screen.DIAGNOSTICS)) }) { Text("Read native diagnostics") }
}

@Composable private fun ModelPanel(title: String, status: ModelStatus, busy: Boolean, onEvent: (UiEvent) -> Unit) {
    Panel {
        Text(title, style = MaterialTheme.typography.titleLarge)
        Text(status.phase.name.words(), Modifier.testTag("model-${status.kind.name}"))
        status.error?.let { Text(it.message, color = MaterialTheme.colorScheme.error) }
        status.identity?.let { Text("${it.modelId.value} · ${it.quantization} · ${it.language}", style = MaterialTheme.typography.bodySmall); Text("${if (status.phase in setOf(ModelPhase.INSTALLED, ModelPhase.READY)) "Verified" else "Pinned"} file: ${it.filename}", style = MaterialTheme.typography.bodySmall) }
        if (status.progressBytes > 0) Text("${status.progressBytes / (1024 * 1024)} MiB received", style = MaterialTheme.typography.bodySmall)
        if (status.phase in setOf(ModelPhase.MISSING, ModelPhase.FAILED)) {
            Text("Download or import the pinned model. Its digest is checked before it can be loaded. No voice data accompanies a model download.", style = MaterialTheme.typography.bodySmall)
            Button({ onEvent(UiEvent.DownloadModel(status.kind)) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Approve model download") }
            OutlinedButton({ onEvent(UiEvent.ImportModel(status.kind)) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Import model file") }
        }
        if (status.phase == ModelPhase.INSTALLED) Button({ onEvent(UiEvent.LoadModel(status.kind)) }, enabled = !busy) { Text("Load on this phone") }
        if (status.phase == ModelPhase.READY) OutlinedButton({ onEvent(UiEvent.UnloadModel(status.kind)) }, enabled = !busy) { Text("Unload model") }
    }
}
@Composable private fun MemoryCounts(session: SessionSnapshot) {
    Panel {
        Text("RawTree memory", style = MaterialTheme.typography.titleLarge)
        val memory = session.rawTreeMemory
        Text("${memory?.dataPointCount?.toString() ?: "Unavailable"} confirmed data points · ${memory?.sessionCount?.toString() ?: "Unavailable"} distinct sessions", Modifier.testTag("memory-count"))
        Text("Count scope: exported logical summaries for this profile and ${session.dataOrigin.name.words().lowercase()} origin. Revisions and repeated reads are not new data points.", style = MaterialTheme.typography.bodySmall)
        Text("${session.cloudSync.pending} queued · ${session.cloudSync.delivered} acknowledged exports · ${session.cloudSync.failed} failed", style = MaterialTheme.typography.bodySmall)
        memory?.let { Text("${if (it.cached) "Cached" else "Last queried"} · ${timestamp(it.retrievedAtMs)}", style = MaterialTheme.typography.bodySmall) }
        Text("Counts update after a completed clip is processed, exported and queried. This version does not stream ten-second segments.", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable private fun RawTreeMemoryPanel(session: SessionSnapshot, busy: Boolean, onEvent: (UiEvent) -> Unit) {
    MemoryCounts(session)
    val memory = session.rawTreeMemory
    Panel {
        Text("From RawTree (${memory?.previousSessions?.size ?: 0} prior sessions)", style = MaterialTheme.typography.titleLarge)
        Text("Up to 8 latest eligible prior sessions in the preceding 56 days, matching profile, task, methods and origin. This bounded view does not establish complete eight-week coverage.", style = MaterialTheme.typography.bodySmall)
        when (memory?.status) {
            null -> Text("Exported history has not been read.")
            RawTreeMemoryStatus.UNAVAILABLE -> Text("Exported history unavailable. Local history and recovery remain on this phone.")
            RawTreeMemoryStatus.INSUFFICIENT_HISTORY -> Text("Insufficient exported history. Two eligible prior sessions are needed for this comparison.")
            RawTreeMemoryStatus.AVAILABLE -> Unit
        }
        if (memory != null) {
            Text("Query window: ${timestamp(memory.windowStartMs)} to ${timestamp(memory.windowEndMs)}", style = MaterialTheme.typography.bodySmall)
            if (memory.previousSessions.isNotEmpty()) Text("Returned history span: ${timestamp(memory.previousSessions.minOf { it.timestampMs })} to ${timestamp(memory.previousSessions.maxOf { it.timestampMs })}", style = MaterialTheme.typography.bodySmall)
            val currentCloud = memory.currentSession?.takeIf { it.summaryVersion == session.summaryVersion && it.metrics == session.metrics }
            Text(if (currentCloud != null) "Current: confirmed RawTree summary ${currentCloud.summaryVersion}" else "Current: local accepted summary; current cloud version is not confirmed.", style = MaterialTheme.typography.bodySmall)
            Row(Modifier.fillMaxWidth()) { Text("Measurement", Modifier.weight(1f)); Text("Prior mean", Modifier.weight(1f)); Text("Current", Modifier.weight(1f)) }
            listOf(Triple("Recording wpm", memory.meanRecordingWpm, session.metrics?.recordingWpm), Triple("RMS", memory.meanEnergyRms, session.metrics?.energyRms)).forEach { (label, mean, current) ->
                val digits = if (label == "RMS") 4 else 1
                Row(Modifier.fillMaxWidth()) { Text(label, Modifier.weight(1f)); Text(mean.display(digits), Modifier.weight(1f)); Text(current.display(digits), Modifier.weight(1f)) }
                val delta = if (mean != null && current != null) current - mean else null
                val percent = if (mean != null && mean > 0 && delta != null) (delta / mean * 100).takeIf { it.isFinite() } else null
                Text("Difference: ${delta.display(digits)} · percent: ${percent?.let { "${it.display()}%" } ?: "Unavailable"}", style = MaterialTheme.typography.bodySmall)
            }
            Text("Pauses, pitch, emotion and drift score: not measured.", style = MaterialTheme.typography.bodySmall)
            Text("Exported session timeline", style = MaterialTheme.typography.titleMedium)
            (listOfNotNull(currentCloud) + memory.previousSessions).forEach { row ->
                Text("${if (row.sessionId == session.sessionId) "Current · " else ""}${timestamp(row.timestampMs)} · ${row.metrics.recordingWpm.display()} recording wpm")
                if (ExportField.TRANSCRIPT_SNIPPET in session.consent.exportFields) {
                    row.topKeyword?.let { Text("Approved keyword: $it", style = MaterialTheme.typography.bodySmall) }
                    row.transcriptSnippet?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
                Text("RawTree · summary ${row.summaryVersion}", style = MaterialTheme.typography.bodySmall)
            }
        }
        OutlinedButton({ onEvent(UiEvent.RefreshExportedMemory) }, enabled = !busy && session.metrics != null, modifier = Modifier.testTag("refresh-memory")) { Text("Refresh exported history") }
        TextButton({ onEvent(UiEvent.Navigate(Screen.DIAGNOSTICS)) }) { Text("Read query diagnostics") }
    }
}

@Composable private fun DiagnosticsScreen(state: AppUiState) {
    Header("Read-only diagnostics", "What was actually returned.", "Opening this screen never sends a query or starts a job.")
    val session = state.session
    val memory = session?.rawTreeMemory
    Panel {
        Text("RawTree", style = MaterialTheme.typography.titleLarge)
        if (memory == null) Text("No memory query has completed for the selected check-in.")
        else {
            Text("Template: ${memory.diagnostics.templateId.name}")
            Text("Window: ${timestamp(memory.windowStartMs)} to ${timestamp(memory.windowEndMs)} · limit 8")
            Text("Returned rows: ${memory.diagnostics.returnedRows?.toString() ?: "Unavailable"} · eligible prior sessions: ${memory.previousSessions.size}")
            Text("Measured latency: ${memory.diagnostics.latencyMs?.let { "$it ms" } ?: "Unavailable"}")
            Text("${if (memory.cached) "Cached" else "Queried"}: ${timestamp(memory.retrievedAtMs)}")
            Text("Status: ${memory.status.name.words()} · error: ${memory.diagnostics.error?.name ?: "None"}")
        }
    }
    Panel {
        Text("Last committed sponsor action", style = MaterialTheme.typography.titleLarge)
        val action = session?.actions?.lastOrNull { it.result is ActionResult.Search || it.result is ActionResult.Extract }
        if (action == null) Text("No committed search or extraction.")
        else { Text("${action.proposal.javaClass.simpleName} · ${action.status.name.words()}"); Text(timestamp(action.completedAtMs ?: action.createdAtMs)) }
        Text("Only template identifiers, counts, times and typed errors appear here. Private requests, credentials and transcripts are excluded.", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable private fun DeleteControl(label: String, explanation: String, confirm: () -> Unit) {
    var show by remember { mutableStateOf(false) }
    TextButton({ show = true }) { Text(label, color = MaterialTheme.colorScheme.error) }
    if (show) AlertDialog(onDismissRequest = { show = false }, title = { Text(label) }, text = { Text(explanation) }, confirmButton = { TextButton({ show = false; confirm() }) { Text("Delete") } }, dismissButton = { TextButton({ show = false }) { Text("Keep") } })
}
