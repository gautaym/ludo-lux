package lux.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import lux.audio.SfxId
import lux.board.PIECE_COIN
import lux.board.pawn
import lux.board.starPath
import lux.core.GameResult
import lux.core.Series
import lux.theme.BoardStyle
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

private fun ordinal(n: Int) = when (n) { 1 -> "1st"; 2 -> "2nd"; 3 -> "3rd"; else -> "${n}th" }

private val medalColors = listOf(
    Color(0xFFFFE27A) to Color(0xFFC98A0B),
    Color(0xFFF1F5F9) to Color(0xFF8B95A5),
    Color(0xFFFFC08A) to Color(0xFF9A5A2A),
)

@Composable
private fun Avatar(st: BoardStyle, color: Int, size: Int = 44) {
    val kind = LocalApp.current.settings.pieceStyle
    Canvas(Modifier.size(size.dp)) {
        val c = st.seat[color]
        drawCircle(Brush.radialGradient(listOf(lerp(c, Color.White, 0.25f), st.seatDeep[color]), center, this.size.minDimension / 2), this.size.minDimension / 2)
        drawCircle(Color.White.copy(alpha = 0.85f), this.size.minDimension / 2, style = Stroke(2.dp.toPx()))
        pawn(Offset(center.x, center.y + this.size.height * (if (kind == PIECE_COIN) 0.18f else 0.3f)), this.size.height * (if (kind == PIECE_COIN) 0.9f else 0.62f), st.pieceSeat[color], st.pieceDeep[color], st.pieceLight[color], st.collar, 1f, kind = kind)
    }
}

/**
 * Between games: what this game scored and the running totals, as a short bar race. One big "Next game" button,
 * so carrying on is the easy choice.
 */
@Composable
fun SeriesScoreboard(series: Series, last: GameResult, st: BoardStyle, onNext: () -> Unit, onMenu: () -> Unit) {
    val app = LocalApp.current
    val played = series.results.size
    val before = series.totalsBefore(played - 1)
    val after = IntArray(series.players) { series.total(it) }
    val order = series.standings()
    val top = max(1, after.max())
    val grow = remember(played) { Animatable(0f) }
    LaunchedEffect(played) {
        delay(350)
        app.sfx(SfxId.Select, 0.6f)
        grow.animateTo(1f, tween(1100, easing = FastOutSlowInEasing))
    }
    val doubled = series.multiplier(played - 1) > 1
    val nextIsFinal = !series.isOver && series.isFinal(series.gameIndex)

    Box(Modifier.fillMaxSize().background(Color(0xB3000000)).clickable(remember { MutableInteractionSource() }, null) {}, contentAlignment = Alignment.Center) {
        Panel(Modifier.widthIn(max = 460.dp).padding(18.dp)) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Label("Game $played of ${series.games}", 34.sp, Palette.gold, display = true, shadow = true)
                if (doubled) Label("Double points game!", 16.sp, Palette.textDim)
                for (seat in order) {
                    val s = series.seats[seat]
                    val gain = last.points[seat]
                    val shown = before[seat] + (after[seat] - before[seat]) * grow.value
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color(0x44000000)).padding(horizontal = 10.dp, vertical = 8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Avatar(st, s.color, 40)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Label(s.name, 22.sp, Color.White, display = true)
                                Label("${ordinal(last.ranks[seat])} place  ·  ${last.piecesHome[seat]} home", 13.sp, Palette.textDim)
                            }
                            // What this game added, popping in as the bars grow.
                            Box(
                                Modifier.scale(0.5f + 0.5f * grow.value).clip(RoundedCornerShape(50))
                                    .background(if (gain > 0) Palette.teal else Color(0xFF475569)).padding(horizontal = 8.dp, vertical = 2.dp),
                            ) { Label("+$gain", 16.sp, Color.White, display = true) }
                            Spacer(Modifier.width(10.dp))
                            Label("${shown.toInt()}", 30.sp, Palette.gold, display = true, modifier = Modifier.width(52.dp), align = TextAlign.End)
                        }
                        Spacer(Modifier.height(6.dp))
                        Canvas(Modifier.fillMaxWidth().height(10.dp)) {
                            val r = CornerRadius(size.height / 2)
                            drawRoundRect(Color(0x33FFFFFF), cornerRadius = r)
                            val w = size.width * (shown / top).coerceIn(0f, 1f)
                            if (w > 1f) drawRoundRect(Brush.horizontalGradient(listOf(st.seatDeep[s.color], st.seat[s.color], lerp(st.seat[s.color], Color.White, 0.3f)), 0f, w), size = Size(w, size.height), cornerRadius = r)
                        }
                    }
                }
                if (nextIsFinal) {
                    Box(Modifier.clip(RoundedCornerShape(12.dp)).background(Color(0x33FFC94A)).padding(horizontal = 12.dp, vertical = 6.dp)) {
                        Label("Final game counts DOUBLE - anyone can still win!", 15.sp, Palette.gold, align = TextAlign.Center)
                    }
                }
                Spacer(Modifier.height(2.dp))
                BigButton(if (nextIsFinal) "Final game" else "Next game", onNext, Modifier.fillMaxWidth(), 70.dp, textSize = 32.sp, icon = { playIcon(it) })
                BigButton("Menu", onMenu, Modifier.fillMaxWidth(0.6f), 50.dp, Color(0xFFE2E8F0), Color(0xFF94A3B8), Color(0xFF1E293B), 20.sp)
                Label("The series is saved - you can continue it later.", 12.sp, Palette.textDim, align = TextAlign.Center)
            }
        }
    }
}

