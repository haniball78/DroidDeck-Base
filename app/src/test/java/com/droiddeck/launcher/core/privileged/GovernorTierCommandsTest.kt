package com.droiddeck.launcher.core.privileged

import org.junit.Assert.assertEquals
import org.junit.Test

class GovernorTierCommandsTest {

    @Test fun coolingWithPidHasAllThreeCommandsInOrder() {
        assertEquals(
            listOf(
                "kill-all" to "am kill-all 2>/dev/null",
                "renice" to "renice -n -5 -p 1234 2>/dev/null",
                "taskset" to "taskset -p 7c 1234 2>/dev/null",
            ),
            buildTierCommands(DynamicAdaptiveGovernor.AdaptiveTier.COOLING_RELIEF, 1234),
        )
    }

    @Test fun balancedWithPidHasReniceAndTaskset() {
        assertEquals(
            listOf(
                "renice" to "renice -n -10 -p 1234 2>/dev/null",
                "taskset" to "taskset -p 7c 1234 2>/dev/null",
            ),
            buildTierCommands(DynamicAdaptiveGovernor.AdaptiveTier.BALANCED_COOL, 1234),
        )
    }

    @Test fun boostWithPidHasReniceOnly() {
        assertEquals(
            listOf("renice" to "renice -n -14 -p 1234 2>/dev/null"),
            buildTierCommands(DynamicAdaptiveGovernor.AdaptiveTier.HEADROOM_BOOST, 1234),
        )
    }

    @Test fun pidAtMostOneSkipsPidCommands() {
        assertEquals(
            listOf("kill-all" to "am kill-all 2>/dev/null"),
            buildTierCommands(DynamicAdaptiveGovernor.AdaptiveTier.COOLING_RELIEF, 1),
        )
        assertEquals(
            emptyList<Pair<String, String>>(),
            buildTierCommands(DynamicAdaptiveGovernor.AdaptiveTier.BALANCED_COOL, 0),
        )
        assertEquals(
            emptyList<Pair<String, String>>(),
            buildTierCommands(DynamicAdaptiveGovernor.AdaptiveTier.HEADROOM_BOOST, -1),
        )
    }
}
