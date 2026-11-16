package com.droiddeck.launcher.core

import android.view.InputDevice
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

    @Test fun padShowsWhenEngagedAndMouseModeOff() {
        assertTrue(TvHandoff.shouldShowPad(true, true, false, true, false))
    }

    @Test fun padHidesWhenMouseModeOn() {
        assertFalse(TvHandoff.shouldShowPad(true, true, false, true, true))
    }

    @Test fun padHidesWhenNotEngagedEvenWithoutMouseMode() {
        assertFalse(TvHandoff.shouldShowPad(true, true, true, true, false))
        assertFalse(TvHandoff.shouldShowPad(true, true, false, false, false))
        assertFalse(TvHandoff.shouldShowPad(false, true, false, true, false))
    }

    @Test fun mousePadShowsOnlyOnLiveTvWithMouseMode() {
        assertTrue(TvHandoff.shouldShowMousePad(true, true))
        assertFalse(TvHandoff.shouldShowMousePad(false, true))
        assertFalse(TvHandoff.shouldShowMousePad(true, false))
        assertFalse(TvHandoff.shouldShowMousePad(false, false))
    }

    @Test fun companionInfoNeedsInfoOnAndMouseModeOff() {
        assertTrue(TvHandoff.shouldShowCompanionInfo(true, true, false, false, false, true, false))
        assertFalse(TvHandoff.shouldShowCompanionInfo(true, true, false, false, false, false, false))
        assertFalse(TvHandoff.shouldShowCompanionInfo(true, true, false, false, false, true, true))
        assertFalse(TvHandoff.shouldShowCompanionInfo(true, false, false, false, false, true, false))
        assertFalse(TvHandoff.shouldShowCompanionInfo(false, true, false, false, false, true, false))
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

    @Test fun selectModePicks4kWhenAsked() {
        val modes = listOf(
            TvHandoff.TvMode(3840, 2160, 60f, 1),
            TvHandoff.TvMode(1920, 1080, 60f, 2),
        )
        assertEquals(1, TvHandoff.selectMode(modes, wantWidth = 3840, wantHeight = 2160)?.modeId)
    }

    @Test fun capOutputSizeKeepsNative4kWhen4kCap() {
        assertEquals(3840 to 2160, TvHandoff.capOutputSize(3840, 2160, 3840, 2160))
    }

    @Test fun capOutputSizeScalesDownTo4kCap() {
        assertEquals(3840 to 2160, TvHandoff.capOutputSize(5120, 2880, 3840, 2160))
    }

    @Test fun keyboardSourceCountsAsKeyboardOrMouse() {
        assertTrue(TvHandoff.isKeyboardOrMouseSource(InputDevice.SOURCE_KEYBOARD))
    }

    @Test fun mouseSourceCountsAsKeyboardOrMouse() {
        assertTrue(TvHandoff.isKeyboardOrMouseSource(InputDevice.SOURCE_MOUSE))
    }

    @Test fun touchAndGamepadSourcesDoNotCountAsKeyboardOrMouse() {
        assertFalse(TvHandoff.isKeyboardOrMouseSource(InputDevice.SOURCE_TOUCHSCREEN))
        assertFalse(TvHandoff.isKeyboardOrMouseSource(InputDevice.SOURCE_GAMEPAD or InputDevice.SOURCE_JOYSTICK))
        assertFalse(TvHandoff.isKeyboardOrMouseSource(0))
    }

    @Test fun gameScopeParsesSteamLaunchUrl() {
        assertEquals("440", TvHandoff.gameScope("steam://rungameid/440"))
        assertEquals("440", TvHandoff.gameScope("steam://run/440"))
        assertEquals("440", TvHandoff.gameScope("steam://rungameid/440/extra"))
    }

    @Test fun gameScopeRejectsNonGameUrls() {
        assertEquals("", TvHandoff.gameScope(null))
        assertEquals("", TvHandoff.gameScope(""))
        assertEquals("", TvHandoff.gameScope("steam://open/library"))
        assertEquals("", TvHandoff.gameScope("steam://rungameid/0"))
        assertEquals("", TvHandoff.gameScope("steam://rungameid/abc"))
        assertEquals("", TvHandoff.gameScope("steam://rungameid/4294967296"))
    }

    @Test fun freezeOnlyWhenNoInputChannelLeft() {
        assertTrue(TvHandoff.shouldFreezeOnInputLoss(tvActive = true, running = true, physicalConnected = false, toggleOn = false))
        assertFalse(TvHandoff.shouldFreezeOnInputLoss(tvActive = true, running = true, physicalConnected = true, toggleOn = false))
        assertFalse(TvHandoff.shouldFreezeOnInputLoss(tvActive = true, running = true, physicalConnected = false, toggleOn = true))
        assertFalse(TvHandoff.shouldFreezeOnInputLoss(tvActive = true, running = false, physicalConnected = false, toggleOn = false))
        assertFalse(TvHandoff.shouldFreezeOnInputLoss(tvActive = false, running = true, physicalConnected = false, toggleOn = false))
    }

    @Test fun companionShowsOnlyOnLitPhoneController() {
        assertTrue(TvHandoff.shouldShowCompanion(tvActive = true, controllerActive = true, loadingVisible = false, drawerOpen = false, pcKeyboardOpen = false))
        assertFalse(TvHandoff.shouldShowCompanion(tvActive = true, controllerActive = false, loadingVisible = false, drawerOpen = false, pcKeyboardOpen = false))
        assertFalse(TvHandoff.shouldShowCompanion(tvActive = false, controllerActive = true, loadingVisible = false, drawerOpen = false, pcKeyboardOpen = false))
        assertFalse(TvHandoff.shouldShowCompanion(tvActive = true, controllerActive = true, loadingVisible = true, drawerOpen = false, pcKeyboardOpen = false))
        assertFalse(TvHandoff.shouldShowCompanion(tvActive = true, controllerActive = true, loadingVisible = false, drawerOpen = true, pcKeyboardOpen = false))
        assertFalse(TvHandoff.shouldShowCompanion(tvActive = true, controllerActive = true, loadingVisible = false, drawerOpen = false, pcKeyboardOpen = true))
    }

    @Test fun fpsFromCountsNeedsPositiveWindow() {
        assertEquals(60.0, TvHandoff.fpsFromCounts(100, 160, 1000), 0.001)
        assertEquals(60.0, TvHandoff.fpsFromCounts(0, 30, 500), 0.001)
        assertEquals(0.0, TvHandoff.fpsFromCounts(100, 160, 0), 0.001)
        assertEquals(0.0, TvHandoff.fpsFromCounts(160, 100, 1000), 0.001)
    }

    @Test fun stickDeadZoneCentersSmallMoves() {
        assertEquals(0f to 0f, TvHandoff.stickDeadZone(0f, 0f, 100f))
        assertEquals(0f to 0f, TvHandoff.stickDeadZone(5f, 0f, 100f))
        assertEquals(0f to 0f, TvHandoff.stickDeadZone(0f, 0f, 0f))
    }

    @Test fun stickDeadZoneStretchesPastTheZone() {
        val full = TvHandoff.stickDeadZone(100f, 0f, 100f)
        assertEquals(100f, full.first, 0.001f)
        assertEquals(0f, full.second, 0.001f)
        val half = TvHandoff.stickDeadZone(50f, 0f, 100f)
        assertEquals((0.5f - 0.12f) / 0.88f * 100f, half.first, 0.01f)
        val diagonal = TvHandoff.stickDeadZone(70.71f, 70.71f, 100f)
        assertEquals(diagonal.first, diagonal.second, 0.01f)
        assertTrue(diagonal.first > 0f && diagonal.first < 100f)
    }

    @Test fun scrcpyMouseCountsAsComputerController() {
        assertTrue(TvHandoff.isComputerControllerDevice("scrcpy", InputDevice.SOURCE_MOUSE, false))
    }

    @Test fun scrcpyKeyboardCountsAsComputerController() {
        assertTrue(TvHandoff.isComputerControllerDevice("scrcpy", InputDevice.SOURCE_KEYBOARD, false))
    }

    @Test fun scrcpyGamepadCountsAsComputerController() {
        assertTrue(TvHandoff.isComputerControllerDevice("scrcpy: Xbox Wireless Controller", InputDevice.SOURCE_GAMEPAD, false))
    }

    @Test fun dongleMouseDoesNotCountAsComputerController() {
        assertFalse(TvHandoff.isComputerControllerDevice("Logitech MX Master", InputDevice.SOURCE_MOUSE, false))
    }

    @Test fun virtualScrcpyDeviceDoesNotCountAsComputerController() {
        assertFalse(TvHandoff.isComputerControllerDevice("scrcpy", InputDevice.SOURCE_MOUSE, true))
    }

    @Test fun touchOnlyScrcpyDeviceDoesNotCountAsComputerController() {
        assertFalse(TvHandoff.isComputerControllerDevice("scrcpy", InputDevice.SOURCE_TOUCHSCREEN, false))
    }

    @Test fun similarNameDoesNotCountAsComputerController() {
        assertFalse(TvHandoff.isComputerControllerDevice("scrcpyfoo", InputDevice.SOURCE_MOUSE, false))
        assertFalse(TvHandoff.isComputerControllerDevice(null, InputDevice.SOURCE_MOUSE, false))
        assertFalse(TvHandoff.isComputerControllerDevice("", InputDevice.SOURCE_MOUSE, false))
    }

    @Test fun computerHidesPadWhenConnected() {
        assertTrue(TvHandoff.computerHidesPad(true, false))
    }

    @Test fun computerPadOverrideShowsPad() {
        assertFalse(TvHandoff.computerHidesPad(true, true))
    }

    @Test fun computerPadOverrideWithoutComputerChangesNothing() {
        assertFalse(TvHandoff.computerHidesPad(false, true))
    }

    @Test fun noComputerNoOverrideShowsPad() {
        assertFalse(TvHandoff.computerHidesPad(false, false))
    }
}
