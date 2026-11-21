package com.droiddeck.launcher.input

import android.content.Context
import android.view.KeyEvent

object GripScheme {
    const val DEFAULT_GAME_KEY = "default"
    const val ZONE_WIDTH_FRAC = 0.40f
    const val ZONE_HEIGHT_FRAC = 0.65f
    const val STICK_VISIBLE_MS = 30_000L
    const val FADED_ALPHA = 0.08f
    const val DRAG_THRESHOLD_DP = 10f
    const val LONG_PRESS_MS = 500L
    const val SMART_OPEN_INITIAL_MS = 120_000L
    const val SMART_OPEN_REOPEN_MS = 60_000L

    data class Zone(val left: Float, val top: Float, val right: Float, val bottom: Float) {
        fun contains(x: Float, y: Float) = x in left..right && y in top..bottom
    }

    fun zone(width: Float, height: Float) =
        Zone(0f, height * (1f - ZONE_HEIGHT_FRAC), width * ZONE_WIDTH_FRAC, height)

    fun stickAlpha(sessionElapsedMs: Long): Float =
        if (sessionElapsedMs < STICK_VISIBLE_MS) 1f else FADED_ALPHA

    enum class Movement { STICK, DPAD }

    class SmartButton {
        private var closedManually = false
        private var openedAtMs: Long? = null

        fun isOpen(sessionElapsedMs: Long, nowMs: Long): Boolean {
            if (closedManually) return false
            if (sessionElapsedMs < SMART_OPEN_INITIAL_MS) return true
            val opened = openedAtMs ?: return false
            return nowMs - opened < SMART_OPEN_REOPEN_MS
        }

        fun toggle(sessionElapsedMs: Long, nowMs: Long): Boolean {
            return if (isOpen(sessionElapsedMs, nowMs)) {
                closedManually = true
                openedAtMs = null
                false
            } else {
                closedManually = false
                openedAtMs = nowMs
                true
            }
        }
    }

    enum class PressOutcome { NONE, TO_STICK, LONG_PRESS, TAP }

    class PressTracker(private val dragThresholdPx: Float) {
        var downX = 0f
            private set
        var downY = 0f
            private set
        private var downMs = 0L
        private var active = false
        private var converted = false
        private var longPressed = false

        val isStick: Boolean get() = converted
        val isLongPress: Boolean get() = longPressed

        fun onDown(x: Float, y: Float, nowMs: Long) {
            downX = x
            downY = y
            downMs = nowMs
            active = true
            converted = false
            longPressed = false
        }

        fun onMove(x: Float, y: Float, nowMs: Long): PressOutcome {
            if (!active || converted || longPressed) return PressOutcome.NONE
            val dx = x - downX
            val dy = y - downY
            if (dx * dx + dy * dy > dragThresholdPx * dragThresholdPx) {
                converted = true
                return PressOutcome.TO_STICK
            }
            return check(nowMs)
        }

        fun check(nowMs: Long): PressOutcome {
            if (!active || converted || longPressed) return PressOutcome.NONE
            if (nowMs - downMs >= LONG_PRESS_MS) {
                longPressed = true
                return PressOutcome.LONG_PRESS
            }
            return PressOutcome.NONE
        }

        fun onUp(): PressOutcome {
            if (!active) return PressOutcome.NONE
            active = false
            return when {
                converted -> PressOutcome.NONE
                longPressed -> PressOutcome.LONG_PRESS
                else -> PressOutcome.TAP
            }
        }

        fun cancel() {
            active = false
        }
    }

    data class CustomKeyButton(val label: String, val keyCode: Int, val xFrac: Float, val yFrac: Float)

    val KEY_OPTIONS: List<Pair<String, Int>> = listOf(
        "M" to KeyEvent.KEYCODE_M,
        "F" to KeyEvent.KEYCODE_F,
        "E" to KeyEvent.KEYCODE_E,
        "Q" to KeyEvent.KEYCODE_Q,
        "R" to KeyEvent.KEYCODE_R,
        "C" to KeyEvent.KEYCODE_C,
        "V" to KeyEvent.KEYCODE_V,
        "Tab" to KeyEvent.KEYCODE_TAB,
        "Esc" to KeyEvent.KEYCODE_ESCAPE,
        "Enter" to KeyEvent.KEYCODE_ENTER,
        "Space" to KeyEvent.KEYCODE_SPACE,
    )

    fun keyCodeFor(label: String): Int =
        KEY_OPTIONS.firstOrNull { it.first == label }?.second ?: KeyEvent.KEYCODE_M

    object CustomKeys {
        private const val PREFS = "grip_keys"

        fun load(context: Context, gameKey: String): List<CustomKeyButton> {
            val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(gameKey, null)
                ?: return emptyList()
            return raw.split(';').mapNotNull { entry ->
                val parts = entry.split('|')
                if (parts.size != 4) return@mapNotNull null
                val keyCode = parts[1].toIntOrNull() ?: return@mapNotNull null
                val x = parts[2].toFloatOrNull() ?: return@mapNotNull null
                val y = parts[3].toFloatOrNull() ?: return@mapNotNull null
                CustomKeyButton(parts[0], keyCode, x, y)
            }
        }

        fun save(context: Context, gameKey: String, buttons: List<CustomKeyButton>) {
            val raw = buttons.joinToString(";") { "${it.label}|${it.keyCode}|${it.xFrac}|${it.yFrac}" }
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(gameKey, raw).apply()
        }

        fun setKey(context: Context, gameKey: String, label: String, keyCode: Int, defaultXFrac: Float, defaultYFrac: Float) {
            val current = load(context, gameKey).firstOrNull()
            val next = if (current == null) CustomKeyButton(label, keyCode, defaultXFrac, defaultYFrac)
            else current.copy(label = label, keyCode = keyCode)
            save(context, gameKey, listOf(next))
        }

        fun move(context: Context, gameKey: String, xFrac: Float, yFrac: Float) {
            val current = load(context, gameKey).firstOrNull() ?: return
            save(context, gameKey, listOf(current.copy(xFrac = xFrac, yFrac = yFrac)))
        }
    }
}
