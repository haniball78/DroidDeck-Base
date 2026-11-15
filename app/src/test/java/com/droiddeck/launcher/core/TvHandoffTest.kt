package com.droiddeck.launcher.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TvHandoffTest {

    @Test fun selectTargetSkipsGlassesAndPrimary() {
        assertEquals(
            11,
            TvHandoff.selectTargetDisplay(listOf(0, 7, 11), glassesDisplayId = 7, primaryDisplayId = 0),
        )
    }

    @Test fun selectTargetReturnsNullWhenOnlyGlassesAndPrimary() {
        assertNull(TvHandoff.selectTargetDisplay(listOf(0, 7), glassesDisplayId = 7, primaryDisplayId = 0))
    }

    @Test fun selectTargetReturnsNullWhenEmpty() {
        assertNull(TvHandoff.selectTargetDisplay(emptyList(), glassesDisplayId = 7, primaryDisplayId = 0))
    }

    @Test fun selectModePicks1080pClosestTo60() {
        val modes = listOf(
            TvHandoff.TvMode(3840, 2160, 60f, 1),
            TvHandoff.TvMode(1920, 1080, 120f, 2),
            TvHandoff.TvMode(1920, 1080, 60f, 3),
            TvHandoff.TvMode(1920, 1080, 50f, 4),
        )
        assertEquals(3, TvHandoff.selectMode(modes)?.modeId)
    }

    @Test fun selectModeReturnsNullWithout1080p() {
        assertNull(TvHandoff.selectMode(listOf(TvHandoff.TvMode(3840, 2160, 60f, 1))))
    }

    @Test fun controllerEngagesWhenTvLiveAndNoPhysicalPad() {
        assertTrue(TvHandoff.shouldEngageController(tvActive = true, running = true, physicalConnected = false, toggleOn = true))
    }

    @Test fun controllerStaysOffWhenTvNotActive() {
        assertFalse(TvHandoff.shouldEngageController(tvActive = false, running = true, physicalConnected = false, toggleOn = true))
    }

    @Test fun controllerStaysOffWhenSessionNotRunning() {
        assertFalse(TvHandoff.shouldEngageController(tvActive = true, running = false, physicalConnected = false, toggleOn = true))
    }

    @Test fun controllerStaysOffWhenPhysicalPadConnected() {
        assertFalse(TvHandoff.shouldEngageController(tvActive = true, running = true, physicalConnected = true, toggleOn = true))
    }

    @Test fun controllerStaysOffWhenToggleOff() {
        assertFalse(TvHandoff.shouldEngageController(tvActive = true, running = true, physicalConnected = false, toggleOn = false))
    }

    @Test fun dimPanelWhenTvLiveAndPhysicalPadConnected() {
        assertTrue(TvHandoff.shouldDimPanel(tvActive = true, physicalConnected = true))
    }

    @Test fun dimPanelStaysOffWhenPhoneIsTheController() {
        assertFalse(TvHandoff.shouldDimPanel(tvActive = true, physicalConnected = false))
    }

    @Test fun dimPanelStaysOffWhenTvNotActive() {
        assertFalse(TvHandoff.shouldDimPanel(tvActive = false, physicalConnected = true))
    }

    @Test fun capOutputSizeKeeps1080pAndBelow() {
        assertEquals(1920 to 1080, TvHandoff.capOutputSize(1920, 1080))
        assertEquals(1280 to 720, TvHandoff.capOutputSize(1280, 720))
    }

    @Test fun capOutputSizeCaps4kTo1080p() {
        assertEquals(1920 to 1080, TvHandoff.capOutputSize(3840, 2160))
        assertEquals(1920 to 1080, TvHandoff.capOutputSize(2560, 1440))
    }

    @Test fun capOutputSizeKeepsEvenDimensions() {
        val capped = TvHandoff.capOutputSize(3839, 2159)
        assertTrue(capped.first <= 1920 && capped.second <= 1080)
        assertEquals(0, capped.first % 2)
        assertEquals(0, capped.second % 2)
    }
}
