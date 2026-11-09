package com.droiddeck.launcher.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.droiddeck.launcher.R
import com.droiddeck.launcher.core.ConnectionCardState
import com.droiddeck.launcher.core.ConnectionCardStateResolver
import com.droiddeck.launcher.core.privileged.PrivilegedBridge
import com.droiddeck.launcher.core.WirelessAdbFix
import com.droiddeck.launcher.core.SplitScreenWindowing
import com.droiddeck.launcher.core.WirelessAdbPairingService
import com.droiddeck.launcher.core.ConnectionFailure
import com.droiddeck.launcher.session.SessionPrefs
import com.droiddeck.launcher.core.privileged.PrivilegedMode
import com.droiddeck.launcher.core.privileged.PrivilegedStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ─────────────────────────────────────────────────────────────
//  PrivilegedEnginePage  –  full settings page
// ─────────────────────────────────────────────────────────────

@Composable
fun PrivilegedEnginePage(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val status by PrivilegedBridge.statusFlow.collectAsState()
    var refreshing by remember { mutableStateOf(false) }

    // Same three checks the Overview connection card polls, same 1.5 s
    // rhythm: without them the banner below would still say Active
    // after wireless debugging is turned off in Settings.
    val engineAuthRejected by PrivilegedBridge.authRejectedFlow.collectAsState()
    val engineDevOn = remember { mutableStateOf(isDevOptionsOn(ctx)) }
    val engineWirelessOn = remember { mutableStateOf(isWirelessAdbOn(ctx)) }
    val enginePaired = remember { mutableStateOf(WirelessAdbFix.isPaired(ctx)) }
    LaunchedEffect(Unit) {
        while (isActive) {
            engineDevOn.value = isDevOptionsOn(ctx)
            engineWirelessOn.value = isWirelessAdbOn(ctx)
            enginePaired.value = WirelessAdbFix.isPaired(ctx)
            delay(1_500)
        }
    }
    val engineState = ConnectionCardStateResolver.resolve(
        daemonActive = status.isActive,
        authRejected = engineAuthRejected,
        devOptionsOn = engineDevOn.value,
        wirelessAdbOn = engineWirelessOn.value,
        paired = enginePaired.value,
    )

    // Auto-refresh every 4 seconds in background
    LaunchedEffect(Unit) {
        while (isActive) {
            withContext(Dispatchers.IO) { PrivilegedBridge.refresh(caller = "engine-page") }
            delay(4_000)
        }
    }

    SettingsPage(
        host = rememberMenuHost(),
        title = "Privileged Engine",
        eyebrow = "System Access & Security",
        onBack = onBack,
        scrollContent = true,
    ) {
        // ── Header status banner ────────────────────────────────────
        StatusBanner(status, engineState, refreshing) {
            refreshing = true
            withContext(Dispatchers.IO) { PrivilegedBridge.refresh(caller = "engine-page") }
            refreshing = false
        }
        Spacer(Modifier.height(12.dp))

        // ── What is this? ─────────────────────────────────────────
        SettingsGroup("What is Privileged Engine?") {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                EngineFeatureRow(
                    Icons.Outlined.Block,
                    "Phantom Process Killer Bypass",
                    "Prevents Android from killing Steam/Proton mid-game. Raises the process limit from 32 to unlimited.",
                )
                EngineFeatureRow(
                    Icons.Outlined.Speed,
                    "Game Performance Boost",
                    "Boosts game performance during sessions, with fixed-performance mode when aggressive mode is on.",
                )
                EngineFeatureRow(
                    Icons.Outlined.Security,
                    "Automatic Permission Grant",
                    "Grants DroidDeck WRITE_SECURE_SETTINGS, DUMP, and PACKAGE_USAGE_STATS at runtime.",
                )
                EngineFeatureRow(
                    Icons.Outlined.Bolt,
                    "Fixed Performance Mode",
                    "Locks the SoC into maximum-performance state during gaming sessions.",
                )
            }
        }

        // ── Embedded Daemon section ─────────────────────────────────
        SettingsGroup("Embedded Daemon (Recommended)") {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "The daemon is a tiny server bundled inside DroidDeck's APK that runs under " +
                    "ADB shell (UID 2000). Activate it once with one command — it then works " +
                    "automatically every time DroidDeck starts, with no external apps needed.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (status.mode == PrivilegedMode.EMBEDDED_DAEMON) {
                    DaemonActiveCard(status)
                } else {
                    DaemonActivationCard(ctx)
                }
            }
        }

        // ── Shizuku section ─────────────────────────────────────────
        SettingsGroup("Shizuku Integration") {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "If you have Shizuku installed and running, DroidDeck connects to it " +
                    "automatically — no extra setup needed. Shizuku provides equivalent " +
                    "privileged access via its own secure IPC channel.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ShizukuStatusRow(status)
                Text(
                    "Note: The Embedded Daemon above is preferred and takes priority over Shizuku " +
                    "if both are active.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                )
            }
        }

        // ── Quick Actions ────────────────────────────────────────────
        if (status.isActive) {
            SettingsGroup("Quick Actions") {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    QuickActionButton(
                        Icons.Outlined.Block, "Disable Phantom Limiter Now",
                        "Apply max_phantom_processes=2147483647 immediately",
                    ) {
                        SessionPrefs.setPhantomDisableEnabled(ctx, true)
                        withContext(Dispatchers.IO) { PrivilegedBridge.disablePhantomProcesses(ctx) }
                        Toast.makeText(ctx, "Phantom process limit disabled", Toast.LENGTH_SHORT).show()
                    }
                    QuickActionButton(
                        Icons.Outlined.VpnKey, "Grant DroidDeck Permissions",
                        "WRITE_SECURE_SETTINGS, DUMP, PACKAGE_USAGE_STATS",
                    ) {
                        withContext(Dispatchers.IO) { PrivilegedBridge.grantDroidDeckPermissions(ctx) }
                        Toast.makeText(ctx, "Permissions granted", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        // ── Technical Details ────────────────────────────────────────
        var showDetails by remember { mutableStateOf(false) }
        SettingsGroup("Technical Details") {
            Row(
                Modifier.fillMaxWidth().clickable { showDetails = !showDetails }.padding(14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Advanced information", style = MaterialTheme.typography.bodyMedium)
                Text(if (showDetails) "▲" else "▼", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            AnimatedVisibility(showDetails,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    TechRow("Active mode", status.shortLabel)
                    if (status.daemonPid > 0) TechRow("Daemon PID", "${status.daemonPid}")
                    if (status.daemonUid > 0) TechRow("Daemon UID", "${status.daemonUid}")
                    if (status.shizukuVersion > 0) TechRow("Shizuku API version", "${status.shizukuVersion}")
                    TechRow("Daemon port", "127.0.0.1:28751")
                    TechRow("Protocol", "Base64-encoded TCP loopback")
                    TechRow("ADB UID", "2000 (shell)")
                    Spacer(Modifier.height(4.dp))
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────
//  Sub-composables
// ─────────────────────────────────────────────────────────────

@Composable
private fun StatusBanner(status: PrivilegedStatus, cardState: ConnectionCardState, refreshing: Boolean, onRefresh: suspend () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val good = status.isActive
    val authRejected by PrivilegedBridge.authRejectedFlow.collectAsState()
    val degraded = ConnectionCardStateResolver.isDegraded(cardState)
    val bannerGood = good && !authRejected && !degraded
    val animColor by animateColorAsState(
        if (bannerGood) pal.good else if (degraded) AttentionAmber else MaterialTheme.colorScheme.error,
        label = "bannerColor",
    )
    val scope = rememberCoroutineScope()

    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(animColor.copy(alpha = 0.13f))
            .border(1.dp, animColor.copy(alpha = 0.4f), RoundedCornerShape(14.dp))
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                Modifier.size(44.dp).clip(CircleShape).background(animColor.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center,
            ) {
                if (refreshing) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = animColor)
                } else {
                    Text(if (degraded) "⚠️" else status.statusIcon, fontSize = 20.sp)
                }
            }
            Column(Modifier.weight(1f)) {
                Text(
                    if (good && authRejected) "Daemon responding — auth rejected"
                    else if (degraded && cardState == ConnectionCardState.ACTIVE_DEV_OFF) "Active — developer options off"
                    else if (degraded) "Active — wireless debugging off"
                    else if (good) "Active — ${status.modeLabel}"
                    else "Privileged Engine Inactive",
                    fontWeight = FontWeight.Bold, fontSize = 15.sp, color = colors.onBackground,
                )
                Text(
                    if (good && authRejected) "The daemon answers but rejects commands: pairing succeeded, but the key is rejected on this port. Restart wireless debugging once; the app retries when the port changes."
                    else if (degraded && cardState == ConnectionCardState.ACTIVE_DEV_OFF) stringResource(R.string.connection_card_warn_dev_off)
                    else if (degraded) stringResource(R.string.connection_card_warn_wireless_off)
                    else if (good) "Full system access granted · phantom limit disabled"
                    else "One ADB command away from full activation",
                    fontSize = 12.sp,
                    color = if (degraded) AttentionAmber else colors.onSurfaceVariant,
                )
            }
            IconButton(onClick = { scope.launch { onRefresh() } }) {
                Icon(Icons.Outlined.Refresh, contentDescription = "Refresh", tint = animColor)
            }
        }
    }
}

@Composable
private fun DaemonActiveCard(status: PrivilegedStatus) {
    val pal = LocalPalette.current
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(pal.good.copy(alpha = 0.1f))
            .border(1.dp, pal.good.copy(alpha = 0.3f), RoundedCornerShape(10.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Outlined.CheckCircle, contentDescription = null, tint = pal.good, modifier = Modifier.size(18.dp))
            Text("Daemon Running", fontWeight = FontWeight.SemiBold, color = pal.good)
        }
        Text("PID ${status.daemonPid}  ·  UID ${status.daemonUid}  ·  port 28751",
            fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("Phantom process limit disabled  ·  Auto-reapplied on every session",
            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

internal fun isDevOptionsOn(ctx: Context): Boolean = runCatching {
    android.provider.Settings.Global.getInt(
        ctx.contentResolver,
        android.provider.Settings.Global.DEVELOPMENT_SETTINGS_ENABLED,
        0
    ) != 0
}.getOrDefault(false)

internal fun isWirelessAdbOn(ctx: Context): Boolean = runCatching {
    android.provider.Settings.Global.getInt(
        ctx.contentResolver,
        "adb_wifi_enabled",
        0
    ) != 0
}.getOrDefault(false)

internal fun openDevOptions(ctx: Context) {
    try {
        ctx.startActivity(WirelessAdbFix.wirelessDebuggingIntent())
    } catch (_: Exception) {
        try {
            ctx.startActivity(Intent(android.provider.Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        } catch (_: Exception) {}
    }
}

internal fun openAboutPhone(ctx: Context) {
    val intent = Intent(android.provider.Settings.ACTION_DEVICE_INFO_SETTINGS).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    try {
        ctx.startActivity(intent)
    } catch (_: Exception) {
        try {
            ctx.startActivity(Intent(android.provider.Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {}
    }
}

/**
 * The single pairing entry: starts the pairing service (the quiet
 * notification engine) and opens Developer options beside the app
 * (split-screen), best-effort — the system may open it fullscreen, and
 * the split ratio is the user's to drag. The in-app panel is the code
 * entry, so no dialog fallback is needed when notifications are off:
 * the code still goes in the panel.
 */
internal fun startUnifiedPairing(ctx: Context) {
    if (WirelessAdbPairingService.notificationsEnabled(ctx)) {
        Toast.makeText(ctx, ctx.getString(R.string.connection_card_toast_watch_notification), Toast.LENGTH_LONG).show()
    } else {
        Toast.makeText(ctx, ctx.getString(R.string.connection_card_toast_notifications_off), Toast.LENGTH_LONG).show()
    }
    WirelessAdbPairingService.start(ctx)
    val intent = WirelessAdbFix.wirelessDebuggingAdjacentIntent()
    val options = SplitScreenWindowing.splitPrimaryOptions()
    if (options != null) ctx.startActivity(intent, options) else ctx.startActivity(intent)
}

@Composable
internal fun SecureGrantConsentDialog(
    onApprove: () -> Unit,
    onDecline: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.secure_grant_title), fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.secure_grant_body), fontSize = 12.sp)
                Text(stringResource(R.string.secure_grant_decline_note), fontSize = 12.sp)
            }
        },
        confirmButton = {
            Button(onClick = onApprove) { Text(stringResource(R.string.secure_grant_allow)) }
        },
        dismissButton = {
            TextButton(onClick = onDecline) { Text(stringResource(R.string.secure_grant_decline)) }
        },
    )
}

@Composable
private fun DaemonActivationCard(ctx: Context) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val cmd = PrivilegedBridge.getStarterAdbCommand(ctx)
    val scope = rememberCoroutineScope()
    var startingAdb by remember { mutableStateOf(false) }
    var oneTapFailed by remember { mutableStateOf(false) }
    var grantConsentPending by remember { mutableStateOf(false) }
    var showResetConfirm by remember { mutableStateOf(false) }
    val lastFailure by PrivilegedBridge.lastDaemonFailureFlow.collectAsState()
    val pairingStage by WirelessAdbPairingService.stage.collectAsState()

    val devOptionsOn = remember { mutableStateOf(isDevOptionsOn(ctx)) }
    val wirelessAdbOn = remember { mutableStateOf(isWirelessAdbOn(ctx)) }
    val isPaired = remember { mutableStateOf(com.droiddeck.launcher.core.WirelessAdbFix.isPaired(ctx)) }

    // Re-check settings state periodically
    LaunchedEffect(Unit) {
        while (isActive) {
            devOptionsOn.value = isDevOptionsOn(ctx)
            wirelessAdbOn.value = isWirelessAdbOn(ctx)
            isPaired.value = com.droiddeck.launcher.core.WirelessAdbFix.isPaired(ctx)
            delay(1_500)
        }
    }

    // The self-grant runs only with the user's consent: the first new
    // pairing asks once; a decline still pairs, but the automatic
    // wireless restart stays unavailable.
    fun runPairing() {
        startUnifiedPairing(ctx)
    }

    fun requestPairing() {
        if (WirelessAdbFix.secureGrantDecision(ctx) == null) grantConsentPending = true
        else runPairing()
    }

    if (grantConsentPending) {
        SecureGrantConsentDialog(
            onApprove = {
                WirelessAdbFix.setSecureGrantDecision(ctx, true)
                grantConsentPending = false
                runPairing()
            },
            onDecline = {
                WirelessAdbFix.setSecureGrantDecision(ctx, false)
                grantConsentPending = false
                runPairing()
            },
            onDismiss = { grantConsentPending = false },
        )
    }

    if (showResetConfirm) {
        PairingResetConfirmDialog(
            onConfirm = {
                showResetConfirm = false
                WirelessAdbFix.resetPairingCompletely(ctx)
                isPaired.value = false
                oneTapFailed = false
                PrivilegedBridge.clearDaemonFailure()
                PrivilegedBridge.clearAuthFailedPort()
                PrivilegedBridge.clearAuthRejected()
                Toast.makeText(ctx, ctx.getString(R.string.pairing_reset_toast), Toast.LENGTH_SHORT).show()
            },
            onDismiss = { showResetConfirm = false },
        )
    }

    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(colors.surface)
            .border(1.dp, colors.outlineVariant, RoundedCornerShape(10.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Smart Setup Assistant (Like Shizuku):", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = colors.onBackground)

        // ── Step 1: Developer Options ──
        if (!devOptionsOn.value) {
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(colors.error.copy(alpha = 0.10f))
                    .border(1.dp, colors.error.copy(alpha = 0.35f), RoundedCornerShape(8.dp)).padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text("⚠️ الخطوة 1: تفعيل خيارات المطور (Developer Options)", fontWeight = FontWeight.Bold, color = colors.error, fontSize = 13.sp)
                Text(
                    "خيارات المطور غير مفعلة على جهازك. اضغط أدناه للانتقال إلى 'حول الهاتف' (About Phone) -> 'معلومات البرنامج' (Software information) -> واضغط 7 مرات متتالية على 'رقم الإصدار' (Build number).",
                    fontSize = 12.sp, color = colors.onBackground
                )
                PrimaryButton("Open About Phone (Step 1)", compact = true) { openAboutPhone(ctx) }
            }
        } else if (!wirelessAdbOn.value) {
            // ── Step 2: Wireless Debugging Toggle ──
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(colors.tertiary.copy(alpha = 0.12f))
                    .border(1.dp, colors.tertiary.copy(alpha = 0.40f), RoundedCornerShape(8.dp)).padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text("⚠️ الخطوة 2: تشغيل تصحيح الأخطاء اللاسلكي (Wireless Debugging)", fontWeight = FontWeight.Bold, color = colors.onBackground, fontSize = 13.sp)
                Text(
                    "خيارات المطور مفعلة! اضغط أدناه للانتقال مباشرة إلى 'خيارات المطور' وقم بتفعيل خيار 'تصحيح الأخطاء اللاسلكي' (Wireless debugging) ثم اختر 'سماح دائماً'.",
                    fontSize = 12.sp, color = colors.onSurfaceVariant
                )
                PrimaryButton("Open Developer Options & Turn ON", compact = true) { openDevOptions(ctx) }
            }
        } else if (!isPaired.value) {
            // ── Step 3: Pairing Needed ──
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(colors.primary.copy(alpha = 0.10f))
                    .border(1.dp, colors.primary.copy(alpha = 0.35f), RoundedCornerShape(8.dp)).padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text("🔗 الخطوة 3: الاقتران لأول مرة (Pairing)", fontWeight = FontWeight.Bold, color = colors.primary, fontSize = 13.sp)
                Text(
                    "تصحيح اللاسلكي قيد التشغيل! نحتاج للاقتران لمرة واحدة فقط لحفظ مفاتيح الأمان:\n" +
                    "اضغط «اقتران» — تُفتح الإعدادات بجانب التطبيق، وفي خيارات المطور اضغط 'إقران الجهاز باستخدام رمز إقران' (Pair with pairing code)، ثم أدخل الأرقام الستة في اللوحة أدناه أو في الإشعار:",
                    fontSize = 12.sp, color = colors.onSurfaceVariant
                )
                PrimaryButton(stringResource(R.string.pairing_action_pair), compact = true) { requestPairing() }
                PairingCodePanel(onRetry = { requestPairing() })
            }
        } else {
            // ── Step 4: Ready to Start ──
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(pal.good.copy(alpha = 0.10f))
                    .border(1.dp, pal.good.copy(alpha = 0.35f), RoundedCornerShape(8.dp)).padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("✅ جاهز للتشغيل الفوري (Ready to Start)", fontWeight = FontWeight.Bold, color = pal.good, fontSize = 13.sp)
                Text(
                    "الهاتف مقترن وتصحيح اللاسلكي مفعل! اضغط الزر أدناه لتشغيل خادم الحماية وإلغاء قيود Phantom Process Killer فوراً بلمسة واحدة:",
                    fontSize = 12.sp, color = colors.onSurfaceVariant
                )
                PrimaryButton(if (startingAdb) "Activating..." else "Start Privileged Engine (One-Tap)", compact = true, enabled = !startingAdb) {
                    scope.launch {
                        startingAdb = true
                        oneTapFailed = false
                        PrivilegedBridge.clearDaemonFailure()
                        val ok = withContext(Dispatchers.IO) {
                            com.droiddeck.launcher.core.WirelessAdbFix.startPrivilegedDaemonWithSavedPairing(ctx)
                        }
                        if (ok) {
                            withContext(Dispatchers.IO) { PrivilegedBridge.refresh(caller = "engine-start") }
                            Toast.makeText(ctx, "Privileged Daemon started successfully!", Toast.LENGTH_SHORT).show()
                        } else {
                            oneTapFailed = true
                            Toast.makeText(ctx, "Could not connect — the device rejects the key on this port. Restart wireless debugging once; the app retries when the port changes.", Toast.LENGTH_LONG).show()
                        }
                        startingAdb = false
                    }
                }

                // If One-Tap failed or if user wants to re-pair after reboot
                if (oneTapFailed) {
                    Column(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(colors.error.copy(alpha = 0.12f))
                            .border(1.dp, colors.error.copy(alpha = 0.40f), RoundedCornerShape(6.dp)).padding(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text("⚠️ تعذر الاتصال التلقائي بنقرة واحدة", fontWeight = FontWeight.Bold, color = colors.error, fontSize = 12.sp)
                        Text(
                            "قد يكون منفذ التصحيح تغير بعد إعادة تشغيل الهاتف أو أزال نظام أندرويد الإقران السابق. يمكنك إعادة إدخال رمز إقران جديد فوراً:",
                            fontSize = 11.sp, color = colors.onBackground
                        )
                        lastFailure?.let { failure ->
                            Text(
                                when (failure.category) {
                                    ConnectionFailure.AUTH -> "السبب: نجح الإقران لكن الجهاز يرفض المفتاح على هذا المنفذ. أطفئ التصحيح اللاسلكي وأعد تشغيله مرة واحدة وسيعيد التطبيق المحاولة تلقائياً عند تغيّر المنفذ. إعادة الإقران أو نسيان الهوية لن تفيد."
                                    ConnectionFailure.TLS -> "السبب: فشل تأمين الاتصال. تحقق أن المنفذ المدخل هو منفذ الإقران وليس منفذ الاتصال."
                                    ConnectionFailure.UNREACHABLE -> "السبب: تعذر الوصول. تأكد أن التصحيح اللاسلكي مفعل ثم أعد المحاولة."
                                    ConnectionFailure.UNKNOWN -> "السبب غير محدد. أعد المحاولة، وإن تكرر أرسل سجل التشخيص."
                                },
                                fontSize = 11.sp, color = colors.onBackground
                            )
                            if (failure.tried.isNotEmpty()) {
                                Text("المحاولات: ${failure.tried}", fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = colors.onSurfaceVariant)
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                Box(Modifier.weight(1f)) {
                                    SecondaryButton(stringResource(R.string.connection_card_action_clear), compact = true) {
                                        showResetConfirm = true
                                    }
                                }
                            }
                        }
                        PrimaryButton(stringResource(R.string.pairing_action_pair), compact = true) { requestPairing() }
                    }
                }

                // Fallback action: always accessible
                SecondaryButton(stringResource(R.string.connection_card_action_clear), compact = true) {
                    showResetConfirm = true
                }
                if (pairingStage is WirelessAdbPairingService.Stage.Waiting ||
                    pairingStage is WirelessAdbPairingService.Stage.CodeNeeded ||
                    pairingStage is WirelessAdbPairingService.Stage.Working ||
                    pairingStage is WirelessAdbPairingService.Stage.Failed
                ) {
                    PairingCodePanel(onRetry = { requestPairing() })
                }
            }
        }

        // ── Reboot Explanation ──
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(colors.surfaceVariant.copy(alpha = 0.40f)).padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text("ℹ️ ماذا يحدث عند إطفاء أو إعادة تشغيل الهاتف؟ (Phone Reboot)", fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = colors.onBackground)
            Text(
                "عند إعادة تشغيل الهاتف، يوقف أندرويد عمليات الخلفية وصلاحيات ADB مؤقتاً لحماية النظام.\n" +
                "لكن ملفات الاقتران تظل محفوظة بشكل دائم في الهاتف! بعد إعادة التشغيل، كل ما عليك هو تفعيل 'التصحيح اللاسلكي' وفتح DroidDeck والضغط على 'تشغيل' — سيعمل في ثوانٍ دون الحاجة لكمبيوتر أو كابل إطلاقاً.",
                fontSize = 11.sp, color = colors.onSurfaceVariant,
            )
        }

        // ── Manual Fallback ──
        Text("Alternative — Manual Command via PC / Terminal:", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = colors.onSurfaceVariant)
        Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(colors.surfaceVariant).padding(8.dp),
        ) {
            Text(cmd, fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = colors.onSurfaceVariant, overflow = TextOverflow.Visible)
        }
        SecondaryButton("Copy ADB Command", compact = true) {
            (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                .setPrimaryClip(ClipData.newPlainText("DroidDeck Daemon Command", cmd))
            Toast.makeText(ctx, "Command copied — paste in ADB shell", Toast.LENGTH_SHORT).show()
        }
    }
}

@Composable
private fun ShizukuStatusRow(status: PrivilegedStatus) {
    val pal = LocalPalette.current
    val colors = MaterialTheme.colorScheme
    val shizActive = status.mode == PrivilegedMode.SHIZUKU_APP
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
            .background(if (shizActive) pal.good.copy(0.1f) else colors.surfaceVariant.copy(0.5f))
            .border(1.dp, if (shizActive) pal.good.copy(0.3f) else colors.outlineVariant, RoundedCornerShape(10.dp))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            if (shizActive) Icons.Outlined.CheckCircle else Icons.Outlined.Cancel,
            contentDescription = null,
            tint = if (shizActive) pal.good else colors.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(
                if (shizActive) "Shizuku connected (API v${status.shizukuVersion})" else "Shizuku not detected",
                fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = colors.onBackground,
            )
            Text(
                if (shizActive) "DroidDeck is using Shizuku for privileged commands"
                else "Install Shizuku from Play Store as an alternative to the Embedded Daemon",
                fontSize = 12.sp, color = colors.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun EngineFeatureRow(icon: ImageVector, title: String, detail: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp).padding(top = 1.dp))
        Column {
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = MaterialTheme.colorScheme.onBackground)
            Text(detail, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun QuickActionButton(icon: ImageVector, title: String, subtitle: String, action: suspend () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    var running by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(colors.surfaceVariant.copy(0.4f))
            .clickable(enabled = !running) { scope.launch { running = true; action(); running = false } }
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (running) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        else Icon(icon, contentDescription = null, tint = colors.primary, modifier = Modifier.size(20.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = colors.onBackground)
            Text(subtitle, fontSize = 11.sp, color = colors.onSurfaceVariant)
        }
        Icon(Icons.Outlined.ChevronRight, contentDescription = null, tint = colors.onSurfaceVariant,
            modifier = Modifier.size(16.dp))
    }
}

@Composable
private fun TechRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onBackground)
    }
}
