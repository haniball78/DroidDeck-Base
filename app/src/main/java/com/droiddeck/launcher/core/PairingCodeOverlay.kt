package com.droiddeck.launcher.core

import android.content.Context
import android.graphics.PixelFormat
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.ViewTreeLifecycleOwner
import androidx.lifecycle.ViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.ViewTreeSavedStateRegistryOwner
import com.droiddeck.launcher.PairingCodeEntryScreen

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
 * are underneath and the code stays valid. Must be used from the main
 * thread; show() is a no-op without the draw-over-apps permission.
 */
class PairingCodeOverlay(
    private val context: Context,
    private val onSubmit: (String) -> Unit,
    private val onCancel: () -> Unit,
) {
    private val windowManager: WindowManager? = context.getSystemService(WindowManager::class.java)
    private var view: ComposeView? = null
    private var lifecycleOwner: OverlayLifecycleOwner? = null
    private val viewModelStoreOwner = object : ViewModelStoreOwner {
        override val viewModelStore = ViewModelStore()
    }

    fun isShowing(): Boolean = view != null

    fun show() {
        if (view != null || !canDrawOverlays(context)) return
        val manager = windowManager ?: return
        val owner = OverlayLifecycleOwner()
        val composeView = ComposeView(context).apply {
            ViewTreeLifecycleOwner.set(this, owner)
            ViewTreeViewModelStoreOwner.set(this, viewModelStoreOwner)
            ViewTreeSavedStateRegistryOwner.set(this, owner)
            setContent {
                MaterialTheme(colorScheme = darkColorScheme()) {
                    PairingCodeEntryScreen(onSubmit = onSubmit, onCancel = onCancel)
                }
            }
        }
        if (runCatching { manager.addView(composeView, overlayParams()) }.isFailure) return
        view = composeView
        lifecycleOwner = owner
        owner.performAttach()
        owner.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        owner.performRestore(null)
        owner.handleLifecycleEvent(Lifecycle.Event.ON_START)
        owner.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    fun hide() {
        val composeView = view ?: return
        view = null
        val owner = lifecycleOwner
        lifecycleOwner = null
        owner?.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        owner?.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        runCatching { windowManager?.removeView(composeView) }
        owner?.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        viewModelStoreOwner.viewModelStore.clear()
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

    /**
     * Compose needs a lifecycle, a ViewModel store and a saved-state
     * registry in the view tree; a service has none of these, so this
     * owner supplies them and is driven through the overlay's own
     * show/hide.
     */
    private class OverlayLifecycleOwner : SavedStateRegistryOwner {
        private val lifecycleRegistry = LifecycleRegistry(this)
        private val savedStateRegistryController = SavedStateRegistryController.create(this)
        override val lifecycle: Lifecycle get() = lifecycleRegistry
        override val savedStateRegistry: SavedStateRegistry
            get() = savedStateRegistryController.savedStateRegistry

        fun performAttach() = savedStateRegistryController.performAttach()
        fun performRestore(savedState: Bundle?) = savedStateRegistryController.performRestore(savedState)
        fun handleLifecycleEvent(event: Lifecycle.Event) = lifecycleRegistry.handleLifecycleEvent(event)
    }
}
