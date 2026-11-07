package com.droiddeck.launcher

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import com.droiddeck.launcher.core.WirelessAdbPairingService
import com.droiddeck.launcher.core.canDrawOverlays

/**
 * The code field behind the pairing notification's body tap, used only
 * when the draw-over-apps permission is missing: the normal route is the
 * floating overlay, which does not take the foreground, while this activity
 * does — opening it dismisses the system pairing dialog and invalidates
 * the code (seen on device 2026-11-07). So this screen also offers the
 * one-tap enable for the overlay permission.
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
                    showOverlayHint = !canDrawOverlays(this),
                    onEnableOverlay = {
                        startActivity(
                            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")),
                        )
                    },
                )
            }
        }
    }
}
