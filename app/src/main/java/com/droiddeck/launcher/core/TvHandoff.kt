package com.droiddeck.launcher.core

import kotlin.math.abs
import kotlin.math.hypot

object TvHandoff {
    data class TvMode(val width: Int, val height: Int, val refreshRate: Float, val modeId: Int)

    /** Live companion readings for the phone strip while the game is on the TV. */
    data class CompanionState(
        val fps: Double,
        val batteryPct: Int,
        val charging: Boolean,
        val batteryTempC: Float,
        val tvSeconds: Long,
        val width: Int,
        val height: Int,
        val physicalInput: Boolean,
        val cappedTo1080p: Boolean,
    )

    /** Virtual-stick dead zone, same as the JoyCon sticks. */
    const val STICK_DEADZONE = 0.12f

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

    // Steam app id from a launch URL ("steam://rungameid/440"), or "" when there is
    // no game (desktop/run mode) or the URL is not a game URL. "" means "global".
    fun gameScope(steamUrl: String?): String {
        if (steamUrl == null) return ""
        val id = when {
            steamUrl.startsWith("steam://rungameid/") -> steamUrl.removePrefix("steam://rungameid/")
            steamUrl.startsWith("steam://run/") -> steamUrl.removePrefix("steam://run/")
            else -> return ""
        }.substringBefore('/').substringBefore('?')
        return if (id.isNotEmpty() && GameEnvironment.validScope(id)) id else ""
    }

    // Freeze the game only when NO input channel is left: the TV is live, the session
    // runs, no physical controller is connected, and the phone is not the controller.
    // A physical pad, or the phone pad, each alone means input is fine.
    fun shouldFreezeOnInputLoss(tvActive: Boolean, running: Boolean, physicalConnected: Boolean, toggleOn: Boolean): Boolean =
        tvActive && running && !physicalConnected && !toggleOn

    // The companion strip lives on the phone while the phone is the lit controller.
    // When a physical controller is connected the panel is dimmed by design, so there
    // is nothing to show; loading, drawer and keyboard cover it the same way.
    fun shouldShowCompanion(
        tvActive: Boolean,
        controllerActive: Boolean,
        loadingVisible: Boolean,
        drawerOpen: Boolean,
        pcKeyboardOpen: Boolean,
    ): Boolean = tvActive && controllerActive && !loadingVisible && !drawerOpen && !pcKeyboardOpen

    // Frames per second from two compositor frame counts. Same window math as the
    // game-thread hints (3 s window there); here the caller samples once a second.
    fun fpsFromCounts(prevCount: Long, nowCount: Long, elapsedMs: Long): Double {
        if (elapsedMs <= 0) return 0.0
        val delta = nowCount - prevCount
        if (delta < 0) return 0.0
        return delta * 1000.0 / elapsedMs
    }

    // Virtual-stick output with a dead zone: inside the zone the stick reports center,
    // past it the remaining travel is stretched over the full range, like PadMouse.
    fun stickDeadZone(dx: Float, dy: Float, reach: Float, deadZone: Float = STICK_DEADZONE): Pair<Float, Float> {
        if (reach <= 0f) return 0f to 0f
        val dz = deadZone.coerceIn(0f, 1f)
        val length = hypot(dx, dy)
        val norm = (length / reach).coerceIn(0f, 1f)
        if (norm <= dz) return 0f to 0f
        val magnitude = (norm - dz) / (1f - dz) * reach
        return dx / length * magnitude to dy / length * magnitude
    }
}
