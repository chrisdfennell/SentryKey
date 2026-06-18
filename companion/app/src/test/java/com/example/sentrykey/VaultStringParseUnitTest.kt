package com.example.sentrykey

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for parseVaultString (VaultStorage.kt) — the parser for the watch
 * vault string "label:secret,label:secret" used by watch -> phone recovery.
 */
class VaultStringParseUnitTest {

    @Test
    fun parsesMultipleEntries() {
        val r = parseVaultString("Discord:JBSWY3DPEHPK3PXP,GitHub:KRSXG5CTMVRXEZLU")
        assertEquals(2, r.size)
        assertEquals("Discord", r[0].label)
        assertEquals("JBSWY3DPEHPK3PXP", r[0].secret)
        assertEquals("GitHub", r[1].label)
        assertEquals("KRSXG5CTMVRXEZLU", r[1].secret)
    }

    @Test
    fun splitsOnLastColon_soLabelsMayContainColons() {
        val r = parseVaultString("AWS:prod:root:JBSWY3DPEHPK3PXP")
        assertEquals(1, r.size)
        assertEquals("AWS:prod:root", r[0].label)
        assertEquals("JBSWY3DPEHPK3PXP", r[0].secret)
    }

    @Test
    fun blankOrWhitespace_returnsEmpty() {
        assertTrue(parseVaultString("").isEmpty())
        assertTrue(parseVaultString("   ").isEmpty())
    }

    @Test
    fun skipsMalformedEntries() {
        // no colon -> skip; empty label (":x") -> skip; trailing colon -> skip.
        val r = parseVaultString("noColonHere,Good:JBSWY3DP, :emptyLabel ,Trailing:")
        assertEquals(1, r.size)
        assertEquals("Good", r[0].label)
        assertEquals("JBSWY3DP", r[0].secret)
    }
}
