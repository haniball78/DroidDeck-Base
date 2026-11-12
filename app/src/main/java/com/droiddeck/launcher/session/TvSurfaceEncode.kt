package com.droiddeck.launcher.session

import android.hardware.HardwareBuffer
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.opengl.EGL14
import android.opengl.EGL15
import android.opengl.EGLExt
import android.opengl.EGLSync
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import com.droiddeck.launcher.core.SessionPart
import com.droiddeck.launcher.core.privileged.TvDiagnosticsLog
import com.droiddeck.launcher.wayland.WaylandCompositor
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class TvSurfaceEncode(private val outFile: File) : SessionPart() {
    override fun start() {
        begin()
    }

    override fun stop() {
        if (!running.get()) {
            thread?.quitSafely()
            return
        }
        stopping = true
        runCatching { encoder?.signalEndOfInputStream() }
        val t = thread
        if (t != null) {
            t.join(STOP_JOIN_MS)
            if (t.isAlive) {
                TvDiagnosticsLog.log(app(), "tv", "surface-encode", fields = mapOf("step" to "stop-timeout"))
            }
            t.quitSafely()
        }
    }

    /** Starts the encoder on its own thread; null when running, else the start-failed reason. */
    fun begin(): String? {
        synchronized(beginLock) {
            if (begun) return startReason
            begun = true
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            startReason = "egl15-unavailable"
            return startReason
        }
        runCatching { System.loadLibrary("droiddeckwayland") }.onFailure {
            Log.w(TAG, "wayland library unavailable", it)
            startReason = "native-lib-unavailable"
            return startReason
        }
        outFile.parentFile?.mkdirs()
        val ready = CountDownLatch(1)
        var setupError: String? = null
        val t = HandlerThread("tv-surface-encode").apply { start() }
        thread = t
        Handler(t.looper).post {
            setupError = setup()
            ready.countDown()
            if (setupError == null) runLoop()
        }
        if (!ready.await(SETUP_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            stopping = true
            t.quitSafely()
            startReason = "start-timeout"
            return startReason
        }
        startReason = setupError
        if (setupError != null) t.quitSafely()
        return startReason
    }

    private fun setup(): String? {
        // The compositor thread advertises the layer asynchronously; poll briefly so a
        // not-yet-initialized compositor is not misread as zero-copy being unavailable.
        var available = false
        for (i in 0 until 20) {
            if (runCatching { WaylandCompositor.nativeEncodeAvailable() }.getOrDefault(false)) { available = true; break }
            Thread.sleep(100)
        }
        if (!available) return "zero-copy-unavailable"
        val size = SessionState.outputSize
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, size.first, size.second).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
            setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL_S)
        }
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
            egl = BootMovieTranscoder.EglWindow(enc.createInputSurface())
            renderer = ImageRenderer(size.first, size.second)
            encoder = enc
            output = out
            enc.start()
        } catch (e: Exception) {
            Log.w(TAG, "surface encode setup failed", e)
            runCatching { renderer?.release() }
            runCatching { egl?.release() }
            runCatching { enc?.release() }
            runCatching { out.close() }
            return "egl-image-failed"
        }
        running.set(true)
        return null
    }

    private fun runLoop() {
        val info = MediaCodec.BufferInfo()
        val out = LongArray(3)
        while (!stopping) {
            val buffer = runCatching { WaylandCompositor.nativeBorrowGameFrame(out) }.getOrNull()
            var drew = false
            if (buffer != null) {
                borrowed.incrementAndGet()
                try {
                    drew = drawFrame(buffer, out[2], out[1].toInt())
                } finally {
                    runCatching { buffer.close() }
                }
            }
            drain(info, 0)
            if (!drew && buffer == null) runCatching { Thread.sleep(IDLE_SLEEP_MS) }
        }
        finish()
    }

    private fun drawFrame(buffer: HardwareBuffer, ahbPtr: Long, fenceFd: Int): Boolean {
        val window = egl ?: return false
        val draw = renderer ?: return false
        var sync: EGLSync? = null
        try {
            if (fenceFd >= 0) {
                sync = runCatching {
                    EGL15.eglCreateSync(
                        window.display, EGLExt.EGL_SYNC_NATIVE_FENCE_ANDROID,
                        longArrayOf(EGLExt.EGL_SYNC_NATIVE_FENCE_FD_ANDROID.toLong(), fenceFd.toLong(), EGL14.EGL_NONE.toLong()), 0,
                    )
                }.getOrNull()
                if (sync == null || sync === EGL15.EGL_NO_SYNC) {
                    // eglCreateSync failed, so EGL never took the fd; adopt and close it.
                    runCatching { ParcelFileDescriptor.adoptFd(fenceFd).close() }
                    return false
                }
                val waited = runCatching {
                    EGL15.eglClientWaitSync(window.display, sync, EGL15.EGL_SYNC_FLUSH_COMMANDS_BIT, FENCE_WAIT_NS)
                }.getOrDefault(EGL15.EGL_TIMEOUT_EXPIRED)
                if (waited != EGL15.EGL_CONDITION_SATISFIED) return false
            }
            val image = runCatching { nativeCreateEglImage(window.displayHandle, ahbPtr) }.getOrDefault(0L)
            if (image == 0L) {
                if (frames.get() == 0L && fatalReason == null) {
                    fatalReason = "egl-image-failed"
                    TvDiagnosticsLog.log(app(), "tv", "surface-encode", fields = mapOf("step" to "egl-image-failed"))
                    stopping = true
                }
                return false
            }
            try {
                val bound = runCatching { nativeBindEglImageToTexture(draw.texture, image) }.isSuccess
                if (!bound) return false
                draw.draw()
                window.swap(System.nanoTime())
                return true
            } finally {
                runCatching { nativeDestroyEglImage(window.displayHandle, image) }
            }
        } finally {
            if (sync != null) runCatching { EGL15.eglDestroySync(window.display, sync) }
        }
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
        runCatching { renderer?.release() }
        runCatching { egl?.release() }
        runCatching { encoder?.stop() }
        runCatching { encoder?.release() }
        logStopped()
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

    private external fun nativeCreateEglImage(displayHandle: Long, ahbPtr: Long): Long
    private external fun nativeBindEglImageToTexture(texture: Int, imageHandle: Long)
    private external fun nativeDestroyEglImage(displayHandle: Long, imageHandle: Long)

    private val beginLock = Any()

    @Volatile
    private var begun = false

    @Volatile
    private var startReason: String? = null

    @Volatile
    private var stopping = false

    @Volatile
    private var fatalReason: String? = null

    private val running = AtomicBoolean(false)
    private val stoppedLogged = AtomicBoolean(false)
    private var thread: HandlerThread? = null

    @Volatile
    private var encoder: MediaCodec? = null
    private var egl: BootMovieTranscoder.EglWindow? = null
    private var renderer: ImageRenderer? = null
    private var output: BufferedOutputStream? = null
    private var written = 0L
    private val startNanos = SystemClock.elapsedRealtime()
    private val frames = AtomicLong(0)
    private val bytes = AtomicLong(0)
    private val keyframes = AtomicLong(0)
    private val borrowed = AtomicLong(0)
    private val firstFrameMs = AtomicLong(-1)

    private class ImageRenderer(private val width: Int, private val height: Int) {
        val texture: Int
        private val program: Int
        private val positions: FloatBuffer = floats(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
        private val coords: FloatBuffer = floats(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)

        init {
            texture = IntArray(1).also { GLES20.glGenTextures(1, it, 0) }[0]
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            program = link(VERTEX, FRAGMENT)
        }

        fun draw() {
            GLES20.glViewport(0, 0, width, height)
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            GLES20.glUseProgram(program)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture)
            val position = GLES20.glGetAttribLocation(program, "aPosition")
            val coord = GLES20.glGetAttribLocation(program, "aCoord")
            GLES20.glEnableVertexAttribArray(position)
            GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 0, positions)
            GLES20.glEnableVertexAttribArray(coord)
            GLES20.glVertexAttribPointer(coord, 2, GLES20.GL_FLOAT, false, 0, coords)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        }

        fun release() {
            GLES20.glDeleteProgram(program)
            GLES20.glDeleteTextures(1, intArrayOf(texture), 0)
        }

        private fun floats(vararg values: Float): FloatBuffer =
            ByteBuffer.allocateDirect(values.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(values); position(0) }

        private fun link(vertex: String, fragment: String): Int {
            fun compile(type: Int, code: String): Int = GLES20.glCreateShader(type).also {
                GLES20.glShaderSource(it, code)
                GLES20.glCompileShader(it)
                val ok = IntArray(1)
                GLES20.glGetShaderiv(it, GLES20.GL_COMPILE_STATUS, ok, 0)
                check(ok[0] != 0) { GLES20.glGetShaderInfoLog(it) }
            }
            return GLES20.glCreateProgram().also {
                GLES20.glAttachShader(it, compile(GLES20.GL_VERTEX_SHADER, vertex))
                GLES20.glAttachShader(it, compile(GLES20.GL_FRAGMENT_SHADER, fragment))
                GLES20.glLinkProgram(it)
                val ok = IntArray(1)
                GLES20.glGetProgramiv(it, GLES20.GL_LINK_STATUS, ok, 0)
                check(ok[0] != 0) { GLES20.glGetProgramInfoLog(it) }
            }
        }

        companion object {
            private val VERTEX = """
                attribute vec4 aPosition;
                attribute vec4 aCoord;
                varying vec2 vCoord;
                void main() {
                    gl_Position = aPosition;
                    vCoord = aCoord.xy;
                }
            """.trimIndent()
            private val FRAGMENT = """
                #extension GL_OES_EGL_image_external : require
                precision mediump float;
                varying vec2 vCoord;
                uniform samplerExternalOES sTexture;
                void main() {
                    gl_FragColor = texture2D(sTexture, vCoord);
                }
            """.trimIndent()
        }
    }

    private companion object {
        const val TAG = "TvSurfaceEncode"
        const val MAX_BYTES = 8L shl 20
        const val BIT_RATE = 8_000_000
        const val FRAME_RATE = 30
        const val I_FRAME_INTERVAL_S = 2
        const val IDLE_SLEEP_MS = 8L
        const val SETUP_TIMEOUT_MS = 10_000L
        const val STOP_JOIN_MS = 2_000L
        const val FENCE_WAIT_NS = 100_000_000L
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
