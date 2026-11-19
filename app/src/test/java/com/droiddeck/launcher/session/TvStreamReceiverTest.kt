package com.droiddeck.launcher.session

import android.graphics.SurfaceTexture
import android.media.MediaFormat
import android.os.Build
import android.view.Surface
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

@RunWith(RobolectricTestRunner::class)
class TvStreamReceiverTest {
    @Test
    fun `read units delivers every framed unit in order`() {
        val units = listOf(
            byteArrayOf(0, 0, 0, 1, 0x65, 0x11, 0x22),
            byteArrayOf(0, 0, 1, 0x41, 0x33),
            byteArrayOf(0, 0, 0, 1, 0x06, 0x44, 0x55, 0x66),
        )
        val input = DataInputStream(ByteArrayInputStream(framed(units)))
        val got = ArrayList<ByteArray>()
        assertEquals(3, readUnits(input, { false }) { got.add(it) })
        assertEquals(3, got.size)
        for (i in units.indices) assertArrayEquals(units[i], got[i])
    }

    @Test
    fun `read units stops on a bad length and keeps what arrived`() {
        val out = ByteArrayOutputStream().also { DataOutputStream(it).writeInt(4); it.write(byteArrayOf(1, 2, 3, 4)) }
        DataOutputStream(out).writeInt(MAX_UNIT_BYTES + 1)
        val input = DataInputStream(ByteArrayInputStream(out.toByteArray()))
        val got = ArrayList<ByteArray>()
        assertEquals(1, readUnits(input, { false }) { got.add(it) })
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), got[0])
    }

    @Test
    fun `read units stops on a truncated unit`() {
        val out = ByteArrayOutputStream().also {
            DataOutputStream(it).writeInt(8)
            it.write(byteArrayOf(1, 2, 3))
        }
        val input = DataInputStream(ByteArrayInputStream(out.toByteArray()))
        val got = ArrayList<ByteArray>()
        assertEquals(0, readUnits(input, { false }) { got.add(it) })
    }

    @Test
    fun `read units delivers nothing once stopped`() {
        val input = DataInputStream(ByteArrayInputStream(framed(listOf(byteArrayOf(1, 2, 3)))))
        var calls = 0
        assertEquals(0, readUnits(input, { true }) { calls++ })
        assertEquals(0, calls)
    }

    @Test
    fun `nsd endpoint takes host and port from resolution`() {
        assertEquals("192.168.49.1" to 28752, nsdEndpoint("192.168.49.1", 28752))
    }

    @Test
    fun `nsd endpoint rejects the port 0 registration quirk`() {
        assertNull(nsdEndpoint("192.168.49.1", 0))
    }

    @Test
    fun `nsd endpoint rejects a missing host or an unusable port`() {
        assertNull(nsdEndpoint(null, 28752))
        assertNull(nsdEndpoint("", 28752))
        assertNull(nsdEndpoint("  ", 28752))
        assertNull(nsdEndpoint("192.168.49.1", -1))
        assertNull(nsdEndpoint("192.168.49.1", 65536))
    }

    @Test
    fun `decoder format sets low-latency keys only on API 30+ when supported`() {
        val flagged = buildDecoderFormat(1920, 1080, Build.VERSION_CODES.R, true)
        assertEquals(1, flagged.getInteger(MediaFormat.KEY_LOW_LATENCY))
        assertEquals(0, flagged.getInteger(MediaFormat.KEY_PRIORITY))
        val plain = buildDecoderFormat(1920, 1080, Build.VERSION_CODES.R, false)
        assertFalse(plain.containsKey(MediaFormat.KEY_LOW_LATENCY))
        assertEquals(0, plain.getInteger(MediaFormat.KEY_PRIORITY))
    }

    @Test
    fun `vendor low-latency key maps qualcomm exynos hisilicon and amlogic`() {
        assertEquals("vendor.qti-ext-dec-low-latency.enable", decoderVendorLowLatencyKey("c2.qti.avc.decoder"))
        assertEquals("vendor.qti-ext-dec-low-latency.enable", decoderVendorLowLatencyKey("OMX.qcom.video.decoder.avc"))
        assertEquals("vendor.rtc-ext-dec-low-latency.enable", decoderVendorLowLatencyKey("c2.exynos.avc.decoder"))
        assertEquals("vendor.hisi-ext-dec-low-latency.enable", decoderVendorLowLatencyKey("OMX.hisi.video.decoder.avc"))
        assertEquals("vendor.low-latency.enable", decoderVendorLowLatencyKey("c2.amlogic.avc.decoder"))
        assertNull(decoderVendorLowLatencyKey("c2.android.avc.decoder"))
        assertNull(decoderVendorLowLatencyKey(null))
    }

    @Test
    fun `decoder format sets the vendor key only with low latency on API 30+`() {
        val key = "vendor.qti-ext-dec-low-latency.enable"
        val flagged = buildDecoderFormat(1920, 1080, Build.VERSION_CODES.R, true, key)
        assertEquals(1, flagged.getInteger(key))
        assertEquals(1, flagged.getInteger(MediaFormat.KEY_LOW_LATENCY))
        val noLowLatency = buildDecoderFormat(1920, 1080, Build.VERSION_CODES.R, false, key)
        assertFalse(noLowLatency.containsKey(key))
        val oldApi = buildDecoderFormat(1920, 1080, Build.VERSION_CODES.Q, true, key)
        assertFalse(oldApi.containsKey(key))
    }

    @Test
    fun `keyframe gate drops p-frames until the first keyframe and keeps its headers`() {
        val gate = KeyframeGate()
        val fed = ArrayList<ByteArray>()
        val sps = byteArrayOf(0, 0, 0, 1, 0x67, 0x42, 0x01)
        val pps = byteArrayOf(0, 0, 1, 0x68, 0x01)
        val pFrame = byteArrayOf(0, 0, 0, 1, 0x41, 0x33)
        val idr = byteArrayOf(0, 0, 0, 1, 0x65, 0x11, 0x22)
        gate.offer(pFrame) { fed.add(it) }
        gate.offer(sps) { fed.add(it) }
        gate.offer(pps) { fed.add(it) }
        assertTrue(fed.isEmpty())
        gate.offer(idr) { fed.add(it) }
        assertEquals(3, fed.size)
        assertArrayEquals(sps, fed[0])
        assertArrayEquals(pps, fed[1])
        assertArrayEquals(idr, fed[2])
        gate.offer(pFrame) { fed.add(it) }
        assertEquals(4, fed.size)
        gate.reset()
        fed.clear()
        gate.offer(pFrame) { fed.add(it) }
        assertTrue(fed.isEmpty())
    }

    @Test
    fun `decoder format omits low-latency below API 30 even when supported`() {
        val fmt = buildDecoderFormat(1920, 1080, Build.VERSION_CODES.Q, true)
        assertFalse(fmt.containsKey(MediaFormat.KEY_LOW_LATENCY))
        assertEquals(0, fmt.getInteger(MediaFormat.KEY_PRIORITY))
    }

    @Test
    fun `decoder format omits priority below API 23`() {
        val fmt = buildDecoderFormat(1920, 1080, Build.VERSION_CODES.LOLLIPOP_MR1, true)
        assertFalse(fmt.containsKey(MediaFormat.KEY_PRIORITY))
        assertFalse(fmt.containsKey(MediaFormat.KEY_LOW_LATENCY))
    }

    @Test
    fun `low-latency probe is false below API 30 and safe with no codecs`() {
        assertFalse(decoderSupportsLowLatency(Build.VERSION_CODES.Q))
        assertFalse(decoderSupportsLowLatency())
    }

    @Test
    fun `decoder still starts with the plain format when low latency is unsupported`() {
        val surface = Surface(SurfaceTexture(0))
        try {
            val decoder = VideoDecoder(surface, 1920, 1080) {}
            assertTrue(decoder.start())
            decoder.release()
        } finally {
            surface.release()
        }
    }

    @Test
    fun `connect failure maps refused, no route, timeout, and other`() {
        assertEquals(ConnectFailure.REFUSED, classifyConnectFailure(ConnectException()))
        assertEquals(ConnectFailure.NO_ROUTE, classifyConnectFailure(NoRouteToHostException()))
        assertEquals(ConnectFailure.TIMEOUT, classifyConnectFailure(SocketTimeoutException()))
        assertEquals(ConnectFailure.OTHER, classifyConnectFailure(null))
        assertEquals(ConnectFailure.OTHER, classifyConnectFailure(RuntimeException("boom")))
    }

    @Test
    fun `connect failure strings name the host and port`() {
        val app = RuntimeEnvironment.getApplication()
        val refused = app.getString(connectFailureStringId(ConnectException()), "192.168.42.129", 28752)
        assertTrue(refused.contains("192.168.42.129:28752"))
        assertTrue(refused.contains("Prepare"))
        val noRoute = app.getString(connectFailureStringId(NoRouteToHostException()), "192.168.42.129", 28752)
        assertTrue(noRoute.contains("192.168.42.129:28752"))
        assertTrue(noRoute.contains("USB tethering"))
        val timeout = app.getString(connectFailureStringId(SocketTimeoutException()), "192.168.42.129", 28752)
        assertTrue(timeout.contains("192.168.42.129:28752"))
        val other = app.getString(connectFailureStringId(null), "192.168.42.129", 28752)
        assertTrue(other.contains("192.168.42.129:28752"))
    }

    private fun framed(units: List<ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        val data = DataOutputStream(out)
        for (unit in units) {
            data.writeInt(unit.size)
            data.write(unit)
        }
        data.flush()
        return out.toByteArray()
    }
}
