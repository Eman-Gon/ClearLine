package com.clearline.app.family

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import java.net.URI
import javax.net.ssl.HttpsURLConnection
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class FamilyConnection(private val context: Context) {
    private val prefs = context.getSharedPreferences("family-connection", Context.MODE_PRIVATE)
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey("clearline-family", null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("clearline-family", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    fun read(): JSONObject {
        val blob = prefs.getString("encrypted", null) ?: return JSONObject()
        return try {
            val bytes = Base64.decode(blob, Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            JSONObject(String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8))
        } catch (_: Exception) { JSONObject() }
    }
    fun save(value: JSONObject) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, key())
        val blob = cipher.iv + cipher.doFinal(value.toString().toByteArray(Charsets.UTF_8))
        prefs.edit().putString("encrypted", Base64.encodeToString(blob, Base64.NO_WRAP)).commit()
    }
    suspend fun request(path: String, method: String = "GET", body: JSONObject? = null): JSONObject = withContext(Dispatchers.IO) {
        val config = read(); val uri = URI(config.optString("base").trimEnd('/'))
        require(uri.scheme == "https" && uri.host != null && uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null && uri.path.isNullOrEmpty()) { "Use the HTTPS backend origin, without a path." }
        val conn = URI(uri.toString() + path).toURL().openConnection() as HttpsURLConnection
        try {
            conn.instanceFollowRedirects = false; conn.connectTimeout = 10000; conn.readTimeout = 30000; conn.requestMethod = method
            conn.setRequestProperty("Authorization", "Bearer " + config.optString("pair"))
            if (body != null) { conn.doOutput = true; conn.setRequestProperty("Content-Type", "application/json"); conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) } }
            val response = (if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream)?.use { input ->
                val output = java.io.ByteArrayOutputStream(); val chunk = ByteArray(8192)
                while (true) { val n = input.read(chunk); if (n < 0) break; require(output.size() + n <= 2_000_000) { "Response too large" }; output.write(chunk, 0, n) }
                output.toString("UTF-8")
            } ?: "{}"
            val json = JSONObject(response)
            check(conn.responseCode in 200..299) { json.optJSONObject("detail")?.optString("code") ?: "Backend request failed (${conn.responseCode})" }
            json
        } finally { conn.disconnect() }
    }
}
