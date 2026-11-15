package com.droiddeck.launcher.ui

import android.content.Context
import android.os.SystemClock
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.TextView
import com.droiddeck.launcher.input.PointerGestures
import com.droiddeck.launcher.session.SessionState
import com.droiddeck.launcher.wayland.WaylandCompositor

// The whole phone screen as a mouse while the game is on the TV: one finger
// drags the pointer, tap clicks, hold drags with the button down, two fingers
// tap for right-click and drag to scroll. No other control is visible then.
class TvPhoneMousePad(context: Context) : FrameLayout(context) {

    private val tapSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private val tapTimeoutMs = 300L
    private val longPressMs = 400L
    private val scrollStepPx = 24f

    private var activePointerId = -1
    private var secondPointerId = -1
    private var lastX = 0f
    private var lastY = 0f
    private var downX = 0f
    private var downY = 0f
    private var downAtMs = 0L
    private var tapCandidate = false
    private var holding = false
    private var secondDownAtMs = -1L
    private var twoFingerTapCandidate = false
    private var twoFingerMoved = false
    private var scrollAccum = 0f

    private val longPressRunnable = Runnable {
        if (activePointerId == -1 || holding || secondPointerId != -1) return@Runnable
        holding = true
        pressButton(PointerGestures.BTN_LEFT, true)
        @Suppress("DEPRECATION")
        (context.getSystemService(Context.VIBRATOR_SERVICE) as? android.os.Vibrator)?.vibrate(15)
    }

    init {
        isClickable = true
        setBackgroundColor(android.graphics.Color.TRANSPARENT)
        addView(
            TextView(context).apply {
                text = "Drag to move · Tap to click · Two fingers: right-click · Hold to drag"
                textSize = 11f
                setTextColor(android.graphics.Color.WHITE)
                alpha = 0.6f
                isClickable = false
                isFocusable = false
            },
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, android.view.Gravity.BOTTOM or android.view.Gravity.CENTER_HORIZONTAL)
                .apply { bottomMargin = (16 * resources.displayMetrics.density).toInt() },
        )
    }

    private fun pressButton(button: Int, down: Boolean) {
        WaylandCompositor.nativeSendSceneInput(3, button, if (down) 1 else 0)
    }

    private fun clickButton(button: Int) {
        pressButton(button, true)
        pressButton(button, false)
    }

    private fun pointerScale(): Float {
        val w = width.toFloat()
        if (w <= 0f) return 1f
        return SessionState.outputSize.first / w
    }

    private fun resetTouch() {
        removeCallbacks(longPressRunnable)
        activePointerId = -1
        secondPointerId = -1
        tapCandidate = false
        holding = false
        secondDownAtMs = -1L
        twoFingerTapCandidate = false
        twoFingerMoved = false
        scrollAccum = 0f
    }

    fun releaseAll() {
        if (holding) pressButton(PointerGestures.BTN_LEFT, false)
        resetTouch()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                activePointerId = event.getPointerId(0)
                secondPointerId = -1
                lastX = event.x
                lastY = event.y
                downX = event.x
                downY = event.y
                downAtMs = SystemClock.uptimeMillis()
                tapCandidate = true
                holding = false
                secondDownAtMs = -1L
                twoFingerTapCandidate = false
                twoFingerMoved = false
                scrollAccum = 0f
                removeCallbacks(longPressRunnable)
                postDelayed(longPressRunnable, longPressMs)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                val index = event.actionIndex
                secondPointerId = event.getPointerId(index)
                secondDownAtMs = SystemClock.uptimeMillis()
                twoFingerTapCandidate = true
                twoFingerMoved = false
                scrollAccum = 0f
                removeCallbacks(longPressRunnable)
                if (holding) {
                    holding = false
                    pressButton(PointerGestures.BTN_LEFT, false)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                val index = event.findPointerIndex(activePointerId)
                if (index == -1) return true
                val x = event.getX(index)
                val y = event.getY(index)
                val dx = x - lastX
                val dy = y - lastY
                lastX = x
                lastY = y
                if (kotlin.math.abs(x - downX) > tapSlop || kotlin.math.abs(y - downY) > tapSlop) {
                    tapCandidate = false
                    twoFingerTapCandidate = false
                    twoFingerMoved = true
                    removeCallbacks(longPressRunnable)
                }
                if (secondPointerId != -1) {
                    scrollAccum -= dy
                    val steps = (scrollAccum / scrollStepPx).toInt()
                    if (steps != 0) {
                        WaylandCompositor.nativeSendSceneInput(4, steps, 0)
                        scrollAccum -= steps * scrollStepPx
                    }
                } else if (dx != 0f || dy != 0f) {
                    val scale = pointerScale()
                    WaylandCompositor.sendPointerDelta(dx * scale, dy * scale)
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                val liftedId = event.getPointerId(event.actionIndex)
                if (liftedId == secondPointerId) {
                    secondPointerId = -1
                } else if (liftedId == activePointerId && secondPointerId != -1) {
                    val remaining = event.findPointerIndex(secondPointerId)
                    if (remaining != -1) {
                        activePointerId = secondPointerId
                        secondPointerId = -1
                        lastX = event.getX(remaining)
                        lastY = event.getY(remaining)
                        downX = lastX
                        downY = lastY
                        tapCandidate = false
                    }
                }
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPressRunnable)
                val now = SystemClock.uptimeMillis()
                when {
                    twoFingerTapCandidate && secondDownAtMs > 0 && now - secondDownAtMs < tapTimeoutMs && !twoFingerMoved ->
                        clickButton(PointerGestures.BTN_RIGHT)
                    holding -> {
                        holding = false
                        pressButton(PointerGestures.BTN_LEFT, false)
                    }
                    tapCandidate && now - downAtMs < tapTimeoutMs ->
                        clickButton(PointerGestures.BTN_LEFT)
                }
                resetTouch()
            }
            MotionEvent.ACTION_CANCEL -> releaseAll()
            else -> return false
        }
        return true
    }
}
