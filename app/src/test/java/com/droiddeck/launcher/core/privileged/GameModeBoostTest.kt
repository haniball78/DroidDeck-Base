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

    @Test
    fun sessionTreeIncludesDescendantsAndTracedProcessesButNotUnrelated() {
        val processes = listOf(
            SessionProcess(100, 1, 0),
            SessionProcess(101, 100, 0),
            SessionProcess(102, 101, 0),
            SessionProcess(103, 1, 100),
            SessionProcess(200, 1, 0),
            SessionProcess(201, 200, 0),
        )
        assertEquals(listOf(101, 102, 103), sessionTreePids(100, processes, 9999).sorted())
        assertEquals(emptyList<Int>(), sessionTreePids(200, listOf(SessionProcess(200, 1, 0)), 9999))
        assertEquals(emptyList<Int>(), sessionTreePids(999, processes, 9999))
    }

    @Test
    fun sessionTreeExcludesTheAppProcessItself() {
        val processes = listOf(
            SessionProcess(100, 1, 0),
            SessionProcess(5000, 100, 0),
            SessionProcess(5001, 5000, 0),
        )
        assertEquals(listOf<Int>(), sessionTreePids(100, processes, 5000))
    }

    @Test
    fun treeCommandTasksetsEveryDescendantThreadWithNoReniceOrOom() {
        val cmd = buildBoostTreeCommand(listOf(101, 103), "fc")
        assertTrue(cmd.contains("/proc/\$pid/task"))
        assertTrue(cmd.contains("taskset -p fc"))
        assertTrue(cmd.contains("tree-diag"))
        assertTrue(cmd.contains("pids=2"))
        assertTrue(!cmd.contains("renice"))
        assertTrue(!cmd.contains("oom_score_adj"))
        val empty = buildBoostTreeCommand(emptyList(), "fc")
        assertTrue(empty.contains("pids=0"))
    }
}
