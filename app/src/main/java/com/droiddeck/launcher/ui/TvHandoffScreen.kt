package com.droiddeck.launcher.ui

import android.app.Presentation
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.view.Display
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import com.droiddeck.launcher.core.TvHandoff
import com.droiddeck.launcher.input.isWifiDisplay
import com.droiddeck.launcher.session.SessionPrefs

class TvHandoffScreen(
    context: Context,
    display: Display,
    private val refreshHz: Int,
    private val callback: SurfaceHolder.Callback,
) : Presentation(context, display) {
    val surfaceView = SurfaceView(getContext())
    val holder: SurfaceHolder get() = surfaceView.holder
    val hz: Float get() = display.mode.refreshRate
    var outputSize: Pair<Int, Int> = display.mode.physicalWidth to display.mode.physicalHeight
        private set
    // True when the TV offers no exact 1080p mode and the render was capped to 1080p.
    var cappedTo1080p: Boolean = false
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = FrameLayout(context).apply { setBackgroundColor(Color.BLACK) }
        root.addView(surfaceView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(root)
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        applyMode()
        surfaceView.holder.addCallback(callback)
    }

    private fun applyMode() {
        val modes = display.supportedModes.map {
            TvHandoff.TvMode(it.physicalWidth, it.physicalHeight, it.refreshRate, it.modeId)
        }
        // Wireless (DeX/Miracast) stays at 1080p: the link cannot carry 4K.
        val render4k = SessionPrefs.tvRender4k(context) && !display.isWifiDisplay()
        val maxWidth = if (render4k) 3840 else 1920
        val maxHeight = if (render4k) 2160 else 1080
        val best = TvHandoff.selectMode(modes, wantWidth = maxWidth, wantHeight = maxHeight, wantHz = refreshHz.toFloat())
        cappedTo1080p = !render4k && best == null &&
            (display.mode.physicalWidth > 1920 || display.mode.physicalHeight > 1080)
        outputSize = best?.let { it.width to it.height }
            ?: TvHandoff.capOutputSize(display.mode.physicalWidth, display.mode.physicalHeight, maxWidth, maxHeight)
        val w = window ?: return
        if (best != null && w.attributes.preferredDisplayModeId != best.modeId) {
            w.attributes = w.attributes.apply { preferredDisplayModeId = best.modeId }
        }
    }
}
