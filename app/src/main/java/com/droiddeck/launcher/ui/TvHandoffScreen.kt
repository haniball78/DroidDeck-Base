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
        val best = TvHandoff.selectMode(modes, wantHz = refreshHz.toFloat()) ?: return
        outputSize = best.width to best.height
        val w = window ?: return
        if (w.attributes.preferredDisplayModeId != best.modeId) {
            w.attributes = w.attributes.apply { preferredDisplayModeId = best.modeId }
        }
    }
}
