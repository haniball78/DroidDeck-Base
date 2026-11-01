package com.droiddeck.launcher.core.privileged

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GameModeBoostTest {

    @Test
    fun boostCommandsHaveNoReniceAndUseAppSideTaskset() {
        val commands = buildBoostAppCommands(1234, "7c")
        assertEquals(3, commands.size)
        assertEquals("taskset-pid", commands[0].first)
        assertEquals("taskset -p 7c 1234 2>/dev/null", commands[0].second)
        assertEquals("oom", commands[1].first)
        assertTrue(commands[1].second.contains("/proc/1234/oom_score_adj"))
        assertTrue(commands[1].second.contains("-1000"))
        assertEquals("taskset-threads", commands[2].first)
        assertTrue(commands[2].second.contains("/proc/1234/task"))
        assertTrue(commands[2].second.contains("taskset -p 7c"))
        assertTrue(commands.all { !it.second.contains("renice") })
    }

    @Test
    fun turboMaskIsUsedWhenGiven() {
        val commands = buildBoostAppCommands(99, "fc")
        assertEquals("taskset -p fc 99 2>/dev/null", commands[0].second)
    }

    @Test
    fun threadsCommandFailsWhenPidDirectoryIsMissing() {
        val cmd = buildBoostThreadsCommand(1234, "7c")
        assertTrue(cmd.contains("/proc/1234"))
        assertTrue(cmd.contains("exit 1"))
        assertTrue(cmd.contains("failed=0"))
    }
}
