package com.droiddeck.launcher.core.privileged

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionRamSnapshotTest {
    private val sample = """
        USER           CMD                              RSS
        system         /system/bin/surfaceflinger       55296
        u0_a408        com.facebook.aura                348160
        root           [kworker/0:1H]                   0
        u0_a300        com.android.chrome --some-flag   192512
        shell          /system/bin/logd                 4096
        u0_a128        com.samsung.android.mobileservice 106496
        mediacodec     media.hwcodec                    22528
        1000           /system/bin/servicemanager       8192
        u0_i104        com.android.chrome:sandboxed_process0 102400
        system         system_server                    45056
        u0_a200        com.instagram.android            3072
        u0_a201        com.example.tiny                 1024
        not a ps line at all

    """.trimIndent()

    @Test
    fun snapshotIntervalIsSixtySecondsAndTopTen() {
        assertEquals(60_000L, RAM_SNAPSHOT_INTERVAL_MS)
        assertEquals(10, RAM_SNAPSHOT_TOP_COUNT)
    }

    @Test
    fun headerSkippedAndTopTenSortedByRss() {
        val snapshot = parsePsSnapshot(sample)
        assertEquals(10, snapshot.processes.size)
        assertEquals(
            listOf(
                "com.facebook.aura",
                "com.android.chrome --some-flag",
                "com.samsung.android.mobileservice",
                "com.android.chrome:sandboxed_process0",
                "/system/bin/surfaceflinger",
                "system_server",
                "media.hwcodec",
                "/system/bin/servicemanager",
                "/system/bin/logd",
                "com.instagram.android",
            ),
            snapshot.processes.map { it.name },
        )
        assertEquals(12, snapshot.totalCount)
        assertEquals(868L, snapshot.totalRssMb)
    }

    @Test
    fun namesWithSpacesArePreserved() {
        val snapshot = parsePsSnapshot("USER CMD RSS\nu0_a300 com.android.chrome --type=renderer --flag 192512\n")
        assertEquals(1, snapshot.processes.size)
        assertEquals("com.android.chrome --type=renderer --flag", snapshot.processes[0].name)
        assertEquals(188L, snapshot.processes[0].rssMb)
        assertFalse(snapshot.processes[0].system)
    }

    @Test
    fun systemFlagDistinguishesAppUsers() {
        assertFalse(isSystemUser("u0_a408"))
        assertFalse(isSystemUser("u0_i104"))
        assertFalse(isSystemUser("10408"))
        assertTrue(isSystemUser("system"))
        assertTrue(isSystemUser("root"))
        assertTrue(isSystemUser("shell"))
        assertTrue(isSystemUser("media"))
        assertTrue(isSystemUser("1000"))
        assertTrue(isSystemUser("0"))
    }

    @Test
    fun rssConvertsKbToMb() {
        val snapshot = parsePsSnapshot("u0_a408 com.facebook.aura 348160\n")
        assertEquals(340L, snapshot.processes[0].rssMb)
    }

    @Test
    fun garbageAndEmptyInputYieldEmptySnapshot() {
        assertEquals(0, parsePsSnapshot("").processes.size)
        assertEquals(0, parsePsSnapshot("USER CMD RSS\n").processes.size)
        assertEquals(0, parsePsSnapshot("not a ps line at all\n").processes.size)
        val empty = parsePsSnapshot("")
        assertEquals(0, empty.totalCount)
        assertEquals(0L, empty.totalRssMb)
    }

    @Test
    fun fieldsCarryTopTenWithNumberedKeys() {
        val fields = ramSnapshotFields(parsePsSnapshot(sample))
        assertEquals("12", fields["count"])
        assertEquals("868", fields["total_rss_mb"])
        assertEquals("10", fields["top_count"])
        assertEquals("com.facebook.aura", fields["p0_name"])
        assertEquals("340", fields["p0_rss_mb"])
        assertEquals("false", fields["p0_system"])
        assertEquals("/system/bin/surfaceflinger", fields["p4_name"])
        assertEquals("true", fields["p4_system"])
        assertEquals("com.instagram.android", fields["p9_name"])
        assertNull(fields["p10_name"])
    }

    @Test
    fun sampleReturnsFalseWhenBridgeDown() {
        assertFalse(SessionRamSnapshot.sample())
    }
}
