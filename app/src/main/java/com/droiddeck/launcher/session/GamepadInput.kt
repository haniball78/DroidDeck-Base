package com.droiddeck.launcher.session

import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import com.droiddeck.launcher.input.EvdevKeys
import com.droiddeck.launcher.input.PadBridge
import com.droiddeck.launcher.input.PadState

/**
 * Gamepad-phone bridge: merges this phone's touch pad with a physical
 * gamepad plugged into this phone and streams the result to the controller
 * phone, into the slot the P2pPadClient was built with. A keyboard or mouse
 * plugged into this phone is forwarded the same way, as key/mouse messages.
 */
object GamepadInput {
    fun install(bridge: PadBridge, client: P2pPadClient) {
        this.bridge = bridge
        this.client = client
    }

    fun clear() {
        bridge = null
        client = null
        touchState.clear()
        hwState.clear()
        merged.clear()
        lastMouseX = null
        lastMouseY = null
    }

    fun onTouchState(state: PadState) {
        touchState.copyFrom(state)
        resend()
    }

    fun onKeyEvent(event: KeyEvent): Boolean {
        val bridge = bridge ?: return false
        if (PadBridge.isControllerKeyEvent(event)) {
            val handled = bridge.onKeyEvent(event)
            bridge.copyStateInto(hwState)
            resend()
            return handled
        }
        val client = client ?: return false
        if (!isForwardableKey(event)) return false
        if (event.action != KeyEvent.ACTION_DOWN && event.action != KeyEvent.ACTION_UP) return false
        // Repeats are consumed silently, like the controller phone does.
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount > 0) return true
        val evdev = EvdevKeys.fromKeyEvent(event.keyCode, event.unicodeChar, event.scanCode)
        if (evdev > 0 || event.unicodeChar > 0) {
            client.sendKey(event.keyCode, event.unicodeChar, event.scanCode, event.action == KeyEvent.ACTION_DOWN)
            return true
        }
        return false
    }

    fun onMotionEvent(event: MotionEvent): Boolean {
        val bridge = bridge ?: return false
        if (PadBridge.isControllerMotionEvent(event)) {
            val handled = bridge.onMotionEvent(event)
            bridge.copyStateInto(hwState)
            resend()
            return handled
        }
        val client = client ?: return false
        if (event.isFromSource(InputDevice.SOURCE_MOUSE)) {
            val x = event.x
            val y = event.y
            val dx = if (lastMouseX == null) 0f else x - lastMouseX!!
            val dy = if (lastMouseY == null) 0f else y - lastMouseY!!
            lastMouseX = x
            lastMouseY = y
            var buttons = 0
            if (event.buttonState and MotionEvent.BUTTON_PRIMARY != 0) buttons = buttons or 1
            if (event.buttonState and MotionEvent.BUTTON_SECONDARY != 0) buttons = buttons or 2
            if (event.buttonState and MotionEvent.BUTTON_TERTIARY != 0) buttons = buttons or 4
            client.sendMouse(dx, dy, buttons, event.getAxisValue(MotionEvent.AXIS_VSCROLL))
            return true
        }
        return false
    }

    // Navigation and system keys stay on this phone; only typing keys go to the game.
    private fun isForwardableKey(event: KeyEvent): Boolean = when (event.keyCode) {
        KeyEvent.KEYCODE_BACK,
        KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE,
        KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_APP_SWITCH,
        KeyEvent.KEYCODE_POWER, KeyEvent.KEYCODE_SLEEP, KeyEvent.KEYCODE_WAKEUP -> false
        else -> true
    }

    private fun resend() {
        PadWire.merge(merged, touchState, hwState)
        client?.send(merged)
    }

    private var bridge: PadBridge? = null
    private var client: P2pPadClient? = null
    private val touchState = PadState()
    private val hwState = PadState()
    private val merged = PadState()
    private var lastMouseX: Float? = null
    private var lastMouseY: Float? = null
}
