package com.example.sentrykey

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Surfaced to the UI with a human-readable message (often straight from the server). */
class CloudException(message: String) : Exception(message)

/**
 * Talks to the SentryKey zero-knowledge backup server (see /server). Only ever
 * sends the derived authKey and the already-encrypted vault envelope — never the
 * master password or plaintext secrets.
 */
object CloudBackupClient {
    // ⬇⬇⬇ Default backup server. Overridable in the app's Cloud Backup screen
    // (e.g. http://10.0.2.2:3000 for an emulator hitting a local dev server).
    // This points at your domain — it works once the /server app is deployed
    // there over HTTPS with DNS pointed at the host.
    const val DEFAULT_SERVER_URL = "https://sentrykey.app"

    data class BackupMeta(val filename: String, val timestamp: String, val sizeBytes: Long)

    /** Registers a new account. Throws [CloudException] on any server-side rejection. */
    suspend fun register(baseUrl: String, username: String, authKey: String, inviteCode: String) =
        withContext(Dispatchers.IO) {
            val body = JSONObject().apply {
                put("username", username)
                put("authKey", authKey)
                put("inviteCode", inviteCode)
            }
            request(baseUrl, "/api/auth/register", "POST", body = body)
            Unit
        }

    /** Logs in and returns the session token. */
    suspend fun login(baseUrl: String, username: String, authKey: String): String =
        withContext(Dispatchers.IO) {
            val body = JSONObject().apply {
                put("username", username)
                put("authKey", authKey)
            }
            val res = request(baseUrl, "/api/auth/login", "POST", body = body)
            res.optString("token").ifEmpty { throw CloudException("Login did not return a session token.") }
        }

    /** Lists the user's stored backups, newest first. */
    suspend fun listBackups(baseUrl: String, token: String): List<BackupMeta> =
        withContext(Dispatchers.IO) {
            val res = request(baseUrl, "/api/backups", "GET", token = token)
            val arr = res.optJSONArray("backups") ?: JSONArray()
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                BackupMeta(
                    filename = o.getString("filename"),
                    timestamp = o.optString("timestamp", o.getString("filename")),
                    sizeBytes = o.optLong("sizeBytes", 0)
                )
            }
        }

    /** Uploads an encrypted envelope; returns the server-assigned filename. */
    suspend fun uploadBackup(baseUrl: String, token: String, envelopeJson: String): String =
        withContext(Dispatchers.IO) {
            val res = request(baseUrl, "/api/backups/upload", "POST", token = token, rawBody = envelopeJson)
            res.optString("filename")
        }

    /** Downloads a backup's raw encrypted envelope JSON. */
    suspend fun downloadBackup(baseUrl: String, token: String, filename: String): String =
        withContext(Dispatchers.IO) {
            requestRaw(baseUrl, "/api/backups/file/$filename", "GET", token = token)
        }

    // ---- HTTP plumbing ----

    private fun normalize(baseUrl: String) = baseUrl.trim().trimEnd('/')

    /** Performs a request and parses a JSON object response; throws [CloudException] on non-2xx. */
    private fun request(
        baseUrl: String,
        path: String,
        method: String,
        token: String? = null,
        body: JSONObject? = null,
        rawBody: String? = null
    ): JSONObject {
        val text = requestRaw(baseUrl, path, method, token, body?.toString() ?: rawBody)
        return if (text.isBlank()) JSONObject() else JSONObject(text)
    }

    private fun requestRaw(
        baseUrl: String,
        path: String,
        method: String,
        token: String? = null,
        payload: String? = null
    ): String {
        val url = URL(normalize(baseUrl) + path)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("Accept", "application/json")
            if (token != null) setRequestProperty("X-Session-Token", token)
            if (payload != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
        }
        try {
            if (payload != null) {
                conn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) {
                throw CloudException(parseError(text, code))
            }
            return text
        } catch (e: CloudException) {
            throw e
        } catch (e: Exception) {
            throw CloudException("Couldn't reach the server. Check the URL and your connection.")
        } finally {
            conn.disconnect()
        }
    }

    private fun parseError(body: String, code: Int): String =
        try {
            JSONObject(body).optString("error").ifEmpty { "Server error ($code)." }
        } catch (e: Exception) {
            "Server error ($code)."
        }
}
