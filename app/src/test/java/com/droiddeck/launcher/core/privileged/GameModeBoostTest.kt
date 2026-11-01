package com.droiddeck.launcher.core.privileged

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GameModeBoostTest {

    @Test
    fun boostCommandsHaveNoReniceOrOomWriteAndUseAppSideTaskset() {
        val commands = buildBoostAppCommands(1234, "7c")
        assertEquals(2, commands.size)
        assertEquals("taskset-pid", commands[0].first)
        assertEquals("taskset -p 7c 1234 2>/dev/null", commands[0].second)
        assertEquals("taskset-threads", commands[1].first)
        assertTrue(commands[1].second.contains("/proc/1234/task"))
        assertTrue(commands[1].second.contains("taskset -p 7c"))
        assertTrue(commands.all { !it.second.contains("renice") })
        assertTrue(commands.all { !it.second.contains("oom_score_adj") })
    }

    @Test
    fun turboMaskIsUsedWhenGiven() {
        val commands = buildBoostAppCommands(99, "fc")
        assertEquals("taskset -p fc 99 2>/dev/null", commands[0].second)
    }

    @Test
    fun resumeBoostRequiresAppliedMasterAndValidPid() {
        assertTrue(GameModeEngine.shouldBoostAtResume(true, 7376, true))
        assertFalse(GameModeEngine.shouldBoostAtResume(false, 7376, true))
        assertFalse(GameModeEngine.shouldBoostAtResume(true, 7376, false))
        assertFalse(GameModeEngine.shouldBoostAtResume(true, 1, true))
        assertFalse(GameModeEngine.shouldBoostAtResume(true, -1, true))
    }

    @Test
    fun threadsCommandFailsWhenPidDirectoryIsMissing() {
        val cmd = buildBoostThreadsCommand(1234, "7c")
        assertTrue(cmd.contains("/proc/1234"))
        assertTrue(cmd.contains("exit 1"))
        assertTrue(cmd.contains("failed=0"))
    }
}
