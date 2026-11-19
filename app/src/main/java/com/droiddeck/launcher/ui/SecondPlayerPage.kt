package com.droiddeck.launcher.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.droiddeck.launcher.R
import com.droiddeck.launcher.input.OnScreenControls
import com.droiddeck.launcher.input.PadBridge
import com.droiddeck.launcher.input.PadState
import com.droiddeck.launcher.session.GamepadInput
import com.droiddeck.launcher.session.P2pPadClient
import com.droiddeck.launcher.session.PadTransport
import com.droiddeck.launcher.session.USB_DISCOVERY_TIMEOUT_MS
import com.droiddeck.launcher.session.UsbTether
import com.droiddeck.launcher.session.discoverNsdController
import com.droiddeck.launcher.session.discoverNsdEndpoints
import com.droiddeck.launcher.session.isLanAddress
import com.droiddeck.launcher.session.isP2pAddress
import com.droiddeck.launcher.session.preferEndpoint
import java.io.File
import kotlinx.coroutines.delay

// ─────────────────────────────────────────────────────────────
//  SecondPlayerPage – join a controller phone as player 1 or 2
// ─────────────────────────────────────────────────────────────

@Composable
fun SecondPlayerPage(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val host = rememberMenuHost()

    var padRunning by remember { mutableStateOf(false) }
    var padStatus by remember { mutableStateOf("Idle — pick a player below, then Start.") }
    var padRef by remember { mutableStateOf<P2pPadClient?>(null) }
    var gamepadSlot by remember { mutableStateOf(1) }
    var transport by remember { mutableStateOf(PadTransport.LAN) }
    var manualIp by remember { mutableStateOf("") }

    val padBridge = remember {
        PadBridge(File(ctx.cacheDir, "droiddeck-pad")).also { it.setBuiltinPad(true) }
    }
    DisposableEffect(padBridge) {
        onDispose { runCatching { padBridge.stop() } }
    }

    fun stopPad() {
        padRunning = false
        GamepadInput.clear()
        runCatching { padBridge.setTouchRoute(null) }
        runCatching { padRef?.close() }
        runCatching { padBridge.stop() }
        padRef = null
    }

    fun installClient(client: P2pPadClient, statusText: String) {
        padRef = client
        GamepadInput.install(padBridge, client)
        padStatus = statusText
        padBridge.setTouchRoute(object : PadBridge.TouchRoute {
            override fun active() = padRunning
            override fun accept(state: PadState) = GamepadInput.onTouchState(state)
        })
    }

    fun startPad() {
        if (padRunning) return
        padRunning = true
        padStatus = "Searching for a controller…"
        Thread({
            if (transport == PadTransport.USB) {
                val host = discoverNsdController(ctx, USB_DISCOVERY_TIMEOUT_MS)?.takeIf { UsbTether.isTetherAddress(it.first) }?.first
                    ?: UsbTether.systemGateway() ?: UsbTether.DEFAULT_GATEWAY
                val client = P2pPadClient(gamepadSlot)
                if (!client.connect(host, UsbTether.USB_PAD_PORT)) {
                    padStatus = "Could not reach the controller over USB — check the cable and USB tethering on the game phone."
                    padRunning = false
                    return@Thread
                }
                installClient(client, "Connected as player ${gamepadSlot + 1} over USB — use the touch pad below.")
                return@Thread
            }
            val typedIp = manualIp.trim()
            if (typedIp.isNotEmpty()) {
                val client = P2pPadClient(gamepadSlot)
                if (!client.connect(typedIp, UsbTether.USB_PAD_PORT)) {
                    padStatus = "Could not reach $typedIp — check the IP and that both phones are on the same Wi-Fi."
                    padRunning = false
                    return@Thread
                }
                installClient(client, "Connected as player ${gamepadSlot + 1} at $typedIp — use the touch pad below.")
                return@Thread
            }
            // The controller advertises twice (home Wi-Fi + Wi-Fi Direct), so
            // collect every endpoint and prefer the one for this transport.
            val endpoints = discoverNsdEndpoints(ctx, 15_000) { endpoint ->
                when (transport) {
                    PadTransport.LAN -> isLanAddress(endpoint.first)
                    PadTransport.P2P -> isP2pAddress(endpoint.first)
                    PadTransport.USB -> false
                }
            }
            if (!padRunning) return@Thread
            val endpoint = preferEndpoint(endpoints, transport)
            if (endpoint == null) {
                padStatus = "Controller not found (controller-not-found)"
                padRunning = false
                return@Thread
            }
            val (host, _) = endpoint
            val client = P2pPadClient(gamepadSlot)
            if (!client.connect(host, UsbTether.USB_PAD_PORT)) {
                padStatus = "Could not reach the controller's gamepad input."
                padRunning = false
                return@Thread
            }
            val over = if (isLanAddress(host)) "home Wi-Fi" else "Wi-Fi Direct"
            installClient(client, "Connected as player ${gamepadSlot + 1} over $over — use the touch pad below.")
        }, "gamepad-start").start()
    }

    fun switchTransport(next: PadTransport) {
        if (next == transport) return
        if (padRunning) stopPad()
        transport = next
    }

    fun missingP2pPermissions(): Array<String> {
        val needed = ArrayList<String>()
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            needed += Manifest.permission.ACCESS_FINE_LOCATION
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED
        ) {
            needed += Manifest.permission.NEARBY_WIFI_DEVICES
        }
        return needed.toTypedArray()
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        if (grants.values.all { it }) {
            startPad()
        } else {
            padStatus = "Wi-Fi Direct needs location permission to find the controller."
        }
    }

    fun ensurePermissionsAndStartPad() {
        // Only the Wi-Fi Direct path needs location permission; home Wi-Fi
        // discovery and USB need none.
        if (transport != PadTransport.P2P) {
            startPad()
            return
        }
        val needed = missingP2pPermissions()
        if (needed.isEmpty()) startPad() else permissionLauncher.launch(needed)
    }

    LaunchedEffect(padRunning) {
        while (padRunning) {
            if (padRef != null && padRef?.connected?.get() == false) {
                padStatus = "Lost the controller — Start again."
                stopPad()
            }
            delay(1000)
        }
    }

    DisposableEffect(Unit) {
        onDispose { stopPad() }
    }

    SettingsPage(
        host = host,
        title = stringResource(R.string.connections_second_player),
        eyebrow = "Join As Player 1 Or 2",
        onBack = onBack,
        scrollContent = true,
    ) {
        SettingsGroup("What is this?") {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    stringResource(R.string.connections_second_player_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        SettingsGroup("Gamepad") {
            SettingsRow(
                label = stringResource(R.string.second_player_home_wifi),
                hint = stringResource(R.string.second_player_home_wifi_hint),
            ) {
                ToggleSwitch(checked = transport == PadTransport.LAN, onChange = { on -> if (on) switchTransport(PadTransport.LAN) })
            }
            SettingsRow(
                label = "Wi-Fi Direct",
                hint = "Find the controller phone over Wi-Fi Direct.",
            ) {
                ToggleSwitch(checked = transport == PadTransport.P2P, onChange = { on -> if (on) switchTransport(PadTransport.P2P) })
            }
            SettingsRow(
                label = "USB cable",
                hint = "Join over a USB cable instead of Wi-Fi.",
            ) {
                ToggleSwitch(checked = transport == PadTransport.USB, onChange = { on -> if (on) switchTransport(PadTransport.USB) })
            }
            SettingsRow(
                label = "Player 1 — this phone is the first controller",
                hint = "Use when the controller phone's USB port is busy with the TV cable: a gamepad plugged into this phone drives player 1.",
            ) {
                ToggleSwitch(checked = gamepadSlot == 0, onChange = { on ->
                    if (on) {
                        if (padRunning) stopPad()
                        gamepadSlot = 0
                    }
                })
            }
            SettingsRow(
                label = "Player 2 — this phone is the second controller",
                hint = "This phone's touch pad drives player 2.",
            ) {
                ToggleSwitch(checked = gamepadSlot == 1, onChange = { on ->
                    if (on) {
                        if (padRunning) stopPad()
                        gamepadSlot = 1
                    }
                })
            }
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (transport == PadTransport.USB) {
                    Text(
                        "Connect the cable, then turn on USB tethering on the game phone (Settings → Hotspot → " +
                        "USB tethering — the app cannot turn it on itself). This phone must be the USB host: with a " +
                        "C-to-C cable the roles negotiate — if it does not connect, switch \"USB controlled by\" to this " +
                        "device, or use a C-to-A cable + A-to-C adapter, which fixes this device as host.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (transport != PadTransport.USB) {
                    AdbTextField(
                        value = manualIp, onValueChange = { manualIp = it },
                        label = stringResource(R.string.second_player_manual_ip),
                        keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Done,
                        placeholder = "192.168.1.122", compact = true,
                        onDone = { ensurePermissionsAndStartPad() },
                    )
                    Text(
                        stringResource(R.string.second_player_manual_ip_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                AndroidView(
                    factory = { context -> OnScreenControls(context, padBridge) },
                    modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
                )
                Text(
                    padStatus,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (padRunning) {
                    SecondaryButton("Stop", modifier = Modifier.fillMaxWidth()) { stopPad() }
                } else {
                    PrimaryButton("Start", fill = true, large = true, modifier = Modifier.fillMaxWidth()) { ensurePermissionsAndStartPad() }
                }
            }
        }
    }
}
