package com.droiddeck.launcher.core.privileged

import org.junit.Assert.assertEquals
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
            tweaksActive = true,
            aggressiveActive = true,
            ramFreeMb = 5200L,
        )
        assertEquals("52", fields["battery"])
        assertEquals("false", fields["charging"])
        assertEquals("3", fields["thermal"])
        assertEquals("30.7", fields["battery_temp_c"])
        assertEquals("0.66", fields["headroom"])
        assertEquals("BALANCED_COOL", fields["governor_tier"])
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
            tweaksActive = true,
            aggressiveActive = false,
            ramFreeMb = -1L,
        )
        assertEquals("41.0", fields["battery_temp_c"])
        assertEquals("unknown", fields["headroom"])
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
            tweaksActive = false,
            aggressiveActive = false,
            ramFreeMb = 1000L,
        )
        assertEquals("default", fields["background_limit"])
    }
}
