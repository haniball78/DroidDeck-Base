package com.droiddeck.launcher.ui

import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.droiddeck.launcher.R
import androidx.compose.ui.res.stringResource
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.runtime.State
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.outlined.DesktopWindows
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.droiddeck.launcher.frontend.Library
import com.droiddeck.launcher.glasses.GlassesMenuMirror
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.inset
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import coil.compose.rememberAsyncImagePainter
import coil.request.ImageRequest
import coil.size.Scale
import coil.transform.RoundedCornersTransformation
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val WALL_MIN_GAMES = 4
private const val WALL_DISTINCT = 40

private val GoldInk = Color(0xFF2B1A00)

private val Crimson = Color(0xFFB3123B)

// Glass-orbs design (Steam section only): orb sizes and the arc dip.
private val OrbSize = 76.dp
private val OrbArcDip = 12.dp
private val PlayOrbSize = 120.dp
private val PlayOrbSizeNarrow = 96.dp

private const val DRIFT_STEP_NS = 15_000_000L

@Composable
private fun lastPlayedText(lastPlayed: Long): String? =
    playedSpan(lastPlayed)?.let { stringResource(R.string.games_last_played, it.replaceFirstChar { c -> c.lowercase() }) }

private fun playedSpan(lastPlayed: Long): String? {
    if (lastPlayed <= 0L) return null
    return android.text.format.DateUtils.getRelativeTimeSpanString(
        lastPlayed * 1000L, System.currentTimeMillis(), android.text.format.DateUtils.MINUTE_IN_MILLIS,
    ).toString()
}

