package com.droiddeck.launcher.core.privileged

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionProfilerTest {
    private fun tempDir(): File = Files.createTempDirectory("profiler-test").toFile()

    @Test
    fun gpuBusyParsesOnlyZeroToOneHundred() {
        assertEquals(24, parseGpuBusyPercent("24\n"))
        assertEquals(0, parseGpuBusyPercent("0"))
        assertEquals(100, parseGpuBusyPercent("100"))
        assertNull(parseGpuBusyPercent(null))
        assertNull(parseGpuBusyPercent(""))
        assertNull(parseGpuBusyPercent("not-a-number"))
        assertNull(parseGpuBusyPercent("-1"))
        assertNull(parseGpuBusyPercent("101"))
    }

    @Test
    fun gpuBusyReadsFromFile() {
        val file = File.createTempFile("gpu_busy", ".txt")
        file.writeText("57\n")
        assertEquals(57, readGpuBusyPercent(file.absolutePath))
        assertNull(readGpuBusyPercent(null))
        assertNull(readGpuBusyPercent("/nonexistent/gpu_busy_percentage"))
    }

    @Test
    fun fieldsFormatUnknownForMissingSensors() {
        val fields = profilerFields(gpuBusyPercent = 24, gpuTempC = 43.2f, cpuLoad = "3.86", fps = "60.0")
        assertEquals("24", fields["gpu_busy_pct"])
        assertEquals("43.2", fields["gpu_temp_c"])
        assertEquals("3.86", fields["cpu_load"])
        assertEquals("60.0", fields["fps"])
        val missing = profilerFields(gpuBusyPercent = null, gpuTempC = null, cpuLoad = "unknown", fps = "unknown")
        assertEquals("unknown", missing["gpu_busy_pct"])
        assertEquals("unknown", missing["gpu_temp_c"])
        assertEquals("unknown", missing["cpu_load"])
        assertEquals("unknown", missing["fps"])
    }

    @Test
    fun fpsFromFrameCounts() {
        assertEquals("unknown", fpsString(prevCount = 0, prevTimeMs = 0, nowCount = 0, nowMs = 5000))
        assertEquals("60.0", fpsString(prevCount = 100, prevTimeMs = 1000, nowCount = 400, nowMs = 6000))
        assertEquals("55.0", fpsString(prevCount = 0, prevTimeMs = 1000, nowCount = 275, nowMs = 6000))
        assertEquals("unknown", fpsString(prevCount = 0, prevTimeMs = 1000, nowCount = 0, nowMs = 6000))
        assertEquals("0.0", fpsString(prevCount = 300, prevTimeMs = 1000, nowCount = 300, nowMs = 6000))
        assertEquals("unknown", fpsString(prevCount = 300, prevTimeMs = 1000, nowCount = 250, nowMs = 6000))
        assertEquals("unknown", fpsString(prevCount = 100, prevTimeMs = 6000, nowCount = 400, nowMs = 6000))
        assertEquals("unknown", fpsString(prevCount = 100, prevTimeMs = 6000, nowCount = 400, nowMs = 5000))
    }

    @Test
    fun fieldsIncludeGovernorTier() {
        val fields = profilerFields(gpuBusyPercent = 24, gpuTempC = 43.2f, cpuLoad = "3.86", fps = "60.0", tier = "HEADROOM_BOOST")
        assertEquals("HEADROOM_BOOST", fields["tier"])
        assertEquals("unknown", profilerFields(gpuBusyPercent = null, gpuTempC = null, cpuLoad = "unknown", fps = "unknown")["tier"])
    }

    @Test
    fun writeSampleAppendsOneJsonLinePerSample() {
        val dir = tempDir()
        val fields = profilerFields(gpuBusyPercent = 24, gpuTempC = 43.2f, cpuLoad = "3.86", fps = "60.0")
        assertTrue(SessionProfiler.writeSample(dir, fields, wallMs = 1700000000000L, elapsedMs = 12345L))
        assertTrue(SessionProfiler.writeSample(dir, fields, wallMs = 1700000005000L, elapsedMs = 17345L))
        val lines = File(dir, SessionProfiler.FILE_NAME).readLines()
        assertEquals(2, lines.size)
        assertTrue(lines[0].contains("\"category\":\"session.profiler\""))
        assertTrue(lines[0].contains("\"event\":\"sample\""))
        assertTrue(lines[0].contains("\"gpu_busy_pct\":\"24\""))
        assertTrue(lines[0].contains("\"gpu_temp_c\":\"43.2\""))
        assertTrue(lines[0].contains("\"cpu_load\":\"3.86\""))
        assertTrue(lines[0].contains("\"fps\":\"60.0\""))
        assertTrue(lines[1].contains("\"ts\":1700000005000"))
    }
}
