package com.clearline.app

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.ViewModel
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import com.clearline.core.ModelKind

class MainActivity : ComponentActivity() {
    private val model: CheckInViewModel by viewModels()
    private val externalActions: PendingExternalActionsViewModel by viewModels()
    private var activityResumed = false
    private val microphone = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        // A result can be redelivered during onStart, before this Activity is interactive.
        // A real background transition clears the original intent, so a late grant cannot record.
        if (externalActions.pendingRecording != null) {
            if (granted) {
                externalActions.microphoneGranted = true
                completeExternalActions()
            } else {
                externalActions.clearRecording()
                model.permissionDenied()
            }
        }
    }
    private val modelDocument = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val kind = externalActions.importKind
        externalActions.importKind = null
        if (uri != null && kind != null) {
            externalActions.pendingImport = kind to uri
            completeExternalActions()
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val state by model.state.collectAsStateWithLifecycle()
            LaunchedEffect(state.screen) {
                // Protect secret-entry screenshots/recent-app previews. Tokens never enter saved UI state.
                if (state.screen == Screen.SETUP) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
            ClearLineUi(state, ::dispatch)
        }
    }
    private fun dispatch(event: UiEvent) {
        when (event) {
            is UiEvent.StartRecording -> {
                if (!event.consent || !activityResumed) return
                if (model.state.value.capture.active || model.state.value.busy) return
                if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) model.handle(event)
                else if (externalActions.pendingRecording == null) {
                    externalActions.pendingRecording = event
                    microphone.launch(Manifest.permission.RECORD_AUDIO)
                }
            }
            is UiEvent.ImportModel -> {
                if (!activityResumed) return
                if (model.state.value.capture.active || model.state.value.busy) {
                    model.reportActionError("Stop recording and wait for the current action before importing a model.")
                    return
                }
                if (externalActions.importKind == null && externalActions.pendingImport == null) {
                    externalActions.importKind = event.kind
                    modelDocument.launch(arrayOf("*/*"))
                }
            }
            is UiEvent.OpenSource -> {
                val uri = Uri.parse(event.url)
                if (uri.scheme in setOf("https", "http") && !uri.host.isNullOrBlank() && uri.userInfo == null) {
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE))
                    } catch (_: ActivityNotFoundException) {
                        model.reportActionError("No browser is available to open this public source.")
                    }
                }
            }
            else -> model.handle(event)
        }
    }
    private fun completeExternalActions() {
        if (!activityResumed) return
        val recording = externalActions.pendingRecording
        if (externalActions.microphoneGranted && recording != null) {
            externalActions.clearRecording()
            if (!model.state.value.capture.active && !model.state.value.busy) model.handle(recording)
            else model.reportActionError("Microphone permission is granted. Finish the current action, then tap Start recording again.")
        }
        externalActions.pendingImport?.let { (kind, uri) ->
            externalActions.pendingImport = null
            if (!model.state.value.capture.active && !model.state.value.busy) model.importModel(kind, uri)
            else model.reportActionError("The model was not imported because recording or another action is active. Choose the file again when it finishes.")
        }
    }
    override fun onStart() { super.onStart(); model.foreground() }
    override fun onPostResume() { super.onPostResume(); activityResumed = true; completeExternalActions() }
    override fun onPause() { activityResumed = false; super.onPause() }
    override fun onStop() {
        if (!isChangingConfigurations) {
            externalActions.clearRecording()
            model.background()
        }
        super.onStop()
    }
}

/** Retained for configuration changes only. Never saves microphone intent across process death. */
internal class PendingExternalActionsViewModel : ViewModel() {
    var pendingRecording: UiEvent.StartRecording? = null
    var microphoneGranted = false
    var importKind: ModelKind? = null
    var pendingImport: Pair<ModelKind, Uri>? = null

    fun clearRecording() {
        pendingRecording = null
        microphoneGranted = false
    }
}
