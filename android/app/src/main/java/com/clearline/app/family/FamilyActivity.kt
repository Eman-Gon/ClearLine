package com.clearline.app.family

import android.Manifest
import android.app.Application
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.clearline.app.MainActivity
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

class FamilyViewModel(app: Application) : AndroidViewModel(app) {
    val connection = FamilyConnection(app)
    private val statusMutable = MutableStateFlow("Connect to the family backend.")
    val status = statusMutable.asStateFlow()
    private val reportsMutable = MutableStateFlow(JSONObject())
    val reports = reportsMutable.asStateFlow()
    private val schedulesMutable = MutableStateFlow(JSONArray())
    val schedules = schedulesMutable.asStateFlow()
    var busy by mutableStateOf(false); private set
    fun config() = connection.read()
    fun connect(base: String, pair: String, profile: String) = action {
        val previous = config()
        connection.save(JSONObject().put("base", base.trim().trimEnd('/')).put("pair", pair).put("profile", UUID.fromString(profile).toString()).put("device", previous.optString("device").ifBlank { UUID.randomUUID().toString() }))
        connection.request("/api/family/status")
        statusMutable.value = "Connected. Scheduling and Liquid run on the server."
        refreshNow()
    }
    fun refresh() { if (config().optString("pair").isNotEmpty() && !busy) action { refreshNow() } }
    private suspend fun refreshNow() {
        val profile = config().getString("profile")
        reportsMutable.value = connection.request("/api/family/profiles/$profile/reports")
        schedulesMutable.value = connection.request("/api/family/profiles/$profile/schedules").getJSONArray("schedules")
    }
    fun schedule(number: String, time: String, zone: String, daily: Boolean, enabled: Boolean, permissions: Boolean, search: Boolean, city: String, demo: Boolean) = action {
        val id = UUID.randomUUID().toString()
        val request = JSONObject().put("schedule_id", id).put("profile_id", config().getString("profile")).put("number", number.trim())
            .put("first_call_at", OffsetDateTime.parse(time).toString()).put("timezone", ZoneId.of(zone).id)
            .put("recurrence", if (daily) "daily" else "once").put("enabled", enabled)
            .put("permission_to_call", permissions).put("cloud_processing_approved", permissions).put("rawtree_storage_approved", permissions)
            .put("resource_search_approved", search).put("participant_preconsented_demo", false)
            .put("data_origin", if (demo) "consented_demo" else "real").put("city", city.trim())
        connection.request("/api/family/schedules/$id", "PUT", request)
        statusMutable.value = if (enabled) "Schedule enabled for the chosen number and time." else "Disabled schedule saved. No call will be placed."
        refreshNow()
    }
    fun pause(id: String) = action { connection.request("/api/family/schedules/$id/pause", "POST", JSONObject()); statusMutable.value = "Future calls paused."; refreshNow() }
    fun retry(id: String) = action { connection.request("/api/family/reports/$id/retry", "POST", JSONObject()); statusMutable.value = "Retrying report processing; no new call."; refreshNow() }
    fun push() { FamilyPush.register(getApplication()) { statusMutable.value = it } }
    private fun action(block: suspend () -> Unit) { if (busy) return; busy = true; viewModelScope.launch { try { block() } catch (e: Exception) { statusMutable.value = e.message ?: "Request failed" } finally { busy = false } } }
}

