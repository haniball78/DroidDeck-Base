package com.droiddeck.launcher.core.privileged

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class JournalIntegrityTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private val badItemJson = "{\"key\":\"app-freezer-packages\",\"original\":\"0\",\"restore\":\"cmd package unsuspend --user 0 com.example.bad 2>/dev/null\",\"verify\":\"for p in com.example.bad; do echo \$p; done | wc -l\",\"mode\":\"bogus\",\"persistent\":true,\"generation\":0,\"verifyExpect\":null}"

    private val journalFile: File
        get() = File(context.applicationContext.noBackupFilesDir, "safety-journal.json")

    private val quarantineFile: File
        get() = File(context.applicationContext.noBackupFilesDir, "safety-journal-quarantine.json")

    @Before
    fun clean() {
        for (name in listOf(
            "safety-journal.json",
            "safety-journal.json.bak",
            "safety-journal.json.tmp",
            "safety-journal-quarantine.json",
            "safety-journal-quarantine.json.bak",
            "safety-journal-quarantine.json.tmp",
        )) {
            File(context.applicationContext.noBackupFilesDir, name).delete()
        }
        File(context.filesDir, "suspended-packages.txt").delete()
    }

    private fun settingItem(key: String, original: String?): JournalItem {
        return JournalItem(
            key = key,
            original = original,
            restoreCmd = "settings put global $key $original 2>/dev/null",
            verifyCmd = "settings get global $key",
            verifyMode = JournalVerifyMode.VALUE,
            persistent = true,
            generation = 7,
        )
    }

    private fun freezerItem(targets: List<String>, disabled: List<String> = emptyList(), chunk: Int = 1): JournalItem {
        return JournalItem(
            key = freezerPackagesKey(chunk),
            original = "0",
            restoreCmd = AppFreezerEngine.packagesRestoreCmd(targets, disabled),
            verifyCmd = AppFreezerEngine.packagesVerifyCmd(targets),
            verifyMode = JournalVerifyMode.VALUE,
            persistent = true,
            generation = 7,
        )
    }

    private fun mixedJournalText(good: JournalItem, badJson: String): String {
        val goodJson = JournalCodec.encode(listOf(good))
            .removePrefix("{\"version\":1,\"items\":[")
            .removeSuffix("]}")
        return "{\"version\":1,\"items\":[$goodJson,$badJson]}"
    }

    @Test
    fun decodePartialKeepsGoodItemsAndReportsBadOne() {
        val good = settingItem("background_process_limit", "8")
        val text = mixedJournalText(good, badItemJson)
        val partial = JournalCodec.decodePartial(text)
        assertNotNull(partial)
        assertEquals(listOf(good), partial!!.items)
        assertEquals(1, partial.badItems.size)
        assertTrue(partial.badItems[0].contains("com.example.bad"))
        assertNull(JournalCodec.decode(text))
    }

    @Test
    fun decodePartialReturnsNullForDocLevelCorruption() {
        assertNull(JournalCodec.decodePartial(""))
        assertNull(JournalCodec.decodePartial("{\"version\":2,\"items\":[]}"))
        assertNull(JournalCodec.decodePartial("{\"version\":1,\"items\":[]} trailing"))
        assertNull(JournalCodec.decodePartial("not json"))
        val empty = JournalCodec.decodePartial(JournalCodec.encode(emptyList()))
        assertNotNull(empty)
        assertEquals(emptyList<JournalItem>(), empty!!.items)
        assertTrue(empty.badItems.isEmpty())
    }

    @Test
    fun decodePartialReportsNonObjectItemAsBad() {
        val partial = JournalCodec.decodePartial("{\"version\":1,\"items\":[\"oops\"]}")
        assertNotNull(partial)
        assertTrue(partial!!.items.isEmpty())
        assertEquals(listOf("\"oops\""), partial.badItems)
    }

    @Test
    fun journalWithOneBadItemLoadsGoodItemsAndQuarantinesBad() {
        val good = settingItem("background_process_limit", "8")
        journalFile.writeText(mixedJournalText(good, badItemJson))
        assertEquals(listOf(good), Tweaks.journalSnapshot(context))
        assertTrue(quarantineFile.exists())
        val quarantined = JournalCodec.decode(quarantineFile.readText())
        assertNotNull(quarantined)
        assertEquals(1, quarantined!!.size)
        assertEquals("app-freezer-packages", quarantined[0].key)
        assertTrue(quarantined[0].original?.contains("com.example.bad") == true)
        assertTrue(quarantined[0].original?.contains("bogus") == true)
        assertEquals(listOf(good), Tweaks.journalSnapshot(context))
        assertEquals(1, JournalCodec.decode(quarantineFile.readText())!!.size)
    }

    @Test
    fun journalWithOnlyBadItemsQuarantinesThemAndClearsJournal() {
        journalFile.writeText("{\"version\":1,\"items\":[$badItemJson]}")
        assertEquals(emptyList<JournalItem>(), Tweaks.journalSnapshot(context))
        assertFalse(journalFile.exists())
        assertFalse(Tweaks.recoveryNeeded(context))
        assertEquals(1, JournalCodec.decode(quarantineFile.readText())?.size)
    }

    @Test
    fun mergeUnionsFreezerPackageTargets() {
        val current = listOf(
            freezerItem(listOf("com.example.a", "com.example.b"), disabled = listOf("com.android.vending")),
            settingItem("background_process_limit", "8"),
        )
        val incoming = listOf(
            freezerItem(listOf("com.example.b", "com.example.c")),
            settingItem("background_process_limit", "4"),
        )
        val merged = JournalMerge.merge(current, incoming)
        assertEquals(2, merged.size)
        val freezer = merged.first { it.key == freezerPackagesKey(1) }
        assertEquals(
            listOf("com.example.a", "com.example.b", "com.example.c"),
            Tweaks.storedTargets(freezer.restoreCmd),
        )
        assertEquals(
            listOf("com.android.vending"),
            AppFreezerPackageJournal.parseEnabledNames(freezer.restoreCmd),
        )
        assertEquals(
            AppFreezerEngine.packagesVerifyCmd(listOf("com.example.a", "com.example.b", "com.example.c")),
            freezer.verifyCmd,
        )
        assertEquals("0", freezer.original)
        assertEquals("8", merged.first { it.key == "background_process_limit" }.original)
    }

    @Test
    fun mergeUnionsSameChunkKeyOnly() {
        val current = listOf(
            freezerItem(listOf("com.example.a"), chunk = 1),
            freezerItem(listOf("com.example.b"), chunk = 2),
        )
        val incoming = listOf(
            freezerItem(listOf("com.example.c"), chunk = 1),
            freezerItem(listOf("com.example.d"), chunk = 3),
        )
        val merged = JournalMerge.merge(current, incoming)
        assertEquals(3, merged.size)
        assertEquals(
            listOf("com.example.a", "com.example.c"),
            Tweaks.storedTargets(merged.first { it.key == freezerPackagesKey(1) }.restoreCmd),
        )
        assertEquals(
            listOf("com.example.b"),
            Tweaks.storedTargets(merged.first { it.key == freezerPackagesKey(2) }.restoreCmd),
        )
        assertEquals(
            listOf("com.example.d"),
            Tweaks.storedTargets(merged.first { it.key == freezerPackagesKey(3) }.restoreCmd),
        )
    }

    @Test
    fun mergeKeepsExistingWhenFreezerTargetsUnparseable() {
        val existing = JournalItem(
            key = freezerPackagesKey(2),
            original = "0",
            restoreCmd = "true 2>/dev/null",
            verifyCmd = "true",
            verifyMode = JournalVerifyMode.UNVERIFIED,
            persistent = true,
            generation = 7,
        )
        val merged = JournalMerge.merge(listOf(existing), listOf(existing.copy(verifyCmd = "something else")))
        assertEquals(listOf(existing), merged)
    }

    @Test
    fun freezerMarkerKeptWhilePackagesStillSuspended() {
        val packages = listOf("com.example.a", "com.example.b")
        assertFalse(RecoveryCoordinator.freezerMarkerCleared(packages) { 2 })
        assertFalse(RecoveryCoordinator.freezerMarkerCleared(packages) { null })
        assertTrue(RecoveryCoordinator.freezerMarkerCleared(packages) { 0 })
    }

    @Test
    fun freezerMarkerClearedWithNoPackagesNeedsNoVerify() {
        assertTrue(RecoveryCoordinator.freezerMarkerCleared(emptyList()) { throw AssertionError("verify must not run with no packages") })
    }

    @Test
    fun legacyPackagesReadsMarkerFile() {
        assertNull(AppFreezerEngine.legacyPackages(context))
        File(context.filesDir, "suspended-packages.txt").writeText("com.example.a\ncom.example.b\n#DOZE\n")
        assertEquals(listOf("com.example.a", "com.example.b"), AppFreezerEngine.legacyPackages(context))
    }
}