/** The end of the series: podium, trophies, confetti and (only now) the Loser sign. */
@Composable
fun SeriesChampion(series: Series, st: BoardStyle, onNew: () -> Unit, onMenu: () -> Unit) {
    val app = LocalApp.current
    val order = series.standings()
    val places = series.places()
    val champ = series.seats[order[0]]
    val party = rememberInfiniteTransition()
    val t by party.animateFloat(0f, 600f, infiniteRepeatable(tween(600_000, easing = LinearEasing)))
    val rise = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        app.sfx(SfxId.Victory)
        rise.animateTo(1f, spring(dampingRatio = 0.55f, stiffness = 120f))
    }
    val title = if (series.games == 1) "Winner!" else "Series champion!"

    Box(Modifier.fillMaxSize().background(Color(0xCC03100F)).clickable(remember { MutableInteractionSource() }, null) {}) {
        // Confetti falling over everything.
        Canvas(Modifier.fillMaxSize()) {
            val pal = st.seat + listOf(Palette.gold, Color.White)
            for (i in 0 until 70) {
                val seed = i * 7919
                val x = ((seed % 1000) / 1000f + sin(t * 0.7f + i) * 0.03f) * size.width
                val speed = 0.06f + (seed % 37) / 37f * 0.08f
                val y = (((t * speed + (seed % 97) / 97f) % 1.1f) - 0.05f) * size.height
                val w = 7.dp.toPx(); val h = 12.dp.toPx() * (0.4f + 0.6f * kotlin.math.abs(cos(t * 3f + i)))
                rotate(t * 90f + i * 40f, Offset(x, y)) { drawRect(pal[i % pal.size], Offset(x - w / 2, y - h / 2), Size(w, h)) }
            }
        }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Spacer(Modifier.height(10.dp))
            Label(title, 40.sp, Palette.gold, display = true, shadow = true, align = TextAlign.Center)
            Label(champ.name, 52.sp, lerp(st.seat[champ.color], Color.White, 0.35f), display = true, shadow = true, align = TextAlign.Center)
            Podium(series, order, st, rise.value, t)
            Panel(Modifier.widthIn(max = 460.dp).fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (seat in order) {
                        val s = series.seats[seat]
                        val place = places[seat]
                        val last = place == series.players && series.players >= 2
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color(0x44000000)).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
                                Canvas(Modifier.fillMaxSize()) {
                                    val (a, b) = medalColors.getOrNull(place - 1) ?: (Color(0xFF64748B) to Color(0xFF334155))
                                    drawCircle(Brush.linearGradient(listOf(a, b)), size.minDimension / 2)
                                    drawCircle(Color.White.copy(alpha = 0.7f), size.minDimension / 2, style = Stroke(2.dp.toPx()))
                                }
                                Label("$place", 18.sp, Color.White, display = true, shadow = true)
                            }
                            Spacer(Modifier.width(8.dp))
                            Avatar(st, s.color, 36)
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Label(s.name, 20.sp, Color.White, display = true)
                                Label("${series.wins(seat)} wins  ·  ${series.piecesHome(seat)} home", 12.sp, Palette.textDim)
                            }
                            if (last) {
                                Box(Modifier.clip(RoundedCornerShape(6.dp)).background(Color(0xFF8B5A2B)).border(1.5.dp, Color(0xFF4A2C10), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 1.dp)) {
                                    Label("LOSER", 13.sp, Color(0xFFFDF2DD), display = true)
                                }
                                Spacer(Modifier.width(8.dp))
                            }
                            Label("${series.total(seat)}", 28.sp, Palette.gold, display = true)
                        }
                    }
                }
            }
            Row(Modifier.widthIn(max = 460.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                BigButton("New series", onNew, Modifier.weight(1.4f), 64.dp, textSize = 26.sp)
                BigButton("Menu", onMenu, Modifier.weight(1f), 64.dp, Color(0xFFE2E8F0), Color(0xFF94A3B8), Color(0xFF1E293B), 22.sp)
            }
            Spacer(Modifier.height(10.dp))
        }
    }
}

