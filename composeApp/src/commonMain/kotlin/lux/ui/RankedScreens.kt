package lux.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import lux.app.Nav
import lux.app.Screen
import lux.audio.SfxId
import lux.board.starPath
import lux.core.Ranked
import lux.core.net.Leaderboard
import lux.core.net.LeaderRow
import lux.core.net.QueueState
import lux.core.net.RankedProfile
import lux.core.net.RankedResult
import lux.core.net.RoomInfo
import lux.net.NetError
import lux.theme.BoardStyle
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Colours of each tier's emblem (Bronze .. Champion), plus grey for placement. */
private val tierColors = listOf(
    Color(0xFFCD7F32) to Color(0xFF7A4318),
    Color(0xFFDDE3EA) to Color(0xFF7D8896),
    Color(0xFFFFD45C) to Color(0xFFB7791F),
    Color(0xFF6EE7D8) to Color(0xFF0F766E),
    Color(0xFF93C5FD) to Color(0xFF4338CA),
    Color(0xFFF9A8D4) to Color(0xFFBE185D),
)
private val placementColors = Color(0xFF94A3B8) to Color(0xFF475569)
private val Purple = Color(0xFFF0ABFC)
private val PurpleDeep = Color(0xFF9333EA)
private val PurpleInk = Color(0xFF2E0A3D)

/** Tier index for a division name like "Gold II" (or -1 for placement). */
private fun tierOf(name: String) = Ranked.TIERS.indexOfFirst { name.startsWith(it) }

/**
 * A tier emblem: a faceted shield in the tier's metal, with its step as a numeral. Champion wears a crown;
 * placement is a plain grey shield with a question mark.
 */
@Composable
fun TierEmblem(division: String, size: Int, modifier: Modifier = Modifier) {
    val fonts = LocalFonts.current
    val measurer = rememberTextMeasurer()
    val tier = tierOf(division)
    val (hi, lo) = if (tier < 0) placementColors else tierColors[tier]
    val step = division.substringAfter(' ', "").let { if (division == "Champion") "" else it }
    val shine = rememberInfiniteTransition()
    val t by shine.animateFloat(0f, 1f, infiniteRepeatable(tween(3200, easing = LinearEasing)))
    Canvas(modifier.size(size.dp)) {
        val w = this.size.width; val h = this.size.height
        val shield = Path().apply {
            moveTo(w * 0.5f, h * 0.04f); lineTo(w * 0.9f, h * 0.18f); lineTo(w * 0.86f, h * 0.58f)
            quadraticTo(w * 0.78f, h * 0.84f, w * 0.5f, h * 0.97f); quadraticTo(w * 0.22f, h * 0.84f, w * 0.14f, h * 0.58f)
            lineTo(w * 0.1f, h * 0.18f); close()
        }
        // Wings for the higher tiers.
        if (tier >= 3) for (side in listOf(-1f, 1f)) {
            val wing = Path().apply {
                moveTo(w * 0.5f + side * w * 0.3f, h * 0.3f); lineTo(w * 0.5f + side * w * 0.5f, h * 0.2f)
                lineTo(w * 0.5f + side * w * 0.42f, h * 0.45f); lineTo(w * 0.5f + side * w * 0.48f, h * 0.5f); lineTo(w * 0.5f + side * w * 0.32f, h * 0.62f); close()
            }
            drawPath(wing, Brush.verticalGradient(listOf(hi, lo)))
            drawPath(wing, lerp(lo, Color.Black, 0.4f), style = Stroke(w * 0.02f))
        }
        drawPath(shield, Brush.linearGradient(listOf(lerp(hi, Color.White, 0.35f), hi, lo), Offset(0f, 0f), Offset(w, h)))
        drawPath(shield, lerp(lo, Color.Black, 0.45f), style = Stroke(w * 0.035f))
        // Inner bevel.
        val inner = Path().apply {
            moveTo(w * 0.5f, h * 0.13f); lineTo(w * 0.8f, h * 0.24f); lineTo(w * 0.77f, h * 0.56f)
            quadraticTo(w * 0.7f, h * 0.77f, w * 0.5f, h * 0.87f); quadraticTo(w * 0.3f, h * 0.77f, w * 0.23f, h * 0.56f)
            lineTo(w * 0.2f, h * 0.24f); close()
        }
        drawPath(inner, Color.White.copy(alpha = 0.55f), style = Stroke(w * 0.018f))
        // A slow shine sweeping across.
        val x = (t * 1.6f - 0.3f) * w
        clipPath(shield) {
            drawPath(Path().apply { moveTo(x, 0f); lineTo(x + w * 0.12f, 0f); lineTo(x - w * 0.2f, h); lineTo(x - w * 0.32f, h); close() }, Color.White.copy(alpha = 0.22f))
        }
        if (tier == Ranked.CHAMPION) {
            val crown = Path().apply {
                moveTo(w * 0.3f, h * 0.2f); lineTo(w * 0.34f, h * 0.02f); lineTo(w * 0.42f, h * 0.12f); lineTo(w * 0.5f, -h * 0.03f)
                lineTo(w * 0.58f, h * 0.12f); lineTo(w * 0.66f, h * 0.02f); lineTo(w * 0.7f, h * 0.2f); close()
            }
            drawPath(crown, Brush.verticalGradient(listOf(Color(0xFFFFE27A), Color(0xFFC98A0B))))
            drawPath(crown, Color(0xFF6B3A00), style = Stroke(w * 0.02f))
            drawPath(starPath(w * 0.5f, h * 0.5f, w * 0.2f, w * 0.085f, 5), Color.White)
        }
        val label = if (tier < 0) "?" else step
        if (label.isNotEmpty()) {
            val style = TextStyle(color = Color.White, fontFamily = fonts.display, fontSize = (h * (if (label.length > 2) 0.26f else 0.34f) / density).sp)
            val m = measurer.measure(label, style)
            drawText(m, color = lerp(lo, Color.Black, 0.5f), topLeft = Offset(w / 2 - m.size.width / 2f + w * 0.015f, h * 0.48f - m.size.height / 2f + w * 0.02f))
            drawText(m, topLeft = Offset(w / 2 - m.size.width / 2f, h * 0.48f - m.size.height / 2f))
        }
    }
}

