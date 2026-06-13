package com.example.sentrykey

import android.net.Uri
import android.util.Base64

/**
 * Imports accounts from Google Authenticator's "Export accounts" QR codes,
 * which encode `otpauth-migration://offline?data=<base64 protobuf>`.
 *
 * The protobuf is parsed by hand (no dependency). Schema (MigrationPayload):
 *   repeated OtpParameters otp_parameters = 1;
 *   OtpParameters { bytes secret=1; string name=2; string issuer=3; ... }
 * GA stores raw secret bytes, so we Base32-encode them into our format.
 */
object GoogleAuthImport {

    fun isMigrationUri(raw: String): Boolean =
        raw.startsWith("otpauth-migration://", ignoreCase = true)

    fun parse(raw: String): List<TwoFactorAccount> {
        if (!isMigrationUri(raw)) return emptyList()
        val marker = "data="
        val idx = raw.indexOf(marker)
        if (idx < 0) return emptyList()
        val dataParam = raw.substring(idx + marker.length).substringBefore('&')
        return try {
            val decoded = Uri.decode(dataParam)
            val bytes = Base64.decode(decoded, Base64.DEFAULT)
            parsePayload(bytes)
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun parsePayload(data: ByteArray): List<TwoFactorAccount> {
        val out = ArrayList<TwoFactorAccount>()
        val reader = ProtoReader(data)
        while (reader.hasMore()) {
            val (field, wire) = reader.readTag()
            if (field == 1 && wire == 2) {
                parseOtpParameters(reader.readBytes())?.let { out.add(it) }
            } else {
                reader.skip(wire)
            }
        }
        return out
    }

    private fun parseOtpParameters(data: ByteArray): TwoFactorAccount? {
        val reader = ProtoReader(data)
        var secret: ByteArray? = null
        var name = ""
        var issuer = ""
        while (reader.hasMore()) {
            val (field, wire) = reader.readTag()
            when {
                field == 1 && wire == 2 -> secret = reader.readBytes()
                field == 2 && wire == 2 -> name = String(reader.readBytes(), Charsets.UTF_8)
                field == 3 && wire == 2 -> issuer = String(reader.readBytes(), Charsets.UTF_8)
                else -> reader.skip(wire)
            }
        }
        val raw = secret ?: return null
        val base32 = base32Encode(raw)
        if (base32.isEmpty()) return null

        val label = when {
            issuer.isNotEmpty() && name.isNotEmpty() && !name.contains(issuer, ignoreCase = true) ->
                "$issuer:$name"
            name.isNotEmpty() -> name
            issuer.isNotEmpty() -> issuer
            else -> "Account"
        }
        return TwoFactorAccount(label, base32)
    }

    /** Minimal protobuf wire-format reader (varint + length-delimited). */
    private class ProtoReader(private val data: ByteArray) {
        private var pos = 0
        fun hasMore() = pos < data.size

        fun readVarint(): Long {
            var result = 0L
            var shift = 0
            while (pos < data.size) {
                val b = data[pos++].toInt() and 0xFF
                result = result or ((b and 0x7F).toLong() shl shift)
                if (b and 0x80 == 0) break
                shift += 7
            }
            return result
        }

        fun readTag(): Pair<Int, Int> {
            val tag = readVarint()
            return Pair((tag ushr 3).toInt(), (tag and 0x7L).toInt())
        }

        fun readBytes(): ByteArray {
            val len = readVarint().toInt()
            val end = (pos + len).coerceAtMost(data.size)
            val slice = data.copyOfRange(pos, end)
            pos = end
            return slice
        }

        fun skip(wire: Int) {
            when (wire) {
                0 -> readVarint()
                1 -> pos += 8
                2 -> pos += readVarint().toInt()
                5 -> pos += 4
            }
        }
    }
}

/** RFC 4648 Base32 encoder (no padding) — inverse of the app's decodeBase32. */
fun base32Encode(data: ByteArray): String {
    val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
    val sb = StringBuilder()
    var buffer = 0
    var bits = 0
    for (byte in data) {
        buffer = (buffer shl 8) or (byte.toInt() and 0xFF)
        bits += 8
        while (bits >= 5) {
            bits -= 5
            sb.append(alphabet[(buffer ushr bits) and 0x1F])
        }
        buffer = buffer and ((1 shl bits) - 1)
    }
    if (bits > 0) {
        sb.append(alphabet[(buffer shl (5 - bits)) and 0x1F])
    }
    return sb.toString()
}

/** Resolves a scanned QR to accounts: GA migration (many) or a single otpauth. */
fun parseScanResult(raw: String): List<TwoFactorAccount> =
    if (GoogleAuthImport.isMigrationUri(raw)) {
        GoogleAuthImport.parse(raw)
    } else {
        parseOtpauthUri(raw)?.let { listOf(it) } ?: emptyList()
    }
