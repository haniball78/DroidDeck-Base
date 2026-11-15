package com.droiddeck.launcher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.droiddeck.launcher.core.TvHandoff

// Live phone strip while the game is on the TV and the phone is the controller:
// FPS, battery temperature and level, time on TV, render size, input path.
@Composable
fun TvCompanionStrip(state: TvHandoff.CompanionState) {
    var collapsed by remember { mutableStateOf(false) }
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .padding(top = 10.dp)
                .background(Color.Black.copy(alpha = 0.65f))
                .padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            if (!collapsed) {
                Text(
                    text = companionLine(state),
                    color = Color.White,
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                )
                if (state.cappedTo1080p) {
                    Text(
                        text = "1080p cap: this TV offers no 1080p mode",
                        color = Color(0xFFFFC46B),
                        fontSize = 11.sp,
                        textAlign = TextAlign.Center,
                    )
                }
            }
            TextButton(onClick = { collapsed = !collapsed }) {
                Text(
                    text = if (collapsed) "▸ show info" else "▾ hide",
                    color = Color.White,
                    fontSize = 11.sp,
                )
            }
        }
    }
}

private fun companionLine(state: TvHandoff.CompanionState): String {
    val fps = "${state.fps.toInt()} FPS"
    val temp = if (state.batteryTempC > 0f) "${state.batteryTempC.toInt()}°C" else "—"
    val battery = if (state.batteryPct >= 0) "${state.batteryPct}%${if (state.charging) " ⚡" else ""}" else "—"
    val time = formatDuration(state.tvSeconds)
    val size = "${state.width}x${state.height}"
    val input = if (state.physicalInput) "Pad" else "Phone"
    return "$fps · $temp · $battery · $time · $size · $input"
}

private fun formatDuration(seconds: Long): String {
    val m = seconds / 60
    val s = seconds % 60
    return "%d:%02d".format(m, s)
}

// Shown when the TV session lost its last input channel and the game froze.
// The button turns the phone into the controller, which unfreezes the game.
@Composable
fun TvInputLostCard(onUsePhoneController: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .padding(horizontal = 24.dp)
                .background(Color.Black.copy(alpha = 0.85f))
                .padding(horizontal = 20.dp, vertical = 16.dp),
        ) {
            Text(
                text = "No controller — game paused",
                color = Color.White,
                fontSize = 17.sp,
                textAlign = TextAlign.Center,
            )
            Text(
                text = "Reconnect a controller, or use the phone as the controller.",
                color = Color.White.copy(alpha = 0.8f),
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp),
            )
            Button(onClick = onUsePhoneController, modifier = Modifier.padding(top = 14.dp)) {
                Text(text = "Use phone as controller")
            }
        }
    }
}
