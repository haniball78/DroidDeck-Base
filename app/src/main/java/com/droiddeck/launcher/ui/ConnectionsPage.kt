package com.droiddeck.launcher.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.DesktopWindows
import androidx.compose.material.icons.outlined.ScreenShare
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.droiddeck.launcher.R
import kotlin.math.hypot
import kotlin.math.roundToInt

private data class ConnNode(
    val route: String,
    val icon: ImageVector,
    val titleRes: Int,
    val hintRes: Int,
    val glow: Color,
    val tag: String,
)

private val ConnNodes = listOf(
    ConnNode("computer", Icons.Outlined.DesktopWindows, R.string.computer_settings, R.string.computer_settings_hint, Color(0xFF4A90D9), "conn:computer"),
    ConnNode("tv", Icons.Outlined.ScreenShare, R.string.tv_settings, R.string.tv_settings_hint, Color(0xFF9B6DD6), "conn:tv"),
    ConnNode("second_player", Icons.Outlined.SportsEsports, R.string.connections_second_player, R.string.connections_second_player_hint, Color(0xFF43B581), "conn:second_player"),
)

private val MapHeight = 480.dp
private val NodeSize = 96.dp
private val CenterSize = 112.dp
private val NodeColWidth = 160.dp

@Composable
internal fun ConnectionsPage(onSelect: (String) -> Unit, modifier: Modifier) {
    Column(modifier = modifier) {
        PageHeader(stringResource(R.string.rail_connections))
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 8.dp),
        ) {
            if (LocalNarrowPane.current) ConstellationStack(onSelect) else ConstellationMap(onSelect)
        }
    }
}

@Composable
private fun ConstellationMap(onSelect: (String) -> Unit) {
    val still = Motion.scale == 0f
    val pulse by rememberInfiniteTransition(label = "connPulse").animateFloat(
        0.72f, 1f, infiniteRepeatable(tween(1600, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "pulse",
    )
    val glowAlpha = if (still) 1f else pulse
    BoxWithConstraints(Modifier.fillMaxWidth().height(MapHeight)) {
        val density = LocalDensity.current
        val wPx = with(density) { maxWidth.toPx() }
        val hPx = with(density) { MapHeight.toPx() }
        val center = Offset(wPx * 0.5f, hPx * 0.38f)
        val anchors = mapOf(
            "computer" to Offset(wPx * 0.14f, hPx * 0.38f),
            "tv" to Offset(wPx * 0.86f, hPx * 0.38f),
            "second_player" to Offset(wPx * 0.5f, hPx * 0.74f),
        )
        Canvas(Modifier.fillMaxSize()) {
            drawStars(size)
            for (node in ConnNodes) drawLink(center, anchors.getValue(node.route), node.glow, density)
        }
        CenterPhoneNode(center, glowAlpha)
        for (node in ConnNodes) {
            ConnNodeItem(node, anchors.getValue(node.route), glowAlpha) { onSelect(node.route) }
        }
    }
}

private fun DrawScope.drawStars(size: Size) {
    for (i in 0 until 26) {
        val x = ((i * 0.371f + 0.07f) % 1f) * size.width
        val y = ((i * 0.529f + 0.13f) % 1f) * size.height
        drawCircle(
            Color.White.copy(alpha = 0.08f + (i % 4) * 0.04f),
            radius = 1f + (i % 3) * 0.7f,
            center = Offset(x, y),
        )
    }
}

private fun DrawScope.drawLink(from: Offset, to: Offset, glow: Color, density: Density) {
    val delta = to - from
    val dist = hypot(delta.x, delta.y)
    if (dist == 0f) return
    val dir = delta / dist
    val gapFrom = with(density) { 64.dp.toPx() }
    val gapTo = with(density) { 56.dp.toPx() }
    val start = from + dir * gapFrom
    val end = to - dir * gapTo
    val perp = Offset(-dir.y, dir.x)
    val bow = with(density) { 26.dp.toPx() }
    val c1 = start + dir * (dist * 0.25f) + perp * bow
    val c2 = end - dir * (dist * 0.25f) + perp * bow
    val path = Path().apply {
        moveTo(start.x, start.y)
        cubicTo(c1.x, c1.y, c2.x, c2.y, end.x, end.y)
    }
    drawPath(path, glow.copy(alpha = 0.12f), style = Stroke(width = with(density) { 7.dp.toPx() }, cap = StrokeCap.Round))
    drawPath(path, glow.copy(alpha = 0.55f), style = Stroke(width = with(density) { 2.dp.toPx() }, cap = StrokeCap.Round))
    drawCircle(glow.copy(alpha = 0.9f), radius = with(density) { 3.5.dp.toPx() }, center = end)
}

@Composable
private fun CenterPhoneNode(center: Offset, glowAlpha: Float) {
    val haloRadius = with(LocalDensity.current) { 70.dp.toPx() }
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(140.dp)
            .offset { IntOffset((center.x - 70.dp.toPx()).roundToInt(), (center.y - 70.dp.toPx()).roundToInt()) }
            .testTag("conn:center"),
    ) {
        Box(Modifier.fillMaxSize().background(Brush.radialGradient(listOf(AttentionAmber.copy(alpha = 0.38f * glowAlpha), Color.Transparent), radius = haloRadius)))
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(CenterSize)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.10f))
                .border(1.5.dp, AttentionAmber.copy(alpha = 0.85f), CircleShape),
        ) {
            Icon(Icons.Outlined.Smartphone, contentDescription = null, tint = AttentionAmber, modifier = Modifier.size(48.dp))
        }
    }
}

