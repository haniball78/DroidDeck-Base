package com.droiddeck.launcher.core.privileged

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HogRestrictionTest {
    private val own = "com.droiddeck.launcher"
    private val protected = setOf("com.google.android.gms", "com.android.phone", "com.samsung.android.game.gos")

    @Test
    fun userHogsRankedByUsageAndCappedAtSeven() {
        val userApps = (1..10).map { "com.example.app$it" }
        val usage = (1..10).associate { "com.example.app$it" to (it * 1000L) }
        val targets = resolveRestrictionTargets(userApps, emptyList(), protected, own, usage)
        assertEquals(7, targets.size)
        assertEquals("com.example.app10", targets[0])
        assertEquals("com.example.app4", targets[6])
    }

    @Test
    fun systemAppsExcludedUnlessSafeTracking() {
        val userApps = listOf("com.example.social")
        val systemApps = listOf(
            "com.samsung.android.bixby.agent",
            "com.samsung.android.game.gos",
            "com.android.systemui",
        )
        val usage = mapOf("com.example.social" to 5000L)
        val targets = resolveRestrictionTargets(userApps, systemApps, protected, own, usage)
        assertTrue(targets.contains("com.example.social"))
        assertTrue(targets.contains("com.samsung.android.bixby.agent"))
        assertFalse(targets.contains("com.samsung.android.game.gos"))
        assertFalse(targets.contains("com.android.systemui"))
    }

    @Test
    fun protectedAndOwnPackageNeverTargeted() {
        val userApps = listOf("com.google.android.gms", own, "com.example.social")
        val usage = mapOf("com.google.android.gms" to 99999L, own to 88888L, "com.example.social" to 1000L)
        val targets = resolveRestrictionTargets(userApps, emptyList(), protected, own, usage)
        assertEquals(listOf("com.example.social"), targets)
    }

    @Test
    fun emptyUsageFallsBackToSafeTrackingOnly() {
        val userApps = listOf("com.example.social", "com.example.shop")
        val systemApps = listOf("com.samsung.android.bixby.agent")
        val targets = resolveRestrictionTargets(userApps, systemApps, protected, own, emptyMap())
        assertEquals(listOf("com.samsung.android.bixby.agent"), targets)
    }

    @Test
    fun invalidPackageNamesFiltered() {
        val userApps = listOf("not-a-package", "com.example.social", "No")
        val usage = mapOf("not-a-package" to 9000L, "com.example.social" to 1000L, "No" to 8000L)
        val targets = resolveRestrictionTargets(userApps, emptyList(), protected, own, usage)
        assertEquals(listOf("com.example.social"), targets)
    }

    @Test
    fun snapshotParsesModesBucketsAndWhitelist() {
        val output = """
            PKG com.example.social
            RUN_ANY_IN_BACKGROUND: allow
            active
            PKG com.example.shop
            RUN_ANY_IN_BACKGROUND: ignore
            50
            WHITELIST
              com.example.social
              android
        """.trimIndent()
        val snapshots = parseHogSnapshot(output, listOf("com.example.social", "com.example.shop"))
        assertEquals(2, snapshots.size)
        val social = snapshots["com.example.social"]!!
        assertEquals("allow", social.appOps)
        assertEquals("active", social.bucket)
        assertTrue(social.whitelisted)
        val shop = snapshots["com.example.shop"]!!
        assertEquals("ignore", shop.appOps)
        assertEquals("restricted", shop.bucket)
        assertFalse(shop.whitelisted)
    }

    @Test
    fun snapshotDropsPackageWithUnreadableState() {
        val output = """
            PKG com.example.social
            No operations for package
            PKG com.example.shop
            RUN_ANY_IN_BACKGROUND: allow
            frequent
            WHITELIST
        """.trimIndent()
        val snapshots = parseHogSnapshot(output, listOf("com.example.social", "com.example.shop"))
        assertEquals(listOf("com.example.shop"), snapshots.keys.toList())
    }

    @Test
    fun targetNeedsRestrictionOnlyWhenSomethingChanges() {
        assertTrue(HogTarget("com.a", "allow", "active", false).needsRestriction)
        assertTrue(HogTarget("com.a", "allow", "restricted", false).needsRestriction)
        assertTrue(HogTarget("com.a", "ignore", "restricted", true).needsRestriction)
        assertFalse(HogTarget("com.a", "ignore", "restricted", false).needsRestriction)
    }

    @Test
    fun applyCmdSetsOnlyChangingParts() {
        val targets = listOf(
            HogTarget("com.a", "allow", "active", true),
            HogTarget("com.b", "ignore", "restricted", false),
        )
        val cmd = buildHogApplyCmd(targets)
        assertTrue(cmd.contains("cmd appops set com.a RUN_ANY_IN_BACKGROUND ignore"))
        assertTrue(cmd.contains("am set-standby-bucket com.a restricted"))
        assertTrue(cmd.contains("am make-uid-idle com.a"))
        assertTrue(cmd.contains("dumpsys deviceidle whitelist -com.a"))
        assertFalse(cmd.contains("cmd appops set com.b"))
        assertFalse(cmd.contains("am set-standby-bucket com.b"))
        assertTrue(cmd.contains("am make-uid-idle com.b"))
        assertFalse(cmd.contains("whitelist -com.b"))
    }

    @Test
    fun restoreCmdsReturnPriorValues() {
        val targets = listOf(HogTarget("com.a", "allow", "frequent", true))
        assertTrue(buildHogAppOpsRestoreCmd(targets).contains("cmd appops set com.a RUN_ANY_IN_BACKGROUND allow"))
        assertTrue(buildHogBucketRestoreCmd(targets).contains("am set-standby-bucket com.a frequent"))
        assertTrue(buildHogWhitelistRestoreCmd(targets).contains("dumpsys deviceidle whitelist +com.a"))
    }

    @Test
    fun verifyCmdsCountStillRestricted() {
        val appOps = buildHogAppOpsVerifyCmd(listOf("com.a", "com.b"))
        assertTrue(appOps.contains("for p in com.a com.b"))
        assertTrue(appOps.contains("cmd appops get \$p RUN_ANY_IN_BACKGROUND"))
        assertTrue(appOps.contains("grep -q -w \"ignore\""))
        assertTrue(appOps.contains("wc -l"))
        val bucket = buildHogBucketVerifyCmd(listOf("com.a"))
        assertTrue(bucket.contains("am get-standby-bucket \$p"))
        assertTrue(bucket.contains("restricted|50"))
        val whitelist = buildHogWhitelistVerifyCmd(listOf("com.a"))
        assertTrue(whitelist.contains("dumpsys deviceidle whitelist"))
        assertTrue(whitelist.contains("grep -q -F"))
    }

    @Test
    fun snapshotCmdCoversTargetsAndWhitelist() {
        val cmd = buildHogSnapshotCmd(listOf("com.a", "com.b"))
        assertTrue(cmd.contains("for p in com.a com.b"))
        assertTrue(cmd.contains("cmd appops get \$p RUN_ANY_IN_BACKGROUND"))
        assertTrue(cmd.contains("am get-standby-bucket \$p"))
        assertTrue(cmd.contains("WHITELIST"))
        assertTrue(cmd.contains("dumpsys deviceidle whitelist"))
    }
}
