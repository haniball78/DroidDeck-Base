package com.droiddeck.launcher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.droiddeck.launcher.frontend.Library
import com.droiddeck.launcher.session.GamePackJobs
import com.droiddeck.launcher.session.GamePackSnapshot
import com.droiddeck.launcher.session.GamePackService
import com.droiddeck.launcher.session.GamePackStore
import com.droiddeck.launcher.session.PackMethod
import com.droiddeck.launcher.session.SpaceDecision
import com.droiddeck.launcher.session.estimateDurationMs
import com.droiddeck.launcher.session.estimatePackedRange
import com.droiddeck.launcher.stores.formatBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun rememberGamePackSnapshot(game: Library.SteamGame): GamePackSnapshot? {
    val ctx = LocalContext.current
    val jobs by GamePackJobs.jobs.collectAsState()
    val job = jobs[game.appId]
    val snap by produceState<GamePackSnapshot?>(null, game.gameId, job?.done, job?.error, job?.cancelled) {
        value = withContext(Dispatchers.IO) { GamePackStore.scan(ctx.applicationContext, game) }
    }
    return snap
}

@Composable
internal fun GamePackSheet(game: Library.SteamGame, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val jobs by GamePackJobs.jobs.collectAsState()
    val job = jobs[game.appId]
    val snap = rememberGamePackSnapshot(game)
    var confirmMethod by remember { mutableStateOf<PackMethod?>(null) }
    var confirmUnpack by remember { mutableStateOf(false) }
    var refused by remember { mutableStateOf(false) }

    if (refused && snap != null) {
        AlertDialog(
            onDismissRequest = { refused = false },
            title = { Text(stringResource(com.droiddeck.launcher.R.string.gamepack_space_refused_title)) },
            text = { Text(stringResource(com.droiddeck.launcher.R.string.gamepack_space_refused_body, formatBytes(snap!!.requiredBytes), formatBytes(snap!!.freeBytes))) },
            confirmButton = { TextButton(onClick = { refused = false }) { Text(stringResource(com.droiddeck.launcher.R.string.common_ok)) } },
        )
        return
    }
    confirmMethod?.let { method ->
        val s = snap
        if (s == null) {
            confirmMethod = null
        } else {
            val range = estimatePackedRange(s.totalBytes)
            val warn = s.decision == SpaceDecision.WARN
            val body = stringResource(
                com.droiddeck.launcher.R.string.gamepack_confirm_pack_body,
                formatBytes(s.totalBytes), formatBytes(s.largestBytes),
                formatBytes(range.first), formatBytes(range.last),
                formatPackDuration(estimateDurationMs(s.totalBytes, method)),
                formatBytes(s.freeBytes), formatBytes(s.requiredBytes),
            ) + if (warn) "\n\n" + stringResource(com.droiddeck.launcher.R.string.gamepack_space_warn) else ""
            ConfirmDialog(
                title = stringResource(com.droiddeck.launcher.R.string.gamepack_confirm_pack_title, game.name),
                text = body,
                confirm = stringResource(com.droiddeck.launcher.R.string.gamepack_confirm_start),
                onConfirm = {
                    confirmMethod = null
                    GamePackService.start(ctx, game, method, unpack = false)
                },
                onDismiss = { confirmMethod = null },
            )
        }
        return
    }
    if (confirmUnpack) {
        val s = snap
        if (s == null) {
            confirmUnpack = false
        } else {
            val warn = s.unpackDecision == SpaceDecision.WARN
            val body = stringResource(
                com.droiddeck.launcher.R.string.gamepack_confirm_unpack_body,
                formatBytes(s.packedBytes), formatBytes(s.packedOrigBytes),
                formatBytes(s.freeBytes), formatBytes(s.unpackRequiredBytes),
            ) + if (warn) "\n\n" + stringResource(com.droiddeck.launcher.R.string.gamepack_space_warn) else ""
            ConfirmDialog(
                title = stringResource(com.droiddeck.launcher.R.string.gamepack_confirm_unpack_title, game.name),
                text = body,
                confirm = stringResource(com.droiddeck.launcher.R.string.gamepack_confirm_start),
                onConfirm = {
                    confirmUnpack = false
                    GamePackService.start(ctx, game, PackMethod.MAX, unpack = true)
                },
                onDismiss = { confirmUnpack = false },
            )
        }
        return
    }

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(game.name) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (job != null && !job.done) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        CircularProgressIndicator(progress = { job.fraction }, strokeWidth = 2.dp, color = LocalPalette.current.signal, trackColor = LocalPalette.current.signal.copy(alpha = 0.22f), modifier = Modifier.size(20.dp))
                        Text(
                            stringResource(
                                if (job.unpack) com.droiddeck.launcher.R.string.gamepack_unpacking else com.droiddeck.launcher.R.string.gamepack_packing,
                            ) + " ${(job.fraction * 100).toInt()}%",
                            fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                        )
                    }
                    Text(
                        stringResource(com.droiddeck.launcher.R.string.gamepack_progress_files, job.filesDone, job.filesTotal),
                        fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = { GamePackService.cancel(ctx, game.appId) }) { Text(stringResource(com.droiddeck.launcher.R.string.gamepack_job_cancel)) }
                } else {
                    if (job?.error != null) Note(job.error!!)
                    when {
                        snap == null -> Text(stringResource(com.droiddeck.launcher.R.string.gamepack_unavailable), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        snap!!.fileCount == 0 && !snap!!.isPacked -> Text(stringResource(com.droiddeck.launcher.R.string.gamepack_empty), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        else -> {
                            SectionTitle(stringResource(com.droiddeck.launcher.R.string.gamepack_how_title), null)
                            Text(stringResource(com.droiddeck.launcher.R.string.gamepack_sheet_intro), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(stringResource(com.droiddeck.launcher.R.string.gamepack_sheet_temp), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Note(stringResource(com.droiddeck.launcher.R.string.gamepack_sheet_steam))
                            if (snap!!.isPacked) {
                                SectionTitle(stringResource(com.droiddeck.launcher.R.string.gamepack_unpack), null)
                                Text(
                                    stringResource(
                                        com.droiddeck.launcher.R.string.gamepack_packed_summary,
                                        formatBytes(snap!!.packedBytes), formatBytes(snap!!.packedOrigBytes), snap!!.packedCount,
                                    ),
                                    fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                SecondaryButton(stringResource(com.droiddeck.launcher.R.string.gamepack_unpack), modifier = Modifier.fillMaxWidth()) { confirmUnpack = true }
                            } else {
                                SectionTitle(stringResource(com.droiddeck.launcher.R.string.gamepack_method_label), null)
                                MethodCard(
                                    stringResource(com.droiddeck.launcher.R.string.gamepack_method_max_title),
                                    stringResource(com.droiddeck.launcher.R.string.gamepack_method_max_body),
                                ) {
                                    if (snap!!.decision == SpaceDecision.REFUSE) refused = true else confirmMethod = PackMethod.MAX
                                }
                                MethodCard(
                                    stringResource(com.droiddeck.launcher.R.string.gamepack_method_fast_title),
                                    stringResource(com.droiddeck.launcher.R.string.gamepack_method_fast_body),
                                ) {
                                    if (snap!!.decision == SpaceDecision.REFUSE) refused = true else confirmMethod = PackMethod.FAST
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text(stringResource(com.droiddeck.launcher.R.string.common_close)) } },
    )
}

@Composable
private fun MethodCard(title: String, body: String, onClick: () -> Unit) {
    val pal = LocalPalette.current
    Box(
        modifier = Modifier.fillMaxWidth().clip(Shape12).background(Color.White.copy(alpha = 0.03f))
            .border(1.dp, pal.line2, Shape12)
            .clickable(role = Role.Button) { onClick() }
            .padding(12.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(body, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun formatPackDuration(ms: Long): String = when {
    ms < 60_000L -> stringResource(com.droiddeck.launcher.R.string.gamepack_time_less_min)
    ms < 3_600_000L -> stringResource(com.droiddeck.launcher.R.string.gamepack_time_min, ms / 60_000L)
    else -> stringResource(com.droiddeck.launcher.R.string.gamepack_time_hours, ms / 3_600_000L, (ms % 3_600_000L) / 60_000L)
}
