package com.droiddeck.launcher.core.privileged

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

class SessionRamReportTest {
    @Test
    fun freedDeltaIsRawAfterMinusBefore() {
        val fields = ramReportFields(
            before = RamSnapshot(availMb = 4000L, cpuLoad = "3.27"),
            after = RamSnapshot(availMb = 5200L, cpuLoad = "0.80"),
            suspendedCount = 164,
            restored = RamSnapshot(availMb = 4100L, cpuLoad = "2.10"),
        )
        assertEquals("4000", fields["before_avail_mb"])
        assertEquals("5200", fields["after_avail_mb"])
        assertEquals("1200", fields["freed_mb"])
        assertEquals("164", fields["suspended_count"])
        assertEquals("3.27", fields["cpu_load_before"])
        assertEquals("0.80", fields["cpu_load_after"])
        assertEquals("4100", fields["restored_avail_mb"])
        assertEquals("2.10", fields["restored_cpu_load"])
    }

    @Test
    fun negativeFreedDeltaIsReportedRaw() {
        val fields = ramReportFields(
            before = RamSnapshot(availMb = 5200L, cpuLoad = "0.80"),
            after = RamSnapshot(availMb = 4000L, cpuLoad = "3.27"),
            suspendedCount = 164,
            restored = null,
        )
        assertEquals("-1200", fields["freed_mb"])
    }

    @Test
    fun unknownAvailMeansUnknownFreed() {
        val beforeUnknown = ramReportFields(
            before = RamSnapshot(availMb = -1L, cpuLoad = "unknown"),
            after = RamSnapshot(availMb = 5200L, cpuLoad = "0.80"),
            suspendedCount = 0,
            restored = null,
        )
        assertEquals("unknown", beforeUnknown["before_avail_mb"])
        assertEquals("unknown", beforeUnknown["freed_mb"])
        val afterUnknown = ramReportFields(
            before = RamSnapshot(availMb = 4000L, cpuLoad = "3.27"),
            after = RamSnapshot(availMb = -1L, cpuLoad = "unknown"),
            suspendedCount = 0,
            restored = null,
        )
        assertEquals("unknown", afterUnknown["after_avail_mb"])
        assertEquals("unknown", afterUnknown["freed_mb"])
    }

    @Test
    fun missingRestoredMeansUnknownRestored() {
        val fields = ramReportFields(
            before = RamSnapshot(availMb = 4000L, cpuLoad = "3.27"),
            after = RamSnapshot(availMb = 5200L, cpuLoad = "0.80"),
            suspendedCount = 164,
            restored = null,
        )
        assertEquals("unknown", fields["restored_avail_mb"])
        assertEquals("unknown", fields["restored_cpu_load"])
    }

    @Test
    fun cpuLoadParsesFirstFieldOnly() {
        assertEquals("3.27", parseCpuLoad("3.27 2.10 1.50 2/500 12345\n"))
        assertEquals("0.00", parseCpuLoad("0.00 0.00 0.00 1/400 9999"))
        assertEquals("unknown", parseCpuLoad(null))
        assertEquals("unknown", parseCpuLoad(""))
        assertEquals("unknown", parseCpuLoad("garbage 2.10 1.50"))
        assertEquals("unknown", parseCpuLoad("-1.0 0.00 0.00"))
    }

    @Test
    fun missingLoadavgFileIsUnknown() {
        assertEquals("unknown", readCpuLoad("/nonexistent/loadavg"))
    }

    @Test
    fun loadavgFileContentIsRead() {
        val file = File.createTempFile("loadavg", ".txt")
        try {
            file.writeText("2.50 1.00 0.50 1/400 9999")
            assertEquals("2.50", readCpuLoad(file.absolutePath))
        } finally {
            file.delete()
        }
    }

    @Test
    fun nullContextSnapshotIsUnknown() {
        val snapshot = readRamSnapshot(null, "/nonexistent/loadavg")
        assertEquals(-1L, snapshot.availMb)
        assertEquals("unknown", snapshot.cpuLoad)
    }

    @Test
    fun performanceFieldsCarriesCpuLoad() {
        val fields = performanceFields(
            batteryPercent = 52,
            charging = false,
            thermalStatus = 3,
            batteryTempC = 30.7f,
            headroom = 0.65f,
            governorTier = "BALANCED_COOL",
            governorRunning = true,
            tweaksActive = true,
            aggressiveActive = true,
            ramFreeMb = 5200L,
            cpuLoad = "3.27",
        )
        assertEquals("3.27", fields["cpu_load"])
        val defaulted = performanceFields(
            batteryPercent = 52,
            charging = false,
            thermalStatus = 3,
            batteryTempC = 30.7f,
            headroom = 0.65f,
            governorTier = "BALANCED_COOL",
            governorRunning = true,
            tweaksActive = true,
            aggressiveActive = true,
            ramFreeMb = 5200L,
        )
        assertEquals("unknown", defaulted["cpu_load"])
    }
}
