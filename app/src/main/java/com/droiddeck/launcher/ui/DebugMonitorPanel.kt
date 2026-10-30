package com.droiddeck.launcher.ui

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Debug
import android.os.Process
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.droiddeck.launcher.core.privileged.PrivilegedBridge
import com.droiddeck.launcher.core.privileged.PrivilegedMode
import com.droiddeck.launcher.core.privileged.PrivilegedStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

// ─────────────────────────────────────────────────────────────
//  DebugMonitorPanel  –  live system dashboard
// ─────────────────────────────────────────────────────────────

data class MonitorSnapshot(
    val privilegedStatus: PrivilegedStatus,
    val appPid: Int,
    val appUid: Int,
    val heapMb: Long,
    val nativeHeapMb: Long,
    val availRamMb: Long,
    val totalRamMb: Long,
    val uptime: String,
    val kernelVersion: String,
    val androidVersion: String,
    val abiList: String,
    val logLines: List<String>,
    val timestamp: String,
)

private fun collectSnapshot(ctx: Context, prevLog: List<String>): MonitorSnapshot {
    val am = ctx.getSystemService(ActivityManager::class.java)
    val memInfo = ActivityManager.MemoryInfo().also { am?.getMemoryInfo(it) }
    val uptime = android.os.SystemClock.elapsedRealtime()
    val h = uptime / 3600000; val m = (uptime % 3600000) / 60000; val s = (uptime % 60000) / 1000
    val log = prevLog.toMutableList()
    val ts = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
    log.add("[$ts] Privileged: ${PrivilegedBridge.statusFlow.value.shortLabel}")
    if (log.size > 80) log.subList(0, log.size - 80).clear()
    return MonitorSnapshot(
        privilegedStatus = PrivilegedBridge.statusFlow.value,
        appPid = Process.myPid(),
        appUid = Process.myUid(),
        heapMb = Runtime.getRuntime().let { (it.totalMemory() - it.freeMemory()) / 1_048_576 },
        nativeHeapMb = Debug.getNativeHeapAllocatedSize() / 1_048_576,
        availRamMb = memInfo.availMem / 1_048_576,
        totalRamMb = memInfo.totalMem / 1_048_576,
        uptime = "${h}h ${m}m ${s}s",
        kernelVersion = System.getProperty("os.version", "?") ?: "?",
        androidVersion = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
        abiList = Build.SUPPORTED_ABIS.firstOrNull() ?: "?",
        logLines = log.toList(),
        timestamp = ts,
    )
}

