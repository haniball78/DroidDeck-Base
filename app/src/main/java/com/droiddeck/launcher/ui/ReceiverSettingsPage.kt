package com.droiddeck.launcher.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.droiddeck.launcher.R
import com.droiddeck.launcher.core.privileged.TvDiagnosticsLog
import com.droiddeck.launcher.input.OnScreenControls
import com.droiddeck.launcher.input.PadBridge
import com.droiddeck.launcher.input.PadState
import com.droiddeck.launcher.session.GamepadInput
import com.droiddeck.launcher.session.P2pPadClient
import com.droiddeck.launcher.session.P2pReceiver
import com.droiddeck.launcher.session.SessionPrefs
import com.droiddeck.launcher.session.TvStreamReceiver
import com.droiddeck.launcher.session.UsbTether
import java.io.File
import kotlinx.coroutines.delay

// ─────────────────────────────────────────────────────────────
//  ReceiverSettingsPage – Receiver / Controller mode
// ─────────────────────────────────────────────────────────────

@Composable
fun ReceiverSettingsPage(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val host = rememberMenuHost()
    var mode by remember { mutableStateOf(SessionPrefs.receiverMode(ctx)) }
    var summary by remember { mutableStateOf(TvDiagnosticsLog.summary(ctx)) }
    var hasFile by remember { mutableStateOf(TvDiagnosticsLog.file(ctx) != null) }

    var surface by remember { mutableStateOf<Surface?>(null) }
    var receiverRunning by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Idle — pick Receiver below, then Start.") }
    var frames by remember { mutableStateOf(0L) }
    var firstFrameMs by remember { mutableStateOf(-1L) }
    var p2pRef by remember { mutableStateOf<P2pReceiver?>(null) }
    var recvRef by remember { mutableStateOf<TvStreamReceiver?>(null) }

    var padRunning by remember { mutableStateOf(false) }
    var padStatus by remember { mutableStateOf("Idle — pick Gamepad below, then Start.") }
    var padRef by remember { mutableStateOf<P2pPadClient?>(null) }
    var padP2pRef by remember { mutableStateOf<P2pReceiver?>(null) }
    var gamepadSlot by remember { mutableStateOf(1) }
    var useUsb by remember { mutableStateOf(false) }

    // Local touch pad for Gamepad mode: its state is routed to the controller
    // phone instead of a guest, so no session runs on this device.
    val padBridge = remember(mode) {
        if (mode == SessionPrefs.RECEIVER_MODE_GAMEPAD) {
            PadBridge(File(ctx.cacheDir, "droiddeck-pad")).also { it.setBuiltinPad(true) }
        } else null
    }
    DisposableEffect(padBridge) {
        onDispose { runCatching { padBridge?.stop() } }
    }

    fun stopPad() {
        padRunning = false
        GamepadInput.clear()
        runCatching { padBridge?.setTouchRoute(null) }
        runCatching { padRef?.close() }
        runCatching { padP2pRef?.stop() }
        runCatching { padBridge?.stop() }
        padRef = null
        padP2pRef = null
    }

    fun startPad() {
        if (padRunning) return
        padRunning = true
        padStatus = "Searching for a controller…"
        Thread({
            if (useUsb) {
                val host = UsbTether.systemGateway() ?: UsbTether.DEFAULT_GATEWAY
                val client = P2pPadClient(gamepadSlot)
                if (!client.connect(host, UsbTether.USB_PAD_PORT)) {
                    padStatus = "Could not reach the controller over USB — check the cable and USB tethering on the game phone."
                    padRunning = false
                    return@Thread
                }
                padRef = client
                padBridge?.let { GamepadInput.install(it, client) }
                padStatus = "Connected as player ${gamepadSlot + 1} over USB — use the touch pad below."
                padBridge?.setTouchRoute(object : PadBridge.TouchRoute {
                    override fun active() = padRunning
                    override fun accept(state: PadState) = GamepadInput.onTouchState(state)
                })
                return@Thread
            }
            val p2p = P2pReceiver(ctx)
            padP2pRef = p2p
            val error = p2p.begin()
            if (!padRunning) {
                p2p.stop()
                return@Thread
            }
            if (error != null) {
                padStatus = "Controller not found ($error)"
                padRunning = false
                return@Thread
            }
            val port = p2p.padPort
            val host = p2p.controllerAddress?.hostAddress
            if (port == null || host == null) {
                padStatus = "This controller has no gamepad input — start a game on the controller phone with Controller mode on."
                p2p.stop()
                padRunning = false
                return@Thread
            }
            val client = P2pPadClient(gamepadSlot)
            if (!client.connect(host, port)) {
                padStatus = "Could not reach the controller's gamepad input."
                p2p.stop()
                padRunning = false
                return@Thread
            }
            padRef = client
            padBridge?.let { GamepadInput.install(it, client) }
            padStatus = "Connected as player ${gamepadSlot + 1} — use the touch pad below."
            padBridge?.setTouchRoute(object : PadBridge.TouchRoute {
                override fun active() = padRunning
                override fun accept(state: PadState) = GamepadInput.onTouchState(state)
            })
        }, "gamepad-start").start()
    }

    fun stopReceiver() {
        receiverRunning = false
        runCatching { recvRef?.stop() }
        runCatching { p2pRef?.stop() }
        recvRef = null
        p2pRef = null
    }

    fun switchTransport(usb: Boolean) {
        if (usb == useUsb) return
        if (receiverRunning) stopReceiver()
        if (padRunning) stopPad()
        useUsb = usb
    }

    fun startReceiver() {
        if (receiverRunning) return
        receiverRunning = true
        status = "Searching for a controller…"
        frames = 0
        firstFrameMs = -1
        Thread({
            if (useUsb) {
                val surf = surface
                if (surf == null) {
                    status = "No video surface yet — wait a moment and try again."
                    receiverRunning = false
                    return@Thread
                }
                val host = UsbTether.systemGateway() ?: UsbTether.DEFAULT_GATEWAY
                val recv = TvStreamReceiver(
                    ctx, surf,
                    fixedEndpoint = host to UsbTether.USB_STREAM_PORT,
                    onStatus = { status = it },
                )
                recvRef = recv
                status = "Connecting to the game phone over USB ($host)…"
                recv.start()
                return@Thread
            }
            val p2p = P2pReceiver(ctx)
            p2pRef = p2p
            val error = p2p.begin()
            if (!receiverRunning) {
                p2p.stop()
                return@Thread
            }
            if (error != null) {
                status = "Controller not found ($error)"
                receiverRunning = false
                return@Thread
            }
            val (w, h) = p2p.videoSize ?: (1920 to 1080)
            val surf = surface
            if (surf == null) {
                status = "No video surface yet — wait a moment and try again."
                p2p.stop()
                receiverRunning = false
                return@Thread
            }
            val recv = TvStreamReceiver(
                ctx, surf, w, h,
                p2p.controllerAddress?.hostAddress,
            ) { status = it }
            recvRef = recv
            status = "Found ${p2p.controllerName ?: "controller"} — connecting to the stream…"
            recv.start()
        }, "receiver-start").start()
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        if (grants.values.all { it }) {
            if (mode == SessionPrefs.RECEIVER_MODE_GAMEPAD) startPad() else startReceiver()
        } else if (mode == SessionPrefs.RECEIVER_MODE_GAMEPAD) {
            padStatus = "Wi-Fi Direct needs location permission to find the controller."
        } else {
            status = "Wi-Fi Direct needs location permission to find the controller."
        }
    }

    fun ensurePermissionsAndStart() {
        val needed = ArrayList<String>()
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            needed += Manifest.permission.ACCESS_FINE_LOCATION
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED
        ) {
            needed += Manifest.permission.NEARBY_WIFI_DEVICES
        }
        if (needed.isEmpty()) startReceiver() else permissionLauncher.launch(needed.toTypedArray())
    }

    fun ensurePermissionsAndStartPad() {
        val needed = ArrayList<String>()
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            needed += Manifest.permission.ACCESS_FINE_LOCATION
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED
        ) {
            needed += Manifest.permission.NEARBY_WIFI_DEVICES
        }
        if (needed.isEmpty()) startPad() else permissionLauncher.launch(needed.toTypedArray())
    }

    LaunchedEffect(receiverRunning) {
        while (receiverRunning) {
            recvRef?.let {
                frames = it.framesDecoded.get()
                firstFrameMs = it.firstFrameMs.get()
            }
            delay(1000)
        }
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
        onDispose {
            stopReceiver()
            stopPad()
        }
    }

    SettingsPage(
        host = host,
        title = "Receiver",
        eyebrow = "Play On Another Screen",
        onBack = onBack,
        scrollContent = true,
    ) {
        // ── What is this? ─────────────────────────────────────────
        SettingsGroup("What is this?") {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "One device runs the game, another shows it. Controller: this phone runs the game " +
                    "and streams it over its own Wi-Fi Direct group — the phone keeps its router Wi-Fi " +
                    "for the game itself. Receiver: this device finds a controller phone and shows its " +
                    "game full-screen. Gamepad: this phone joins a controller phone as player 1 or 2 — its touch pad, " +
                    "or a gamepad plugged into this phone, drives the chosen player while you watch on the TV or a receiver. The Vestel TV cannot be a receiver (it runs Vestel Smart OS, not Android).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // ── Mode ──────────────────────────────────────────────────
        SettingsGroup("Mode") {
            SettingsRow(
                label = "Controller — stream this phone's game",
                hint = "Start a game while this is on: the phone creates the DroidDeck-TV group (5 GHz) and streams the game to any receiver.",
            ) {
                ToggleSwitch(checked = mode == SessionPrefs.RECEIVER_MODE_CONTROLLER, onChange = { on ->
                    mode = if (on) SessionPrefs.RECEIVER_MODE_CONTROLLER else SessionPrefs.RECEIVER_MODE_OFF
                    SessionPrefs.setReceiverMode(ctx, mode)
                })
            }
            SettingsRow(
                label = "Receiver — show another phone's game",
                hint = "This device finds a controller phone and shows its game below. No game runs on this device.",
            ) {
                ToggleSwitch(checked = mode == SessionPrefs.RECEIVER_MODE_RECEIVER, onChange = { on ->
                    mode = if (on) SessionPrefs.RECEIVER_MODE_RECEIVER else SessionPrefs.RECEIVER_MODE_OFF
                    SessionPrefs.setReceiverMode(ctx, mode)
                    if (!on) stopReceiver()
                })
            }
            SettingsRow(
                label = "Gamepad — second controller for two-player games",
                hint = "This phone joins a controller phone as player 1 or 2 — its touch pad, or a gamepad plugged into this phone, drives the chosen player. Pick the role below. Watch the game on the TV or a receiver.",
            ) {
                ToggleSwitch(checked = mode == SessionPrefs.RECEIVER_MODE_GAMEPAD, onChange = { on ->
                    mode = if (on) SessionPrefs.RECEIVER_MODE_GAMEPAD else SessionPrefs.RECEIVER_MODE_OFF
                    SessionPrefs.setReceiverMode(ctx, mode)
                    if (!on) stopPad()
                })
            }
        }

        // ── Receiver ──────────────────────────────────────────────
        if (mode == SessionPrefs.RECEIVER_MODE_RECEIVER) {
            SettingsGroup("Receiver") {
                SettingsRow(
                    label = stringResource(R.string.receiver_transport_wifi),
                    hint = "Find the controller phone over Wi-Fi Direct.",
                ) {
                    ToggleSwitch(checked = !useUsb, onChange = { on -> if (on) switchTransport(false) })
                }
                SettingsRow(
                    label = stringResource(R.string.receiver_transport_usb),
                    hint = "Play over a USB cable instead of Wi-Fi.",
                ) {
                    ToggleSwitch(checked = useUsb, onChange = { on -> if (on) switchTransport(true) })
                }
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (useUsb) {
                        Text(
                            "Connect the cable, then turn on USB tethering on the game phone (Settings → Hotspot → " +
                            "USB tethering — the app cannot turn it on itself). This tablet must be the USB host: with a " +
                            "C-to-C cable the roles negotiate — if it does not connect, switch \"USB controlled by\" to this " +
                            "device, or use a C-to-A cable + A-to-C adapter, which fixes this device as host.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    AndroidView(
                        factory = { context ->
                            SurfaceView(context).apply {
                                holder.addCallback(object : SurfaceHolder.Callback {
                                    override fun surfaceCreated(holder: SurfaceHolder) {
                                        surface = holder.surface
                                    }

                                    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit
                                    override fun surfaceDestroyed(holder: SurfaceHolder) {
                                        surface = null
                                    }
                                })
                            }
                        },
                        modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
                    )
                    Text(
                        status,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "Frames decoded: $frames" + if (firstFrameMs >= 0) " — first frame after ${firstFrameMs}ms" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Actions {
                        if (receiverRunning) {
                            SecondaryButton("Stop", compact = true) { stopReceiver() }
                        } else {
                            SecondaryButton("Start", compact = true) { if (useUsb) startReceiver() else ensurePermissionsAndStart() }
                        }
                    }
                }
            }
        }

        // ── Gamepad ───────────────────────────────────────────────
        if (mode == SessionPrefs.RECEIVER_MODE_GAMEPAD) {
            SettingsGroup("Gamepad") {
                SettingsRow(
                    label = stringResource(R.string.receiver_transport_wifi),
                    hint = "Find the controller phone over Wi-Fi Direct.",
                ) {
                    ToggleSwitch(checked = !useUsb, onChange = { on -> if (on) switchTransport(false) })
                }
                SettingsRow(
                    label = stringResource(R.string.receiver_transport_usb),
                    hint = "Join over a USB cable instead of Wi-Fi.",
                ) {
                    ToggleSwitch(checked = useUsb, onChange = { on -> if (on) switchTransport(true) })
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
                    if (useUsb) {
                        Text(
                            "Connect the cable, then turn on USB tethering on the game phone (Settings → Hotspot → " +
                            "USB tethering — the app cannot turn it on itself). This phone must be the USB host: with a " +
                            "C-to-C cable the roles negotiate — if it does not connect, switch \"USB controlled by\" to this " +
                            "device, or use a C-to-A cable + A-to-C adapter, which fixes this device as host.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (padBridge != null) {
                        AndroidView(
                            factory = { context -> OnScreenControls(context, padBridge) },
                            modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
                        )
                    }
                    Text(
                        padStatus,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Actions {
                        if (padRunning) {
                            SecondaryButton("Stop", compact = true) { stopPad() }
                        } else {
                            SecondaryButton("Start", compact = true) { if (useUsb) startPad() else ensurePermissionsAndStartPad() }
                        }
                    }
                }
            }
        }

        // ── TV diagnostics log ────────────────────────────────────
        SettingsGroup("TV diagnostics log") {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "The stream records here: group up, server started, first frame, and any start failure. " +
                    "Check it if the receiver stays black.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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
