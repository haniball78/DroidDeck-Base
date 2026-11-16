package com.droiddeck.launcher.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.droiddeck.launcher.R
import com.droiddeck.launcher.core.TvHandoff
import kotlinx.coroutines.delay

// ─────────────────────────────────────────────────────────────
//  ComputerSettingsPage – PC as a controller via scrcpy
// ─────────────────────────────────────────────────────────────

@Composable
fun ComputerSettingsPage(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val host = rememberMenuHost()
    var computerState by remember { mutableStateOf(TvHandoff.computerState(ctx)) }

    SettingsPage(
        host = host,
        title = "Computer",
        eyebrow = "PC as controller via scrcpy",
        onBack = onBack,
        scrollContent = true,
    ) {
        // ── What is this? ─────────────────────────────────────────
        SettingsGroup("What is this?") {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "The computer drives the game through scrcpy: the phone sees its mouse and keyboard as real hardware, so the app treats the computer as a controller and the on-screen pad hands over to it. Stop scrcpy and the pad comes back.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "While the computer is recognized, a small \"Phone pad\" button appears during a session: tap it to bring the phone pad back while the computer keeps driving the game, and tap it again to return to automatic. The button never stops the app from recognizing the computer.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // ── Step 1: install scrcpy ────────────────────────────────
        SettingsGroup("Step 1: Install scrcpy") {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "Install scrcpy 2.4 or newer on the computer. Nothing else is needed — no Python.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            CommandRow("Check the version", "scrcpy --version")
            CommandRow("Windows", "winget install Genymobile.scrcpy")
            CommandRow("macOS", "brew install scrcpy")
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "Linux: the official release from GitHub, not the distro package (Ubuntu 24.04 ships 1.25, too old). The release zip bundles adb.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // ── Step 2: connect the phone ─────────────────────────────
        SettingsGroup("Step 2: Connect the phone") {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "USB: turn on USB debugging in Developer options, plug in the USB cable and accept the Allow USB debugging? prompt.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "Wireless: turn on Wireless debugging in Developer options, tap Pair device with pairing code, then run the two commands below on the computer.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            CommandRow("Pair (wireless)", "adb pair <phone-ip>:<pair-port>")
            CommandRow("Connect (wireless)", "adb connect <phone-ip>:<port>")
            CommandRow("Connect over TCP", "scrcpy --tcpip=<phone-ip>:<port>")
        }

        // ── Step 3: run scrcpy ────────────────────────────────────
        SettingsGroup("Step 3: Run scrcpy") {
            CommandRow("Screen off", "scrcpy --mouse=uhid --keyboard=uhid --turn-screen-off --stay-awake -f")
            CommandRow("Screen on", "scrcpy --mouse=uhid --keyboard=uhid --stay-awake -f")
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "Screen off: the phone screen goes off and the mirror stays on the computer. MOD+o toggles the phone screen off and on while scrcpy runs.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // ── Step 4: check ─────────────────────────────────────────
        SettingsGroup("Step 4: Check") {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
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
    }
}

@Composable
private fun CommandRow(label: String, command: String) {
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(2000)
            copied = false
        }
    }
    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                command,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            SecondaryButton(if (copied) "Copied" else "Copy", compact = true) {
                clipboard.setText(AnnotatedString(command))
                copied = true
            }
        }
    }
}
