package com.droiddeck.launcher.session

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SessionStreamPlanTest {
    @Test
    fun `session with no second player adds nothing`() {
        assertEquals(StreamPlan.NONE, sessionStreamPlan(false))
    }

    @Test
    fun `session with a second player adds the pad server and group`() {
        assertEquals(StreamPlan.PAD_AND_GROUP, sessionStreamPlan(true))
    }
}
