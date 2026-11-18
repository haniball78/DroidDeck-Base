package com.droiddeck.launcher.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ControllerStreamTest {
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
}
