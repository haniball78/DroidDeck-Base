package com.droiddeck.launcher.ui

import android.app.Activity
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.droiddeck.launcher.R
import com.droiddeck.launcher.core.WirelessAdbFix
import com.droiddeck.launcher.core.WirelessAdbPairingService
import com.droiddeck.launcher.core.privileged.DiagnosticsLog
import kotlinx.coroutines.launch

/**
 * The single code-entry panel for pairing, shared by the card and the
 * engine page: it observes the pairing service stage, shows the 6-digit
 * field when the code is needed, the automatic port read-only, and the
 * manual port field only after a failure. The notification stays the
 * quiet background engine; the panel is where the code is typed.
 */
@Composable
fun PairingCodePanel(
    modifier: Modifier = Modifier,
    onRetry: () -> Unit,
) {
    val ctx = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val stage by WirelessAdbPairingService.stage.collectAsState()
    var codeText by rememberSaveable { mutableStateOf("") }
    var portText by rememberSaveable { mutableStateOf("") }
    var showManualPort by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var manuallyPaired by remember { mutableStateOf(false) }

    LaunchedEffect(stage) {
        if (WirelessAdbFix.shouldShowManualPort(stage, showManualPort)) showManualPort = true
    }
    var halfLogged by remember { mutableStateOf(false) }
    LaunchedEffect(stage) {
        if (!halfLogged && stage is WirelessAdbPairingService.Stage.CodeNeeded) {
            halfLogged = true
            (ctx as? Activity)?.let { activity ->
                DiagnosticsLog.log(
                    ctx, "adbpair.panel", "split-half",
                    fields = mapOf("half" to WirelessAdbFix.splitHalf(activity)),
                )
            }
        }
    }

    fun submit() {
        if (busy) return
        val code = codeText.filter(Char::isDigit)
        if (code.length != 6) return
        if (showManualPort) {
            val port = portText.toIntOrNull() ?: return
            busy = true
            scope.launch {
                val result = WirelessAdbFix.pairAndStartPrivilegedDaemon(ctx, port, code)
                busy = false
                if (result.isSuccess) {
                    manuallyPaired = true
                    codeText = ""
                    Toast.makeText(ctx, ctx.getString(R.string.pairing_panel_toast_paired), Toast.LENGTH_SHORT).show()
                } else {
                    val err = result.exceptionOrNull()?.localizedMessage ?: ""
                    Toast.makeText(ctx, ctx.getString(R.string.pairing_panel_toast_error, err), Toast.LENGTH_LONG).show()
                }
            }
        } else {
            WirelessAdbPairingService.sendCode(ctx, code)
            codeText = ""
        }
    }

    val half = remember(stage) {
        (ctx as? Activity)?.let { WirelessAdbFix.splitHalf(it) } ?: "unknown"
    }
    val showForm = stage is WirelessAdbPairingService.Stage.CodeNeeded ||
        stage is WirelessAdbPairingService.Stage.Failed

    Column(
        modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            stringResource(R.string.pairing_panel_explanation),
            fontSize = 12.sp, color = colors.onSurfaceVariant,
        )
        if (manuallyPaired) {
            Text(
                stringResource(R.string.pairing_panel_done),
                fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = colors.primary,
            )
        } else when (val s = stage) {
            is WirelessAdbPairingService.Stage.Waiting -> Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Text(stringResource(R.string.pairing_panel_waiting), fontSize = 12.sp, color = colors.onSurfaceVariant)
            }
            is WirelessAdbPairingService.Stage.Working -> Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Text(s.step, fontSize = 12.sp, color = colors.onSurfaceVariant)
            }
            is WirelessAdbPairingService.Stage.Failed -> Text(
                s.error, fontSize = 12.sp, color = colors.error,
            )
            is WirelessAdbPairingService.Stage.Parked -> Text(
                stringResource(R.string.connection_card_finishing_setup),
                fontSize = 12.sp, color = colors.onBackground,
            )
            is WirelessAdbPairingService.Stage.Done -> Text(
                stringResource(R.string.pairing_panel_done),
                fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = colors.primary,
            )
            else -> {}
        }
        if (showForm && !manuallyPaired) {
            if (half == "top" || half == "bottom") {
                Text(
                    stringResource(R.string.pairing_panel_swap_hint),
                    fontSize = 11.sp, color = colors.onSurfaceVariant,
                )
            }
            val pairingPort = (stage as? WirelessAdbPairingService.Stage.CodeNeeded)?.pairingPort
            Text(
                if (pairingPort != null) stringResource(R.string.pairing_panel_port_auto, pairingPort)
                else stringResource(R.string.pairing_panel_port_discovering),
                fontSize = 12.sp, color = colors.onSurfaceVariant,
            )
            (stage as? WirelessAdbPairingService.Stage.CodeNeeded)?.error?.let { error ->
                Text(error, fontSize = 12.sp, color = colors.error)
            }
            OutlinedTextField(
                value = codeText,
                onValueChange = { if (it.length <= 6) codeText = it.filter(Char::isDigit) },
                label = { Text(stringResource(R.string.pairing_panel_code_label)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
            if (showManualPort) {
                OutlinedTextField(
                    value = portText,
                    onValueChange = { portText = it.filter(Char::isDigit) },
                    label = { Text(stringResource(R.string.pairing_panel_port_manual_label)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                PrimaryButton(
                    stringResource(R.string.pairing_panel_submit), compact = true,
                    enabled = !busy && codeText.length == 6 &&
                        (!showManualPort || portText.toIntOrNull() != null),
                    onClick = { submit() },
                )
                if (stage is WirelessAdbPairingService.Stage.Failed) {
                    SecondaryButton(
                        stringResource(R.string.pairing_panel_retry), compact = true,
                        enabled = !busy, onClick = onRetry,
                    )
                }
            }
        }
    }
}

/** Confirmation before the full pairing reset (it wipes everything). */
@Composable
fun PairingResetConfirmDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.pairing_reset_confirm_title), fontWeight = FontWeight.Bold) },
        text = { Text(stringResource(R.string.pairing_reset_confirm_body), fontSize = 12.sp) },
        confirmButton = {
            Button(onClick = onConfirm) { Text(stringResource(R.string.pairing_reset_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}
