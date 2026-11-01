package com.droiddeck.launcher.core.privileged

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SuspendThreadsDiagTest {

    @Test
    fun threadsCommandCountsAndReportsFailedTids() {
        val cmd = buildBoostThreadsCommand(1234, "fc")
        assertTrue(cmd.contains("/proc/1234/task"))
        assertTrue(cmd.contains("taskset -p fc"))
        assertTrue(cmd.contains("failed=0"))
        assertTrue(cmd.contains("threads-diag"))
        assertTrue(cmd.contains("failed_tids"))
    }

    @Test
    fun parseThreadsDiagExtractsCountsAndTids() {
        val diag = parseThreadsDiag("threads-diag ok=41 failed=2 total=43 failed_tids= 987 991")
        assertEquals(41, diag?.ok)
        assertEquals(2, diag?.failed)
        assertEquals(43, diag?.total)
        assertEquals(listOf("987", "991"), diag?.failedTids)
    }

    @Test
    fun parseThreadsDiagReturnsNullWithoutCounts() {
        assertNull(parseThreadsDiag(""))
        assertNull(parseThreadsDiag(null))
        assertNull(parseThreadsDiag("threads-diag failed_tids= 1"))
    }

    @Test
    fun parseThreadsDiagAllowsEmptyFailedList() {
        val diag = parseThreadsDiag("threads-diag ok=10 failed=0 total=10 failed_tids=")
        assertEquals(10, diag?.ok)
        assertEquals(0, diag?.failed)
        assertTrue(diag?.failedTids?.isEmpty() == true)
    }

    @Test
    fun suspendRollbackClearsEngineFlags() {
        GameModeEngine.onSuspendRollback()
        AppFreezerEngine.onSuspendRollback()
        assertFalse(GameModeEngine.isActive())
        assertFalse(GameModeEngine.isAggressiveActive())
        assertFalse(AppFreezerEngine.isDozeActive())
        assertEquals(0, AppFreezerEngine.getSuspendedCount())
    }
}
