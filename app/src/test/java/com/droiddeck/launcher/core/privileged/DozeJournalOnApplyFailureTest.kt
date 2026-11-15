package com.droiddeck.launcher.core.privileged

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DozeJournalOnApplyFailureTest {

    @Test
    fun removesKeyOnlyWhenReReadProvesNotForceIdled() {
        assertTrue(
            DozeJournalOnApplyFailure.shouldRemoveDozeKey(
                0, 0, 0,
                "mForceIdle=false mScreenOn=true\n",
            )
        )
        assertTrue(
            DozeJournalOnApplyFailure.shouldRemoveDozeKey(
                0, 0, 0,
                "Deep idle mode enabled: true\n  mForceIdle=false\n  mScreenOn=false\n",
            )
        )
    }

    @Test
    fun keepsKeyWhenReReadShowsStillForceIdled() {
        assertFalse(
            DozeJournalOnApplyFailure.shouldRemoveDozeKey(
                0, 0, 0,
                "mForceIdle=true mScreenOn=false\n",
            )
        )
        assertFalse(
            DozeJournalOnApplyFailure.shouldRemoveDozeKey(
                0, 0, 0,
                "Deep idle mode enabled: true\n  mForceIdle=true\n  mScreenOn=false\n",
            )
        )
    }

    @Test
    fun keepsKeyWhenReReadIsUnreadable() {
        assertFalse(DozeJournalOnApplyFailure.shouldRemoveDozeKey(0, 0, -1, ""))
        assertFalse(DozeJournalOnApplyFailure.shouldRemoveDozeKey(0, 0, -1, null))
        assertFalse(DozeJournalOnApplyFailure.shouldRemoveDozeKey(0, 0, 0, ""))
        assertFalse(DozeJournalOnApplyFailure.shouldRemoveDozeKey(0, 0, 0, null))
        assertFalse(
            DozeJournalOnApplyFailure.shouldRemoveDozeKey(
                0, 0, 0,
                "Deep idle mode enabled: true\n  mScreenOn=false\n",
            )
        )
        assertFalse(
            DozeJournalOnApplyFailure.shouldRemoveDozeKey(
                0, 0, -1,
                "mForceIdle=false\n",
            )
        )
    }

    @Test
    fun keepsKeyWhenAnythingWasAppliedOrSuspended() {
        assertFalse(
            DozeJournalOnApplyFailure.shouldRemoveDozeKey(
                1, 0, 0,
                "mForceIdle=false\n",
            )
        )
        assertFalse(
            DozeJournalOnApplyFailure.shouldRemoveDozeKey(
                0, 3, 0,
                "mForceIdle=false\n",
            )
        )
        assertFalse(
            DozeJournalOnApplyFailure.shouldRemoveDozeKey(
                0, -1, 0,
                "mForceIdle=false\n",
            )
        )
    }
}