@Composable
internal fun SteamHome(s: FrontEndState, a: FrontEndActions, modifier: Modifier) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(Unit) { com.droiddeck.launcher.frontend.SteamFeatured.load(ctx) }
    val featured = com.droiddeck.launcher.frontend.SteamFeatured.games
    val signedIn = s.offlineAccount != null
    val owned by produceState(emptyList<Pair<Int, java.io.File>>(), signedIn) {
        value = if (!signedIn) emptyList() else withContext(Dispatchers.IO) { runCatching { Library.steamLibraryArt(ctx).shuffled() }.getOrDefault(emptyList()) }
    }
    val games = remember(s.steamGames) { s.steamGames.sortedWith(compareByDescending<Library.SteamGame> { it.lastPlayed }.thenBy { it.gameId }) }
    val hero = games.firstOrNull { it.lastPlayed > 0L }
    val shelf = games.filter { it.lastPlayed > 0L }.take(3)
    val showRuntime = s.busy || !s.ready || (s.available != null && s.available != s.installed)
    val narrow = LocalNarrowPane.current
    var words by remember { mutableStateOf<Rect?>(null) }
    // Glass orbs float: one transition, one duration per orb (same pattern as the shelf bob).
    val orbTransition = rememberInfiniteTransition(label = "orbFloat")
    val orbFloats = listOf(2800, 3200, 3600, 3000).map { ms ->
        orbTransition.animateFloat(0f, 1f, infiniteRepeatable(tween(ms, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "orbFloat")
    }
    val mode = com.droiddeck.launcher.session.SessionService.MODE_STEAM
    val tabs = modeSheetTabs(steam = true).map { it to sheetTabLabel(it) }
    val back = stringResource(R.string.hop_from_play)
    val extras = SheetExtras(
        frameGen = { FrameGenGroup(s, a) },
        performance = { PerformanceLink(a, "steam", SheetTab.SESSION) },
        drivers = { summary -> DriversLink(a, summary, "steam", back) },
        account = { host ->
            SettingsGroup(stringResource(R.string.setup_account)) {
                ToggleRow(
                    host, "offline", stringResource(R.string.setup_offline),
                    s.offlineAccount?.let { if (it.isEmpty()) stringResource(R.string.setup_signed_in_unnamed) else stringResource(R.string.setup_signed_in, it) } ?: stringResource(R.string.setup_sign_in_first),
                    s.offline, enabled = s.offlineAccount != null,
                ) { a.onOffline() }
            }
        },
    )
    val (glasses, glassesSettings) = rememberGlasses()
    val headset = glasses.present && glasses.canControl
    val drop = rememberDropSheetState()
    LaunchedEffect(headset) { if (!headset && (drop.open || drop.shown)) drop.reset() }
    LiftHero(
        key = "steam", tabs = tabs,
        drop = drop.takeIf { headset },
        overlay = { lifted ->
            if (headset) DropSheet(
                drop, glassesTabs(glasses) + (GlassesTab.CONTROLLERS to stringResource(R.string.vr_controllers_tab)), covered = lifted,
                button = { enabled ->
                    PrimaryButton(stringResource(R.string.games_play_steamvr), enabled = !s.busy && glasses.usable, large = true, icon = Icons.Filled.PlayArrow, fill = true,
                        modifier = Modifier.fillMaxWidth().testTag("play-steamvr")) { if (enabled) { drop.open = false; a.onPlaySteamVr() } }
                },
            ) { tab ->
                if (tab == GlassesTab.CONTROLLERS) VrControllerGroups(rememberMenuHost())
                else GlassesGroups(rememberMenuHost(), glasses, glassesSettings,
                    onRecenter = GlassesMenuMirror::recenter, inSession = false, tab = tab)
            }
        },
        onOpen = a.onSteamSettings, onPrimary = a.onPlay,
        modifier = modifier.clipToBounds(),
        start = if (narrow) 20.dp else 44.dp, bottom = if (narrow) 20.dp else 44.dp,
        lipBeside = !narrow,
        background = {
            when {
                hero != null -> HeroBackdrop(hero)
                games.isEmpty() -> EmptyLibrary(
                    avoid = words,
                    wall = if (owned.isEmpty() && featured.isEmpty()) null else { { CapsuleWall(emptyList(), owned = owned, featured = featured) } },
                ) { Box(Modifier.fillMaxSize().graphicsLayer { scaleX = 1.06f; scaleY = 1.06f }) { WallFade() } }
                games.size < WALL_MIN_GAMES -> CapsuleFan(games)
                else -> CapsuleWall(games, owned = owned, featured = featured)
            }
        },
        scrim = { if (hero == null && games.size >= WALL_MIN_GAMES) WallFade() },
        words = {
            if (hero != null) {
                SteamHeroWords(hero, s, a, narrow)
            } else {
                Row(
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.onPlaced { words = it.boundsInParent() },
                ) {
                    if (showRuntime) RuntimeChip(s)
                }
            }
        },
        button = { click ->
            GlassOrb(
                label = stringResource(R.string.games_play_steam), icon = Icons.Filled.PlayArrow, glow = Crimson,
                arcOffset = 0.dp, float = orbFloats[0].value, enabled = !s.busy,
                modifier = Modifier.testTag("play-steam"), onClick = { click() },
            )
        },
        beside = {
            if (hero != null) {
                val show = LocalShowSection.current
                val pal = LocalPalette.current
                GlassOrb(
                    label = stringResource(R.string.rail_desktop), icon = Icons.Outlined.DesktopWindows, glow = pal.signal,
                    arcOffset = OrbArcDip, float = orbFloats[1].value, onClick = { show("desktop") },
                )
                GlassOrb(
                    label = stringResource(R.string.connections_second_player), icon = Icons.Filled.SportsEsports, glow = pal.good,
                    arcOffset = OrbArcDip, float = orbFloats[2].value, onClick = { show("second_player") },
                )
            }
        },
        lipContent = {
            // Visual only: the lip's own Box handles the click (opens settings).
            GlassOrb(
                label = stringResource(R.string.drawer_page_settings), icon = Icons.Filled.Settings, glow = AttentionAmber,
                arcOffset = 0.dp, float = orbFloats[3].value, onClick = null,
            )
        },
        afterButton = {
            if (hero != null) SteamHeroShelf(shelf, narrow)
        },
        rows = { tab -> a.modeSheet(mode, tab, extras) },
    )
}

@Composable
private fun HeroBackdrop(hero: Library.SteamGame) {
    val colors = MaterialTheme.colorScheme
    val art = hero.hero ?: hero.art
    Box(Modifier.fillMaxSize()) {
        if (art != null) {
            AsyncImage(model = art, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Box(Modifier.fillMaxSize().background(artBrush(hueOf(hero.name))))
        }
        Box(
            Modifier.fillMaxSize().background(
                Brush.horizontalGradient(
                    0f to colors.background.copy(alpha = 0.78f),
                    0.30f to colors.background.copy(alpha = 0.40f),
                    0.62f to colors.background.copy(alpha = 0.08f),
                    1f to colors.background.copy(alpha = 0.30f),
                ),
            ),
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0f to colors.background.copy(alpha = 0.48f),
                    0.30f to colors.background.copy(alpha = 0.05f),
                    0.62f to colors.background.copy(alpha = 0.15f),
                    1f to colors.background.copy(alpha = 0.88f),
                ),
            ),
        )
    }
}

