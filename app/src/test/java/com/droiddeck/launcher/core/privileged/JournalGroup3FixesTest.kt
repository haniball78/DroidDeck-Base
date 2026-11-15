package com.droiddeck.launcher.core.privileged

import android.content.Context
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class JournalGroup3FixesTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private val journalFile: File
        get() = File(context.applicationContext.noBackupFilesDir, "safety-journal.json")

    private val quarantineFile: File
        get() = File(context.applicationContext.noBackupFilesDir, "safety-journal-quarantine.json")

    private val backupFile: File
        get() = File(context.applicationContext.noBackupFilesDir, "safety-journal-repair-backup.json")

    @Before
    fun clean() {
        for (name in listOf(
            "safety-journal.json",
            "safety-journal.json.bak",
            "safety-journal.json.tmp",
            "safety-journal-quarantine.json",
            "safety-journal-quarantine.json.bak",
            "safety-journal-quarantine.json.tmp",
            "safety-journal-repair-backup.json",
            "safety-journal-repair-backup.json.bak",
            "safety-journal-repair-backup.json.tmp",
            "daemon-session-token.txt",
        )) {
            val file = File(context.applicationContext.noBackupFilesDir, name)
            if (file.isDirectory) file.deleteRecursively() else file.delete()
        }
        Settings.Global.putString(context.contentResolver, "droiddeck_deadman_expired", "0")
    }

    private fun settingItem(key: String, original: String?, generation: Int = 7): JournalItem {
        val restore = if (original == null) {
            "settings delete global $key 2>/dev/null"
        } else {
            "settings put global $key $original 2>/dev/null"
        }
        return JournalItem(
            key = key,
            original = original,
            restoreCmd = restore,
            verifyCmd = "settings get global $key",
            verifyMode = JournalVerifyMode.VALUE,
            persistent = true,
            generation = generation,
        )
    }

    private fun corruptItem(): JournalItem {
        return settingItem("background_process_limit", "8")
            .copy(restoreCmd = "Daemon error: failed to connect")
    }

    private fun quarantinedItems(): List<JournalItem>? {
        if (!quarantineFile.exists()) return null
        return JournalCodec.decode(quarantineFile.readText())
    }

    @Test
    fun rollbackQuarantinesCorruptItemAndRetriesSkipIt() {
        val good = settingItem("sem_enhanced_cpu_responsiveness", "0")
        journalFile.writeText(JournalCodec.encode(listOf(corruptItem(), good)))
        assertFalse(Tweaks.rollback(context))
        assertEquals(listOf(corruptItem().key), quarantinedItems()?.map { it.key })
        assertEquals(listOf(good), Tweaks.journalSnapshot(context))
        assertFalse(Tweaks.rollback(context))
        assertEquals(listOf(corruptItem().key), quarantinedItems()?.map { it.key })
        assertEquals(listOf(good), Tweaks.journalSnapshot(context))
    }

    @Test
    fun rollbackWithOnlyCorruptItemQuarantinesItAndClearsJournal() {
        journalFile.writeText(JournalCodec.encode(listOf(corruptItem())))
        assertTrue(Tweaks.rollback(context))
        assertFalse(journalFile.exists())
        assertFalse(Tweaks.recoveryNeeded(context))
        assertEquals(listOf(corruptItem().key), quarantinedItems()?.map { it.key })
    }

    @Test
    fun backupRefusesCorruptJournalAndKeepsGoodBackup() {
        val good = settingItem("background_process_limit", "8")
        journalFile.writeText(JournalCodec.encode(listOf(good)))
        assertTrue(Tweaks.backupJournal(context))
        assertTrue(backupFile.exists())
        val goodBackup = backupFile.readText()
        journalFile.writeText("not json at all {{{")
        assertFalse(Tweaks.backupJournal(context))
        assertEquals(goodBackup, backupFile.readText())
    }

    @Test
    fun backupRefusesJournalWithBadItem() {
        val good = settingItem("background_process_limit", "8")
        val goodJson = JournalCodec.encode(listOf(good))
            .removePrefix("{\"version\":1,\"items\":[")
            .removeSuffix("]}")
        val badJson = "{\"key\":\"app-freezer-packages\",\"original\":\"0\",\"restore\":\"\",\"verify\":null,\"mode\":\"value\",\"persistent\":true,\"generation\":0,\"verifyExpect\":null}"
        journalFile.writeText(JournalCodec.encode(listOf(good)))
        assertTrue(Tweaks.backupJournal(context))
        assertEquals(JournalCodec.encode(listOf(good)), backupFile.readText())
        journalFile.writeText("{\"version\":1,\"items\":[$goodJson,$badJson]}")
        assertFalse(Tweaks.backupJournal(context))
        assertEquals(JournalCodec.encode(listOf(good)), backupFile.readText())
    }

    @Test
    fun backupSucceedsWithNoJournal() {
        assertTrue(Tweaks.backupJournal(context))
        assertFalse(backupFile.exists())
    }

    @Test
    fun quarantineKeyDedupsByKey() {
        val item = settingItem("background_process_limit", "8")
        assertTrue(Tweaks.recordItems(context, listOf(item)))
        assertTrue(Tweaks.quarantineKey(context, item.key))
        assertEquals(listOf(item.key), quarantinedItems()?.map { it.key })
        assertTrue(Tweaks.journalSnapshot(context).isEmpty())
        assertTrue(Tweaks.recordItems(context, listOf(item)))
        assertTrue(Tweaks.quarantineKey(context, item.key))
        assertEquals(listOf(item.key), quarantinedItems()?.map { it.key })
        assertEquals(1, quarantinedItems()?.size)
    }

    @Test
    fun quarantineKeyRefusesWhenQuarantineHasUnpreservableEntry() {
        val kept = settingItem("background_process_limit", "8")
        val keptJson = JournalCodec.encode(listOf(kept))
            .removePrefix("{\"version\":1,\"items\":[")
            .removeSuffix("]}")
        val ghostJson = "{\"key\":\"ghost-item\"}"
        val quarantineText = "{\"version\":1,\"items\":[$keptJson,$ghostJson]}"
        quarantineFile.writeText(quarantineText)
        val item = settingItem("sem_enhanced_cpu_responsiveness", "0")
        assertTrue(Tweaks.recordItems(context, listOf(item)))
        assertFalse(Tweaks.quarantineKey(context, item.key))
        assertEquals(quarantineText, quarantineFile.readText())
        assertEquals(listOf(item), Tweaks.journalSnapshot(context))
    }

    @Test
    fun quarantineKeyRestoresBothFilesWhenJournalSaveFails() {
        val item = settingItem("background_process_limit", "8")
        assertTrue(Tweaks.recordItems(context, listOf(item)))
        val bakDir = File(context.applicationContext.noBackupFilesDir, "safety-journal.json.bak")
        assertTrue(bakDir.mkdir())
        File(bakDir, "stale").writeText("stale")
        try {
            assertFalse(Tweaks.quarantineKey(context, item.key))
            assertFalse(quarantineFile.exists())
            assertEquals(listOf(item), Tweaks.journalSnapshot(context))
        } finally {
            bakDir.deleteRecursively()
        }
    }

    @Test
    fun testRestoreNowReportsSuccessWhenNothingToRestore() {
        val report = GameModeEngine.testRestoreNow(context)
        assertTrue(report.contains("game mode restored to defaults"))
        assertFalse(report.contains("FAILED"))
    }

    @Test
    fun testRestoreNowReportsFailureWhenRestoreFails() {
        val item = settingItem("background_process_limit", "8")
        journalFile.writeText(JournalCodec.encode(listOf(item)))
        val report = GameModeEngine.testRestoreNow(context)
        assertTrue(report.contains("FAILED"))
        assertFalse(report.contains("game mode restored to defaults"))
    }

    @Test
    fun deadManExpiredFlagIsOnlyOne() {
        assertTrue(RecoveryCoordinator.deadManExpiredFlagSet("1"))
        assertTrue(RecoveryCoordinator.deadManExpiredFlagSet(" 1\n"))
        assertFalse(RecoveryCoordinator.deadManExpiredFlagSet("0"))
        assertFalse(RecoveryCoordinator.deadManExpiredFlagSet("true"))
        assertFalse(RecoveryCoordinator.deadManExpiredFlagSet(""))
        assertFalse(RecoveryCoordinator.deadManExpiredFlagSet(null))
    }

    @Test
    fun recoveryPendingWhenOnlyDeadManFlagSet() {
        Settings.Global.putString(context.contentResolver, "droiddeck_deadman_expired", "1")
        assertTrue(RecoveryCoordinator.isDeadManExpired(context))
        assertTrue(RecoveryCoordinator.recoveryPending(context))
        assertTrue(RecoveryCoordinator.attempt(context))
    }

    @Test
    fun recoveryNotPendingWhenFlagClearAndNoJournal() {
        assertFalse(RecoveryCoordinator.isDeadManExpired(context))
        assertFalse(RecoveryCoordinator.recoveryPending(context))
    }
}
