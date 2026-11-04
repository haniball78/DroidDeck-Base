package com.droiddeck.launcher.core.privileged

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PerformanceLogTest {
    @Test
    fun fieldsFormatTemperatureAndHeadroom() {
        val fields = performanceFields(
            batteryPercent = 52,
            charging = false,
            thermalStatus = 3,
            batteryTempC = 30.7f,
            headroom = 0.6566667f,
            governorTier = "BALANCED_COOL",
            governorRunning = true,
            tweaksActive = true,
            aggressiveActive = true,
            ramFreeMb = 5200L,
            cpuTempC = 45.2f,
            gpuTempC = 48.0f,
        )
        assertEquals("52", fields["battery"])
        assertEquals("false", fields["charging"])
        assertEquals("3", fields["thermal"])
        assertEquals("30.7", fields["battery_temp_c"])
        assertEquals("45.2", fields["cpu_temp_c"])
        assertEquals("48.0", fields["gpu_temp_c"])
        assertEquals("0.66", fields["headroom"])
        assertEquals("known", fields["headroom_status"])
        assertEquals("BALANCED_COOL", fields["governor_tier"])
        assertEquals("true", fields["governor_running"])
        assertEquals("4", fields["background_limit"])
        assertEquals("true", fields["tweaks_active"])
        assertEquals("true", fields["aggressive_active"])
        assertEquals("5200", fields["ram_free_mb"])
    }

    @Test
    fun coolingReliefMapsToBackgroundLimitTwoAndUnknownHeadroom() {
        val fields = performanceFields(
            batteryPercent = 90,
            charging = true,
            thermalStatus = 3,
            batteryTempC = 41.0f,
            headroom = Float.NaN,
            governorTier = "COOLING_RELIEF",
            governorRunning = true,
            tweaksActive = true,
            aggressiveActive = false,
            ramFreeMb = -1L,
        )
        assertEquals("41.0", fields["battery_temp_c"])
        assertEquals("unknown", fields["headroom"])
        assertEquals("unknown", fields["headroom_status"])
        assertEquals("unknown", fields["cpu_temp_c"])
        assertEquals("unknown", fields["gpu_temp_c"])
        assertEquals("2", fields["background_limit"])
    }

    @Test
    fun inactiveTweaksMapToDefaultBackgroundLimit() {
        val fields = performanceFields(
            batteryPercent = 90,
            charging = false,
            thermalStatus = 0,
            batteryTempC = 30.0f,
            headroom = 0.5f,
            governorTier = "BALANCED_COOL",
            governorRunning = false,
            tweaksActive = false,
            aggressiveActive = false,
            ramFreeMb = 1000L,
        )
        assertEquals("default", fields["background_limit"])
        // A stopped governor must never be reported as BALANCED_COOL.
        assertEquals("STOPPED", fields["governor_tier"])
        assertEquals("false", fields["governor_running"])
    }

    @Test
    fun thermalMilliCelsiusParsesOnlyPlausibleValues() {
        assertEquals(45.0f, parseThermalMilliCelsius("45000\n"))
        assertNull(parseThermalMilliCelsius(null))
        assertNull(parseThermalMilliCelsius(""))
        assertNull(parseThermalMilliCelsius("not-a-temp"))
        assertNull(parseThermalMilliCelsius("999999"))
    }
}
