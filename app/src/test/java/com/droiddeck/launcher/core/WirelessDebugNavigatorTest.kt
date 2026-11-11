package com.droiddeck.launcher.core

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class WirelessDebugNavigatorTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test fun splitDeadlineIsFourSeconds() {
        assertEquals(4_000L, WirelessDebugNavigator.SPLIT_DEADLINE_MS)
    }

    @Test fun splitFlagsArePublicOnly() {
        assertEquals(
            Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT or Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK,
            WirelessDebugNavigator.splitFlags,
        )
        assertEquals(0, WirelessDebugNavigator.splitFlags and Intent.FLAG_ACTIVITY_CLEAR_TASK)
    }

    @Test fun plainFlagsArePublicOnly() {
        assertEquals(
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK,
            WirelessDebugNavigator.plainFlags,
        )
        assertEquals(0, WirelessDebugNavigator.plainFlags and Intent.FLAG_ACTIVITY_CLEAR_TASK)
    }

    @Test fun targetFallsBackToHighlightedDevOptionsWithoutARuntime() {
        // On the JVM runtime nothing resolves, so the cascade falls through
        // to the safe fallback: the Developer-options root, highlighted.
        val intent = WirelessDebugNavigator.targetIntent(context)
        assertEquals(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS, intent.action)
        assertNull(intent.component)
        assertEquals(
            WirelessDebugNavigator.WIRELESS_ROW_KEY,
            intent.getStringExtra(WirelessDebugNavigator.FRAGMENT_ARG_KEY),
        )
    }

    @Test fun targetWithNullHighlightHasNoExtra() {
        val intent = WirelessDebugNavigator.targetIntent(context, highlightKey = null)
        assertEquals(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS, intent.action)
        assertFalse(intent.hasExtra(WirelessDebugNavigator.FRAGMENT_ARG_KEY))
    }

    @Test fun plainIntentCarriesPlainFlags() {
        val intent = WirelessDebugNavigator.plainIntent(context)
        assertEquals(WirelessDebugNavigator.plainFlags, intent.flags)
    }

    @Test fun xiaomiCheckMatchesManufacturer() {
        assertTrue(WirelessDebugNavigator.isXiaomi("Xiaomi"))
        assertTrue(WirelessDebugNavigator.isXiaomi("XIAOMI"))
        assertFalse(WirelessDebugNavigator.isXiaomi("samsung"))
        assertFalse(WirelessDebugNavigator.isXiaomi())
    }
}
