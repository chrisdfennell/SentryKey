package com.fennell.sentrykey.wear

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

data class WearAccount(val label: String, val secret: String)

/** RFC 4648 Base32 decode (same as the phone/watch apps). */
fun decodeBase32(base32: String): ByteArray {
    val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
    val clean = base32.trim().uppercase().replace("=", "").replace(" ", "")
    var buffer = 0
    var bitsLeft = 0
    val out = ArrayList<Byte>()
    for (c in clean) {
        val v = alphabet.indexOf(c)
        if (v < 0) continue
        buffer = (buffer shl 5) or v
        bitsLeft += 5
        if (bitsLeft >= 8) {
            bitsLeft -= 8
            out.add(((buffer shr bitsLeft) and 0xFF).toByte())
        }
    }
    return out.toByteArray()
}

/** RFC 6238 TOTP (SHA-1, 6 digits, 30s) — matches the phone's getTOTPCode. */
fun getTOTPCode(secret: String, timeSeconds: Long): String {
    return try {
        val key = decodeBase32(secret)
        if (key.isEmpty()) return "000000"
        val epoch30 = timeSeconds / 30
        val data = ByteArray(8)
        var temp = epoch30
        for (i in 7 downTo 0) {
            data[i] = (temp and 0xFF).toByte()
            temp = temp shr 8
        }
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(key, "HmacSHA1"))
        val hash = mac.doFinal(data)
        val offset = hash[hash.size - 1].toInt() and 0x0F
        val binary = ((hash[offset].toInt() and 0x7F) shl 24) or
            ((hash[offset + 1].toInt() and 0xFF) shl 16) or
            ((hash[offset + 2].toInt() and 0xFF) shl 8) or
            (hash[offset + 3].toInt() and 0xFF)
        (binary % 1000000).toString().padStart(6, '0')
    } catch (e: Exception) {
        "000000"
    }
}