@Composable
private fun SteamHeroWords(hero: Library.SteamGame, s: FrontEndState, a: FrontEndActions, narrow: Boolean) {
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth()) {
        Text(
            stringResource(R.string.steam_resume_playing), fontSize = if (narrow) 24.sp else 32.sp, fontWeight = FontWeight.Bold,
            color = AttentionAmber, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        Text(
            lastPlayedText(hero.lastPlayed)?.let { "$it • ${hero.name}" } ?: hero.name,
            fontSize = 14.sp, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp),
        )
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PlayOrb(
                enabled = !s.busy, narrow = narrow, modifier = Modifier.testTag("hero-play"),
            ) { a.onSteamGame(hero) }
            if (s.busy || !s.ready || (s.available != null && s.available != s.installed)) RuntimeChip(s)
        }
    }
}

// Glass orb: a translucent circle with a coloured glow, a big icon and a label.
// When onClick is null the orb is visual only (the lip's own Box handles clicks).
@Composable
private fun GlassOrb(
    label: String,
    icon: ImageVector,
    glow: Color,
    arcOffset: Dp,
    float: Float,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)?,
) {
    val src = remember { MutableInteractionSource() }
    val hot = rememberHot(src)
    val still = Motion.scale == 0f
    val floatY = if (still) 0.dp else (-3).dp * float
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(OrbSize)
            .offset(y = arcOffset + floatY)
            .shadow(16.dp, CircleShape, spotColor = glow, ambientColor = glow.copy(alpha = 0.35f))
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.12f))
            .drawBehind {
                drawCircle(
                    brush = Brush.radialGradient(
                        listOf(glow.copy(alpha = 0.30f), Color.White.copy(alpha = 0.08f), Color.Transparent),
                        center = center,
                        radius = size.minDimension / 2f,
                    ),
                    radius = size.minDimension / 2f,
                )
                drawCircle(
                    brush = Brush.radialGradient(
                        listOf(Color.White.copy(alpha = 0.30f), Color.Transparent),
                        center = Offset(size.width * 0.30f, size.height * 0.26f),
                        radius = size.minDimension * 0.30f,
                    ),
                    radius = size.minDimension * 0.30f,
                    center = Offset(size.width * 0.30f, size.height * 0.26f),
                )
            }
            .border(1.dp, Color.White.copy(alpha = if (hot) 0.55f else 0.30f), CircleShape)
            .hoverable(src)
            .then(
                if (onClick != null) Modifier
                    .clickable(interactionSource = src, indication = LocalIndication.current, role = Role.Button, enabled = enabled) { onClick() }
                    .controllerConfirm(enabled = enabled, onClick = onClick)
                else Modifier,
            )
            .graphicsLayer { alpha = if (enabled) 1f else 0.5f },
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 4.dp),
        ) {
            Icon(icon, null, tint = Color.White, modifier = Modifier.size(26.dp))
            Text(
                label, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = Color.White,
                maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

// Large glowing golden orb: the hero play button. Radial gold, pulsing scale,
// glass shine, play triangle + label inside. Same handler and testTag as before.
@Composable
private fun PlayOrb(
    enabled: Boolean,
    narrow: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val orbSize = if (narrow) PlayOrbSizeNarrow else PlayOrbSize
    val still = Motion.scale == 0f
    val pulse = rememberInfiniteTransition(label = "playOrbPulse")
    val glow by pulse.animateFloat(0.55f, 1f, infiniteRepeatable(tween(1600, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "glow")
    val src = remember { MutableInteractionSource() }
    val scale = if (still) 1f else 1f + 0.03f * glow
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(orbSize)
            .graphicsLayer { scaleX = scale; scaleY = scale; alpha = if (enabled) 1f else 0.5f }
            .shadow(24.dp, CircleShape, spotColor = AttentionAmber, ambientColor = AttentionAmber.copy(alpha = 0.4f))
            .clip(CircleShape)
            .drawBehind {
                drawCircle(
                    brush = Brush.radialGradient(
                        listOf(Color(0xFFFFF3C4), AttentionAmber, Color(0xFFD97E06)),
                        center = Offset(size.width * 0.5f, size.height * 0.38f),
                        radius = size.minDimension * 0.62f,
                    ),
                    radius = size.minDimension / 2f,
                )
                drawCircle(
                    brush = Brush.radialGradient(
                        listOf(Color.White.copy(alpha = 0.45f), Color.Transparent),
                        center = Offset(size.width * 0.30f, size.height * 0.24f),
                        radius = size.minDimension * 0.30f,
                    ),
                    radius = size.minDimension * 0.30f,
                    center = Offset(size.width * 0.30f, size.height * 0.24f),
                )
            }
            .hoverable(src)
            .clickable(interactionSource = src, indication = LocalIndication.current, role = Role.Button, enabled = enabled) { onClick() }
            .controllerConfirm(enabled = enabled, onClick = onClick),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Filled.PlayArrow, null, tint = GoldInk, modifier = Modifier.size(if (narrow) 36.dp else 44.dp))
            Text(
                stringResource(R.string.games_launch), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = GoldInk,
                maxLines = 1, modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

@Composable
private fun SteamHeroShelf(shelf: List<Library.SteamGame>, narrow: Boolean) {
    val show = LocalShowSection.current
    val transition = rememberInfiniteTransition(label = "shelfBob")
    val bobs = listOf(2800, 3200, 3600).map { ms ->
        transition.animateFloat(0f, 1f, infiniteRepeatable(tween(ms, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "bob")
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val bubbleW = ((maxWidth - 10.dp * 2) / 3).coerceIn(96.dp, 260.dp)
        val bubbleH = if (narrow) 104.dp else 128.dp
        Column(Modifier.fillMaxWidth()) {
            Spacer(Modifier.height(18.dp))
            Text(stringResource(R.string.steam_recently_played), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = AttentionAmber)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                shelf.forEachIndexed { i, game ->
                    RecentBubble(game, bubbleW, bubbleH, bobs[i % bobs.size].value) { show("app:${game.appId}") }
                }
            }
        }
    }
}

// Images-only shelf card: no name text below, no flat coloured side panels. The
// title painted inside the art must survive, so the full art is fitted over a
// blurred, enlarged copy of itself filling the card (the Games hero's backdrop
// trick) — never a plain Crop, which cut titles before.
@Composable
private fun RecentBubble(game: Library.SteamGame, w: Dp, h: Dp, bob: Float, onOpen: () -> Unit) {
    val still = Motion.scale == 0f
    val shape = RoundedCornerShape(32.dp)
    val art = game.art ?: game.hero
    val src = remember { MutableInteractionSource() }
    val hot = rememberHot(src)
    Box(
        Modifier.size(w, h)
            .graphicsLayer { translationY = if (still) 0f else -4.dp.toPx() * bob }
            .clip(shape)
            .background(artBrush(hueOf(game.name)))
            .hoverable(src)
            .clickable(interactionSource = src, indication = LocalIndication.current, role = Role.Button, onClick = onOpen)
            .controllerConfirm(onClick = onOpen)
            .testTag("shelf-bubble:${game.appId}"),
    ) {
        if (art != null) {
            AsyncImage(
                model = art, contentDescription = null, contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().blur(24.dp).graphicsLayer { scaleX = 1.3f; scaleY = 1.3f },
            )
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.20f)))
            AsyncImage(
                model = art, contentDescription = game.name, contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().clip(shape),
            )
        }
        Box(Modifier.fillMaxSize().clip(shape).background(Brush.verticalGradient(0f to Color.White.copy(alpha = 0.10f), 0.35f to Color.Transparent)))
        Box(Modifier.fillMaxSize().border(1.dp, Color.White.copy(alpha = if (hot) 0.45f else 0.25f), shape))
    }
}

@Composable
internal fun WallFade() {
    val colors = MaterialTheme.colorScheme
    Box(
        Modifier.fillMaxSize().background(
            Brush.horizontalGradient(
                0f to colors.background.copy(alpha = 0.97f),
                0.34f to colors.background.copy(alpha = 0.86f),
                0.68f to colors.background.copy(alpha = 0.25f),
                1f to colors.background.copy(alpha = 0.1f),
            ),
        ),
    )
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.55f to Color.Transparent, 1f to colors.background.copy(alpha = 0.9f))))
}

