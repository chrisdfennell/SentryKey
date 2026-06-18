package com.example.sentrykey

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM unit tests for the TOTP engine (getTOTPCode / decodeBase32 in
 * MainActivity.kt). These use javax.crypto, so no Android device is needed:
 *   ./gradlew testGithubDebugUnitTest
 *
 * The known-answer values come from RFC 6238 Appendix B (the SHA-1 vectors),
 * which is the spec every authenticator must agree on.
 */
class TotpUnitTest {

    // RFC 6238 reference seed "12345678901234567890" (20 ASCII bytes) in Base32.
    private val rfcSeedB32 = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ"

    @Test
    fun decodeBase32_decodesRfcSeed() {
        assertEquals("12345678901234567890", String(decodeBase32(rfcSeedB32), Charsets.US_ASCII))
    }

    @Test
    fun decodeBase32_isCaseSpaceAndPaddingInsensitive() {
        assertArrayEquals(decodeBase32(rfcSeedB32), decodeBase32("gezdgnbvgy3tqojqgezdgnbvgy3tqojq"))
        assertArrayEquals(decodeBase32("JBSWY3DP"), decodeBase32(" jbsw-y3dp= "))
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
    fun getTOTPCode_isStableAcrossA30sStep() {
        // Same 30s step -> identical code (TOTP only changes at step boundaries).
        assertEquals(getTOTPCode(rfcSeedB32, 0L), getTOTPCode(rfcSeedB32, 29L))
        assertEquals(getTOTPCode(rfcSeedB32, 30L), getTOTPCode(rfcSeedB32, 59L))
    }

    @Test
    fun getTOTPCode_isAlwaysSixDigits() {
        val code = getTOTPCode(rfcSeedB32, 1L)
        assertEquals(6, code.length)
        assertTrue("code must be all digits, got '$code'", code.all { it.isDigit() })
    }
}
