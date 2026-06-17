package com.example.sentrykey

import android.util.Base64
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Optional passphrase encryption for the phone <-> watch BLE sync string.
 *
 * Why a custom HMAC-SHA1 construction instead of AES: the watch must be able to
 * decrypt, and the Garmin watch's native crypto (Toybox.Cryptography) faults
 * uncatchably for SHA-1 on some fenix 8 firmware, so the watch only has a pure
 * Monkey C HMAC-SHA1. This scheme is built entirely from HMAC-SHA1 so all three
 * platforms (Android/iOS/watch) can implement it from the same primitive.
 *
 * Construction (everything HMAC-SHA1 based):
 *   salt   = 16 random bytes
 *   nonce  =  8 random bytes
 *   key    = PBKDF2-HMAC-SHA1(passphrase, salt, ITERATIONS, dkLen=40)
 *   encKey = key[0..20), macKey = key[20..40)
 *   keystream block i = HMAC-SHA1(encKey, nonce || be32(i))   // 20 bytes each
 *   ciphertext = plaintext XOR keystream[0 .. plaintext.size)
 *   mac    = HMAC-SHA1(macKey, salt || nonce || ciphertext)   // 20 bytes
 *   wire   = "SKENC1:" + base64( salt(16) || nonce(8) || mac(20) || ciphertext )
 *
 * This is defense-in-depth over a BLE link that is already encrypted at the link
 * layer; it is deliberately simple and is NOT a security-audited protocol.
 *
 * NOTE: ITERATIONS is kept low because the watch runs PBKDF2 in interpreted
 * Monkey C. Raising it strengthens the KDF but slows watch sync proportionally.
 */
object SyncCrypto {
    const val MARKER = "SKENC1:"
    const val ITERATIONS = 1000

    private const val SALT_LEN = 16
    private const val NONCE_LEN = 8
    private const val MAC_LEN = 20
    private const val HMAC = "HmacSHA1"

    fun isEncrypted(payload: String): Boolean = payload.startsWith(MARKER)

    fun encrypt(plaintext: String, passphrase: String): String {
        val random = SecureRandom()
        val salt = ByteArray(SALT_LEN).also { random.nextBytes(it) }
        val nonce = ByteArray(NONCE_LEN).also { random.nextBytes(it) }
        return encryptWith(plaintext, passphrase, salt, nonce)
    }

    // Deterministic core, separated so a test can pin a fixed salt/nonce vector.
    internal fun encryptWith(plaintext: String, passphrase: String, salt: ByteArray, nonce: ByteArray): String {
        val (encKey, macKey) = deriveKeys(passphrase, salt)
        val plainBytes = plaintext.toByteArray(Charsets.UTF_8)
        val ciphertext = xorKeystream(plainBytes, encKey, nonce)
        val mac = hmac(macKey, salt + nonce + ciphertext)
        val blob = salt + nonce + mac + ciphertext
        return MARKER + Base64.encodeToString(blob, Base64.NO_WRAP)
    }

    /** Throws [BadPasswordException] if the MAC fails (wrong passphrase or tamper). */
    fun decrypt(payload: String, passphrase: String): String {
        if (!isEncrypted(payload)) throw BadPasswordException("Not an encrypted sync payload.")
        val blob = try {
            Base64.decode(payload.substring(MARKER.length), Base64.NO_WRAP)
        } catch (e: Exception) {
            throw BadPasswordException("Corrupt sync payload.")
        }
        val minLen = SALT_LEN + NONCE_LEN + MAC_LEN
        if (blob.size < minLen) throw BadPasswordException("Corrupt sync payload.")

        val salt = blob.copyOfRange(0, SALT_LEN)
        val nonce = blob.copyOfRange(SALT_LEN, SALT_LEN + NONCE_LEN)
        val mac = blob.copyOfRange(SALT_LEN + NONCE_LEN, minLen)
        val ciphertext = blob.copyOfRange(minLen, blob.size)

        val (encKey, macKey) = deriveKeys(passphrase, salt)
        val expected = hmac(macKey, salt + nonce + ciphertext)
        if (!constantTimeEquals(mac, expected)) {
            throw BadPasswordException("Wrong passphrase or corrupt payload.")
        }
        val plain = xorKeystream(ciphertext, encKey, nonce)
        return String(plain, Charsets.UTF_8)
    }

    // ---- primitives ----

    private fun deriveKeys(passphrase: String, salt: ByteArray): Pair<ByteArray, ByteArray> {
        val spec = PBEKeySpec(passphrase.toCharArray(), salt, ITERATIONS, 40 * 8)
        val dk = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1").generateSecret(spec).encoded
        return dk.copyOfRange(0, 20) to dk.copyOfRange(20, 40)
    }

    private fun xorKeystream(input: ByteArray, encKey: ByteArray, nonce: ByteArray): ByteArray {
        val out = ByteArray(input.size)
        var produced = 0
        var counter = 0
        while (produced < input.size) {
            val block = hmac(encKey, nonce + be32(counter))
            var i = 0
            while (i < block.size && produced < input.size) {
                out[produced] = (input[produced].toInt() xor block[i].toInt()).toByte()
                produced++; i++
            }
            counter++
        }
        return out
    }

    private fun hmac(key: ByteArray, message: ByteArray): ByteArray {
        val mac = Mac.getInstance(HMAC)
        mac.init(SecretKeySpec(key, HMAC))
        return mac.doFinal(message)
    }

    private fun be32(v: Int): ByteArray = byteArrayOf(
        ((v ushr 24) and 0xFF).toByte(),
        ((v ushr 16) and 0xFF).toByte(),
        ((v ushr 8) and 0xFF).toByte(),
        (v and 0xFF).toByte()
    )

    private fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].toInt() xor b[i].toInt())
        return diff == 0
    }
}
