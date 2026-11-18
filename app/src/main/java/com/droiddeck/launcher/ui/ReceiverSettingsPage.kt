package com.droiddeck.launcher.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.DesktopWindows
import androidx.compose.material.icons.outlined.ScreenShare
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.droiddeck.launcher.R
import com.droiddeck.launcher.core.privileged.TvDiagnosticsLog
import com.droiddeck.launcher.input.OnScreenControls
import com.droiddeck.launcher.input.PadBridge
import com.droiddeck.launcher.input.PadState
import com.droiddeck.launcher.session.ControllerStream
import com.droiddeck.launcher.session.GamepadInput
import com.droiddeck.launcher.session.P2pPadClient
import com.droiddeck.launcher.session.SessionPrefs
import com.droiddeck.launcher.session.TvStreamReceiver
import com.droiddeck.launcher.session.UsbTether
import com.droiddeck.launcher.session.discoverNsdController
import java.io.File
import kotlinx.coroutines.delay

// ─────────────────────────────────────────────────────────────
//  ReceiverSettingsPage – Receiver / Controller mode
// ─────────────────────────────────────────────────────────────

@Composable
fun ReceiverSettingsPage(onBack: () -> Unit, onGoToGames: (() -> Unit)? = null) {
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
    var recvRef by remember { mutableStateOf<TvStreamReceiver?>(null) }

    var padRunning by remember { mutableStateOf(false) }
    var padStatus by remember { mutableStateOf("Idle — pick Gamepad below, then Start.") }
    var padRef by remember { mutableStateOf<P2pPadClient?>(null) }
    var gamepadSlot by remember { mutableStateOf(1) }
    var useUsb by remember { mutableStateOf(false) }

    var receiverSendInput by remember { mutableStateOf(SessionPrefs.receiverSendInput(ctx)) }
    var receiverInputRunning by remember { mutableStateOf(false) }
    var receiverInputStatus by remember { mutableStateOf("") }
    var receiverPadRef by remember { mutableStateOf<P2pPadClient?>(null) }

    var prepStatus by remember { mutableStateOf(ControllerStream.status) }
    var prepGroup by remember { mutableStateOf<String?>(ControllerStream.groupName) }
    var prepExisting by remember { mutableStateOf(ControllerStream.usedExistingGroup) }
    var prepReason by remember { mutableStateOf<String?>(ControllerStream.failureReason) }

    // Local touch pad: shared by the Gamepad section and the Receiver
    // section's input. The sections are exclusive (one mode at a time), so
    // only one of them uses it at a time; no session runs on this device.
    val padBridge = remember(mode, receiverSendInput) {
        if (mode == SessionPrefs.RECEIVER_MODE_GAMEPAD ||
            (mode == SessionPrefs.RECEIVER_MODE_RECEIVER && receiverSendInput)
        ) {
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
        runCatching { padBridge?.stop() }
        padRef = null
    }

    fun stopReceiverInput() {
        if (!receiverInputRunning) return
        receiverInputRunning = false
        GamepadInput.clear()
        runCatching { padBridge?.setTouchRoute(null) }
        runCatching { receiverPadRef?.close() }
        runCatching { padBridge?.stop() }
        receiverPadRef = null
    }

    fun startReceiverInput() {
        if (receiverInputRunning) return
        receiverInputRunning = true
        receiverInputStatus = "Connecting input…"
        Thread({
            if (useUsb) {
                val host = UsbTether.systemGateway() ?: UsbTether.DEFAULT_GATEWAY
                val client = P2pPadClient(gamepadSlot)
                if (!client.connect(host, UsbTether.USB_PAD_PORT)) {
                    receiverInputStatus = "Could not reach the controller over USB — check the cable and USB tethering on the game phone."
                    receiverInputRunning = false
                    return@Thread
                }
                receiverPadRef = client
                padBridge?.let { GamepadInput.install(it, client) }
                receiverInputStatus = "Connected as player ${gamepadSlot + 1} over USB — use the touch pad below."
                padBridge?.setTouchRoute(object : PadBridge.TouchRoute {
                    override fun active() = receiverInputRunning
                    override fun accept(state: PadState) = GamepadInput.onTouchState(state)
                })
                return@Thread
            }
            if (!receiverRunning) {
                receiverInputStatus = "Start the video first."
                receiverInputRunning = false
                return@Thread
            }
            // The video receiver resolved the controller over NSD; its host is
            // the pad host too (the pad server always binds the fixed port).
            var waited = 0
            var host: String? = null
            while (receiverInputRunning && waited < 10000) {
                host = recvRef?.resolvedEndpoint?.first
                if (host != null) break
                runCatching { Thread.sleep(200) }
                waited += 200
            }
            if (!receiverInputRunning) return@Thread
            if (host == null) {
                receiverInputStatus = "This controller has no gamepad input — start a game on the controller phone with Controller mode on."
                receiverInputRunning = false
                return@Thread
            }
            val client = P2pPadClient(gamepadSlot)
            if (!client.connect(host, UsbTether.USB_PAD_PORT)) {
                receiverInputStatus = "Could not reach the controller's gamepad input."
                receiverInputRunning = false
                return@Thread
            }
            receiverPadRef = client
            padBridge?.let { GamepadInput.install(it, client) }
            receiverInputStatus = "Connected as player ${gamepadSlot + 1} — use the touch pad below."
            padBridge?.setTouchRoute(object : PadBridge.TouchRoute {
                override fun active() = receiverInputRunning
                override fun accept(state: PadState) = GamepadInput.onTouchState(state)
            })
        }, "receiver-input-start").start()
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
            val endpoint = discoverNsdController(ctx, 15_000)
            if (!padRunning) return@Thread
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
        stopReceiverInput()
        runCatching { recvRef?.stop() }
        recvRef = null
    }

    fun switchTransport(usb: Boolean) {
        if (usb == useUsb) return
        if (receiverRunning) stopReceiver()
        if (padRunning) stopPad()
        if (receiverInputRunning) stopReceiverInput()
        useUsb = usb
    }

    // Per-session choices (transport, player role, control-from-device)
    // are page-local; Start persists the control choice so the next
    // session defaults to the last one.
    fun saveReceiverChoices() {
        SessionPrefs.setReceiverSendInput(ctx, receiverSendInput)
    }

    // Tapping a role card selects it (tapping the selected one turns the
    // role off) and stops the previous role's activity.
    fun selectMode(newMode: String) {
        val next = if (newMode == mode) SessionPrefs.RECEIVER_MODE_OFF else newMode
        if (mode == SessionPrefs.RECEIVER_MODE_RECEIVER && next != SessionPrefs.RECEIVER_MODE_RECEIVER) stopReceiver()
        if (mode == SessionPrefs.RECEIVER_MODE_GAMEPAD && next != SessionPrefs.RECEIVER_MODE_GAMEPAD) stopPad()
        if (mode == SessionPrefs.RECEIVER_MODE_CONTROLLER && next != SessionPrefs.RECEIVER_MODE_CONTROLLER) ControllerStream.stop()
        mode = next
        SessionPrefs.setReceiverMode(ctx, mode)
    }

    fun startReceiver() {
        if (receiverRunning) return
        saveReceiverChoices()
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
            // Plain NSD discovery is the only path: P2P DNS-SD is dead on this
            // phone, and its discoverPeers scan is the prime suspect for
            // breaking the tablet-to-phone link. The stream receiver resolves
            // the controller host and port over NSD itself.
            val surf = surface
            if (surf == null) {
                status = "No video surface yet — wait a moment and try again."
                receiverRunning = false
                return@Thread
            }
            val recv = TvStreamReceiver(ctx, surf) { status = it }
            recvRef = recv
            status = "Searching for a controller…"
            recv.start()
        }, "receiver-start").start()
    }

    fun startPrepare() {
        if (ControllerStream.isPrepared) return
        Thread({
            ControllerStream.prepare(ctx)
        }, "controller-prepare").start()
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
            when (mode) {
                SessionPrefs.RECEIVER_MODE_GAMEPAD -> startPad()
                SessionPrefs.RECEIVER_MODE_CONTROLLER -> startPrepare()
                else -> startReceiver()
            }
        } else if (mode == SessionPrefs.RECEIVER_MODE_GAMEPAD) {
            padStatus = "Wi-Fi Direct needs location permission to find the controller."
        } else {
            status = "Wi-Fi Direct needs location permission to find the controller."
        }
    }

    fun ensurePermissionsAndPrepare() {
        val needed = missingP2pPermissions()
        if (needed.isEmpty()) startPrepare() else permissionLauncher.launch(needed)
    }

    fun ensurePermissionsAndStart() {
        val needed = missingP2pPermissions()
        if (needed.isEmpty()) startReceiver() else permissionLauncher.launch(needed)
    }

    fun ensurePermissionsAndStartPad() {
        val needed = missingP2pPermissions()
        if (needed.isEmpty()) startPad() else permissionLauncher.launch(needed)
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

    LaunchedEffect(mode) {
        while (mode == SessionPrefs.RECEIVER_MODE_CONTROLLER) {
            prepStatus = ControllerStream.status
            prepGroup = ControllerStream.groupName
            prepExisting = ControllerStream.usedExistingGroup
            prepReason = ControllerStream.failureReason
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

    // The receiver input follows the video: it runs only while this device
    // is in Receiver mode, the toggle is on, and the video is connected.
    LaunchedEffect(receiverRunning, receiverSendInput, mode) {
        if (mode == SessionPrefs.RECEIVER_MODE_RECEIVER && receiverSendInput && receiverRunning) {
            if (!receiverInputRunning) {
                saveReceiverChoices()
                startReceiverInput()
            }
        } else if (receiverInputRunning) {
            saveReceiverChoices()
            stopReceiverInput()
        }
    }

    LaunchedEffect(receiverInputRunning) {
        while (receiverInputRunning) {
            if (receiverPadRef != null && receiverPadRef?.connected?.get() == false) {
                receiverInputStatus = "Lost the controller — input stopped."
                stopReceiverInput()
            }
            delay(1000)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            stopReceiver()
            stopPad()
            // The prepared controller stream is not stopped here: it must survive
            // leaving this page for Games, and ends with the session instead.
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
                    "One device runs the game, another shows it. Controller streams the game over its own " +
                    "Wi-Fi Direct group, while the phone keeps its router Wi-Fi for the game itself. Receiver " +
                    "finds a controller phone and shows its game full-screen — turn on the control toggle below " +
                    "to also drive the game from here. Gamepad joins a controller phone as player 1 or 2. " +
                    "The Vestel TV cannot be a receiver (it runs Vestel Smart OS, not Android).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // ── Your role ─────────────────────────────────────────────
        SettingsGroup("Your role") {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                RoleCard(
                    icon = Icons.Outlined.ScreenShare,
                    title = stringResource(R.string.receiver_role_controller),
                    hint = stringResource(R.string.receiver_role_controller_hint),
                    selected = mode == SessionPrefs.RECEIVER_MODE_CONTROLLER,
                ) { selectMode(SessionPrefs.RECEIVER_MODE_CONTROLLER) }
                RoleCard(
                    icon = Icons.Outlined.DesktopWindows,
                    title = stringResource(R.string.receiver_role_receiver),
                    hint = stringResource(R.string.receiver_role_receiver_hint),
                    selected = mode == SessionPrefs.RECEIVER_MODE_RECEIVER,
                ) { selectMode(SessionPrefs.RECEIVER_MODE_RECEIVER) }
                RoleCard(
                    icon = Icons.Outlined.SportsEsports,
                    title = stringResource(R.string.receiver_role_gamepad),
                    hint = stringResource(R.string.receiver_role_gamepad_hint),
                    selected = mode == SessionPrefs.RECEIVER_MODE_GAMEPAD,
                ) { selectMode(SessionPrefs.RECEIVER_MODE_GAMEPAD) }
            }
        }

        // ── Controller ────────────────────────────────────────────
        if (mode == SessionPrefs.RECEIVER_MODE_CONTROLLER) {
            SettingsGroup("Controller") {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        when (prepStatus) {
                            ControllerStream.Status.IDLE -> stringResource(R.string.receiver_prepare_hint)
                            ControllerStream.Status.FORMING -> stringResource(R.string.receiver_prepare_forming)
                            ControllerStream.Status.READY -> if (prepExisting) stringResource(R.string.receiver_prepare_ready_existing) else stringResource(R.string.receiver_prepare_ready, prepGroup ?: "DroidDeck-TV")
                            ControllerStream.Status.FAILED -> stringResource(R.string.receiver_prepare_failed, prepReason ?: "")
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (prepStatus == ControllerStream.Status.IDLE || prepStatus == ControllerStream.Status.FAILED) {
                        PrimaryButton(stringResource(R.string.receiver_prepare), fill = true, large = true, modifier = Modifier.fillMaxWidth()) { ensurePermissionsAndPrepare() }
                    } else {
                        SecondaryButton(stringResource(R.string.receiver_prepare_stop), modifier = Modifier.fillMaxWidth()) { ControllerStream.stop() }
                    }
                    if (onGoToGames != null) {
                        PrimaryButton("Start a game", fill = true, large = true, modifier = Modifier.fillMaxWidth()) { onGoToGames() }
                    } else {
                        Text(
                            "Prepare display above, then start any game from Games.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
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
                SettingsRow(
                    label = stringResource(R.string.receiver_send_input),
                    hint = "Show the touch pad below the video and drive the game from this device — touch pad, plugged-in gamepad, keyboard or mouse.",
                ) {
                    ToggleSwitch(checked = receiverSendInput, onChange = { on ->
                        receiverSendInput = on
                    })
                }
                if (receiverSendInput) {
                    SettingsRow(
                        label = "Player 1 — this device is the first controller",
                        hint = "A gamepad plugged into this device drives player 1.",
                    ) {
                        ToggleSwitch(checked = gamepadSlot == 0, onChange = { on ->
                            if (on) {
                                val wasRunning = receiverInputRunning
                                if (wasRunning) stopReceiverInput()
                                gamepadSlot = 0
                                if (wasRunning && receiverRunning) startReceiverInput()
                            }
                        })
                    }
                    SettingsRow(
                        label = "Player 2 — this device is the second controller",
                        hint = "This device's touch pad drives player 2.",
                    ) {
                        ToggleSwitch(checked = gamepadSlot == 1, onChange = { on ->
                            if (on) {
                                val wasRunning = receiverInputRunning
                                if (wasRunning) stopReceiverInput()
                                gamepadSlot = 1
                                if (wasRunning && receiverRunning) startReceiverInput()
                            }
                        })
                    }
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
                    if (receiverRunning) {
                        SecondaryButton("Stop", modifier = Modifier.fillMaxWidth()) { stopReceiver() }
                    } else {
                        PrimaryButton("Start", fill = true, large = true, modifier = Modifier.fillMaxWidth()) { if (useUsb) startReceiver() else ensurePermissionsAndStart() }
                    }
                    if (receiverSendInput && padBridge != null) {
                        AndroidView(
                            factory = { context -> OnScreenControls(context, padBridge) },
                            modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
                        )
                        if (receiverInputStatus.isNotEmpty()) {
                            Text(
                                receiverInputStatus,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
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
                    if (padRunning) {
                        SecondaryButton("Stop", modifier = Modifier.fillMaxWidth()) { stopPad() }
                    } else {
                        PrimaryButton("Start", fill = true, large = true, modifier = Modifier.fillMaxWidth()) { if (useUsb) startPad() else ensurePermissionsAndStartPad() }
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

@Composable
private fun RoleCard(icon: ImageVector, title: String, hint: String, selected: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    Row(
        Modifier.fillMaxWidth().clip(Shape14)
            .background(if (selected) colors.primary.copy(alpha = 0.12f) else colors.surface)
            .border(if (selected) 2.dp else 1.dp, if (selected) colors.primary else pal.line, Shape14)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, contentDescription = null, tint = colors.primary, modifier = Modifier.size(22.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = colors.onBackground)
            Text(hint, fontSize = 12.5.sp, color = colors.onSurfaceVariant)
        }
        Icon(
            if (selected) Icons.Outlined.CheckCircle else Icons.Outlined.ChevronRight,
            contentDescription = null,
            tint = if (selected) colors.primary else colors.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
    }
}
