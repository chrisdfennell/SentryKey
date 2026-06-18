package com.example.sentrykey

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for unionAccounts (AutoSyncManager) — the multi-device conflict
 * merge that must NEVER drop a 2FA secret. Mirrors the web dashboard's merge, so
 * both platforms resolve conflicts identically.
 */
class SyncMergeUnitTest {

    @Test
    fun unionsDisjointLists() {
        val r = unionAccounts(
            listOf(TwoFactorAccount("GitHub", "AAA")),
            listOf(TwoFactorAccount("Discord", "BBB"))
        )
        assertEquals(2, r.size)
        assertTrue(r.any { it.label == "GitHub" && it.secret == "AAA" })
        assertTrue(r.any { it.label == "Discord" && it.secret == "BBB" })
    }

    @Test
    fun dedupesExactDuplicates() {
        val a = listOf(TwoFactorAccount("GitHub", "AAA"), TwoFactorAccount("Discord", "BBB"))
        val b = listOf(TwoFactorAccount("GitHub", "AAA")) // already present in a
        assertEquals(2, unionAccounts(a, b).size)
    }

    @Test
    fun neverDropsASecretOnConcurrentAdds() {
        // Both devices started from {Shared}; A added X, B added Y.
        val a = listOf(TwoFactorAccount("Shared", "S"), TwoFactorAccount("X", "x"))
        val b = listOf(TwoFactorAccount("Shared", "S"), TwoFactorAccount("Y", "y"))
        val r = unionAccounts(a, b)
        assertEquals(3, r.size)
        assertTrue(r.any { it.secret == "S" })
        assertTrue(r.any { it.secret == "x" })
        assertTrue(r.any { it.secret == "y" })
    }

    @Test
    fun keepsLocalEntriesFirst() {
        val r = unionAccounts(listOf(TwoFactorAccount("A", "1")), listOf(TwoFactorAccount("B", "2")))
        assertEquals("A", r[0].label)
        assertEquals("B", r[1].label)
    }
}
