package com.droiddeck.launcher.core.privileged

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveHogWatcherTest {
    private val own = "com.droiddeck.launcher"
    private val protected = setOf("com.google.android.gms", "com.android.phone", "com.samsung.android.game.gos")
    private val userApps = listOf("com.example.social", "com.example.shop")
    private val systemApps = listOf("com.samsung.android.bixby.agent", "com.samsung.android.game.gos", "com.android.systemui")

    @Test
    fun userAppClassifiedUserAndTrackingSystemClassifiedSystem() {
        assertEquals(LiveHogClass.USER, classifyLiveHog("com.example.social", userApps, systemApps, protected, own))
        assertEquals(
            LiveHogClass.SYSTEM_TRACKING,
            classifyLiveHog("com.samsung.android.bixby.agent", userApps, systemApps, protected, own),
        )
    }

    @Test
    fun otherSystemProtectedOwnAndUnknownAreExcluded() {
        assertEquals(LiveHogClass.EXCLUDED, classifyLiveHog("com.samsung.android.game.gos", userApps, systemApps, protected, own))
        assertEquals(LiveHogClass.EXCLUDED, classifyLiveHog("com.android.systemui", userApps, systemApps, protected, own))
        assertEquals(LiveHogClass.EXCLUDED, classifyLiveHog("com.google.android.gms", userApps, systemApps, protected, own))
        assertEquals(LiveHogClass.EXCLUDED, classifyLiveHog(own, userApps, systemApps, protected, own))
        assertEquals(LiveHogClass.EXCLUDED, classifyLiveHog("com.example.unknown", userApps, systemApps, protected, own))
        assertEquals(LiveHogClass.EXCLUDED, classifyLiveHog("not-a-package", userApps, systemApps, protected, own))
    }

    @Test
    fun thresholdsAreUserSettings() {
        assertEquals(250L, liveHogThresholds(LiveHogClass.USER).ramMb)
        assertEquals(14.0, liveHogThresholds(LiveHogClass.USER).cpuPct, 0.0)
        assertEquals(300L, liveHogThresholds(LiveHogClass.SYSTEM_TRACKING).ramMb)
        assertEquals(18.0, liveHogThresholds(LiveHogClass.SYSTEM_TRACKING).cpuPct, 0.0)
    }

    @Test
    fun userHogAtRamOrCpuBoundary() {
        assertTrue(isLiveHog(LiveHogSample("com.a", 250L, null), LiveHogClass.USER))
        assertFalse(isLiveHog(LiveHogSample("com.a", 249L, null), LiveHogClass.USER))
        assertTrue(isLiveHog(LiveHogSample("com.a", 10L, 14.0), LiveHogClass.USER))
        assertFalse(isLiveHog(LiveHogSample("com.a", 10L, 13.9), LiveHogClass.USER))
        assertFalse(isLiveHog(LiveHogSample("com.a", 10L, null), LiveHogClass.USER))
    }

    @Test
    fun trackingSystemNeedsHigherThresholds() {
        assertFalse(isLiveHog(LiveHogSample("com.a", 250L, 14.0), LiveHogClass.SYSTEM_TRACKING))
        assertTrue(isLiveHog(LiveHogSample("com.a", 300L, null), LiveHogClass.SYSTEM_TRACKING))
        assertTrue(isLiveHog(LiveHogSample("com.a", 10L, 18.0), LiveHogClass.SYSTEM_TRACKING))
        assertFalse(isLiveHog(LiveHogSample("com.a", 299L, 17.9), LiveHogClass.SYSTEM_TRACKING))
    }

    @Test
    fun mediumTierCatchesBelowThresholdUserApps() {
        assertEquals(150L, liveHogMediumThresholds(LiveHogClass.USER).ramMb)
        assertEquals(5.0, liveHogMediumThresholds(LiveHogClass.USER).cpuPct, 0.0)
        assertTrue(isLiveHogMedium(LiveHogSample("com.a", 150L, null), LiveHogClass.USER))
        assertFalse(isLiveHogMedium(LiveHogSample("com.a", 149L, null), LiveHogClass.USER))
        assertTrue(isLiveHogMedium(LiveHogSample("com.a", 10L, 5.0), LiveHogClass.USER))
        assertFalse(isLiveHogMedium(LiveHogSample("com.a", 10L, 4.9), LiveHogClass.USER))
        assertFalse(isLiveHogMedium(LiveHogSample("com.a", 10L, null), LiveHogClass.USER))
        assertFalse(isLiveHogMedium(LiveHogSample("com.a", 500L, 50.0), LiveHogClass.SYSTEM_TRACKING))
    }

    @Test
    fun sampleParsesStatFieldsAndTotal() {
        val output = """
            P 1234 256000 1234 (com.example.social) S 1000 1000 1000 0 -1 4194304 50 0 0 0 500 200 0 0 20 0 1 0 0 0 0 0 0 0 0 0 0 0 0 0 17 0 0 0 0 0 0
            P 1235 128000 1235 (com.example.social:push) S 1000 1000 1000 0 -1 4194304 50 0 0 0 100 50 0 0 20 0 1 0 0 0 0 0 0 0 0 0 0 0 0 0 17 0 0 0 0 0 0
            P 9999 notanumber
            T cpu  1000 200 3000 4000 100 50 20 0 0 0
        """.trimIndent()
        val snapshot = parseLiveProcSample(output)
        assertEquals(2, snapshot.entries.size)
        val first = snapshot.entries[1234]!!
        assertEquals(256000L, first.rssKb)
        assertEquals(500L, first.utime)
        assertEquals(200L, first.stime)
        val second = snapshot.entries[1235]!!
        assertEquals(100L, second.utime)
        assertEquals(50L, second.stime)
        assertEquals(8370L, snapshot.totalTicks)
    }

    @Test
    fun cpuPctIsShareOfTotalCapacity() {
        assertEquals(5.0, computeLiveCpuPct(1000L, 1500L, 10000L, 20000L)!!, 0.0001)
        assertNull(computeLiveCpuPct(1000L, 1500L, 10000L, 10000L))
        assertNull(computeLiveCpuPct(1500L, 1000L, 10000L, 20000L))
    }

    @Test
    fun aggregateSumsProcessesPerPackageAndNullCpuOnFirstTick() {
        val pidToPkg = mapOf(1 to "com.a", 2 to "com.a", 3 to "com.b")
        val snapshot = LiveProcSnapshot(
            mapOf(
                1 to LiveProcEntry(1, 204800L, 100L, 50L),
                2 to LiveProcEntry(2, 51200L, 200L, 100L),
                3 to LiveProcEntry(3, 102400L, 10L, 10L),
            ),
            totalTicks = 10000L,
        )
        val first = aggregateLiveSamples(snapshot, null, pidToPkg)
        assertEquals(250L, first["com.a"]!!.rssMb)
        assertNull(first["com.a"]!!.cpuPct)
        assertEquals(100L, first["com.b"]!!.rssMb)
        val next = LiveProcSnapshot(
            mapOf(
                1 to LiveProcEntry(1, 204800L, 200L, 100L),
                2 to LiveProcEntry(2, 51200L, 300L, 150L),
                3 to LiveProcEntry(3, 102400L, 20L, 10L),
            ),
            totalTicks = 20000L,
        )
        val second = aggregateLiveSamples(next, snapshot, pidToPkg)
        // com.a gained 300 ticks over a 10000-tick total delta: 3%.
        assertEquals(3.0, second["com.a"]!!.cpuPct!!, 0.0001)
    }

    @Test
    fun oneHitNeverActsSecondHitRestricts() {
        assertEquals(LiveHogAction.NONE, liveHogActionFor(1, LiveHogStage.NONE, 1000L, 0L, 0, false, false))
        assertEquals(LiveHogAction.RESTRICT, liveHogActionFor(2, LiveHogStage.NONE, 1000L, 0L, 0, false, false))
    }

    @Test
    fun restrictedHogGetsKilledNext() {
        assertEquals(LiveHogAction.KILL, liveHogActionFor(3, LiveHogStage.RESTRICTED, 1000L, 0L, 0, false, false))
    }

    @Test
    fun killedHogForceStopsOnlyWhenAggressiveAndUserApp() {
        assertEquals(LiveHogAction.FORCE_STOP, liveHogActionFor(4, LiveHogStage.KILLED, 1000L, 0L, 1, true, false))
        assertEquals(LiveHogAction.KILL, liveHogActionFor(4, LiveHogStage.KILLED, 1000000L, 0L, 1, true, true))
        assertEquals(LiveHogAction.NONE, liveHogActionFor(4, LiveHogStage.KILLED, 1000L, 0L, 1, false, false))
    }

    @Test
    fun repeatedKillsThrottledByCooldownAndCap() {
        // 100 s after the last kill: inside the 300 s cooldown.
        assertEquals(LiveHogAction.NONE, liveHogActionFor(4, LiveHogStage.KILLED, 100000L, 0L, 1, false, false))
        // 400 s after the last kill: cooldown passed.
        assertEquals(LiveHogAction.KILL, liveHogActionFor(4, LiveHogStage.KILLED, 400000L, 0L, 1, false, false))
        // Cap reached: no more kills.
        assertEquals(LiveHogAction.NONE, liveHogActionFor(4, LiveHogStage.KILLED, 400000L, 0L, 3, false, false))
        assertEquals(LiveHogAction.NONE, liveHogActionFor(5, LiveHogStage.FORCE_STOPPED, 400000L, 0L, 1, true, false))
    }

    @Test
    fun sampleCmdCoversPidsAndProcFiles() {
        val cmd = buildLiveProcSampleCmd(listOf(123, 456))
        assertTrue(cmd.contains("for p in 123 456"))
        assertTrue(cmd.contains("grep VmRSS /proc/\$p/status"))
        assertTrue(cmd.contains("cat /proc/\$p/stat"))
        assertTrue(cmd.contains("head -1 /proc/stat"))
        assertTrue(cmd.contains("echo \"P \$p"))
        assertEquals("", buildLiveProcSampleCmd(emptyList()))
    }

    @Test
    fun journalKeysArePerPackage() {
        assertEquals("live-hog-appops-com.example.app", liveHogJournalKey("appops", "com.example.app"))
        assertEquals("live-hog-buckets-com.example.app", liveHogJournalKey("buckets", "com.example.app"))
        assertEquals("live-hog-whitelist-com.example.app", liveHogJournalKey("whitelist", "com.example.app"))
    }

    @Test
    fun pollIsThirtySecondsForFirstThreeMinutesThenNinety() {
        assertEquals(30_000L, liveHogPollDelay(0L))
        assertEquals(30_000L, liveHogPollDelay(179_999L))
        assertEquals(90_000L, liveHogPollDelay(180_000L))
        assertEquals(90_000L, liveHogPollDelay(1_000_000L))
    }

    @Test
    fun phaseNameIsFastThenSteady() {
        assertEquals("fast", liveHogPhaseName(0L))
        assertEquals("fast", liveHogPhaseName(179_999L))
        assertEquals("steady", liveHogPhaseName(180_000L))
    }

    @Test
    fun topSummaryListsTopThreeByRssWithHits() {
        val samples = mapOf(
            "com.a" to LiveHogSample("com.a", 260L, 15.0),
            "com.b" to LiveHogSample("com.b", 180L, null),
            "com.c" to LiveHogSample("com.c", 320L, 5.0),
            "com.d" to LiveHogSample("com.d", 100L, 2.0),
        )
        val hits = mapOf("com.a" to 2, "com.c" to 1)
        assertEquals("com.c:320:5.0:1;com.a:260:15.0:2;com.b:180:?:0", liveHogTopSummary(samples, hits))
        assertEquals("com.c:320:5.0:1;com.a:260:15.0:2", liveHogTopSummary(samples, hits, 2))
        assertEquals("", liveHogTopSummary(emptyMap(), emptyMap()))
    }
}
