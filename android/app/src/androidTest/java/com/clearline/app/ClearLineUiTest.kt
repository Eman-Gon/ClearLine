package com.clearline.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.clearline.core.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Device UI checks. These are not substitutes for microphone or model acceptance. */
@RunWith(AndroidJUnit4::class)
class ClearLineUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun openingSavedSummaryAndDiagnosticsDispatchesNoWork() {
        val events = mutableListOf<UiEvent>()
        compose.setContent { ClearLineUi(AppUiState(screen = Screen.SUMMARY, session = session()), events::add) }
        compose.waitForIdle()
        assertTrue(events.isEmpty())
        compose.onNodeWithTag("memory-count").performScrollTo().assertTextContains("Unavailable")
        assertTrue(events.isEmpty())
        compose.onNodeWithText("Read query diagnostics").performScrollTo().performClick()
        assertEquals(listOf(UiEvent.Navigate(Screen.DIAGNOSTICS)), events)
    }

    @Test fun editedSearchRequiresExplicitApprovalAndEmitsExactReviewedQuery() {
        val events = mutableListOf<UiEvent>()
        compose.setContent { ClearLineUi(AppUiState(screen = Screen.FOLLOW_UP, session = session()), events::add) }
        compose.onNodeWithTag("resource-city").performScrollTo().performTextInput("Oakland")
        compose.onNodeWithTag("resource-query").performScrollTo().performTextReplacement("caregiver communication support near Oakland")
        compose.onNodeWithTag("search").performScrollTo().assertIsNotEnabled()
        assertTrue(events.isEmpty())
        compose.onNodeWithText("I approve this exact query and city for Nimble.").performScrollTo().performClick()
        compose.onNodeWithTag("search").performScrollTo().performClick()
        val event = events.single() as UiEvent.RequestResources
        assertEquals("caregiver communication support near Oakland", event.draft.query)
        assertEquals("Oakland", event.draft.city)
        assertEquals(CallConcern.WORD_FINDING, event.draft.basis.concern)
        assertEquals(4L, event.revision)
    }

    @Test fun snippetIsSeparateEditableAndApprovalCarriesDisplayedRevision() {
        val events = mutableListOf<UiEvent>()
        compose.setContent { ClearLineUi(AppUiState(screen = Screen.SUMMARY, session = session()), events::add) }
        compose.onNodeWithText("Descriptive measurements", useUnmergedTree = true).performScrollTo().performClick()
        compose.onNodeWithText("Optional transcript snippet and keyword").performScrollTo().performClick()
        compose.onNodeWithTag("export-snippet").performScrollTo().performTextReplacement("I had trouble finding words.")
        compose.onNodeWithTag("export-keyword").performScrollTo().performTextReplacement("communication")
        compose.onNodeWithTag("save-export").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("I approve these exact optional text values for RawTree.").performScrollTo().performClick()
        compose.onNodeWithTag("save-export").performScrollTo().performClick()
        val event = events.single() as UiEvent.ExportConsent
        assertEquals(setOf(ExportField.MEASUREMENTS, ExportField.TRANSCRIPT_SNIPPET), event.fields)
        assertEquals("I had trouble finding words.", event.snippet)
        assertEquals("communication", event.keyword)
        assertEquals(4L, event.inputRevision)
        assertEquals(0L, event.revision)
    }

    private fun session(): SessionSnapshot {
        val id = SessionId.new()
        val clipId = ClipId.new()
        val metrics = RecordingMetrics(20.0, 8, 24.0, 0.05, dataOrigin = DataOrigin.SYNTHETIC)
        val clip = CompletedLocalClip(id, clipId, "/fixture/unused.wav", "a".repeat(64), 20.0, DataOrigin.SYNTHETIC, createdAtMs = 100)
        return SessionSnapshot(sessionId = id, profileId = ProfileId.new(), phase = Phase.AWAITING_USER_CHOICE,
            inputRevision = 4, executionMode = ExecutionMode.SYNTHETIC_FIXTURE, dataOrigin = DataOrigin.SYNTHETIC,
            createdAtMs = 100, updatedAtMs = 200, consent = ConsentState(recording = true), metrics = metrics,
            captureFinished = true, summaryVersion = 1,
            clips = listOf(StoredClip(clip, ClipReceipt(id, clipId, clip.sha256, 120),
                AudioResult(clipId, "I had trouble finding words in conversation today.", metrics, ModelId("synthetic-ui-test"), 150))))
    }
}
