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

    fun shouldEngageController(tvActive: Boolean, running: Boolean, physicalConnected: Boolean, toggleOn: Boolean): Boolean =
        tvActive && running && toggleOn && !physicalConnected

    // Dim only when a physical controller is connected: otherwise the phone itself
    // is the controller and its pad must stay visible.
    fun shouldDimPanel(tvActive: Boolean, physicalConnected: Boolean): Boolean =
        tvActive && physicalConnected

    // No exact 1080p display mode: still render at most 1080p and let the TV upscale.
    fun capOutputSize(nativeWidth: Int, nativeHeight: Int): Pair<Int, Int> {
        if (nativeWidth <= 1920 && nativeHeight <= 1080) return nativeWidth to nativeHeight
        val scale = minOf(1920f / nativeWidth, 1080f / nativeHeight)
        return ((nativeWidth * scale).toInt() and 1.inv()) to ((nativeHeight * scale).toInt() and 1.inv())
    }
}
