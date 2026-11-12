package com.droiddeck.launcher.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}