// ====================================================================== the ranked home

@Composable
fun RankedScreen(nav: Nav) {
    val app = LocalApp.current
    val net = app.net
    val scope = rememberCoroutineScope()
    var me by remember { mutableStateOf<RankedProfile?>(null) }
    var board by remember { mutableStateOf<Leaderboard?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var searching by remember { mutableStateOf(0) }       // 0 = not searching, else players (2 or 4)
    var waited by remember { mutableStateOf(0) }
    var inQueue by remember { mutableStateOf(0) }
    nav.onBack = { if (searching != 0) { scope.launch { net.cancelQueue() }; searching = 0 } else nav.screen = Screen.Menu }

    LaunchedEffect(net.signedIn) {
        if (!net.signedIn) return@LaunchedEffect
        try { me = net.rankedMe(); board = net.leaderboard(); error = null } catch (e: NetError) { error = e.message }
    }
    // While searching, poll the ticket; once matched, go straight into the game.
    LaunchedEffect(searching) {
        if (searching == 0) return@LaunchedEffect
        try {
            var st = net.queue(searching)
            while (st.state == QueueState.Waiting) {
                waited = st.waited; inQueue = st.inQueue
                delay(1000)
                st = net.queueStatus()
            }
            if (st.state == QueueState.Matched) {
                app.sfx(SfxId.HomeEnter, 0.7f)
                nav.screen = Screen.Room(st.room, "")
            } else searching = 0
        } catch (e: NetError) { error = e.message; searching = 0 }
    }

    OnlinePage("Ranked", { nav.onBack?.invoke() }) {
        if (!net.signedIn) { NameStep("Ranked games against players of your level.", "What name should other players see?"); return@OnlinePage }
        ErrorNote(error)
        val p = me
        Panel(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TierEmblem(p?.division?.takeIf { p.placed } ?: "Placement", 104)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (p == null) Label("Loading...", 20.sp, Palette.textDim)
                    else if (!p.placed) {
                        Label("Placement", 30.sp, Color.White, display = true)
                        Label("${p.placementPlayed} of ${p.placementGames} games played", 16.sp, Palette.textDim)
                        Label("Your rank is revealed after ${p.placementGames} games.", 13.sp, Palette.textDim)
                    } else {
                        Label(p.division, 32.sp, lerp(tierColors[p.tier].first, Color.White, 0.2f), display = true, shadow = true)
                        if (p.shield > 0) Label("Protected: ${p.shield} more ${if (p.shield == 1) "game" else "games"}", 14.sp, Color(0xFF86EFAC))
                        Label("#${p.position} this season  ·  ${p.wins} wins in ${p.games}", 14.sp, Palette.textDim)
                    }
                    if (p != null) {
                        // Progress through the current step (or through placement).
                        Canvas(Modifier.fillMaxWidth().height(12.dp)) {
                            val r = androidx.compose.ui.geometry.CornerRadius(size.height / 2)
                            drawRoundRect(Color(0x33FFFFFF), cornerRadius = r)
                            val c = if (p.placed) tierColors[p.tier] else placementColors
                            drawRoundRect(Brush.horizontalGradient(listOf(c.second, c.first)), size = Size(size.width * p.progress.coerceIn(0.03f, 1f), size.height), cornerRadius = r)
                        }
                    }
                }
            }
        }
        p?.let { Label("Season ${it.season}  ·  ${it.seasonDaysLeft} days left${if (it.peak.isNotEmpty()) "  ·  Best: ${it.peak}" else ""}", 14.sp, Palette.textDim, align = TextAlign.Center, modifier = Modifier.fillMaxWidth()) }

        Label("Find a match", 22.sp, Color.White, display = true)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            BigButton("2 players", { searching = 2 }, Modifier.weight(1f), 66.dp, Purple, PurpleDeep, PurpleInk, 24.sp)
            BigButton("4 players", { searching = 4 }, Modifier.weight(1f), 66.dp, Purple, PurpleDeep, PurpleInk, 24.sp)
        }
        Label("Ranked games are one game each, against people only. Leaving a game counts as last place.", 13.sp, Palette.textDim)

        val b = board
        if (b != null) Panel(Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Section("Season leaderboard") {}
                if (b.rows.isEmpty()) Label("Nobody has finished placement yet this season. Be the first!", 16.sp, Palette.textDim)
                for (r in b.rows) LeaderRowView(r)
                b.mine?.let { Spacer(Modifier.height(4.dp)); LeaderRowView(it) }
            }
        }
    }

    AnimatedVisibility(searching != 0, enter = fadeIn(), exit = fadeOut()) {
        Searching(searching, waited, inQueue) { scope.launch { net.cancelQueue() }; searching = 0 }
    }
}

