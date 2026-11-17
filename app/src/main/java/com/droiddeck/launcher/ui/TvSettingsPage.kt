package com.droiddeck.launcher.ui

import android.content.Context
import android.hardware.display.DisplayManager
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
import com.droiddeck.launcher.input.SecondScreenDisplay
import com.droiddeck.launcher.input.SecondScreenDisplays
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
    var tvRender4k by remember { mutableStateOf(SessionPrefs.tvRender4k(ctx)) }
    var detectedDisplays by remember { mutableStateOf(readDetectedDisplays(ctx)) }
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
                    "While the handoff is on, a running game moves to a connected " +
                    "TV at 1080p (or 4K when the 4K render toggle below is on) when a TV is connected — by HDMI cable or wirelessly " +
                    "with Samsung DeX. Turning it off, or disconnecting the TV, moves the game back to the phone.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "The TV diagnostics log below records the whole TV session: each handoff step " +
                    "(shown, attached, resized, restored, failed) and the controller mode switching on " +
                    "and off. It is a separate file from the main diagnostics log.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // ── Handoff ─────────────────────────────────────────────
        SettingsGroup("TV handoff") {
            SettingsRow(
                label = "TV handoff",
                hint = "While on, a running game moves to the TV at 1080p when one is connected, by HDMI cable or wirelessly with Samsung DeX. Turn off to move it back to the phone.",
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
                hint = "While on, the phone becomes the controller when the game is on the TV and no physical controller is connected: on-screen controls stay visible and gyro aiming runs.",
            ) {
                ToggleSwitch(checked = tvControllerMode, onChange = { on ->
                    tvControllerMode = on
                    SessionPrefs.setTvControllerMode(ctx, on)
                })
            }
        }

        // ── TV 4K render ────────────────────────────────────────
        SettingsGroup("TV 4K render") {
            SettingsRow(
                label = "TV 4K render",
                hint = "Off by default: while on, the game renders at the TV's native 4K (3840x2160) instead of 1080p. Sharper picture, but much heavier on the phone: more heat and battery drain. Applies the next time the game moves to the TV. Wireless displays always stay at 1080p.",
            ) {
                ToggleSwitch(checked = tvRender4k, onChange = { on ->
                    tvRender4k = on
                    SessionPrefs.setTvRender4k(ctx, on)
                })
            }
        }

        // ── Wireless display (DeX) ───────────────────────────────
        SettingsGroup("Wireless display (DeX)") {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "To play on the TV without a cable: on the phone, open Samsung DeX and connect wirelessly to the TV. " +
                    "The same handoff then moves the game to the TV, and the phone becomes the controller. No app is needed " +
                    "on the TV. Wireless stays at 1080p and adds a short delay, so fast games are better on the cable.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (detectedDisplays.isEmpty()) {
                    Text(
                        "No external display detected — connect DeX first.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    detectedDisplays.forEach { display ->
                        Text(
                            display.label,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Actions {
                    SecondaryButton(stringResource(R.string.setup_check_again), compact = true) {
                        detectedDisplays = readDetectedDisplays(ctx)
                    }
                }
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

private fun readDetectedDisplays(ctx: Context): List<SecondScreenDisplay> {
    val displayManager = ctx.getSystemService(DisplayManager::class.java) ?: return emptyList()
    return SecondScreenDisplays.available(displayManager)
}
