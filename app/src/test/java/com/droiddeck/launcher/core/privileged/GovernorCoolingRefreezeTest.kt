package com.droiddeck.launcher.core.privileged

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GovernorCoolingRefreezeTest {

    @Test fun reliefEntryFiresFromAnyOtherTier() {
        assertTrue(
            isCoolingReliefEntry(
                DynamicAdaptiveGovernor.AdaptiveTier.BALANCED_COOL,
                DynamicAdaptiveGovernor.AdaptiveTier.COOLING_RELIEF,
            ),
        )
        assertTrue(
            isCoolingReliefEntry(
                DynamicAdaptiveGovernor.AdaptiveTier.HEADROOM_BOOST,
                DynamicAdaptiveGovernor.AdaptiveTier.COOLING_RELIEF,
            ),
        )
    }

    @Test fun reliefEntryDoesNotFireWhileAlreadyInRelief() {
        assertFalse(
            isCoolingReliefEntry(
                DynamicAdaptiveGovernor.AdaptiveTier.COOLING_RELIEF,
                DynamicAdaptiveGovernor.AdaptiveTier.COOLING_RELIEF,
            ),
        )
    }

    @Test fun reliefEntryDoesNotFireOnOtherTransitions() {
        assertFalse(
            isCoolingReliefEntry(
                DynamicAdaptiveGovernor.AdaptiveTier.COOLING_RELIEF,
                DynamicAdaptiveGovernor.AdaptiveTier.BALANCED_COOL,
            ),
        )
        assertFalse(
            isCoolingReliefEntry(
                DynamicAdaptiveGovernor.AdaptiveTier.BALANCED_COOL,
                DynamicAdaptiveGovernor.AdaptiveTier.BALANCED_COOL,
            ),
        )
        assertFalse(
            isCoolingReliefEntry(
                DynamicAdaptiveGovernor.AdaptiveTier.BALANCED_COOL,
                DynamicAdaptiveGovernor.AdaptiveTier.HEADROOM_BOOST,
            ),
        )
    }

    @Test fun resuspendScriptsAreSuspendOnly() {
        val scripts = resuspendScripts(listOf("com.example.a", "com.example.b"))
        assertEquals(1, scripts.size)
        assertTrue(scripts[0].contains("cmd package suspend --user 0 com.example.a com.example.b"))
        assertFalse(scripts[0].contains("force-stop"))
        assertFalse(scripts[0].contains("am kill"))
        assertFalse(scripts[0].contains("pm disable"))
        assertFalse(scripts[0].contains("unsuspend"))
    }

    @Test fun resuspendScriptsChunkAtFiftyAndCoverEveryTargetOnce() {
        val targets = (1..120).map { "com.example.app$it" }
        val scripts = resuspendScripts(targets)
        assertEquals(3, scripts.size)
        val covered = scripts.flatMap { script ->
            script.removePrefix("cmd package suspend --user 0 ").removeSuffix(" 2>/dev/null; ").split(" ")
        }
        assertEquals(targets, covered)
    }

    @Test fun resuspendScriptsEmptyListIsEmpty() {
        assertEquals(emptyList<String>(), resuspendScripts(emptyList()))
    }

    @Test fun resuspendScriptsFitServerLineLimit() {
        val targets = (1..205).map { "com.example.longer.app.name$it" }
        val scripts = resuspendScripts(targets)
        assertTrue(scripts.isNotEmpty())
        for (script in scripts) {
            val encoded = java.util.Base64.getEncoder()
                .encodeToString(script.toByteArray(Charsets.UTF_8))
            assertTrue("EXEC:${encoded}".length <= 32768)
            assertTrue("EXEC:${encoded}".length <= 8192)
        }
    }

    @Test fun refreezeTargetsKeepProtectedPackagesFrozenOut() {
        val snapshot = listOf(
            "com.android.systemui",
            "com.google.android.gms",
            "com.sec.android.app.launcher",
            "com.tailscale.ipn",
            "com.termux",
            "com.droiddeck.launcher",
            "com.facebook.aura",
            "com.example.hog",
        )
        assertEquals(
            listOf("com.facebook.aura", "com.example.hog"),
            filterSuspendTargets(snapshot, AppFreezerEngine.BASE_PROTECTED_PACKAGES),
        )
    }
}
