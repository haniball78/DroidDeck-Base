package com.droiddeck.launcher.core

import android.app.ActivityOptions
import android.os.Bundle
import com.droiddeck.launcher.core.privileged.DiagnosticsLog

/**
 * Split-screen placement via the system's windowing-mode launch option.
 * The mode value is the WindowConfiguration constant, inlined so no
 * hidden class is referenced: SPLIT_SCREEN_PRIMARY = 3.
 * `ActivityOptions.setLaunchWindowingMode` is @hide @TestApi on the API 36
 * runtime, so it is reached by reflection only. Every call is best-effort
 * and fails soft to null; each attempt is logged as `adbpair/split-launch`
 * with an `applied` field. After a successful pair, Settings is closed
 * (our own app is never relaunched): closing it leaves only our app, so
 * it takes the full screen by itself.
 */
object SplitScreenWindowing {

    /** Request code for the Settings launch, so Done can close exactly it. */
    const val REQUEST_PAIRING_SETTINGS = 1001

    /** 4 s after a successful pair, close Settings. */
    const val CLOSE_SETTINGS_DELAY_MS = 4_000L

    /** Settle gap between opening Settings and starting the pairing. */
    const val PAIRING_STAGE_DELAY_MS = 400L

    private const val WINDOWING_MODE_SPLIT_SCREEN_PRIMARY = 3
    private const val SPLIT_SCREEN_CREATE_MODE_TOP_OR_LEFT = 0

    /** Launch the target into the primary (top, in portrait) half. */
    fun splitPrimaryOptions(): Bundle? =
        windowingOptions(WINDOWING_MODE_SPLIT_SCREEN_PRIMARY, "split-launch")

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
