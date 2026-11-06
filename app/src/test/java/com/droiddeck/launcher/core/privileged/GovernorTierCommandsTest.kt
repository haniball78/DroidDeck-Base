package com.droiddeck.launcher.core.privileged

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GovernorTierCommandsTest {

    @Test fun coolingDaemonHasKillAllOnly() {
        assertEquals(
            listOf("kill-all" to "am kill-all 2>/dev/null"),
            buildDaemonTierCommands(DynamicAdaptiveGovernor.AdaptiveTier.COOLING_RELIEF),
        )
    }

    @Test fun coolingAppHasTasksetOnly() {
        assertEquals(
            listOf("taskset" to "taskset -p 7c 1234 2>/dev/null"),
            buildAppTierCommands(DynamicAdaptiveGovernor.AdaptiveTier.COOLING_RELIEF, 1234),
        )
    }

    @Test fun balancedDaemonHasNoCommandsAndAppHasTaskset() {
        assertEquals(
            emptyList<Pair<String, String>>(),
            buildDaemonTierCommands(DynamicAdaptiveGovernor.AdaptiveTier.BALANCED_COOL),
        )
        assertEquals(
            listOf("taskset" to "taskset -p 7c 1234 2>/dev/null"),
            buildAppTierCommands(DynamicAdaptiveGovernor.AdaptiveTier.BALANCED_COOL, 1234),
        )
    }

    @Test fun boostPinsPidAndThreadsToFcAppSide() {
        assertEquals(
            emptyList<Pair<String, String>>(),
            buildDaemonTierCommands(DynamicAdaptiveGovernor.AdaptiveTier.HEADROOM_BOOST),
        )
        val cmds = buildAppTierCommands(DynamicAdaptiveGovernor.AdaptiveTier.HEADROOM_BOOST, 1234)
        assertEquals(2, cmds.size)
        assertEquals("taskset" to "taskset -p fc 1234 2>/dev/null", cmds[0])
        assertEquals("taskset-threads", cmds[1].first)
        assertTrue(cmds[1].second.contains("/proc/1234/task"))
        assertTrue(cmds[1].second.contains("taskset -p fc"))
    }

    @Test fun pidAtMostOneSkipsAppCommands() {
        assertEquals(
            listOf("kill-all" to "am kill-all 2>/dev/null"),
            buildDaemonTierCommands(DynamicAdaptiveGovernor.AdaptiveTier.COOLING_RELIEF),
        )
        assertEquals(
            emptyList<Pair<String, String>>(),
            buildAppTierCommands(DynamicAdaptiveGovernor.AdaptiveTier.COOLING_RELIEF, 1),
        )
        assertEquals(
            emptyList<Pair<String, String>>(),
            buildAppTierCommands(DynamicAdaptiveGovernor.AdaptiveTier.BALANCED_COOL, 0),
        )
        assertEquals(
            emptyList<Pair<String, String>>(),
            buildAppTierCommands(DynamicAdaptiveGovernor.AdaptiveTier.HEADROOM_BOOST, -1),
        )
    }

    @Test fun treePinMaskMatchesTier() {
        assertEquals("fc", treePinMask(DynamicAdaptiveGovernor.AdaptiveTier.HEADROOM_BOOST))
        assertEquals("7c", treePinMask(DynamicAdaptiveGovernor.AdaptiveTier.BALANCED_COOL))
        assertEquals("7c", treePinMask(DynamicAdaptiveGovernor.AdaptiveTier.COOLING_RELIEF))
    }
}
