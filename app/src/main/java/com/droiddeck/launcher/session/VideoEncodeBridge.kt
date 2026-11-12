package com.droiddeck.launcher.session

import android.util.Log
import com.droiddeck.launcher.core.SessionPart
import com.droiddeck.launcher.core.privileged.TvDiagnosticsLog
import java.io.File

/** Steam's Remote Play V4L2 encoder in the guest talks to this MediaCodec encoder (venc.c, venc_server.cpp). */
class VideoEncodeBridge(val socket: File) : SessionPart() {
    override fun start() {
        socket.parentFile?.mkdirs()
        val ok = runCatching { System.loadLibrary("ddvenc"); nativeStart(socket.absolutePath) }
            .onFailure { Log.w(TAG, "hardware video encoding unavailable", it) }
            .getOrDefault(false)
        Log.i(TAG, if (ok) "Remote Play hardware encoding on at $socket" else "Remote Play hardware encoding off")
    }

    override fun stop() {
        stopTapAndLog()
        runCatching { nativeStop() }
        socket.delete()
    }

    /** Starts the stream-loopback tap: encoded bytes are copied to [path] (capped at 8 MB) and counted. */
    fun startTap(path: String): Boolean {
        if (tapActive) return true
        File(path).parentFile?.mkdirs()
        // The tap starts before components.start(), so the library is not loaded yet.
        runCatching { System.loadLibrary("ddvenc") }
        val ok = runCatching { nativeStartTap(path) }.getOrDefault(false)
        tapActive = ok
        return ok
    }

    /** [frames, bytes, keyframes, dropped, firstFrameMs, elapsedMs]; zeros with firstFrameMs -1 when the tap never ran. */
    fun tapStats(): LongArray = runCatching { nativeTapStats() }.getOrDefault(LongArray(6))

    private fun stopTapAndLog() {
        if (!tapActive) return
        tapActive = false
        val stats = tapStats()
        runCatching { nativeStopTap() }
        if (stats.getOrElse(0) { 0L } > 0) {
            TvDiagnosticsLog.log(app(), "tv", "stream-loopback", fields = mapOf(
                "step" to "first-frame",
                "firstFrameMs" to stats.getOrElse(4) { -1L }.toString(),
            ))
        }
        TvDiagnosticsLog.log(app(), "tv", "stream-loopback", fields = mapOf(
            "step" to "stopped",
            "summary" to tapSummary(stats),
        ))
    }

    private external fun nativeStart(path: String): Boolean
    private external fun nativeStop()
    private external fun nativeStartTap(path: String): Boolean
    private external fun nativeStopTap()
    private external fun nativeTapStats(): LongArray

    private var tapActive = false

    private companion object {
        const val TAG = "VideoEncodeBridge"
    }
}

/** [frames, bytes, keyframes, dropped, firstFrameMs, elapsedMs]; short arrays read as zero, firstFrameMs stays -1 when no frame arrived. */
fun tapSummary(stats: LongArray): String {
    val frames = stats.getOrElse(0) { 0L }
    val bytes = stats.getOrElse(1) { 0L }
    val keyframes = stats.getOrElse(2) { 0L }
    val dropped = stats.getOrElse(3) { 0L }
    val firstFrameMs = stats.getOrElse(4) { -1L }
    val elapsedMs = stats.getOrElse(5) { 0L }
    return "frames=$frames bytes=$bytes keyframes=$keyframes dropped=$dropped firstFrameMs=$firstFrameMs elapsedMs=$elapsedMs"
}