class FamilyActivity : ComponentActivity() {
    private val model: FamilyViewModel by viewModels()
    private val notifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { if (it) model.push() }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        setContent { MaterialTheme {
            val status by model.status.collectAsState(); val data by model.reports.collectAsState(); val schedules by model.schedules.collectAsState()
            val lifecycle = LocalLifecycleOwner.current.lifecycle
            LaunchedEffect(lifecycle) { lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { while (true) { model.refresh(); delay(5000) } } }
            val config = remember { model.config() }
            var base by remember { mutableStateOf(config.optString("base")) }; var pair by remember { mutableStateOf(config.optString("pair")) }
            var profile by remember { mutableStateOf(config.optString("profile").ifBlank { UUID.randomUUID().toString() }) }
            var number by remember { mutableStateOf("") }; var time by remember { mutableStateOf(OffsetDateTime.now().plusMinutes(10).withSecond(0).withNano(0).toString()) }
            var zone by remember { mutableStateOf(ZoneId.systemDefault().id) }; var city by remember { mutableStateOf("") }
            var permission by remember { mutableStateOf(false) }; var search by remember { mutableStateOf(false) }; var daily by remember { mutableStateOf(false) }; var enabled by remember { mutableStateOf(false) }; var demo by remember { mutableStateOf(true) }
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("ClearLine family", style = MaterialTheme.typography.headlineLarge)
                Text("Scheduled calls, remembered conversations, family updates.")
                Text(status, color = MaterialTheme.colorScheme.primary)
                Text("Backend connection", style = MaterialTheme.typography.titleLarge)
                OutlinedTextField(base, { base = it }, label = { Text("HTTPS backend origin") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(pair, { pair = it }, label = { Text("Pairing code") }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(profile, { profile = it }, label = { Text("Parent profile ID") }, modifier = Modifier.fillMaxWidth())
                Button({ model.connect(base, pair, profile) }, enabled = !model.busy) { Text("Save connection") }
                Button({ if (Build.VERSION.SDK_INT >= 33) notifications.launch(Manifest.permission.POST_NOTIFICATIONS) else model.push() }) { Text("Enable report notifications") }
                Text("New check-in schedule", style = MaterialTheme.typography.titleLarge)
                OutlinedTextField(number, { number = it }, label = { Text("Parent number, including +country code") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(time, { time = it }, label = { Text("First call (ISO time with offset)") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(zone, { zone = it }, label = { Text("Timezone, e.g. America/Los_Angeles") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(city, { city = it }, label = { Text("City for public resources (optional)") }, modifier = Modifier.fillMaxWidth())
                Choice("Daily at this local time", daily) { daily = it }
                Choice("Scripted demo — label and separate its history", demo) { demo = it }
                Choice("The phone owner permits scheduled calls; cloud processing, RawTree transcript/report storage and family sharing are approved. The parent will still be asked before recording.", permission) { permission = it }
                Choice("Allow concern-based public Nimble searches without names or raw transcripts", search) { search = it }
                Choice("Enable calling at the number and time above", enabled) { enabled = it }
                Button({ model.schedule(number, time, zone, daily, enabled, permission, search, city, demo) }, enabled = !model.busy && (!enabled || permission)) { Text("Save chosen schedule") }
                Text("Schedules", style = MaterialTheme.typography.titleLarge)
                for (i in 0 until schedules.length()) { val s = schedules.getJSONObject(i)
                    Text("${s.optString("number")} · ${s.optString("next_due")} · ${s.optString("recurrence")} · ${if (s.optBoolean("enabled")) "enabled" else "paused"}")
                    TextButton({ model.pause(s.getString("schedule_id")) }, enabled = !model.busy) { Text("Pause future calls") }
                }
                Text("Family reports", style = MaterialTheme.typography.titleLarge)
                val reports = data.optJSONArray("reports") ?: JSONArray()
                if (reports.length() == 0) Text("No completed reports yet. No history or measurements are assumed.")
                for (i in 0 until reports.length()) {
                    val r = reports.getJSONObject(i)
                    ReportCard(r)
                    if (r.optString("stage") == "failed" && r.getJSONObject("report").has("resume_stage")) TextButton({ model.retry(r.getString("session_id")) }, enabled = !model.busy) { Text("Retry report processing") }
                }
                val occurrences = data.optJSONArray("occurrences") ?: JSONArray()
                for (i in 0 until occurrences.length()) { val o = occurrences.getJSONObject(i); Text("Call ${o.optString("due_at")}: ${o.optString("state")} ${o.optString("error_code", "")}") }
                TextButton({ startActivity(Intent(this@FamilyActivity, MainActivity::class.java)) }) { Text("Open optional on-device voice recorder") }
            }
        } }
    }
}
@Composable private fun Choice(label: String, checked: Boolean, change: (Boolean) -> Unit) { Row(Modifier.fillMaxWidth()) { Checkbox(checked, change); Text(label, Modifier.padding(top = 12.dp)) } }
@Composable private fun ReportCard(row: JSONObject) {
    val report = row.getJSONObject("report"); val memory = report.optJSONObject("rawtree"); val liquid = report.optJSONObject("liquid"); val nimble = report.optJSONObject("nimble")
    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("${row.optString("stage")} · ${report.optString("data_origin", "unknown provenance")}", style = MaterialTheme.typography.titleMedium)
        if (!row.isNull("error_code")) Text("Needs attention: ${row.optString("error_code")}")
        Text("RawTree memory: ${memory?.optInt("session_count") ?: "unavailable"} prior sessions")
        val history = memory?.optJSONArray("sessions") ?: JSONArray()
        for (i in 0 until history.length()) { val s = history.getJSONObject(i); Text("${s.optString("created_at")} — ${s.optString("transcript").take(200)}") }
        Text("Liquid summary", style = MaterialTheme.typography.titleSmall)
        Text(liquid?.optString("summary") ?: "Analysis not complete.")
        val changes = liquid?.optJSONArray("changes") ?: JSONArray()
        for (i in 0 until changes.length()) { val c = changes.getJSONObject(i); Text(c.optString("description")); val evidence = c.optJSONArray("evidence") ?: JSONArray(); for (j in 0 until evidence.length()) { val e = evidence.getJSONObject(j); Text("${e.optString("session_id")}: “${e.optString("quote")}”") } }
        liquid?.let { Text(it.optString("limitations")); Text("Voice measurements unavailable in this transcript-only flow.") }
        Text("Nimble search: ${nimble?.optString("query") ?: "not run"}")
        nimble?.let { Text(it.optString("reason")); val results = it.optJSONArray("results") ?: JSONArray(); for (i in 0 until results.length()) { val s = results.getJSONObject(i); Text("${s.optString("title")}\n${s.optString("url")}\n${s.optString("description")}") } }
        Text("RawTree write: ${if (report.optJSONObject("rawtree_write")?.optBoolean("readback_verified") == true) "readback verified" else "not yet verified"}")
        Text("Notifications: ${row.optJSONArray("notifications")?.toString() ?: row.optString("notification_status")}")
    } }
}
