package com.droiddeck.launcher.session

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import com.droiddeck.launcher.core.SessionPart
import com.droiddeck.launcher.core.privileged.TvDiagnosticsLog
import com.droiddeck.launcher.wayland.WaylandCompositor
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class TvSurfaceEncode(private val outFile: File, private var chunkSink: ((ByteArray) -> Unit)? = null) : SessionPart() {
    override fun start() {
        begin()
    }

    override fun stop() {
        stopping = true
        if (!running.get()) {
            thread?.quitSafely()
            restoreZeroCopy()
            return
        }
        runCatching { encoder?.signalEndOfInputStream() }
        val t = thread
        if (t != null) {
            t.join(STOP_JOIN_MS)
            if (t.isAlive) {
                TvDiagnosticsLog.log(app(), "tv", "surface-encode", fields = mapOf("step" to "stop-timeout"))
            }
            t.quitSafely()
        }
        restoreZeroCopy()
    }

    private fun restoreZeroCopy() {
        if (!zeroCopyRestored.compareAndSet(false, true)) return
        if (zeroCopyBefore == false) {
            runCatching { WaylandCompositor.nativeSetZeroCopy(false) }
        }
    }

    /**
     * Starts the encoder on its own thread; null when starting, else the start-failed reason.
     * Setup waits for the compositor (it only appears once the game is up), so a null
     * return means "starting", not "encoding yet"; setup failures are logged.
     */
    fun begin(): String? {
        synchronized(beginLock) {
            if (begun) return startReason
            begun = true
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            startReason = "api-unavailable"
            return startReason
        }
        runCatching { System.loadLibrary("droiddeckwayland") }.onFailure {
            Log.w(TAG, "wayland library unavailable", it)
            startReason = "native-lib-unavailable"
            return startReason
        }
        outFile.parentFile?.mkdirs()
        val t = HandlerThread("tv-surface-encode").apply { start() }
        thread = t
        Handler(t.looper).post {
            val error = setup()
            if (error == null) {
                runLoop()
            } else if (error != "stopped") {
                startReason = error
                TvDiagnosticsLog.log(app(), "tv", "surface-encode", fields = mapOf("step" to "setup-failed", "reason" to error))
                restoreZeroCopy()
                t.quitSafely()
            }
        }
        return null
    }

    /** AVC encoder format; low-latency keys apply only on the API level that defines them (priority 0 = realtime). */
    internal fun buildFormat(width: Int, height: Int, sdkInt: Int = Build.VERSION.SDK_INT): MediaFormat =
        MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
            setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL_S)
            if (sdkInt >= Build.VERSION_CODES.M) setInteger(MediaFormat.KEY_PRIORITY, 0)
            if (sdkInt >= Build.VERSION_CODES.Q) setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
            if (sdkInt >= Build.VERSION_CODES.R) setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
        }

    private fun setup(): String? {
        // The tap only fills when the game presents on the display layer, which needs
        // zero-copy presentation on; a normal SDR session leaves it off, so the encoder
        // turns it on for the run and restores it on stop.
        zeroCopyBefore = runCatching { WaylandCompositor.nativeIsZeroCopy() }.getOrNull()
        if (zeroCopyBefore == false) {
            runCatching { WaylandCompositor.nativeSetZeroCopy(true) }
            TvDiagnosticsLog.log(app(), "tv", "surface-encode", fields = mapOf("step" to "zero-copy-on"))
        }
        // The compositor thread advertises the layer asynchronously, and only once the
        // game is up; poll until it appears (bounded) so the encoder starts mid-session.
        var available = false
        for (i in 0 until COMPOSITOR_WAIT_MS / COMPOSITOR_POLL_MS) {
            if (stopping) return "stopped"
            if (runCatching { WaylandCompositor.nativeEncodeAvailable() }.getOrDefault(false)) { available = true; break }
            Thread.sleep(COMPOSITOR_POLL_MS)
        }
        if (!available) return "zero-copy-unavailable"
        val size = SessionState.outputSize
        val format = buildFormat(size.first, size.second)
        val name = runCatching { MediaCodecList(MediaCodecList.REGULAR_CODECS).findEncoderForFormat(format) }.getOrNull()
            ?: return "no-avc-encoder"
        val out = try {
            BufferedOutputStream(FileOutputStream(outFile))
        } catch (e: Exception) {
            Log.w(TAG, "surface encode output unavailable", e)
            return "output-unavailable"
        }
        var enc: MediaCodec? = null
        try {
            enc = MediaCodec.createByCodecName(name)
            enc.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val surface = enc.createInputSurface()
            inputSurface = surface
            enc.start()
            if (nativeVkEncodeStart(surface, size.first, size.second) != 0) {
                runCatching { enc.stop() }
                runCatching { enc.release() }
                runCatching { surface.release() }
                inputSurface = null
                runCatching { out.close() }
                return "vk-encode-failed"
            }
            encoder = enc
            output = out
        } catch (e: Exception) {
            Log.w(TAG, "surface encode setup failed", e)
            runCatching { nativeVkEncodeStop() }
            runCatching { inputSurface?.release() }
            inputSurface = null
            runCatching { enc?.release() }
            runCatching { out.close() }
            return "vk-encode-failed"
        }
        running.set(true)
        return null
    }

    private fun runLoop() {
        val info = MediaCodec.BufferInfo()
        val out = LongArray(3)
        while (!stopping) {
            val buffer = runCatching { WaylandCompositor.nativeBorrowGameFrame(out) }.getOrNull()
            var presented = false
            if (buffer != null) {
                borrowed.incrementAndGet()
                try {
                    when (vkPresentOutcome(nativeVkEncodePresentFrame(out[2], out[1].toInt()))) {
                        VkPresentOutcome.PRESENTED -> presented = true
                        VkPresentOutcome.FATAL -> {
                            if (fatalReason == null) {
                                fatalReason = "vk-present-failed"
                                TvDiagnosticsLog.log(app(), "tv", "surface-encode", fields = mapOf("step" to "vk-present-failed"))
                                stopping = true
                            }
                        }
                        VkPresentOutcome.SKIPPED -> Unit
                    }
                } finally {
                    runCatching { buffer.close() }
                }
            }
            drain(info, 0)
            if (!presented && buffer == null) runCatching { Thread.sleep(IDLE_SLEEP_MS) }
        }
        finish()
    }

    private fun drain(info: MediaCodec.BufferInfo, timeoutUs: Long) {
        while (true) {
            val index = runCatching { encoder?.dequeueOutputBuffer(info, timeoutUs) }.getOrNull() ?: break
            if (index == MediaCodec.INFO_TRY_AGAIN_LATER) break
            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) continue
            if (index < 0) continue
            if (handleOutput(index, info)) break
        }
    }

    private fun handleOutput(index: Int, info: MediaCodec.BufferInfo): Boolean {
        val enc = encoder
        if (enc != null && info.size > 0) {
            val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
            runCatching {
                val buf = enc.getOutputBuffer(index)
                if (buf != null) {
                    val chunk = ByteArray(info.size)
                    buf.position(info.offset)
                    buf.get(chunk)
                    writeChunk(chunk)
                    chunkSink?.invoke(chunk)
                    if (!isConfig) {
                        frames.incrementAndGet()
                        if (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0) keyframes.incrementAndGet()
                        if (firstFrameMs.get() == -1L) firstFrameMs.set(SystemClock.elapsedRealtime() - startNanos)
                    }
                }
            }
        }
        val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
        runCatching { enc?.releaseOutputBuffer(index, false) }
        return eos
    }

    private fun writeChunk(chunk: ByteArray) {
        val out = output ?: return
        val remaining = MAX_BYTES - written
        if (remaining <= 0) return
        val n = minOf(chunk.size.toLong(), remaining).toInt()
        runCatching {
            out.write(chunk, 0, n)
            written += n
            bytes.addAndGet(n.toLong())
        }
    }

    private fun finish() {
        val info = MediaCodec.BufferInfo()
        var tries = 0
        while (tries++ < FINAL_DRAIN_TRIES) {
            val index = runCatching { encoder?.dequeueOutputBuffer(info, FINAL_DRAIN_TIMEOUT_US) }.getOrNull() ?: break
            if (index == MediaCodec.INFO_TRY_AGAIN_LATER) continue
            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) continue
            if (index < 0) continue
            if (handleOutput(index, info)) break
        }
        runCatching { output?.flush() }
        runCatching { output?.close() }
        runCatching { nativeVkEncodeStop() }
        runCatching { inputSurface?.release() }
        inputSurface = null
        runCatching { encoder?.stop() }
        runCatching { encoder?.release() }
        logStopped()
        restoreZeroCopy()
    }

    private fun logStopped() {
        if (!stoppedLogged.compareAndSet(false, true)) return
        val presented = runCatching { WaylandCompositor.nativeEncodePresented() }.getOrDefault(0L)
        val dropped = (presented - borrowed.get()).coerceAtLeast(0L)
        val stats = longArrayOf(
            frames.get(), bytes.get(), keyframes.get(), dropped, firstFrameMs.get(),
            SystemClock.elapsedRealtime() - startNanos,
        )
        TvDiagnosticsLog.log(app(), "tv", "surface-encode", fields = mapOf("step" to "stopped", "summary" to surfaceSummary(stats)))
    }

    private external fun nativeVkEncodeStart(surface: Surface, width: Int, height: Int): Int
    private external fun nativeVkEncodePresentFrame(ahbPtr: Long, fenceFd: Int): Int
    private external fun nativeVkEncodeStop()

    private val beginLock = Any()

    @Volatile
    private var begun = false

    @Volatile
    private var startReason: String? = null

    @Volatile
    private var stopping = false

    @Volatile
    private var fatalReason: String? = null

    @Volatile
    private var zeroCopyBefore: Boolean? = null

    private val zeroCopyRestored = AtomicBoolean(false)

    private val running = AtomicBoolean(false)
    private val stoppedLogged = AtomicBoolean(false)
    private var thread: HandlerThread? = null

    @Volatile
    private var encoder: MediaCodec? = null
    private var inputSurface: Surface? = null
    private var output: BufferedOutputStream? = null
    private var written = 0L
    private val startNanos = SystemClock.elapsedRealtime()
    private val frames = AtomicLong(0)
    private val bytes = AtomicLong(0)
    private val keyframes = AtomicLong(0)
    private val borrowed = AtomicLong(0)
    private val firstFrameMs = AtomicLong(-1)

    private companion object {
        const val TAG = "TvSurfaceEncode"
        const val MAX_BYTES = 8L shl 20
        const val BIT_RATE = 8_000_000
        const val FRAME_RATE = 30
        const val I_FRAME_INTERVAL_S = 2
        const val IDLE_SLEEP_MS = 8L
        const val COMPOSITOR_WAIT_MS = 60_000L
        const val COMPOSITOR_POLL_MS = 100L
        const val STOP_JOIN_MS = 2_000L
        const val FINAL_DRAIN_TRIES = 20
        const val FINAL_DRAIN_TIMEOUT_US = 100_000L
    }
}

/** [frames, bytes, keyframes, dropped, firstFrameMs, elapsedMs]; short arrays read as zero, firstFrameMs stays -1 when no frame arrived. */
fun surfaceSummary(stats: LongArray): String {
    val frames = stats.getOrElse(0) { 0L }
    val bytes = stats.getOrElse(1) { 0L }
    val keyframes = stats.getOrElse(2) { 0L }
    val dropped = stats.getOrElse(3) { 0L }
    val firstFrameMs = stats.getOrElse(4) { -1L }
    val elapsedMs = stats.getOrElse(5) { 0L }
    return "frames=$frames bytes=$bytes keyframes=$keyframes dropped=$dropped firstFrameMs=$firstFrameMs elapsedMs=$elapsedMs"
}

/** What runLoop does with a borrowed frame, from the native present result: >0 presented, <0 fatal, 0 skipped. */
enum class VkPresentOutcome { PRESENTED, SKIPPED, FATAL }

fun vkPresentOutcome(result: Int): VkPresentOutcome = when {
    result > 0 -> VkPresentOutcome.PRESENTED
    result < 0 -> VkPresentOutcome.FATAL
    else -> VkPresentOutcome.SKIPPED
}
