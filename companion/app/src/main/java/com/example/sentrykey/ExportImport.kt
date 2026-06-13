package com.example.sentrykey

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.net.Uri
import androidx.core.content.FileProvider
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Standards-based (otpauth://) export & import so accounts move freely between
 * SentryKey and any other authenticator (Google Authenticator, Authy, 1Password…).
 */
object ExportImport {

    /** Builds a standard `otpauth://totp/...` URI for one account. */
    fun accountToOtpauthUri(account: TwoFactorAccount): String {
        val issuer = account.label.substringBefore(":").substringBefore(" (").trim()
        val base = "otpauth://totp/${Uri.encode(account.label)}?secret=${Uri.encode(account.secret)}"
        return if (issuer.isNotEmpty()) "$base&issuer=${Uri.encode(issuer)}" else base
    }

    /** Serializes the vault to a pretty JSON backup (includes otpauth URIs). */
    fun accountsToJson(accounts: List<TwoFactorAccount>): String {
        val arr = JSONArray()
        for (a in accounts) {
            arr.put(JSONObject().apply {
                put("label", a.label)
                put("secret", a.secret)
                put("otpauth", accountToOtpauthUri(a))
            })
        }
        return JSONObject().apply {
            put("app", "SentryKey")
            put("version", 1)
            put("accounts", arr)
        }.toString(2)
    }

    /** Parses an imported file: either our JSON backup or a blob of otpauth URIs. */
    fun parseImport(text: String): List<TwoFactorAccount> {
        val trimmed = text.trim()
        return try {
            if (trimmed.startsWith("{") || trimmed.startsWith("[")) parseJson(trimmed)
            else parseOtpauthBlob(trimmed)
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun parseJson(text: String): List<TwoFactorAccount> {
        val arr = if (text.startsWith("[")) {
            JSONArray(text)
        } else {
            JSONObject(text).optJSONArray("accounts") ?: JSONArray()
        }
        val out = ArrayList<TwoFactorAccount>()
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            val secret = obj.optString("secret")
            if (secret.isNotEmpty()) {
                val label = obj.optString("label").ifEmpty { "Account" }
                out.add(TwoFactorAccount(label, secret.uppercase()))
            } else {
                val otp = obj.optString("otpauth")
                if (otp.isNotEmpty()) parseOtpauthUri(otp)?.let { out.add(it) }
            }
        }
        return out
    }

    private fun parseOtpauthBlob(text: String): List<TwoFactorAccount> =
        text.split(Regex("\\s+"))
            .filter { it.startsWith("otpauth://", ignoreCase = true) }
            .mapNotNull { parseOtpauthUri(it) }

    /** Writes a JSON backup to the cache dir and opens the system share sheet. */
    fun shareBackup(context: Context, accounts: List<TwoFactorAccount>) {
        val file = File(context.cacheDir, "sentrykey-vault.json")
        file.writeText(accountsToJson(accounts))
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Export SentryKey vault"))
    }

    /** Renders an otpauth URI as a scannable QR bitmap. */
    fun generateQrBitmap(content: String, size: Int = 600): Bitmap {
        val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        for (x in 0 until size) {
            for (y in 0 until size) {
                bmp.setPixel(x, y, if (matrix.get(x, y)) AndroidColor.BLACK else AndroidColor.WHITE)
            }
        }
        return bmp
    }
}
