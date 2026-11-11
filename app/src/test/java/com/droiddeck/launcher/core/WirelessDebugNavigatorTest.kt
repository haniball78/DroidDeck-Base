package com.droiddeck.launcher.core

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ResolveInfo
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

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

    @Test fun fallbackPinsToSettingsWhenItResolves() {
        // The One UI case: the implicit action resolves to Settings and the
        // permission controller, which shows the system resolver. The
        // fallback must name the Settings activity explicitly.
        val settings = ResolveInfo().apply {
            activityInfo = ActivityInfo().apply {
                packageName = "com.android.settings"
                name = "com.android.settings.Settings\$DevelopmentSettingsActivity"
            }
        }
        val permissionController = ResolveInfo().apply {
            activityInfo = ActivityInfo().apply {
                packageName = "com.android.permissioncontroller"
                name = "com.android.permissioncontroller.PermissionControllerActivity"
            }
        }
        shadowOf(context.packageManager).addResolveInfoForIntent(
            Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS),
            listOf(settings, permissionController),
        )
        val intent = WirelessDebugNavigator.targetIntent(context)
        assertEquals(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS, intent.action)
        assertEquals("com.android.settings", intent.component?.packageName)
        assertEquals(
            "com.android.settings.Settings\$DevelopmentSettingsActivity",
            intent.component?.className,
        )
        assertEquals(
            WirelessDebugNavigator.WIRELESS_ROW_KEY,
            intent.getStringExtra(WirelessDebugNavigator.FRAGMENT_ARG_KEY),
        )
    }

    @Test fun fallbackStaysImplicitWhenSettingsDoesNotResolve() {
        // Only the permission controller resolves: the fallback stays
        // implicit, exactly as before.
        val permissionController = ResolveInfo().apply {
            activityInfo = ActivityInfo().apply {
                packageName = "com.android.permissioncontroller"
                name = "com.android.permissioncontroller.PermissionControllerActivity"
            }
        }
        shadowOf(context.packageManager).addResolveInfoForIntent(
            Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS),
            permissionController,
        )
        val intent = WirelessDebugNavigator.targetIntent(context)
        assertEquals(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS, intent.action)
        assertNull(intent.component)
    }

    @Test fun xiaomiCheckMatchesManufacturer() {
        assertTrue(WirelessDebugNavigator.isXiaomi("Xiaomi"))
        assertTrue(WirelessDebugNavigator.isXiaomi("XIAOMI"))
        assertFalse(WirelessDebugNavigator.isXiaomi("samsung"))
        assertFalse(WirelessDebugNavigator.isXiaomi())
    }
}