/** Gold in the middle, silver left, bronze right; each step carries that player's pawn and a trophy. */
@Composable
private fun Podium(series: Series, order: List<Int>, st: BoardStyle, rise: Float, t: Float) {
    val kind = LocalApp.current.settings.pieceStyle
    val slots = listOf(1, 0, 2).filter { it < order.size }   // 2nd, 1st, 3rd from left to right
    Canvas(Modifier.widthIn(max = 420.dp).fillMaxWidth().height(230.dp)) {
        val w = size.width / 3
        val heights = floatArrayOf(0.5f, 0.36f, 0.26f)
        for (slot in slots) {
            val seat = order[slot]
            val color = series.seats[seat].color
            val col = when (slot) { 1 -> 0; 0 -> 1; else -> 2 }
            val h = size.height * heights[slot] * rise
            val left = col * w + w * 0.06f
            val top = size.height - h
            val (a, b) = medalColors[slot]
            drawRoundRect(Brush.verticalGradient(listOf(a, b), top, size.height), Offset(left, top), Size(w * 0.88f, h), CornerRadius(10.dp.toPx()))
            drawRoundRect(Color.Black.copy(alpha = 0.25f), Offset(left, top), Size(w * 0.88f, h), CornerRadius(10.dp.toPx()), style = Stroke(2.dp.toPx()))
            val cx = left + w * 0.44f
            if (h > 30.dp.toPx()) drawPath(starPath(cx, top + h * 0.45f, 16.dp.toPx(), 7.dp.toPx(), 5), Color.White.copy(alpha = 0.85f))
            // The player's pawn bouncing on top of its step; the winner bounces highest.
            val bounce = kotlin.math.abs(sin(t * (if (slot == 0) 5f else 3.5f) + slot)) * (if (slot == 0) 14f else 6f) * rise
            val u = (if (slot == 0) 62f else 52f).dp.toPx()
            pawn(Offset(cx, top - 4.dp.toPx() - bounce.dp.toPx()), u * (if (kind == PIECE_COIN) 1.3f else 1f), st.pieceSeat[color], st.pieceDeep[color], st.pieceLight[color], st.collar, rise.coerceIn(0f, 1f), kind = kind)
            if (slot == 0) {
                // Twinkling crown of light over the champion.
                for (i in 0 until 5) {
                    val ang = t * 1.4f + i * (2 * PI.toFloat() / 5)
                    val r = u * 0.55f
                    val o = Offset(cx + cos(ang) * r, top - u * 1.05f - bounce.dp.toPx() + sin(ang) * r * 0.35f)
                    drawPath(starPath(o.x, o.y, 6.dp.toPx(), 1.8.dp.toPx(), 4), Palette.gold.copy(alpha = rise.coerceIn(0f, 1f)))
                }
            }
        }
    }
}

/** A small round marker for a colour (used by the menu's "Continue series" button). */
@Composable
fun ColorDots(st: BoardStyle, colors: List<Int>) {
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        for (c in colors) Box(Modifier.size(12.dp).clip(CircleShape).background(st.seat[c]).border(1.5.dp, Color.White, CircleShape))
    }
}
