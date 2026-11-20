package com.droiddeck.launcher.core.privileged

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DiagnosticsLogEnabledTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `diagnostics log is off by default`() {
        context.getSharedPreferences("droiddeck-diagnostics", Context.MODE_PRIVATE)
            .edit().remove("enabled").apply()
        assertFalse(DiagnosticsLog.isEnabled(context))
    }

    @Test
    fun `diagnostics log toggle round-trips`() {
        DiagnosticsLog.setEnabled(context, true)
        assertTrue(DiagnosticsLog.isEnabled(context))
        DiagnosticsLog.setEnabled(context, false)
        assertFalse(DiagnosticsLog.isEnabled(context))
    }
}
