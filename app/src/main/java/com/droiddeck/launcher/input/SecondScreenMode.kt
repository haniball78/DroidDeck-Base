package com.droiddeck.launcher.input

import android.hardware.display.DisplayManager
import android.view.Display
import androidx.annotation.StringRes
import com.droiddeck.launcher.R
import java.lang.reflect.Method

enum class SecondScreenMode(val id: String, @StringRes val label: Int) {
    NONE("none", R.string.second_mode_none),
    KEYBOARD_TRACKPAD("keyboard-trackpad", R.string.second_mode_keyboard_trackpad),
    TERMINAL("terminal", R.string.second_mode_terminal),
    DECK_CONTROLS("deck-controls", R.string.second_mode_deck_controls),
}

data class SecondScreenDisplay(val id: Int, val label: String)

object SecondScreenDisplays {
    // A wireless DeX display carries no FLAG_PRESENTATION, so the presentation
    // category query never returns it. Query all displays and filter here:
    // a presentation display, or a Wi-Fi display.
    fun available(displayManager: DisplayManager, primaryDisplayId: Int = Display.DEFAULT_DISPLAY): List<SecondScreenDisplay> =
        displayManager.displays
            .asSequence()
            .filter { it.isValid && it.displayId != primaryDisplayId && ((it.flags and Display.FLAG_PRESENTATION) != 0 || it.isWifiDisplay()) }
            .map { target ->
                val mode = target.mode
                SecondScreenDisplay(
                    target.displayId,
                    "${target.name} · ${mode.physicalWidth}×${mode.physicalHeight}",
                )
            }
            .toList()
}

// Display.getType() and Display.TYPE_WIFI are hidden APIs, absent from the
// public SDK (verified against android-34 android.jar). Read the type
// reflectively; any failure means "not Wi-Fi", i.e. today's behaviour.
private const val DISPLAY_TYPE_WIFI = 3 // DisplayInfo.TYPE_WIFI, stable since API 17.

private val displayGetType: Method? by lazy {
    runCatching { Display::class.java.getMethod("getType") }.getOrNull()
}

internal fun Display.isWifiDisplay(): Boolean =
    runCatching { displayGetType?.invoke(this) as? Int == DISPLAY_TYPE_WIFI }.getOrDefault(false)
