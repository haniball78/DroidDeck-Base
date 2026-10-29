package com.droiddeck.launcher.session

import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Test

class SafetyMonitorPlainModeTest {
    private class RealClock : Clock {
        private val startNs = System.nanoTime()
        override fun nowMs(): Long = (System.nanoTime() - startNs) / 1_000_000L
    }

    private class CountingListener : SafetyListener {
        val bridgeLostCount = AtomicInteger(0)
        override fun onWarnBattery(levelPercent: Int) {}
        override fun onEndTweaks(reason: String) {}
        override fun onStopCountdownTick(secondsLeft: Int) {}
        override fun onStopCountdownFinished() {}
        override fun onBridgeLost() {
            bridgeLostCount.incrementAndGet()
        }
        override fun onAlert(message: String) {}
    }

    private fun monitorWithProbe(probe: BridgeProbe, listener: CountingListener): SafetyMonitor {
        return SafetyMonitor(
            batterySource = object : BatterySource {
                override fun read(): BatteryReading? = null
            },
            thermalSource = object : ThermalSource {
                override fun thermalStatus(): Int = 0
            },
            bridgeProbe = probe,
            clock = RealClock(),
            listener = listener,
            pollMs = 10L,
            heartbeatMs = 50L,
        )
    }

    @Test
    fun cachedTicksDoNotReReportALostBridge() {
        val probes = AtomicInteger(0)
        val listener = CountingListener()
        val monitor = monitorWithProbe(
            probe = object : BridgeProbe {
                override fun status(): BridgeStatus {
                    probes.incrementAndGet()
                    return BridgeStatus.LOST
                }
            },
            listener = listener,
        )
        try {
            monitor.start()
            Thread.sleep(500L)
        } finally {
            monitor.stop()
        }
        assertEquals(1, listener.bridgeLostCount.get())
    }

    @Test
    fun bridgeLostIsReportedAgainAfterARealRecovery() {
        val probes = AtomicInteger(0)
        val listener = CountingListener()
        val monitor = monitorWithProbe(
            probe = object : BridgeProbe {
                override fun status(): BridgeStatus {
                    return when (probes.incrementAndGet()) {
                        1 -> BridgeStatus.LOST
                        2 -> BridgeStatus.OK
                        else -> BridgeStatus.LOST
                    }
                }
            },
            listener = listener,
        )
        try {
            monitor.start()
            Thread.sleep(500L)
        } finally {
            monitor.stop()
        }
        assertEquals(2, listener.bridgeLostCount.get())
    }
}
