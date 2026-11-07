package com.droiddeck.launcher.core

import android.content.Context
import android.graphics.PixelFormat
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import com.droiddeck.launcher.R

/** Where a tap on the pairing notification body goes. */
enum class CodeEntryTarget { APP, OVERLAY, ACTIVITY }

/**
 * Notification-body routing for the pairing flow. The overlay floats over
 * the system pairing dialog without taking the foreground — the code-entry
 * activity steals it, which dismisses the dialog and invalidates the code
 * (seen on device 2026-11-07) — so the overlay is used whenever the
 * draw-over-apps permission is granted. Without it the activity is the
 * fallback; anything but CodeNeeded just opens the app.
 */
fun codeEntryTarget(codeNeeded: Boolean, overlayPermitted: Boolean): CodeEntryTarget =
    if (codeNeeded) {
        if (overlayPermitted) CodeEntryTarget.OVERLAY else CodeEntryTarget.ACTIVITY
    } else {
        CodeEntryTarget.APP
    }

fun canDrawOverlays(context: Context): Boolean = Settings.canDrawOverlays(context)

/**
 * The pairing-code field as a floating window over the system pairing
 * dialog. A WindowManager overlay (TYPE_APPLICATION_OVERLAY) adds no task
 * and takes no foreground, so Settings and its dialog stay exactly as they
 * are underneath and the code stays valid. Plain framework views: the
 * Compose view-tree owners are not on this build's compile classpath
 * (CI 2026-11-07), so no Compose here. Must be used from the main
 * thread; show() is a no-op without the draw-over-apps permission.
 */
class PairingCodeOverlay(
    private val context: Context,
    private val onSubmit: (String) -> Unit,
    private val onCancel: () -> Unit,
) {
    private val windowManager: WindowManager? = context.getSystemService(WindowManager::class.java)
    private var view: View? = null

    fun isShowing(): Boolean = view != null

    fun show() {
        if (view != null || !canDrawOverlays(context)) return
        val manager = windowManager ?: return
        val root = LayoutInflater.from(context).inflate(R.layout.pairing_code_overlay, null)
        val codeField = root.findViewById<EditText>(R.id.overlay_code)
        val error = root.findViewById<TextView>(R.id.overlay_error)
        val enter = root.findViewById<Button>(R.id.overlay_enter)
        fun submit() {
            val code = codeField.text.toString()
            if (code.length == 6) onSubmit(code) else error.visibility = View.VISIBLE
        }
        codeField.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                error.visibility = View.GONE
                enter.isEnabled = s?.length == 6
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        codeField.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                submit()
                true
            } else {
                false
            }
        }
        enter.setOnClickListener { submit() }
        root.findViewById<Button>(R.id.overlay_cancel).setOnClickListener { onCancel() }
        if (runCatching { manager.addView(root, overlayParams()) }.isFailure) return
        view = root
        codeField.post {
            codeField.requestFocus()
            (context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
                ?.showSoftInput(codeField, 0)
        }
    }

    fun hide() {
        val root = view ?: return
        view = null
        runCatching { windowManager?.removeView(root) }
    }

    private fun overlayParams() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        title = "DroidDeck pairing code"
    }
}