@Composable
private fun LeaderRowView(r: LeaderRow) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (r.me) Color(0x33F0ABFC) else Color(0x33000000)).padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Label("#${r.place}", 18.sp, Palette.gold, display = true, modifier = Modifier.width(48.dp))
        TierEmblem(r.division, 32)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Label(r.name + if (r.me) " (you)" else "", 18.sp, Color.White, display = true)
            Label("${r.division}  ·  ${r.wins} wins", 12.sp, Palette.textDim)
        }
    }
}

/** "Finding players..." with a spinning die-pip ring, the wait time, and Cancel. */
@Composable
private fun Searching(players: Int, waited: Int, inQueue: Int, onCancel: () -> Unit) {
    val spin = rememberInfiniteTransition()
    val a by spin.animateFloat(0f, 360f, infiniteRepeatable(tween(1600, easing = LinearEasing)))
    Box(Modifier.fillMaxSize().background(Color(0xD9000000)).clickable(remember { MutableInteractionSource() }, null) {}, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.padding(24.dp)) {
            Canvas(Modifier.size(120.dp)) {
                rotate(a) {
                    for (i in 0 until 8) {
                        val ang = i * PI.toFloat() / 4
                        val k = 0.3f + 0.7f * (i / 7f)
                        drawCircle(Purple.copy(alpha = k), size.minDimension * 0.07f * (0.6f + 0.4f * k), Offset(center.x + cos(ang) * size.minDimension * 0.38f, center.y + sin(ang) * size.minDimension * 0.38f))
                    }
                }
            }
            Label("Finding $players-player match...", 26.sp, Color.White, display = true, align = TextAlign.Center)
            Label("Waiting ${waited}s  ·  $inQueue looking now", 16.sp, Palette.textDim)
            Label("We look for players close to your level first.", 14.sp, Palette.textDim, align = TextAlign.Center)
            BigButton("Cancel", onCancel, Modifier.width(200.dp), 56.dp, Color(0xFFE2E8F0), Color(0xFF94A3B8), Color(0xFF1E293B), 22.sp)
        }
    }
}