@Composable
internal fun CapsuleWall(
    games: List<Library.SteamGame>,
    driftMs: Int = if (games.isEmpty()) 75_000 else 40_000,
    modifier: Modifier = Modifier,
    owned: List<Pair<Int, java.io.File>> = emptyList(),
    featured: List<com.droiddeck.launcher.frontend.SteamFeatured.Game> = emptyList(),
) {
    val pal = LocalPalette.current
    val blank = remember(pal) { WallCapsule(Brush.linearGradient(listOf(pal.surface, pal.background)), edge = pal.line) }
    val padding = remember(games, owned, featured) {
        val shown = games.map { it.appId }.toMutableSet()
        val library = owned.filter { shown.add(it.first) }.map { (id, art) -> id.toString() to art }
        val store = if (owned.isEmpty()) featured.filter { shown.add(it.appId) }.map { it.name to it.art } else emptyList()
        (library + store).take((WALL_DISTINCT - games.size).coerceAtLeast(0))
    }
    TiltedWall(games.size + padding.size, driftMs, modifier) { i ->
        when {
            i < games.size -> WallCapsule(artBrush(hueOf(games[i].name)), games[i].art)
            i - games.size < padding.size -> padding[i - games.size].let { (seed, art) -> WallCapsule(artBrush(hueOf(seed)), art) }
            else -> blank
        }
    }
}

