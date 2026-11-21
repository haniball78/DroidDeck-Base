package com.droiddeck.launcher.input

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GripSchemeTest {

    @Test fun zoneIsFortyPercentWideAndSixtyFivePercentTallBottomAnchored() {
        val zone = GripScheme.zone(1200f, 800f)
        assertEquals(0f, zone.left, 0.01f)
        assertEquals(480f, zone.right, 0.01f)
        assertEquals(280f, zone.top, 0.01f)
        assertEquals(800f, zone.bottom, 0.01f)
        assertTrue(zone.contains(100f, 600f))
        assertFalse(zone.contains(500f, 600f))
        assertFalse(zone.contains(100f, 200f))
    }

    @Test fun stickIsFullyVisibleForThirtySecondsThenVeryTransparent() {
        assertEquals(1f, GripScheme.stickAlpha(0L), 0f)
        assertEquals(1f, GripScheme.stickAlpha(29_999L), 0f)
        assertEquals(GripScheme.FADED_ALPHA, GripScheme.stickAlpha(30_000L), 0f)
        assertEquals(GripScheme.FADED_ALPHA, GripScheme.stickAlpha(300_000L), 0f)
        assertTrue(GripScheme.FADED_ALPHA < 0.1f)
    }

    @Test fun smartButtonStaysOpenTwoMinutesThenCloses() {
        val smart = GripScheme.SmartButton()
        assertTrue(smart.isOpen(0L, 0L))
        assertTrue(smart.isOpen(119_999L, 119_999L))
        assertFalse(smart.isOpen(120_000L, 120_000L))
        assertFalse(smart.isOpen(300_000L, 300_000L))
    }

    @Test fun smartButtonReopensForOneMinuteAfterTap() {
        val smart = GripScheme.SmartButton()
        assertTrue(smart.toggle(300_000L, 300_000L))
        assertTrue(smart.isOpen(300_000L, 300_000L))
        assertTrue(smart.isOpen(359_999L, 359_999L))
        assertFalse(smart.isOpen(360_000L, 360_000L))
    }

    @Test fun smartButtonTapWhileOpenClosesItImmediately() {
        val smart = GripScheme.SmartButton()
        assertFalse(smart.toggle(10_000L, 10_000L))
        assertFalse(smart.isOpen(10_000L, 10_000L))
        assertTrue(smart.toggle(20_000L, 20_000L))
        assertTrue(smart.isOpen(20_000L, 20_000L))
    }

    @Test fun pressWithoutDragIsATap() {
        val tracker = GripScheme.PressTracker(10f)
        tracker.onDown(100f, 200f, 1000L)
        assertEquals(GripScheme.PressOutcome.TAP, tracker.onUp())
    }

    @Test fun dragPastThresholdConvertsToStickWithDownPointAnchor() {
        val tracker = GripScheme.PressTracker(10f)
        tracker.onDown(100f, 200f, 1000L)
        assertEquals(GripScheme.PressOutcome.NONE, tracker.onMove(105f, 200f, 1100L))
        assertEquals(GripScheme.PressOutcome.TO_STICK, tracker.onMove(111f, 200f, 1100L))
        assertEquals(100f, tracker.downX, 0f)
        assertEquals(200f, tracker.downY, 0f)
    }

    @Test fun longHoldWithoutDragIsALongPressAndLaterDragsDoNotConvert() {
        val tracker = GripScheme.PressTracker(10f)
        tracker.onDown(100f, 200f, 1000L)
        assertEquals(GripScheme.PressOutcome.LONG_PRESS, tracker.check(1500L))
        assertEquals(GripScheme.PressOutcome.NONE, tracker.onMove(200f, 200f, 1600L))
        assertEquals(GripScheme.PressOutcome.LONG_PRESS, tracker.onUp())
    }

    @Test fun holdShortOfThresholdThenReleaseIsStillATap() {
        val tracker = GripScheme.PressTracker(10f)
        tracker.onDown(100f, 200f, 1000L)
        assertEquals(GripScheme.PressOutcome.NONE, tracker.check(1400L))
        assertEquals(GripScheme.PressOutcome.TAP, tracker.onUp())
    }

    @Test fun keyCodeForMapsLabelsToAndroidKeyCodes() {
        assertEquals(KeyEvent.KEYCODE_M, GripScheme.keyCodeFor("M"))
        assertEquals(KeyEvent.KEYCODE_TAB, GripScheme.keyCodeFor("Tab"))
        assertEquals(KeyEvent.KEYCODE_ESCAPE, GripScheme.keyCodeFor("Esc"))
        assertEquals(KeyEvent.KEYCODE_SPACE, GripScheme.keyCodeFor("Space"))
        assertEquals(KeyEvent.KEYCODE_ENTER, GripScheme.keyCodeFor("Enter"))
    }
}
