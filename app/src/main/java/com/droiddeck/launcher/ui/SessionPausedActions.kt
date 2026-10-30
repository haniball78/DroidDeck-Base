package com.droiddeck.launcher.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.droiddeck.launcher.session.SuspendKind

@Composable
fun SessionPausedActions(
    kind: SuspendKind,
    withoutOptimizationsEnabled: Boolean,
    withoutOptimizationsDisabledReason: String?,
    onResumeWithOptimizations: () -> Unit,
    onResumeWithoutOptimizations: () -> Unit,
    onFinish: () -> Unit,
    highlightWithoutOptimizations: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val reason = if (kind == SuspendKind.SAFETY) {
        "Paused for safety: performance tweaks were restored."
    } else {
        "Paused."
    }
    Card(modifier = modifier.fillMaxWidth().padding(16.dp)) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(reason)
            if (highlightWithoutOptimizations) {
                Button(
                    onClick = onResumeWithoutOptimizations,
                    enabled = withoutOptimizationsEnabled,
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(vertical = 14.dp),
                ) {
                    Text("Resume without optimizations", fontSize = 16.sp)
                }
                Text("This button resumes the game now, without performance tweaks.", fontSize = 13.sp)
                Button(onClick = onResumeWithOptimizations, modifier = Modifier.fillMaxWidth()) {
                    Text("Resume with optimizations")
                }
            } else {
                Button(onClick = onResumeWithOptimizations, modifier = Modifier.fillMaxWidth()) {
                    Text("Resume with optimizations")
                }
                Button(
                    onClick = onResumeWithoutOptimizations,
                    enabled = withoutOptimizationsEnabled,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Resume without optimizations")
                }
            }
            if (!withoutOptimizationsEnabled && withoutOptimizationsDisabledReason != null) {
                Text(withoutOptimizationsDisabledReason)
            }
            Button(onClick = onFinish, modifier = Modifier.fillMaxWidth()) {
                Text("Finish")
            }
        }
    }
}
