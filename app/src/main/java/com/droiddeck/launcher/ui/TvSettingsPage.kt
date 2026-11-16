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
import com.droiddeck.launcher.core.TvHandoff
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
    var tvSurfaceEncodeTest by remember { mutableStateOf(SessionPrefs.tvSurfaceEncodeTest(ctx)) }
    var tvStreamTest by remember { mutableStateOf(SessionPrefs.tvStreamTest(ctx)) }
    var tvRender4k by remember { mutableStateOf(SessionPrefs.tvRender4k(ctx)) }
    var computerState by remember { mutableStateOf(TvHandoff.computerState(ctx)) }
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
                    "TV at 1080p (or 4K when the 4K render toggle below is on) when the TV is plugged in. Turning it off, or unplugging " +
                    "the TV, moves the game back to the phone.",
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
                hint = "While on, a running game moves to a connected TV at 1080p when the TV is plugged in. Turn off to move it back to the phone.",
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
                hint = "Off by default: while on, the game renders at the TV's native 4K (3840x2160) instead of 1080p. Sharper picture, but much heavier on the phone: more heat and battery drain. Applies the next time the game moves to the TV.",
            ) {
                ToggleSwitch(checked = tvRender4k, onChange = { on ->
                    tvRender4k = on
                    SessionPrefs.setTvRender4k(ctx, on)
                })
            }
        }

        // ── Computer (scrcpy) ─────────────────────────────────────
        SettingsGroup("Computer") {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "The computer drives the game through scrcpy: the phone sees its mouse and keyboard as real hardware, so the app treats the computer as a controller and the on-screen pad hands over to it. Stop scrcpy and the pad comes back.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "Install first: scrcpy 2.4 or newer on the computer (check with scrcpy --version). Windows: winget install Genymobile.scrcpy, or the release zip from GitHub — adb is bundled. macOS: brew install scrcpy. Linux: the official release, not the distro package (Ubuntu 24.04 ships 1.25, too old). Nothing else is needed — no Python.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "On the phone: turn on USB debugging in Developer options, plug in the USB cable and accept the Allow USB debugging? prompt.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "Screen off (the game is on the TV through the mirror): scrcpy --mouse=uhid --keyboard=uhid --turn-screen-off --stay-awake -f",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "Screen on: scrcpy --mouse=uhid --keyboard=uhid -f",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    when (computerState) {
                        TvHandoff.ComputerState.SCRCPY -> "Computer connected — scrcpy is running, mouse and keyboard recognized as a controller."
                        TvHandoff.ComputerState.DEBUGGING -> "Computer connected over USB with debugging on, but scrcpy is not running."
                        TvHandoff.ComputerState.NONE -> "No computer connected."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Actions {
                SecondaryButton(stringResource(R.string.setup_check_again), compact = true) {
                    computerState = TvHandoff.computerState(ctx)
                }
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

        // ── TV surface encode (P3 slice A2) ─────────────────────
        SettingsGroup("TV surface encode") {
            SettingsRow(
                label = "TV surface encode test",
                hint = "Test only: while on, a running session encodes the live game frames to filesDir/venc/surface.h264 (capped at 8 MB) and counts frames in the TV diagnostics log. No network, no receiver yet — it only proves whether live frames reach a hardware encoder.",
            ) {
                ToggleSwitch(checked = tvSurfaceEncodeTest, onChange = { on ->
                    tvSurfaceEncodeTest = on
                    SessionPrefs.setTvSurfaceEncodeTest(ctx, on)
                })
            }
        }

        // ── TV stream (P3 slice A3) ─────────────────────────────
        SettingsGroup("TV stream") {
            SettingsRow(
                label = "TV stream test",
                hint = "Test only: while on, a running session serves the encoded game frames over raw TCP on the Wi-Fi network (advertised as _droiddeck-tv._tcp) for the DroidDeck TV receiver app. It reads filesDir/venc/surface.h264, so turn the surface encode test on too — without it the stream carries zero frames.",
            ) {
                ToggleSwitch(checked = tvStreamTest, onChange = { on ->
                    tvStreamTest = on
                    SessionPrefs.setTvStreamTest(ctx, on)
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
