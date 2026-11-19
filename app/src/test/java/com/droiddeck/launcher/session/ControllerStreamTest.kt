package com.droiddeck.launcher.session

import android.content.Context
import android.net.wifi.p2p.WifiP2pManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import java.net.InetSocketAddress
import java.net.Socket

@RunWith(RobolectricTestRunner::class)
class ControllerStreamTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    @Test
    fun `unprepared session with no second player adds nothing`() {
        assertEquals(StreamPlan.NONE, sessionStreamPlan(false, false))
    }

    @Test
    fun `unprepared session with a second player adds the pad server and group`() {
        assertEquals(StreamPlan.PAD_AND_GROUP, sessionStreamPlan(false, true))
    }

    @Test
    fun `prepared session adds only the encoder`() {
        assertEquals(StreamPlan.ENCODER_ONLY, sessionStreamPlan(true, false))
    }

    @Test
    fun `prepared session with a second player still adds only the encoder`() {
        assertEquals(StreamPlan.ENCODER_ONLY, sessionStreamPlan(true, true))
    }

    @Test
    fun `prepared state starts idle`() {
        ControllerStream.stop()
        assertEquals(ControllerStream.Status.IDLE, ControllerStream.status)
        assertFalse(ControllerStream.isPrepared)
    }

    @Test
    fun `group failure keeps the servers up and the preparation usable`() {
        ControllerStream.stop()
        try {
            failNextGroup()
            assertNull(ControllerStream.prepare(context))
            assertTrue(waitForStatus(ControllerStream.Status.READY_NO_GROUP))
            assertEquals(ControllerStream.Status.READY_NO_GROUP, ControllerStream.status)
            assertTrue(ControllerStream.isPrepared)
            assertNotNull(ControllerStream.streamSink)
            assertNotNull(ControllerStream.failureReason)
            // The servers are actually listening: a USB receiver could connect.
            assertTrue(canConnect(UsbTether.USB_STREAM_PORT))
            assertTrue(canConnect(UsbTether.USB_PAD_PORT))
            assertTrue(canConnect(UsbTether.USB_CONTROL_PORT))
            assertNotNull(ControllerStream.udpVideoServer)
            assertNotNull(ControllerStream.controlServer)
            assertNotNull(ControllerStream.framePump)
            // A UDP subscribe reaches the video server.
            assertTrue(subscribeUdp())
        } finally {
            ControllerStream.stop()
        }
        assertEquals(ControllerStream.Status.IDLE, ControllerStream.status)
        assertFalse(ControllerStream.isPrepared)
        // Nothing left listening after stop.
        assertFalse(canConnect(UsbTether.USB_STREAM_PORT))
        assertFalse(canConnect(UsbTether.USB_PAD_PORT))
        assertFalse(canConnect(UsbTether.USB_CONTROL_PORT))
        assertNull(ControllerStream.udpVideoServer)
        assertNull(ControllerStream.controlServer)
        assertNull(ControllerStream.framePump)
    }

    @Test
    fun `stop and re-prepare after a group failure is sane`() {
        ControllerStream.stop()
        try {
            failNextGroup()
            assertNull(ControllerStream.prepare(context))
            assertTrue(waitForStatus(ControllerStream.Status.READY_NO_GROUP))
            assertEquals(ControllerStream.Status.READY_NO_GROUP, ControllerStream.status)
            // A second prepare while prepared is a no-op, not duplicate servers.
            assertNull(ControllerStream.prepare(context))
            assertEquals(ControllerStream.Status.READY_NO_GROUP, ControllerStream.status)
            assertTrue(canConnect(UsbTether.USB_STREAM_PORT))
            ControllerStream.stop()
            assertEquals(ControllerStream.Status.IDLE, ControllerStream.status)
            assertFalse(canConnect(UsbTether.USB_STREAM_PORT))
            // A fresh prepare works again.
            failNextGroup()
            assertNull(ControllerStream.prepare(context))
            assertTrue(waitForStatus(ControllerStream.Status.READY_NO_GROUP))
            assertEquals(ControllerStream.Status.READY_NO_GROUP, ControllerStream.status)
            assertTrue(ControllerStream.isPrepared)
            assertTrue(canConnect(UsbTether.USB_STREAM_PORT))
            assertTrue(canConnect(UsbTether.USB_PAD_PORT))
        } finally {
            ControllerStream.stop()
        }
    }

    /**
     * Makes the next P2P group creation fail (Robolectric's shadow posts
     * onFailure to the next ActionListener); the group then fails async,
     * exactly like the real create-group failure on the phone.
     */
    private fun failNextGroup() {
        val mgr = context.getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager
        Shadows.shadowOf(mgr).setNextActionFailure(0)
    }

    /** Polls for a status the async group callback sets; true when it arrives. */
    private fun waitForStatus(status: ControllerStream.Status, tries: Int = 200): Boolean {
        repeat(tries) {
            if (ControllerStream.status == status) return true
            Thread.sleep(50)
        }
        return ControllerStream.status == status
    }

    private fun canConnect(port: Int): Boolean {
        return runCatching {
            Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 1_000); it.isConnected }
        }.getOrDefault(false)
    }

    /** Records a subscribe for the video server (the VM sandbox blocks real UDP sends). */
    private fun subscribeUdp(): Boolean {
        val server = ControllerStream.udpVideoServer ?: return false
        server.onSubscribeDatagram(InetSocketAddress("127.0.0.1", 40000))
        return server.hasClient()
    }
}