internal class WallCapsule(val ground: Brush, val art: Any? = null, val edge: Color? = null, val icon: Any? = null, val bar: Color? = null)

@Composable
internal fun TiltedWall(
    count: Int,
    driftMs: Int,
    modifier: Modifier = Modifier,
    capW: androidx.compose.ui.unit.Dp = 112.dp,
    capH: androidx.compose.ui.unit.Dp = 168.dp,
    gap: androidx.compose.ui.unit.Dp = 14.dp,
    corner: androidx.compose.ui.unit.Dp = 10.dp,
    capsule: (index: Int) -> WallCapsule,
) {
    val still = Motion.scale == 0f
    val drift = remember { mutableFloatStateOf(0f) }
    val period = androidx.compose.runtime.rememberUpdatedState(driftMs)
    if (!still) LaunchedEffect(Unit) {
        var shown = 0L
        while (true) withFrameNanos { now ->
            if (shown == 0L) shown = now
            else if (now - shown >= DRIFT_STEP_NS) {
                drift.floatValue = ((drift.floatValue + (now - shown) / (period.value * 1_000_000.0 * Motion.scale)) % 1.0).toFloat()
                shown = now
            }
        }
    }
    val density = LocalDensity.current
    BoxWithConstraints(modifier.fillMaxSize()) {
        val tilt = Math.toRadians(13.0)
        val cos = kotlin.math.cos(tilt).toFloat()
        val sin = kotlin.math.sin(tilt).toFloat()
        val wallW = (maxWidth * cos + maxHeight * sin) * 1.1f
        val wallH = maxOf(maxHeight * 2.4f, (maxWidth * sin + maxHeight * cos) * 1.1f)
        val columns = ((wallW + gap) / (capW + gap)).toInt() + 1
        val perRun = ((wallH + gap) / (capH + gap)).toInt() + 1
        val run = (capH + gap) * perRun
        val cap = with(density) { Size(capW.toPx(), capH.toPx()) }
        val step = with(density) { (capH + gap).toPx() }
        val radius = with(density) { corner.toPx() }
        val line = with(density) { 1.dp.toPx() }
        val barH = with(density) { 12.dp.toPx() }
        val indexOf = { c: Int, i: Int -> if (count == 0) 0 else (c * 5 + (i % perRun) * 3) % count }
        val shown = remember(count, columns, perRun) { (0 until columns).flatMap { c -> (0 until perRun).map { indexOf(c, it) } }.distinct() }
        val looks = HashMap<Int, Pair<WallCapsule, Painter?>>(shown.size * 2)
        for (index in shown) key(index) {
            val look = capsule(index)
            looks[index] = look to (look.art?.let { rememberCapsuleArt(it, cap, radius) } ?: look.icon?.let { rememberIcon(it) })
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(gap),
            modifier = Modifier
                .wrapContentSize(Alignment.TopStart, unbounded = true)
                .offset(x = -(wallW - maxWidth) / 2, y = -(wallH - maxHeight) / 2)
                .requiredSize(wallW, wallH)
                .graphicsLayer { rotationZ = -13f },
        ) {
            repeat(columns) { c ->
                Spacer(
                    Modifier.width(capW).wrapContentHeight(Alignment.Top, unbounded = true).height(run * 2 - gap)
                        .graphicsLayer {
                            val shift = if (still) 0f else run.toPx() * drift.floatValue
                            translationY = if (c % 2 == 0) -shift else shift - run.toPx()
                        }
                        .drawBehind {
                            for (i in 0 until perRun * 2) {
                                val (look, art) = looks[indexOf(c, i)] ?: continue
                                val top = i * step
                                inset(0f, top, 0f, size.height - top - cap.height) { drawCapsule(look, art, radius, line, barH) }
                            }
                        },
                )
            }
        }
    }
}