@Composable
private fun ConnNodeItem(node: ConnNode, anchor: Offset, glowAlpha: Float, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val src = remember { MutableInteractionSource() }
    val hot = rememberHot(src)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(NodeColWidth)
            .offset { IntOffset((anchor.x - 80.dp.toPx()).roundToInt(), (anchor.y - 48.dp.toPx()).roundToInt()) }
            .testTag(node.tag)
            .hoverable(src)
            .clickable(interactionSource = src, indication = LocalIndication.current, role = Role.Button, onClick = onClick)
            .controllerConfirm(onClick = onClick),
    ) {
        NodeDisc(node.icon, node.glow, glowAlpha, hot)
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(node.titleRes), fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = colors.onBackground,
            maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
        )
        Text(
            stringResource(node.hintRes), fontSize = 12.5.sp, color = colors.onSurfaceVariant,
            maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun NodeDisc(icon: ImageVector, glow: Color, glowAlpha: Float, hot: Boolean) {
    val haloRadius = with(LocalDensity.current) { 60.dp.toPx() }
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(120.dp)) {
        Box(Modifier.fillMaxSize().background(Brush.radialGradient(listOf(glow.copy(alpha = 0.32f * glowAlpha), Color.Transparent), radius = haloRadius)))
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(NodeSize)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.08f))
                .border(1.5.dp, glow.copy(alpha = if (hot) 0.95f else 0.6f), CircleShape),
        ) {
            Icon(icon, contentDescription = null, tint = glow, modifier = Modifier.size(40.dp))
        }
    }
}

@Composable
private fun ConstellationStack(onSelect: (String) -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(72.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.08f))
                .border(1.5.dp, AttentionAmber.copy(alpha = 0.8f), CircleShape)
                .testTag("conn:center"),
        ) {
            Icon(Icons.Outlined.Smartphone, contentDescription = null, tint = AttentionAmber, modifier = Modifier.size(32.dp))
        }
        for (node in ConnNodes) {
            ConnectionCard(node.icon, stringResource(node.titleRes), stringResource(node.hintRes), Modifier.testTag(node.tag)) { onSelect(node.route) }
        }
    }
}

@Composable
private fun ConnectionCard(icon: ImageVector, title: String, hint: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    Row(
        modifier.fillMaxWidth().clip(Shape14).background(colors.surface).border(1.dp, pal.line, Shape14)
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
        Icon(Icons.Outlined.ChevronRight, contentDescription = null, tint = colors.onSurfaceVariant, modifier = Modifier.size(18.dp))
    }
}