@Composable
fun DebugMonitorPanel(onBack: () -> Unit) {
    val ctx = LocalContext.current
    var snapshot by remember { mutableStateOf(collectSnapshot(ctx, emptyList())) }
    var commandInput by remember { mutableStateOf("") }
    var commandOutput by remember { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }
    var liveMetrics by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var verificationReport by remember { mutableStateOf<String?>(null) }
    var verifying by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // Auto-refresh every 2 s
    LaunchedEffect(Unit) {
        while (isActive) {
            delay(2_000)
            withContext(Dispatchers.IO) {
                PrivilegedBridge.refresh(caller = "debug-panel")
                snapshot = collectSnapshot(ctx, snapshot.logLines)
                liveMetrics = com.droiddeck.launcher.core.privileged.GameModeEngine.runLiveVerification(ctx) +
                    com.droiddeck.launcher.core.privileged.AppFreezerEngine.runDiagnostic(ctx) +
                    com.droiddeck.launcher.core.privileged.DynamicAdaptiveGovernor.getDiagnosticReport(ctx)
            }
        }
    }

    SettingsPage(
        host = rememberMenuHost(),
        title = "Debug Monitor",
        eyebrow = "Live System Dashboard",
        onBack = onBack,
        scrollContent = false,
    ) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // ── Live metrics row ─────────────────────────────────
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MetricCard(Modifier.weight(1f), "Privileged", snapshot.privilegedStatus.statusIcon + " " + snapshot.privilegedStatus.shortLabel,
                    color = if (snapshot.privilegedStatus.isActive) LocalPalette.current.good else MaterialTheme.colorScheme.error)
                MetricCard(Modifier.weight(1f), "PID / UID", "${snapshot.appPid} / ${snapshot.appUid}")
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MetricCard(Modifier.weight(1f), "Heap", "${snapshot.heapMb} MB")
                MetricCard(Modifier.weight(1f), "Native", "${snapshot.nativeHeapMb} MB")
                MetricCard(Modifier.weight(1f), "Free RAM", "${snapshot.availRamMb} MB")
            }

            // ── System info ─────────────────────────────────────
            SettingsGroup("Device Info") {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    MonRow("Android", snapshot.androidVersion)
                    MonRow("ABI", snapshot.abiList)
                    MonRow("Kernel", snapshot.kernelVersion)
                    MonRow("Uptime", snapshot.uptime)
                    MonRow("RAM", "${snapshot.availRamMb} / ${snapshot.totalRamMb} MB free")
                    MonRow("Model", "${Build.MANUFACTURER} ${Build.MODEL}")
                }
            }

            // ── Game Mode & Performance Engine ──────────────────
            SettingsGroup("Game Mode & Performance Engine") {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    val active = com.droiddeck.launcher.core.privileged.GameModeEngine.isActive()
                    val aggressive = com.droiddeck.launcher.core.privileged.GameModeEngine.isAggressiveActive()
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Active Profile", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            if (active) (if (aggressive) "🔥 TURBO (Aggressive)" else "❄️ COOL (Load Reducer)") else "⚪ STANDBY (Launches with game)",
                            fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                            color = if (active) (if (aggressive) MaterialTheme.colorScheme.tertiary else LocalPalette.current.good) else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    // Display actual live queries from the Android OS
                    liveMetrics.forEach { (label, value) ->
                        MonRow(label, value)
                    }

                    MonRow("Affinity Target", if (aggressive) "Cores 2-7 (0xfc - All Perf + Prime)" else "Cores 2-6 (0x7c - 5x A720 Perf, X4 Off)")

                    Spacer(Modifier.height(4.dp))
                    Text("Live Verification & Diagnostic Test:", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)

                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                scope.launch {
                                    verifying = true
                                    val r = withContext(Dispatchers.IO) { com.droiddeck.launcher.core.privileged.GameModeEngine.testApplyNow(ctx) }
                                    verificationReport = r
                                    liveMetrics = withContext(Dispatchers.IO) {
                                        com.droiddeck.launcher.core.privileged.GameModeEngine.runLiveVerification(ctx) +
                                        com.droiddeck.launcher.core.privileged.AppFreezerEngine.runDiagnostic(ctx) +
                                        com.droiddeck.launcher.core.privileged.DynamicAdaptiveGovernor.getDiagnosticReport(ctx)
                                    }
                                    verifying = false
                                }
                            },
                            modifier = Modifier.weight(1f),
                            enabled = snapshot.privilegedStatus.isActive && !verifying,
                            shape = RoundedCornerShape(8.dp),
                        ) {
                            Text(if (verifying) "Testing..." else "Test Apply", fontSize = 11.sp)
                        }
                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    verifying = true
                                    val r = withContext(Dispatchers.IO) { com.droiddeck.launcher.core.privileged.GameModeEngine.testRestoreNow(ctx) }
                                    verificationReport = r
                                    liveMetrics = withContext(Dispatchers.IO) {
                                        com.droiddeck.launcher.core.privileged.GameModeEngine.runLiveVerification(ctx) +
                                        com.droiddeck.launcher.core.privileged.AppFreezerEngine.runDiagnostic(ctx) +
                                        com.droiddeck.launcher.core.privileged.DynamicAdaptiveGovernor.getDiagnosticReport(ctx)
                                    }
                                    verifying = false
                                }
                            },
                            modifier = Modifier.weight(1f),
                            enabled = snapshot.privilegedStatus.isActive && !verifying,
                            shape = RoundedCornerShape(8.dp),
                        ) {
                            Text("Restore Defaults", fontSize = 11.sp)
                        }
                    }

                    if (!verificationReport.isNullOrEmpty()) {
                        Box(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant).padding(10.dp)
                        ) {
                            Column {
                                Text(verificationReport!!, fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                TextButton(onClick = { verificationReport = null }) { Text("Dismiss", fontSize = 11.sp) }
                            }
                        }
                    }
                }
            }

            // ── Privileged command runner ────────────────────────
            SettingsGroup("Run Privileged Command") {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val privActive = snapshot.privilegedStatus.isActive
                    if (!privActive) {
                        Text("Activate Privileged Engine first (go to Privileged Engine tab)",
                            fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                    }
                    OutlinedTextField(
                        value = commandInput,
                        onValueChange = { commandInput = it },
                        label = { Text("Shell command") },
                        placeholder = { Text("e.g. getprop ro.build.version.sdk") },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = privActive && !running,
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        shape = RoundedCornerShape(8.dp),
                        trailingIcon = {
                            if (running) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            else IconButton(
                                onClick = {
                                    scope.launch {
                                        running = true
                                        val result = withContext(Dispatchers.IO) { PrivilegedBridge.execute(commandInput) }
                                        commandOutput = "[exit ${result.exitCode}]\n${result.output.take(2000)}"
                                        running = false
                                    }
                                },
                                enabled = privActive && commandInput.isNotBlank(),
                            ) { Icon(Icons.Outlined.PlayArrow, contentDescription = "Run", tint = MaterialTheme.colorScheme.primary) }
                        },
                    )
                    if (commandOutput.isNotEmpty()) {
                        Box(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant).padding(10.dp)
                        ) {
                            Column {
                                Text(commandOutput, fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                TextButton(onClick = { commandOutput = "" }) { Text("Clear", fontSize = 11.sp) }
                            }
                        }
                    }
                }
            }

            // ── Live event log ───────────────────────────────────
            SettingsGroup("Event Log  ·  ${snapshot.timestamp}") {
                Box(
                    Modifier.fillMaxWidth().heightIn(min = 80.dp, max = 200.dp)
                        .clip(RoundedCornerShape(bottomStart = 12.dp, bottomEnd = 12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(10.dp)
                ) {
                    val scrollState = rememberScrollState()
                    LaunchedEffect(snapshot.logLines.size) { scrollState.animateScrollTo(scrollState.maxValue) }
                    Column(Modifier.verticalScroll(scrollState)) {
                        snapshot.logLines.forEach { line ->
                            Text(line, fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

// ── Helpers ─────────────────────────────────────────────────

@Composable
private fun MetricCard(modifier: Modifier, label: String, value: String, color: Color = MaterialTheme.colorScheme.onBackground) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier.clip(RoundedCornerShape(10.dp)).background(colors.surfaceVariant.copy(0.5f))
            .border(1.dp, colors.outlineVariant, RoundedCornerShape(10.dp)).padding(10.dp),
    ) {
        Text(label, fontSize = 10.sp, color = colors.onSurfaceVariant)
        Text(value, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = color, maxLines = 1)
    }
}

@Composable
private fun MonRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onBackground, maxLines = 1)
    }
}
