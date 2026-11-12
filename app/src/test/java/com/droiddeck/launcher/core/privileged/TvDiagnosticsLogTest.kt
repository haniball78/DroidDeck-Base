package com.droiddeck.launcher.core.privileged

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TvDiagnosticsLogTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun reset() {
        TvDiagnosticsLog.deleteCurrentFile(context)
        DiagnosticsLog.deleteCurrentFile(context)
        TvDiagnosticsLog.setEnabled(context, true)
    }

    @Test
    fun `tv log is on by default`() {
        context.getSharedPreferences("droiddeck-tv-diagnostics", Context.MODE_PRIVATE)
            .edit().remove("enabled").apply()
        assertTrue(TvDiagnosticsLog.isEnabled(context))
    }

    @Test
    fun `tv log toggle round-trips`() {
        TvDiagnosticsLog.setEnabled(context, false)
        assertEquals(false, TvDiagnosticsLog.isEnabled(context))
        TvDiagnosticsLog.setEnabled(context, true)
        assertEquals(true, TvDiagnosticsLog.isEnabled(context))
    }

    @Test
    fun `handoff event lands in the tv file only`() {
        reset()
        TvDiagnosticsLog.log(
            context, "tv", "handoff",
            fields = mapOf("step" to "attached", "display" to "1", "size" to "1920x1080"),
        )
        val tvFile = TvDiagnosticsLog.file(context)
        assertNotNull(tvFile)
        val text = tvFile!!.readText()
        assertTrue(text.contains("\"category\":\"tv\""))
        assertTrue(text.contains("\"event\":\"handoff\""))
        assertTrue(text.contains("\"step\":\"attached\""))
        assertEquals(1L, TvDiagnosticsLog.summary(context)?.lines)
        // Separation is about content, not about the main file existing:
        // other tests in this run may already have written to the main
        // diagnostics log. The handoff event itself must never be there.
        val mainText = DiagnosticsLog.file(context)?.readText()
        assertTrue(mainText == null || !mainText.contains("\"category\":\"tv\""))
    }

    @Test
    fun `attached context receives context-less logs`() {
        reset()
        TvDiagnosticsLog.attach(context)
        TvDiagnosticsLog.log("tv", "handoff", fields = mapOf("step" to "shown"))
        assertNotNull(TvDiagnosticsLog.file(context))
        assertTrue(TvDiagnosticsLog.file(context)!!.readText().contains("\"step\":\"shown\""))
    }

    @Test
    fun `disabled tv log blocks writes`() {
        reset()
        TvDiagnosticsLog.setEnabled(context, false)
        TvDiagnosticsLog.log(context, "tv", "handoff", fields = mapOf("step" to "attached"))
        assertNull(TvDiagnosticsLog.file(context))
    }

    @Test
    fun `delete removes the tv file`() {
        reset()
        TvDiagnosticsLog.log(context, "tv", "handoff", fields = mapOf("step" to "attached"))
        assertNotNull(TvDiagnosticsLog.file(context))
        assertTrue(TvDiagnosticsLog.deleteCurrentFile(context))
        assertNull(TvDiagnosticsLog.file(context))
        assertFalse(TvDiagnosticsLog.file(context)?.exists() ?: false)
    }
}
