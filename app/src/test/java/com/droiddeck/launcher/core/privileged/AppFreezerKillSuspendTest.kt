package com.droiddeck.launcher.core.privileged

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppFreezerKillSuspendTest {

    @Test
    fun killBeforeSuspendPerApp() {
        val script = killSuspendScript(
            running = listOf("com.example.a", "com.example.b"),
            nonRunning = listOf("com.example.c"),
        )
        val killA = script.indexOf("am kill com.example.a")
        val suspendA = script.indexOf("cmd package suspend --user 0 com.example.a")
        val killB = script.indexOf("am kill com.example.b")
        assertTrue(killA >= 0)
        assertTrue(suspendA > killA)
        assertTrue(suspendA < killB)
        assertTrue(!script.contains("am kill com.example.c"))
        assertTrue(script.contains("cmd package suspend --user 0 com.example.c"))
    }

    @Test
    fun allowlistMembership() {
        assertEquals(42, AppFreezerEngine.SAFE_SYSTEM_ALLOWLIST.size)
        assertTrue(isSafeSystemTarget("com.facebook.services", emptySet()))
        assertTrue(isSafeSystemTarget("com.samsung.android.bixby.agent", emptySet()))
        assertTrue(isSafeSystemTarget("com.google.android.as.oss", emptySet()))
        assertFalse(isSafeSystemTarget("com.example.unknown", emptySet()))
        assertFalse(isSafeSystemTarget("com.android.systemui", emptySet()))
    }

    @Test
    fun launcherIconRule() {
        assertTrue(isSafeSystemTarget("com.example.newapp", setOf("com.example.newapp")))
        assertFalse(isSafeSystemTarget("com.example.newapp", emptySet()))
    }

    @Test
    fun runningTargetRssMatchesExactColonAndTruncated() {
        val ps = """
            USER           CMD                              RSS
            u0_a408        com.facebook.aura                348160
            u0_a300        com.android.chrome --some-flag   192512
            u0_i104        com.android.chrome:sandboxed_process0 102400
            u0_a200        stagram.android                  307200
            u0_a201        com.example.tiny                 1024
        """.trimIndent()
        val rss = runningTargetRss(
            ps,
            listOf(
                "com.facebook.aura",
                "com.android.chrome",
                "com.instagram.android",
                "com.example.tiny",
                "com.example.notrunning",
            ),
        )
        assertEquals(340L, rss["com.facebook.aura"])
        assertEquals(288L, rss["com.android.chrome"])
        assertEquals(300L, rss["com.instagram.android"])
        assertEquals(1L, rss["com.example.tiny"])
        assertFalse(rss.containsKey("com.example.notrunning"))
    }

    @Test
    fun comprehensiveNoThreshold() {
        val ps = "USER CMD RSS\nu0_a201 com.example.tiny 1024\n"
        val rss = runningTargetRss(ps, listOf("com.example.tiny"))
        assertEquals(1L, rss["com.example.tiny"])
    }

    @Test
    fun gosAndGameToolsProtected() {
        assertTrue(AppFreezerEngine.BASE_PROTECTED_PACKAGES.contains("com.samsung.android.game.gos"))
        assertTrue(AppFreezerEngine.BASE_PROTECTED_PACKAGES.contains("com.samsung.android.game.gametools"))
        assertFalse(AppFreezerEngine.KNOWN_BLOATWARE.contains("com.samsung.android.game.gos"))
    }

    @Test
    fun chromeIsClosed() {
        assertFalse(AppFreezerEngine.BASE_PROTECTED_PACKAGES.contains("com.android.chrome"))
        assertTrue(isSafeSystemTarget("com.android.chrome", setOf("com.android.chrome")))
    }

    @Test
    fun protectedSetNeverClosed() {
        val protected = AppFreezerEngine.BASE_PROTECTED_PACKAGES
        for (pkg in listOf(
            "com.android.systemui",
            "com.google.android.gms",
            "com.sec.android.app.launcher",
            "com.samsung.android.honeyboard",
            "com.tailscale.ipn",
            "com.termux",
            "com.droiddeck.launcher",
        )) {
            assertTrue(pkg, protected.contains(pkg))
        }
        assertEquals(
            listOf("com.facebook.aura"),
            filterSuspendTargets(
                listOf(
                    "com.android.systemui",
                    "com.google.android.gms",
                    "com.sec.android.app.launcher",
                    "com.samsung.android.honeyboard",
                    "com.tailscale.ipn",
                    "com.termux",
                    "com.droiddeck.launcher",
                    "com.facebook.aura",
                ),
                protected,
            ),
        )
    }

    @Test
    fun systemCloseTargetsUnion() {
        val installed = setOf(
            "com.samsung.android.forest",
            "com.facebook.services",
            "com.example.newapp",
            "com.android.systemui",
        )
        val targets = systemCloseTargets(installed, setOf("com.example.newapp"))
        assertTrue(targets.contains("com.samsung.android.forest"))
        assertTrue(targets.contains("com.facebook.services"))
        assertTrue(targets.contains("com.example.newapp"))
        assertFalse(targets.contains("com.samsung.android.bixby.agent"))
        assertFalse(targets.contains("com.android.systemui"))
    }
}
