package com.droiddeck.launcher.session

import android.view.KeyEvent
import android.view.MotionEvent
import com.droiddeck.launcher.input.PadBridge
import com.droiddeck.launcher.input.PadState

/**
 * Gamepad-phone bridge: merges this phone's touch pad with a physical
 * gamepad plugged into this phone and streams the result to the controller
 * phone, into the slot the P2pPadClient was built with.
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
    }

    fun onTouchState(state: PadState) {
        touchState.copyFrom(state)
        resend()
    }

    fun onKeyEvent(event: KeyEvent): Boolean {
        val bridge = bridge ?: return false
        if (!PadBridge.isControllerKeyEvent(event)) return false
        val handled = bridge.onKeyEvent(event)
        bridge.copyStateInto(hwState)
        resend()
        return handled
    }

    fun onMotionEvent(event: MotionEvent): Boolean {
        val bridge = bridge ?: return false
        if (!PadBridge.isControllerMotionEvent(event)) return false
        val handled = bridge.onMotionEvent(event)
        bridge.copyStateInto(hwState)
        resend()
        return handled
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
}
