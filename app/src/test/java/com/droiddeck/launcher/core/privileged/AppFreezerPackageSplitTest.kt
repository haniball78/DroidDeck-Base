package com.droiddeck.launcher.core.privileged

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppFreezerPackageSplitTest {

    private fun packagesItem(targets: List<String>, chunk: Int = 1): JournalItem {
        return JournalItem(
            key = freezerPackagesKey(chunk),
            original = "0",
            restoreCmd = AppFreezerEngine.packagesRestoreCmd(targets),
            verifyCmd = AppFreezerEngine.packagesVerifyCmd(targets),
            verifyMode = JournalVerifyMode.VALUE,
            persistent = true,
            generation = 7,
        )
    }

    @Test
    fun isFreezerPackagesKeyRecognizesBaseAndChunkedKeys() {
        assertTrue(isFreezerPackagesKey("app-freezer-packages"))
        assertTrue(isFreezerPackagesKey(freezerPackagesKey(1)))
        assertTrue(isFreezerPackagesKey(freezerPackagesKey(2)))
        assertTrue(isFreezerPackagesKey(freezerPackagesKey(3)))
        assertTrue(!isFreezerPackagesKey("app-freezer-doze"))
        assertTrue(!isFreezerPackagesKey("app-freezer-packages-foo"))
        assertTrue(!isFreezerPackagesKey("app-freezer-packages-"))
        assertTrue(!isFreezerPackagesKey("app-freezer"))
    }

    @Test
    fun chunkedRestoreSplitsTargetsAndKeepsPmEnableOnLastChunk() {
        val targets = (1..205).map { "com.example.longer.app.name$it" }
        val disabled = listOf("com.facebook.aura", "com.facebook.katana", "com.android.vending", "com.samsung.android.smartsuggestions")
        val items = AppFreezerEngine.freezerPackagesItems(targets, disabled, 7)
        assertEquals(3, items.size)
        assertEquals(listOf(freezerPackagesKey(1), freezerPackagesKey(2), freezerPackagesKey(3)), items.map { it.key })
        assertEquals(targets, items.flatMap { Tweaks.storedTargets(it.restoreCmd) })
        assertEquals(emptyList<String>(), AppFreezerPackageJournal.parseEnabledNames(items[0].restoreCmd))
        assertEquals(emptyList<String>(), AppFreezerPackageJournal.parseEnabledNames(items[1].restoreCmd))
        assertEquals(disabled, AppFreezerPackageJournal.parseEnabledNames(items[2].restoreCmd))
        for (item in items) {
            assertEquals("0", item.original)
            assertEquals(JournalVerifyMode.VALUE, item.verifyMode)
            assertTrue(item.persistent)
            assertEquals(7, item.generation)
            val encoded = java.util.Base64.getEncoder()
                .encodeToString(item.restoreCmd.toByteArray(Charsets.UTF_8))
            assertTrue("EXEC:${encoded}".length <= 8192)
            val verifyEncoded = java.util.Base64.getEncoder()
                .encodeToString(item.verifyCmd!!.toByteArray(Charsets.UTF_8))
            assertTrue("EXEC:${verifyEncoded}".length <= 8192)
        }
    }

    @Test
    fun rollbackDistinguishesExecuteFromVerifyMismatch() {
        val item = packagesItem(listOf("com.example.a"))
        val executeFailed = JournalRollback.rollback(
            listOf(item),
            execute = { 1 },
            readVerified = { "0" },
        )
        assertEquals("execute", executeFailed.failedStage)
        val verifyFailed = JournalRollback.rollback(
            listOf(item),
            execute = { 0 },
            readVerified = { "1" },
        )
        assertEquals("verify-mismatch", verifyFailed.failedStage)
        val unreadable = JournalRollback.rollback(
            listOf(item),
            execute = { 0 },
            readVerified = { null },
        )
        assertEquals("verify-unreadable", unreadable.failedStage)
    }

    @Test
    fun parseSuspendedNamesKeepsOnlyPackageNamesInOrder() {
        assertEquals(
            listOf("com.example.a", "com.example.b"),
            AppFreezerPackageJournal.parseSuspendedNames(
                "com.example.a\nnot a package\ncom.example.b\ncom.example.a\n\n",
            ),
        )
        assertEquals(emptyList<String>(), AppFreezerPackageJournal.parseSuspendedNames(""))
    }

    @Test
    fun parseEnabledNamesExtractsPmEnableTargets() {
        val restore = AppFreezerEngine.packagesRestoreCmd(
            listOf("com.example.a", "com.example.b"),
            listOf("com.android.vending", "com.facebook.aura"),
        )
        assertEquals(
            listOf("com.android.vending", "com.facebook.aura"),
            AppFreezerPackageJournal.parseEnabledNames(restore),
        )
        assertEquals(
            emptyList<String>(),
            AppFreezerPackageJournal.parseEnabledNames(
                AppFreezerEngine.packagesRestoreCmd(listOf("com.example.a")),
            ),
        )
    }

    @Test
    fun filterSuspendTargetsIncludesWebApks() {
        val targets = filterSuspendTargets(
            listOf(
                "com.example.a",
                "org.chromium.webapk.ae52a25cb8056f7df_v2",
                "com.facebook.katana",
            ),
            emptySet(),
        )
        assertEquals(
            listOf(
                "com.example.a",
                "org.chromium.webapk.ae52a25cb8056f7df_v2",
                "com.facebook.katana",
            ),
            targets,
        )
    }

    @Test
    fun splitItemKeepsOnlyRemainingPackages() {
        val original = packagesItem(listOf("com.example.a", "com.example.b", "com.example.c"), chunk = 2)
        val split = AppFreezerPackageJournal.splitItem(original, listOf("com.example.b"))
        assertEquals(freezerPackagesKey(2), split.key)
        assertEquals("0", split.original)
        assertEquals(JournalVerifyMode.VALUE, split.verifyMode)
        assertEquals(AppFreezerEngine.packagesRestoreCmd(listOf("com.example.b")), split.restoreCmd)
        assertEquals(AppFreezerEngine.packagesVerifyCmd(listOf("com.example.b")), split.verifyCmd)
        assertTrue(!split.restoreCmd.contains("com.example.a"))
        assertTrue(!split.restoreCmd.contains("com.example.c"))
    }

    @Test
    fun splitItemPreservesPmEnableTargets() {
        val original = JournalItem(
            key = freezerPackagesKey(3),
            original = "0",
            restoreCmd = AppFreezerEngine.packagesRestoreCmd(
                listOf("com.example.a", "com.example.b"),
                listOf("com.android.vending", "com.facebook.aura"),
            ),
            verifyCmd = AppFreezerEngine.packagesVerifyCmd(listOf("com.example.a", "com.example.b")),
            verifyMode = JournalVerifyMode.VALUE,
            persistent = true,
            generation = 7,
        )
        val split = AppFreezerPackageJournal.splitItem(original, listOf("com.example.b"))
        assertEquals(freezerPackagesKey(3), split.key)
        assertEquals(
            listOf("com.android.vending", "com.facebook.aura"),
            AppFreezerPackageJournal.parseEnabledNames(split.restoreCmd),
        )
        assertTrue(!split.restoreCmd.contains("com.example.a"))
    }

    @Test
    fun namesCommandListsNamesWithoutCount() {
        val cmd = AppFreezerEngine.packagesVerifyNamesCmd(listOf("com.example.a", "com.example.b"))
        assertTrue(cmd.contains("com.example.a"))
        assertTrue(cmd.contains("com.example.b"))
        assertTrue(!cmd.contains("wc -l"))
        assertTrue(cmd.endsWith("; true"))
    }
}
