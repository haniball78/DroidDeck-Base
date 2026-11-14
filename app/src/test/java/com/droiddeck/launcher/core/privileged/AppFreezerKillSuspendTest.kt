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
        val forceStopA = script.indexOf("am force-stop com.example.a")
        val suspendA = script.indexOf("cmd package suspend --user 0 com.example.a")
        val forceStopB = script.indexOf("am force-stop com.example.b")
        assertTrue(forceStopA >= 0)
        assertTrue(suspendA > forceStopA)
        assertTrue(suspendA < forceStopB)
        assertTrue(!script.contains("am kill"))
        assertTrue(!script.contains("am force-stop com.example.c"))
        assertTrue(script.contains("cmd package suspend --user 0 com.example.c"))
    }

    @Test
    fun stickyAppsDisabledBetweenForceStopAndSuspend() {
        val script = killSuspendScript(
            running = listOf("com.facebook.aura", "com.example.a"),
            nonRunning = emptyList(),
            sticky = setOf("com.facebook.aura"),
        )
        val forceStop = script.indexOf("am force-stop com.facebook.aura")
        val disable = script.indexOf("pm disable-user --user 0 com.facebook.aura")
        val suspend = script.indexOf("cmd package suspend --user 0 com.facebook.aura")
        assertTrue(forceStop >= 0)
        assertTrue(disable > forceStop)
        assertTrue(suspend > disable)
        assertTrue(!script.contains("pm disable-user --user 0 com.example.a"))
    }

    @Test
    fun stickySetIsOnlyTheFourProvenApps() {
        assertEquals(
            setOf("com.facebook.aura", "com.facebook.katana", "com.android.vending", "com.samsung.android.smartsuggestions"),
            AppFreezerEngine.STICKY_PACKAGES,
        )
        assertFalse(AppFreezerEngine.STICKY_PACKAGES.contains("com.google.android.gms"))
        assertFalse(AppFreezerEngine.STICKY_PACKAGES.contains("com.termux.api"))
    }

    @Test
    fun playStoreIsAStickyTargetNotProtected() {
        assertFalse(AppFreezerEngine.BASE_PROTECTED_PACKAGES.contains("com.android.vending"))
        assertTrue(AppFreezerEngine.BASE_PROTECTED_PACKAGES.contains("com.google.android.gms"))
        assertTrue(AppFreezerEngine.BASE_PROTECTED_PACKAGES.contains("com.google.android.gsf"))
        assertEquals(
            listOf("com.android.vending"),
            filterSuspendTargets(
                listOf("com.android.vending", "com.google.android.gms"),
                AppFreezerEngine.BASE_PROTECTED_PACKAGES,
            ),
        )
    }

    @Test
    fun wakeReceiverForInstagramUsesFbns() {
        assertEquals("com.instagram.push.FbnsInitBroadcastReceiver", wakeReceiverFor("com.instagram.android"))
        assertEquals("com.google.firebase.iid.FirebaseInstanceIdReceiver", wakeReceiverFor("com.whatsapp"))
        assertEquals("com.google.firebase.iid.FirebaseInstanceIdReceiver", wakeReceiverFor("com.facebook.aura"))
    }

    @Test
    fun wakeBroadcastWakesOnlyForceStoppedAndFallsBackOnlyForMessaging() {
        val script = wakeBroadcastScript(
            forceStopped = listOf("com.whatsapp", "com.instagram.android", "com.example.a"),
            fallback = setOf("com.whatsapp"),
        )
        assertTrue(script.contains("am broadcast -a com.droiddeck.WAKE_TEST -n com.whatsapp/com.google.firebase.iid.FirebaseInstanceIdReceiver -f 32"))
        assertTrue(script.contains("am broadcast -a com.droiddeck.WAKE_TEST -n com.instagram.android/com.instagram.push.FbnsInitBroadcastReceiver -f 32"))
        assertTrue(script.contains("am broadcast -a com.droiddeck.WAKE_TEST -n com.example.a/com.google.firebase.iid.FirebaseInstanceIdReceiver -f 32"))
        assertTrue(script.contains("sleep 5"))
        assertTrue(script.contains("for p in com.whatsapp; do"))
        assertTrue(script.contains("monkey -p \$p"))
        assertTrue(!script.contains("for p in com.instagram.android"))
        assertTrue(!script.contains("for p in com.example.a"))
    }

    @Test
    fun wakeBroadcastWithNoFallbackAppsHasNoMonkey() {
        val script = wakeBroadcastScript(
            forceStopped = listOf("com.example.a"),
            fallback = setOf("com.whatsapp"),
        )
        assertTrue(script.contains("am broadcast"))
        assertTrue(!script.contains("monkey"))
    }

    @Test
    fun restoreCmdReEnablesDisabledSticky() {
        val cmd = AppFreezerEngine.packagesRestoreCmd(
            listOf("com.example.a"),
            disabled = listOf("com.facebook.aura"),
        )
        assertTrue(cmd.contains("cmd package unsuspend --user 0 com.example.a"))
        assertTrue(cmd.contains("pm enable com.facebook.aura"))
        assertTrue(cmd.indexOf("pm enable com.facebook.aura") > cmd.indexOf("cmd package unsuspend"))
    }

    @Test
    fun allowlistMembership() {
        assertEquals(51, AppFreezerEngine.SAFE_SYSTEM_ALLOWLIST.size)
        assertTrue(isSafeSystemTarget("com.facebook.services", emptySet()))
        assertTrue(isSafeSystemTarget("com.samsung.android.bixby.agent", emptySet()))
        assertTrue(isSafeSystemTarget("com.google.android.as.oss", emptySet()))
        assertTrue(isSafeSystemTarget("com.android.intentresolver", emptySet()))
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
            "com.sec.android.app.clockpackage",
            "com.android.settings",
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
                    "com.sec.android.app.clockpackage",
                    "com.android.settings",
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

    @Test
    fun batchRetryDoesNotRetryOnSuccess() {
        var calls = 0
        val result = runBatchWithRetry("echo hi", sleepMs = 0) { _ ->
            calls++
            PrivilegedResult(0, "ok", PrivilegedMode.NONE)
        }
        assertEquals(1, calls)
        assertEquals(0, result.exitCode)
    }

    @Test
    fun batchRetryRunsOnceOnFailureThenReturnsLastResult() {
        var calls = 0
        val result = runBatchWithRetry("echo hi", sleepMs = 0) { _ ->
            calls++
            PrivilegedResult(-1, "", PrivilegedMode.NONE)
        }
        assertEquals(2, calls)
        assertEquals(-1, result.exitCode)
    }

    @Test
    fun batchRetrySucceedsOnSecondAttempt() {
        var calls = 0
        val result = runBatchWithRetry("echo hi", sleepMs = 0) { _ ->
            calls++
            if (calls == 1) PrivilegedResult(-1, "", PrivilegedMode.NONE)
            else PrivilegedResult(0, "ok", PrivilegedMode.NONE)
        }
        assertEquals(2, calls)
        assertEquals(0, result.exitCode)
    }

    @Test
    fun appliedVerificationCountsKilledAndNamesSurvivors() {
        val verified = appliedVerification(
            orderedRunning = listOf("com.a", "com.b", "com.c"),
            stillRunning = listOf("com.b"),
            suspendedCount = 10,
        )
        assertEquals(2, verified.killed)
        assertEquals(10, verified.suspended)
        assertEquals(listOf("com.b"), verified.survivors)
    }

    @Test
    fun appliedVerificationBoundsSurvivorsToFive() {
        val running = (1..8).map { "com.app$it" }
        val verified = appliedVerification(
            orderedRunning = running,
            stillRunning = running,
            suspendedCount = 0,
        )
        assertEquals(0, verified.killed)
        assertEquals(5, verified.survivors.size)
        assertEquals(running.take(5), verified.survivors)
    }

    @Test
    fun appliedVerificationAllKilledHasNoSurvivors() {
        val verified = appliedVerification(
            orderedRunning = listOf("com.a", "com.b"),
            stillRunning = emptyList(),
            suspendedCount = 2,
        )
        assertEquals(2, verified.killed)
        assertEquals(2, verified.suspended)
        assertTrue(verified.survivors.isEmpty())
    }
}
