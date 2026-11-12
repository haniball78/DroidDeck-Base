package com.droiddeck.tv

import android.app.Activity
import android.media.MediaCodec
import android.media.MediaFormat
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import java.io.DataInputStream
import java.io.EOFException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * P3 slice A3 receiver: discovers the phone's DroidDeck TV stream over NSD,
 * reads length-prefixed Annex B NAL units over raw TCP and decodes them to the
 * SurfaceView with a hardware H.264 decoder. Proof slice only: no PIN, no
 * input return channel, no audio.
 */
class MainActivity : Activity() {
    private lateinit var surfaceView: SurfaceView
    private lateinit var statusText: TextView
    private var surface: Surface? = null
    private val surfaceReady = CountDownLatch(1)
    private val destroyed = AtomicBoolean(false)
    private val framesDecoded = AtomicLong(0)
    private val firstFrameMs = AtomicLong(-1)
    private var worker: Thread? = null
    private var nsdManager: NsdManager? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var currentSocket: Socket? = null
    private var decoder: MediaCodec? = null
    private val connectStartNanos = AtomicLong(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val root = FrameLayout(this)
        surfaceView = SurfaceView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }
        statusText = TextView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.START,
            ).apply { setMargins(24, 24, 24, 24) }
            setBackgroundColor(0x88000000.toInt())
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 18f
            text = "DroidDeck TV — starting…"
        }
        root.addView(surfaceView)
        root.addView(statusText)
        setContentView(root)
        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                surface = holder.surface
                surfaceReady.countDown()
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                surface = null
                runCatching { currentSocket?.close() }
            }
        })
        worker = Thread({ runLoop() }, "droiddeck-tv").apply { start() }
    }

    override fun onDestroy() {
        destroyed.set(true)
        runCatching { currentSocket?.close() }
        stopDiscovery()
        runCatching { decoder?.stop() }
        runCatching { decoder?.release() }
        worker?.interrupt()
        super.onDestroy()
    }

    private fun runLoop() {
        if (!surfaceReady.await(SURFACE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            setStatus("DroidDeck TV — no video surface, cannot decode")
            return
        }
        while (!destroyed.get()) {
            val endpoint = discover()
            if (endpoint == null) {
                sleepQuietly(RETRY_MS)
                continue
            }
            connectStartNanos.set(System.nanoTime())
            val socket = runCatching {
                Socket().apply {
                    tcpNoDelay = true
                    connect(InetSocketAddress(endpoint.first, endpoint.second), CONNECT_TIMEOUT_MS)
                }
            }.getOrNull()
            if (socket == null) {
                setStatus("DroidDeck TV — connect to ${endpoint.first}:${endpoint.second} failed, searching again…")
                sleepQuietly(RETRY_MS)
                continue
            }
            currentSocket = socket
            setStatus("DroidDeck TV — connected to ${endpoint.first}:${endpoint.second}")
            Log.i(TAG, "connected to ${endpoint.first}:${endpoint.second}")
            serve(socket)
            runCatching { socket.close() }
            currentSocket = null
            Log.i(TAG, "disconnected, frames decoded: ${framesDecoded.get()}")
            if (!destroyed.get()) setStatus("DroidDeck TV — disconnected, searching again…")
        }
    }

    private fun serve(socket: Socket) {
        val dec = runCatching {
            MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
                configure(
                    MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 1920, 1080),
                    surface, null, 0,
                )
                start()
            }
        }.getOrNull()
        if (dec == null) {
            setStatus("DroidDeck TV — no H.264 decoder on this TV")
            return
        }
        decoder = dec
        val input = DataInputStream(socket.getInputStream())
        val info = MediaCodec.BufferInfo()
        try {
            while (!destroyed.get() && !socket.isClosed) {
                val length = runCatching { input.readInt() }.getOrNull() ?: break
                if (length <= 0 || length > MAX_UNIT_BYTES) {
                    Log.w(TAG, "bad unit length: $length")
                    break
                }
                val unit = ByteArray(length)
                try {
                    input.readFully(unit)
                } catch (e: EOFException) {
                    break
                }
                val ptsUs = System.nanoTime() / 1000
                val index = runCatching { dec.dequeueInputBuffer(INPUT_TIMEOUT_US) }.getOrDefault(-1)
                if (index >= 0) {
                    val buf = dec.getInputBuffer(index)
                    if (buf != null) {
                        buf.clear()
                        buf.put(unit)
                        dec.queueInputBuffer(index, 0, length, ptsUs, 0)
                    } else {
                        runCatching { dec.queueInputBuffer(index, 0, 0, ptsUs, 0) }
                    }
                }
                drain(dec, info)
            }
        } catch (e: EOFException) {
            // The phone closed the stream; the loop below reconnects.
        } catch (e: Exception) {
            Log.w(TAG, "tv stream read failed", e)
        } finally {
            runCatching { dec.stop() }
            runCatching { dec.release() }
            decoder = null
        }
    }

    private fun drain(dec: MediaCodec, info: MediaCodec.BufferInfo) {
        while (true) {
            val index = runCatching { dec.dequeueOutputBuffer(info, 0) }.getOrDefault(MediaCodec.INFO_TRY_AGAIN_LATER)
            if (index == MediaCodec.INFO_TRY_AGAIN_LATER) return
            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                Log.i(TAG, "decoder output format: ${dec.outputFormat}")
                continue
            }
            if (index < 0) return
            runCatching { dec.releaseOutputBuffer(index, true) }
            val n = framesDecoded.incrementAndGet()
            if (firstFrameMs.get() == -1L) {
                firstFrameMs.set((System.nanoTime() - connectStartNanos.get()) / 1_000_000)
                Log.i(TAG, "first frame decoded after ${firstFrameMs.get()} ms")
                setStatus("DroidDeck TV — playing, ${n} frames decoded")
            } else if (n % 60L == 0L) {
                setStatus("DroidDeck TV — playing, $n frames decoded")
            }
        }
    }

    /** Discovers one _droiddeck-tv._tcp service; null on timeout. */
    private fun discover(): Pair<String, Int>? {
        val manager = getSystemService(NSD_SERVICE) as? NsdManager ?: return null
        nsdManager = manager
        var endpoint: Pair<String, Int>? = null
        val found = CountDownLatch(1)
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (serviceInfo.serviceType?.contains(SERVICE_TYPE) != true) return
                runCatching { manager.resolveService(serviceInfo, resolveListener(found) { endpoint = it }) }
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "discovery failed: $errorCode")
                found.countDown()
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
        }
        discoveryListener = listener
        runCatching { manager.discoverServices("$SERVICE_TYPE.", NsdManager.PROTOCOL_DNS_SD, listener) }
            .onFailure {
                Log.w(TAG, "discoverServices failed", it)
                return null
            }
        found.await(DISCOVERY_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        stopDiscovery()
        return endpoint
    }

    private fun resolveListener(found: CountDownLatch, onResolved: (Pair<String, Int>) -> Unit) =
        object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                val host = serviceInfo.host?.hostAddress ?: return
                onResolved(host to serviceInfo.port)
                found.countDown()
            }
        }

    private fun stopDiscovery() {
        val manager = nsdManager
        val listener = discoveryListener
        nsdManager = null
        discoveryListener = null
        if (manager != null && listener != null) {
            runCatching { manager.stopServiceDiscovery(listener) }
        }
    }

    private fun setStatus(text: String) {
        runOnUiThread { statusText.text = text }
    }

    private fun sleepQuietly(ms: Long) {
        runCatching { Thread.sleep(ms) }
    }

    private companion object {
        const val TAG = "DroidDeckTv"
        const val SERVICE_TYPE = "_droiddeck-tv._tcp"
        const val SURFACE_TIMEOUT_MS = 5_000L
        const val DISCOVERY_TIMEOUT_MS = 10_000L
        const val CONNECT_TIMEOUT_MS = 5_000
        const val RETRY_MS = 2_000L
        const val INPUT_TIMEOUT_US = 10_000L
        const val MAX_UNIT_BYTES = 16 * 1024 * 1024
    }
}
