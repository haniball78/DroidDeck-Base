package com.droiddeck.launcher.core.privileged

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import androidx.core.content.FileProvider
import com.droiddeck.launcher.core.LogRedactor
import com.droiddeck.launcher.session.SessionFiles
import java.io.File

// TV handoff events live in their own file, so a TV test can be shared
// without the whole diagnostics log. Same machinery as DiagnosticsLog,
// different file and prefs only.
object TvDiagnosticsLog {
    const val FILE_NAME = "droiddeck-tv-diagnostics.jsonl"
    private const val TAG = "TvDiagnosticsLog"
    private const val PREFS = "droiddeck-tv-diagnostics"
    private const val KEY_ENABLED = "enabled"

    fun isEnabled(context: Context): Boolean {
        return runCatching {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, true)
        }.getOrDefault(true)
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        runCatching {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, enabled).apply()
        }
    }

    fun deleteCurrentFile(context: Context): Boolean {
        return runCatching {
            DiagnosticsCore.deleteCurrentFile(SessionFiles.logDirectory(context), FILE_NAME)
        }.getOrDefault(false)
    }

    @Volatile
    private var attached: Context? = null

    private val lock = Any()

    fun attach(context: Context) {
        runCatching { attached = context.applicationContext }
    }

    fun log(context: Context?, category: String, event: String, generation: Int = 0, fields: Map<String, String> = emptyMap()) {
        val ctx = context ?: attached ?: return
        if (!isEnabled(ctx)) return
        try {
            val dir = SessionFiles.logDirectory(ctx)
            val line = DiagnosticsCore.render(
                System.currentTimeMillis(),
                SystemClock.elapsedRealtime(),
                category,
                event,
                generation,
                fields,
            )
            synchronized(lock) {
                DiagnosticsCore.append(dir, FILE_NAME, line)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "tv diagnostics write failed", t)
        }
    }

    fun log(category: String, event: String, generation: Int = 0, fields: Map<String, String> = emptyMap()) {
        log(null, category, event, generation, fields)
    }

    fun file(context: Context): File? {
        return try {
            File(SessionFiles.logDirectory(context), FILE_NAME).takeIf { it.isFile }
        } catch (_: Exception) {
            null
        }
    }

    fun summary(context: Context): DiagnosticsSummary? {
        return try {
            DiagnosticsCore.summary(SessionFiles.logDirectory(context), FILE_NAME)
        } catch (_: Exception) {
            null
        }
    }

    fun shareIntent(context: Context): Intent? {
        return try {
            val src = file(context) ?: return null
            val shareDir = File(context.cacheDir, "share").apply { mkdirs() }
            val copy = File(shareDir, FILE_NAME)
            copy.bufferedWriter(Charsets.UTF_8).use { writer ->
                LogRedactor.scrubForShare(src, writer)
            }
            val uri = FileProvider.getUriForFile(context, context.packageName + ".logs", copy)
            val send = Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_STREAM, uri)
                .putExtra(Intent.EXTRA_SUBJECT, FILE_NAME)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            send.clipData = ClipData.newRawUri(FILE_NAME, uri)
            Intent.createChooser(send, FILE_NAME).apply {
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        } catch (_: Exception) {
            null
        }
    }

    fun formatTime(wallMs: Long): String = DiagnosticsLog.formatTime(wallMs)
}
