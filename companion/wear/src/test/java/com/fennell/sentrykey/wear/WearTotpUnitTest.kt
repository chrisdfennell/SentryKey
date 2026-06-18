package com.fennell.sentrykey.wear

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM unit tests for the Wear OS TOTP engine (Totp.kt). Uses the SAME
 * RFC 6238 known-answer vectors as the phone app and server, so every device
 * that generates codes is provably in agreement. No watch/emulator needed:
 *   ./gradlew :wear:testDebugUnitTest
 */
class WearTotpUnitTest {

    // RFC 6238 reference seed "12345678901234567890" (20 ASCII bytes) in Base32.
    private val rfcSeedB32 = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ"

    @Test
    fun decodeBase32_decodesRfcSeed() {
        assertEquals("12345678901234567890", String(decodeBase32(rfcSeedB32), Charsets.US_ASCII))
        assertArrayEquals(decodeBase32(rfcSeedB32), decodeBase32("gezdgnbvgy3tqojqgezdgnbvgy3tqojq"))
    }

    @Test
    fun getTOTPCode_matchesRfc6238KnownAnswers() {
        // 6-digit codes = last six digits of RFC 6238 Appendix B's 8-digit values.
        assertEquals("287082", getTOTPCode(rfcSeedB32, 59L))
        assertEquals("081804", getTOTPCode(rfcSeedB32, 1111111109L))
        assertEquals("050471", getTOTPCode(rfcSeedB32, 1111111111L))
        assertEquals("005924", getTOTPCode(rfcSeedB32, 1234567890L))
        assertEquals("279037", getTOTPCode(rfcSeedB32, 2000000000L))
        assertEquals("353130", getTOTPCode(rfcSeedB32, 20000000000L))
    }

    @Test
    fun getTOTPCode_isAlwaysSixDigits() {
        val code = getTOTPCode(rfcSeedB32, 1L)
        assertEquals(6, code.length)
        assertTrue("code must be all digits, got '$code'", code.all { it.isDigit() })
    }

    @Test
    fun parseVaultString_splitsOnLastColonAndSkipsBlank() {
        val r = parseVaultString("Discord:JBSWY3DPEHPK3PXP,AWS:prod:KRSXG5CTMVRXEZLU")
        assertEquals(2, r.size)
        assertEquals("Discord", r[0].label)
        assertEquals("JBSWY3DPEHPK3PXP", r[0].secret)
        assertEquals("AWS:prod", r[1].label)
        assertEquals("KRSXG5CTMVRXEZLU", r[1].secret)
        assertTrue(parseVaultString("").isEmpty())
    }
}
