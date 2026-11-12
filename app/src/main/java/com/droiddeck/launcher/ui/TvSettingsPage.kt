package com.droiddeck.launcher.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.droiddeck.launcher.R
import com.droiddeck.launcher.core.privileged.TvDiagnosticsLog
import com.droiddeck.launcher.session.SessionPrefs

// ─────────────────────────────────────────────────────────────
//  TvSettingsPage  –  TV handoff test + its own diagnostics log
// ─────────────────────────────────────────────────────────────

@Composable
fun TvSettingsPage(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val host = rememberMenuHost()
    var tvHandoffTest by remember { mutableStateOf(SessionPrefs.tvHandoffTest(ctx)) }
    var tvControllerMode by remember { mutableStateOf(SessionPrefs.tvControllerMode(ctx)) }
    var tvStreamLoopbackTest by remember { mutableStateOf(SessionPrefs.tvStreamLoopbackTest(ctx)) }
    var tvDiagnosticsEnabled by remember { mutableStateOf(TvDiagnosticsLog.isEnabled(ctx)) }
    var summary by remember { mutableStateOf(TvDiagnosticsLog.summary(ctx)) }
    var hasFile by remember { mutableStateOf(TvDiagnosticsLog.file(ctx) != null) }

    SettingsPage(
        host = host,
        title = "TV",
        eyebrow = "Television & External Display",
        onBack = onBack,
        scrollContent = true,
    ) {
        // ── What is this? ─────────────────────────────────────────
        SettingsGroup("What is this?") {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "Test only: while the handoff test is on, a running game moves to a connected " +
                    "TV at 1080p when the TV is plugged in. Turning the test off, or unplugging " +
                    "the TV, moves the game back to the phone.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "The TV diagnostics log below records the whole TV session: each handoff step " +
                    "(shown, attached, resized, restored, failed), the controller mode switching on " +
                    "and off, and the End button. It is a separate file from the main diagnostics log.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // ── Handoff test ──────────────────────────────────────────
        SettingsGroup("TV handoff test") {
            SettingsRow(
                label = "TV handoff test",
                hint = "Test only: while on, a running game moves to a connected TV at 1080p when the TV is plugged in. Turn off to move it back to the phone.",
            ) {
                ToggleSwitch(checked = tvHandoffTest, onChange = { on ->
                    tvHandoffTest = on
                    SessionPrefs.setTvHandoffTest(ctx, on)
                })
            }
        }

        // ── TV controller (P2) ────────────────────────────────────
        SettingsGroup("TV controller") {
            SettingsRow(
                label = "TV controller mode",
                hint = "While on, the phone becomes the controller when the game is on the TV and no physical controller is connected: on-screen controls stay visible, gyro aiming runs, and a small End button appears on the phone (tap twice to end the session).",
            ) {
                ToggleSwitch(checked = tvControllerMode, onChange = { on ->
                    tvControllerMode = on
                    SessionPrefs.setTvControllerMode(ctx, on)
                })
            }
        }

        // ── TV stream loopback (P3 slice A1) ─────────────────────
        SettingsGroup("TV stream loopback") {
            SettingsRow(
                label = "TV stream loopback test",
                hint = "Test only: while on, a running session taps the encoded video bitstream into filesDir/venc/loopback.h264 (capped at 8 MB) and counts frames in the TV diagnostics log. No network, no receiver yet — it only proves whether frames reach an encoder at all.",
            ) {
                ToggleSwitch(checked = tvStreamLoopbackTest, onChange = { on ->
                    tvStreamLoopbackTest = on
                    SessionPrefs.setTvStreamLoopbackTest(ctx, on)
                })
            }
        }

        // ── TV diagnostics log ────────────────────────────────────
        SettingsGroup("TV diagnostics log") {
            ToggleRow(
                host = host,
                key = "tv-diagnostics-enabled",
                label = "TV diagnostics log",
                hint = null,
                checked = tvDiagnosticsEnabled,
            ) { on ->
                tvDiagnosticsEnabled = on
                if (on) {
                    TvDiagnosticsLog.setEnabled(ctx, true)
                    TvDiagnosticsLog.log(ctx, "tv.control", "enabled")
                } else {
                    TvDiagnosticsLog.log(ctx, "tv.control", "disabled")
                    TvDiagnosticsLog.setEnabled(ctx, false)
                }
            }
            Column(Modifier.padding(horizontal = 14.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    stringResource(
                        R.string.diagnostics_summary,
                        if (summary != null && summary!!.lastWallMs > 0) {
                            TvDiagnosticsLog.formatTime(summary!!.lastWallMs)
                        } else {
                            "—"
                        },
                        summary?.lines?.toInt() ?: 0,
                    ),
                    fontSize = 13.sp, color = colors.onSurfaceVariant,
                )
                Actions {
                    SecondaryButton("Share TV log", compact = true, enabled = hasFile) {
                        TvDiagnosticsLog.shareIntent(ctx)?.let { ctx.startActivity(it) }
                    }
                    SecondaryButton("Delete TV log", compact = true, enabled = hasFile) {
                        TvDiagnosticsLog.deleteCurrentFile(ctx)
                        summary = TvDiagnosticsLog.summary(ctx)
                        hasFile = TvDiagnosticsLog.file(ctx) != null
                    }
                    SecondaryButton(stringResource(R.string.setup_check_again), compact = true) {
                        summary = TvDiagnosticsLog.summary(ctx)
                        hasFile = TvDiagnosticsLog.file(ctx) != null
                    }
                }
            }
        }
    }
}