/** A ranked match was found: who is playing, at what rank, and it starts by itself. */
@Composable
fun MatchFound(room: RoomInfo, leave: () -> Unit) {
    val st = lux.theme.BoardStyles.get(room.board)
    OnlinePage("Match found!", leave) {
        Panel(Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                for (s in room.seats) Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(18.dp).clip(CircleShape).background(st.seat[s.color]).border(2.dp, Color.White, CircleShape))
                    Spacer(Modifier.width(10.dp))
                    TierEmblem(s.rank.ifEmpty { "Placement" }, 40)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Label(s.name, 22.sp, Color.White, display = true)
                        Label(s.rank.ifEmpty { "Placement" }, 14.sp, Palette.textDim)
                    }
                    Label(if (s.connected) "Ready" else "Joining...", 14.sp, if (s.connected) Color(0xFF86EFAC) else Palette.textDim)
                }
            }
        }
        Label("The game starts as soon as everyone is here.", 18.sp, Palette.gold, display = true, align = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    }
}

/** After a ranked game: my change, big, with promotion fanfare; then everyone's results. */
@Composable
fun RankedResultOverlay(r: RankedResult, st: BoardStyle, me: String, onAgain: () -> Unit, onDone: () -> Unit) {
    val app = LocalApp.current
    val mine = r.changes.firstOrNull { it.userId == me }
    val pop = remember { Animatable(0f) }
    LaunchedEffect(r) {
        if (mine?.promoted == true) app.sfx(SfxId.Victory) else app.sfx(SfxId.Select, 0.7f)
        pop.animateTo(1f, spring(dampingRatio = 0.5f, stiffness = 140f))
    }
    Box(Modifier.fillMaxSize().background(Color(0xCC03100F)).clickable(remember { MutableInteractionSource() }, null) {}, contentAlignment = Alignment.Center) {
        Panel(Modifier.widthIn(max = 460.dp).padding(18.dp)) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (mine != null) {
                    val title = when {
                        mine.promoted && mine.before == "Placement" -> "You are ${mine.after}!"
                        mine.promoted -> "Promoted to ${mine.after}!"
                        mine.demoted -> "Down to ${mine.after}"
                        mine.place == 1 -> "You won!"
                        else -> "${ordinalOf(mine.place)} place"
                    }
                    Label(title, 32.sp, if (mine.demoted) Palette.textDim else Palette.gold, display = true, shadow = true, align = TextAlign.Center)
                    TierEmblem(mine.after, 120, Modifier.scale(0.6f + 0.4f * pop.value))
                    if (mine.placement > 0) Label("Placement ${mine.placement} of ${Ranked.PLACEMENT_GAMES}", 18.sp, Color.White, display = true)
                    else Label("${if (mine.delta >= 0) "+" else ""}${mine.delta}", 40.sp, if (mine.delta >= 0) Color(0xFF86EFAC) else Color(0xFFFCA5A5), display = true, shadow = true)
                }
                for (c in r.changes.sortedBy { it.place }) {
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (c.userId == me) Color(0x33F0ABFC) else Color(0x33000000)).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Label("${c.place}", 22.sp, Palette.gold, display = true, modifier = Modifier.width(28.dp))
                        Box(Modifier.size(16.dp).clip(CircleShape).background(st.seat[c.color]).border(2.dp, Color.White, CircleShape))
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Label(c.name + if (c.left) " (left)" else "", 18.sp, Color.White, display = true)
                            Label(c.after, 12.sp, Palette.textDim)
                        }
                        if (c.placement == 0) Label("${if (c.delta >= 0) "+" else ""}${c.delta}", 20.sp, if (c.delta >= 0) Color(0xFF86EFAC) else Color(0xFFFCA5A5), display = true)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    BigButton("Play again", onAgain, Modifier.weight(1.3f), 60.dp, Purple, PurpleDeep, PurpleInk, 22.sp)
                    BigButton("Done", onDone, Modifier.weight(1f), 60.dp, Color(0xFFE2E8F0), Color(0xFF94A3B8), Color(0xFF1E293B), 22.sp)
                }
            }
        }
    }
}

private fun ordinalOf(n: Int) = when (n) { 1 -> "1st"; 2 -> "2nd"; 3 -> "3rd"; else -> "${n}th" }
