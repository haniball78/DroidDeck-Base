package com.droiddeck.launcher

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.droiddeck.launcher.core.WirelessAdbPairingService

/**
 * The code field behind the pairing notification's body tap. A
 * notification's RemoteInput action cannot be opened from a body tap, so
 * the notification points here instead: a translucent activity whose card
 * sits at the bottom, leaving the system pairing-code dialog visible
 * above it, with the field focused and the keyboard up on arrival.
 * A tap on a stale notification (pairing already done/failed) falls back
 * to the app instead of showing a dead field.
 */
class PairingCodeEntryActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (WirelessAdbPairingService.stage.value !is WirelessAdbPairingService.Stage.CodeNeeded) {
            startActivity(
                Intent(this, FrontEndActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
            )
            finish()
            return
        }
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                PairingCodeEntryScreen(
                    onSubmit = { code ->
                        // The pairing may have finished meanwhile (e.g. via
                        // the inline reply); never submit into a dead flow.
                        if (WirelessAdbPairingService.stage.value is WirelessAdbPairingService.Stage.CodeNeeded) {
                            WirelessAdbPairingService.sendCode(this, code)
                        }
                        finish()
                    },
                    onCancel = { finish() },
                )
            }
        }
    }
}

@Composable
private fun PairingCodeEntryScreen(onSubmit: (String) -> Unit, onCancel: () -> Unit) {
    val activity = LocalContext.current as Activity
    val stage by WirelessAdbPairingService.stage.collectAsState()
    // Pairing finished while the field was open: close, the notification
    // itself now carries the result.
    LaunchedEffect(stage) {
        if (stage is WirelessAdbPairingService.Stage.Done ||
            stage is WirelessAdbPairingService.Stage.Failed
        ) {
            activity.finish()
        }
    }
    var code by remember { mutableStateOf("") }
    var showError by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
        keyboard?.show()
    }
    fun submit() {
        if (code.length == 6) onSubmit(code) else showError = true
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        Card(
            Modifier.fillMaxWidth().padding(16.dp),
            shape = RoundedCornerShape(16.dp),
        ) {
            Column(
                Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(stringResource(R.string.adbpair_title_code), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.adbpair_code_hint), style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    value = code,
                    onValueChange = {
                        if (it.length <= 6 && it.all(Char::isDigit)) {
                            code = it
                            showError = false
                        }
                    },
                    label = { Text(stringResource(R.string.adbpair_code_label)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                    modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                )
                if (showError) {
                    Text(stringResource(R.string.adbpair_six_digits), color = MaterialTheme.colorScheme.error)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { submit() }, enabled = code.length == 6) {
                        Text(stringResource(R.string.adbpair_enter_code))
                    }
                    OutlinedButton(onClick = onCancel) {
                        Text(stringResource(R.string.common_cancel))
                    }
                }
            }
        }
    }
}
