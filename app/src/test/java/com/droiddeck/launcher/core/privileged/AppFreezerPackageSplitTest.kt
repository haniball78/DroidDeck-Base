package com.droiddeck.launcher.core.privileged

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppFreezerPackageSplitTest {

    private fun packagesItem(targets: List<String>): JournalItem {
        return JournalItem(
            key = "app-freezer-packages",
            original = "0",
            restoreCmd = AppFreezerEngine.packagesRestoreCmd(targets),
            verifyCmd = AppFreezerEngine.packagesVerifyCmd(targets),
            verifyMode = JournalVerifyMode.VALUE,
            persistent = true,
            generation = 7,
        )
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
    fun splitItemKeepsOnlyRemainingPackages() {
        val original = packagesItem(listOf("com.example.a", "com.example.b", "com.example.c"))
        val split = AppFreezerPackageJournal.splitItem(original, listOf("com.example.b"))
        assertEquals("app-freezer-packages", split.key)
        assertEquals("0", split.original)
        assertEquals(JournalVerifyMode.VALUE, split.verifyMode)
        assertEquals(AppFreezerEngine.packagesRestoreCmd(listOf("com.example.b")), split.restoreCmd)
        assertEquals(AppFreezerEngine.packagesVerifyCmd(listOf("com.example.b")), split.verifyCmd)
        assertTrue(!split.restoreCmd.contains("com.example.a"))
        assertTrue(!split.restoreCmd.contains("com.example.c"))
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
