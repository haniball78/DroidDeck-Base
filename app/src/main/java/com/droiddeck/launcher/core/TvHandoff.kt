package com.droiddeck.launcher.core

import kotlin.math.abs

object TvHandoff {
    data class TvMode(val width: Int, val height: Int, val refreshRate: Float, val modeId: Int)

    fun selectTargetDisplay(displayIds: List<Int>, glassesDisplayId: Int, primaryDisplayId: Int): Int? =
        displayIds.firstOrNull { it != primaryDisplayId && it != glassesDisplayId }

    fun selectMode(
        modes: List<TvMode>,
        wantWidth: Int = 1920,
        wantHeight: Int = 1080,
        wantHz: Float = 60f,
    ): TvMode? =
        modes.filter { it.width == wantWidth && it.height == wantHeight }
            .minByOrNull { abs(it.refreshRate - wantHz) }
}
