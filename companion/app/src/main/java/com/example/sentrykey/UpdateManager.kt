package com.example.sentrykey

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

// ----------------------------------------------------------------------------------
// GitHub Releases self-updater — `github` (sideload) flavor only.
// Google Play forbids apps from downloading/installing APKs to self-update, so
// this path is compiled into the github flavor, which carries the
// REQUEST_INSTALL_PACKAGES permission (src/github/AndroidManifest.xml). The play
// flavor uses Google Play In-App Updates (PlayUpdater) and never self-installs.
// Note: the unauthenticated GitHub API limit is 60 req/hour; debug builds poll
// every 30s for testing, release builds check once on launch.
// ----------------------------------------------------------------------------------

val GITHUB_UPDATES = !BuildConfig.USE_PLAY_UPDATES
const val UPDATE_POLL_SECONDS = 30L

object UpdateManager {
    private const val RELEASES_API =
        "https://api.github.com/repos/chrisdfennell/SentryKey/releases/latest"
    private const val APK_ASSET_NAME = "com.fennell.sentrykey.apk"

    data class ReleaseInfo(val tag: String, val apkUrl: String?)

    /** Fetches the latest release tag + APK download URL, or null on any error/throttle. */
    suspend fun fetchLatest(): ReleaseInfo? = withContext(Dispatchers.IO) {
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(RELEASES_API).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("Accept", "application/vnd.github+json")
                connectTimeout = 10_000
                readTimeout = 10_000
            }
            if (conn.responseCode != 200) return@withContext null
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(body)
            val tag = json.getString("tag_name")
            val assets = json.getJSONArray("assets")
            var apkUrl: String? = null
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                if (asset.getString("name") == APK_ASSET_NAME) {
                    apkUrl = asset.getString("browser_download_url")
                    break
                }
            }
            ReleaseInfo(tag, apkUrl)
        } catch (e: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    /** Downloads the APK to the cache dir, returning the file or null on failure. */
    suspend fun downloadApk(context: Context, url: String): File? = withContext(Dispatchers.IO) {
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = 15_000
                readTimeout = 60_000
            }
            if (conn.responseCode != 200) return@withContext null
            val file = File(context.cacheDir, "SentryKey-update.apk")
            conn.inputStream.use { input ->
                file.outputStream().use { output -> input.copyTo(output) }
            }
            file
        } catch (e: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    /** Launches the system package installer for the downloaded APK. */
    fun installApk(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}

/** Whether the app may install APKs (Android O+ requires explicit "unknown sources"). */
fun canInstallApks(context: Context): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        context.packageManager.canRequestPackageInstalls()
    } else {
        true
    }

/** Sends the user to the "allow install unknown apps" settings screen. */
fun requestInstallPermission(context: Context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}

@Composable
fun UpdateBanner(tag: String, busy: Boolean, onUpdate: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF13251A), RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                "⬇ Update available",
                color = Color(0xFF22C55E),
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp
            )
            Text(tag, color = Color.White, fontSize = 12.sp)
        }
        Button(
            onClick = onUpdate,
            enabled = !busy,
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF22C55E)),
            shape = RoundedCornerShape(8.dp)
        ) {
            if (busy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    color = Color(0xFF07080B),
                    strokeWidth = 2.dp
                )
            } else {
                Text("Update", color = Color(0xFF07080B), fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }
        }
    }
}