@Composable
private fun rememberCapsuleArt(model: Any, size: Size, radius: Float): Painter {
    val context = LocalContext.current
    val w = size.width.roundToInt()
    val h = size.height.roundToInt()
    return rememberAsyncImagePainter(
        remember(model, w, h, radius) {
            ImageRequest.Builder(context).data(model).size(w, h).scale(Scale.FILL)
                .transformations(RoundedCornersTransformation(radius)).build()
        },
    )
}

@Composable
private fun rememberIcon(model: Any): Painter =
    if (model is Int) androidx.compose.ui.res.painterResource(model) else rememberAsyncImagePainter(model)

private fun DrawScope.drawCapsule(look: WallCapsule, art: Painter?, radius: Float, line: Float, barH: Float = 0f) {
    drawRoundRect(look.ground, size = size, cornerRadius = CornerRadius(radius))
    look.bar?.let { drawRect(it, Offset(0f, size.height - barH), Size(size.width, barH)) }
    if (art != null && look.icon != null) {
        val side = minOf(size.width, size.height - (if (look.bar != null) barH else 0f)) * 0.5f
        val cy = (size.height - (if (look.bar != null) barH else 0f)) / 2f
        translate((size.width - side) / 2f, cy - side / 2f) { with(art) { draw(Size(side, side)) } }
    } else if (art != null) with(art) { draw(size) }
    look.edge?.let {
        drawRoundRect(it, Offset(line / 2, line / 2), Size(size.width - line, size.height - line), CornerRadius(radius - line / 2), style = Stroke(line))
    }
}

@Composable
private fun CapsuleFan(games: List<Library.SteamGame>) {
    val pal = LocalPalette.current
    val density = LocalDensity.current
    val transition = rememberInfiniteTransition(label = "fan")
    val floats = listOf(9_000, 11_000, 13_000).map { ms ->
        transition.animateFloat(0f, 1f, infiniteRepeatable(tween(ms, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "float")
    }
    val still = Motion.scale == 0f
    val slots = listOf(
        FanSlot(0.54f, 0.14f, 0.62f, -9f),
        FanSlot(0.70f, 0.24f, 0.56f, 5f),
        FanSlot(0.40f, 0.30f, 0.50f, -17f),
    )
    BoxWithConstraints(
        Modifier.fillMaxSize().drawBehind {
            drawRect(
                Brush.radialGradient(
                    listOf(pal.signal.copy(alpha = 0.14f), Color.Transparent),
                    center = Offset(size.width * 0.68f, size.height * 0.45f), radius = size.height * 0.6f,
                ),
            )
        },
    ) {
        val radius = with(density) { 14.dp.toPx() }
        for (i in games.indices.reversed()) {
            val slot = slots[i]
            val h = maxHeight * slot.height
            val v: State<Float> = floats[i]
            val look = WallCapsule(artBrush(hueOf(games[i].name)), games[i].art)
            val art = look.art?.let { rememberCapsuleArt(it, with(density) { Size((h * 2f / 3f).toPx(), h.toPx()) }, radius) }
            Spacer(
                Modifier
                    .offset(x = maxWidth * slot.x, y = maxHeight * slot.y)
                    .size(h * 2f / 3f, h)
                    .graphicsLayer {
                        val t = if (still) 0f else v.value
                        rotationZ = slot.lean + 2f * t
                        translationY = -12.dp.toPx() * t
                        shadowElevation = 24.dp.toPx()
                        shape = RoundedCornerShape(14.dp)
                        clip = false
                    }
                    .drawBehind { drawCapsule(look, art, radius, 0f) },
            )
        }
    }
}

private class FanSlot(val x: Float, val y: Float, val height: Float, val lean: Float)
