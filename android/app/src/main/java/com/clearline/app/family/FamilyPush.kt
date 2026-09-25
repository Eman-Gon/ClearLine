package com.clearline.app.family

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import com.clearline.app.BuildConfig
import com.clearline.app.R
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject

object FamilyPush {
    fun initialize(context: Context): Boolean {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("clearline_reports", "Check-in reports", NotificationManager.IMPORTANCE_DEFAULT))
        if (FirebaseApp.getApps(context).isNotEmpty()) return true
        if (listOf(BuildConfig.FIREBASE_APP_ID, BuildConfig.FIREBASE_API_KEY, BuildConfig.FIREBASE_PROJECT_ID, BuildConfig.FIREBASE_SENDER_ID).any { it.isBlank() }) return false
        FirebaseApp.initializeApp(context, FirebaseOptions.Builder().setApplicationId(BuildConfig.FIREBASE_APP_ID).setApiKey(BuildConfig.FIREBASE_API_KEY).setProjectId(BuildConfig.FIREBASE_PROJECT_ID).setGcmSenderId(BuildConfig.FIREBASE_SENDER_ID).build())
        return true
    }
    fun register(context: Context, result: (String) -> Unit) {
        if (!initialize(context)) { result("Push setup missing: Firebase configuration must be added to the APK."); return }
        FirebaseMessaging.getInstance().isAutoInitEnabled = true
        FirebaseMessaging.getInstance().token.addOnSuccessListener { token ->
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val connection = FamilyConnection(context); val config = connection.read()
                    connection.request("/api/family/devices", "POST", JSONObject().put("device_id", config.getString("device")).put("profile_id", config.getString("profile")).put("token", token))
                    result("Push token registered. Handset delivery still requires a completed test.")
                } catch (_: Exception) { result("Push registration failed. Check the backend connection.") }
            }
        }.addOnFailureListener { result("Firebase token unavailable.") }
    }
}
class FamilyMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) { FamilyPush.register(this) {} }
    override fun onMessageReceived(message: RemoteMessage) {
        FamilyPush.initialize(this)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val session = message.data["session_id"] ?: return
        val intent = Intent(this, FamilyActivity::class.java).putExtra("session_id", session)
        val pending = PendingIntent.getActivity(this, session.hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(this, "clearline_reports").setSmallIcon(R.drawable.ic_clearline)
            .setContentTitle("ClearLine check-in update").setContentText("Open ClearLine to view the report or follow-up needed.")
            .setContentIntent(pending).setAutoCancel(true).build()
        getSystemService(NotificationManager::class.java).notify(session, 1, notification)
    }
}
