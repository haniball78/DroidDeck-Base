package com.droiddeck.launcher.core

import android.app.ActivityOptions
import android.os.Bundle
import com.droiddeck.launcher.core.privileged.DiagnosticsLog

/**
 * Split-screen placement via the system's windowing-mode launch option.
 * The mode values are the WindowConfiguration constants, inlined so no
 * hidden class is referenced: FULLSCREEN = 1, SPLIT_SCREEN_PRIMARY = 3.
 * `ActivityOptions.setLaunchWindowingMode` is @hide @TestApi on the API 36
 * runtime, so it is reached by reflection only. Every call is best-effort
 * and fails soft to null; each attempt is logged as `adbpair/split-launch`
 * or `adbpair/split-return` with an `applied` field.
 */
object SplitScreenWindowing {

    /** 3 s after a successful pair, the app returns to fullscreen. */
    const val RETURN_TO_FULLSCREEN_DELAY_MS = 3_000L

    private const val WINDOWING_MODE_FULLSCREEN = 1
    private const val WINDOWING_MODE_SPLIT_SCREEN_PRIMARY = 3
    private const val SPLIT_SCREEN_CREATE_MODE_TOP_OR_LEFT = 0

    /** Launch the target into the primary (top, in portrait) half. */
    fun splitPrimaryOptions(): Bundle? =
        windowingOptions(WINDOWING_MODE_SPLIT_SCREEN_PRIMARY, "split-launch")

    /** Launch the target fullscreen, exiting split-screen. */
    fun fullscreenOptions(): Bundle? =
        windowingOptions(WINDOWING_MODE_FULLSCREEN, "split-return")

    private fun windowingOptions(mode: Int, event: String): Bundle? {
        val bundle = runCatching {
            val options = ActivityOptions.makeBasic()
            ActivityOptions::class.java
                .getMethod("setLaunchWindowingMode", Integer.TYPE)
                .invoke(options, mode)
            if (mode == WINDOWING_MODE_SPLIT_SCREEN_PRIMARY) {
                runCatching {
                    ActivityOptions::class.java
                        .getMethod("setSplitScreenCreateMode", Integer.TYPE)
                        .invoke(options, SPLIT_SCREEN_CREATE_MODE_TOP_OR_LEFT)
                }
            }
            options.toBundle()
        }.getOrNull()
        runCatching {
            DiagnosticsLog.log(
                "adbpair", event,
                fields = mapOf("applied" to (bundle != null).toString()),
            )
        }
        return bundle
    }
}
