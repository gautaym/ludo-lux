package lux.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import lux.board.ART_CELLS
import lux.board.SceneFonts
import lux.board.View
import lux.board.drawBackdrop
import lux.board.drawScene
import lux.board.PIECE_COIN
import lux.board.pawn
import lux.board.rememberBoard
import lux.game.Geo
import lux.game.MatchDirector
import lux.game.Stage
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt

/** Drives the stage clock from the display's frames. */
@Composable
fun StageClock(stage: Stage) {
    val scope = rememberCoroutineScope()
    stage.scope = scope
    LaunchedEffect(stage) {
        var last = -1L
        while (true) {
            withFrameNanos { t ->
                val dt = if (last < 0) 1f / 60 else (t - last) / 1e9f
                last = t
                stage.onFrame(dt)
            }
        }
    }
}

@Composable
fun MatchScreen(
    stage: Stage, director: MatchDirector, paused: Boolean, onPause: () -> Unit, onResume: () -> Unit,
    onRestart: () -> Unit, onQuit: () -> Unit,
    /** Series points per seat so far (shown on the plates), or null outside a series. */
    seriesPoints: IntArray? = null,
    /** Short label for the top bar, e.g. "Game 2 of 3". */
    seriesLabel: String? = null,
    restartLabel: String = "Restart",
    /** Shown instead of the single-game results when the game ends (the series scoreboard). */
    endOverlay: (@Composable () -> Unit)? = null,
) {
    val app = LocalApp.current
    val fonts = LocalFonts.current
    val st = stage.style
    val measurer = rememberTextMeasurer()
    val sceneFonts = remember(measurer, fonts) { SceneFonts(measurer, fonts.display) }
    stage.paused = paused

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        val topBar = with(density) { 64.dp.toPx() }
        val plateH = with(density) { 84.dp.toPx() }
        val margin = with(density) { 6.dp.toPx() }
        // The board is as big as the screen allows (edge to edge in portrait); the player plates sit above and below it.
        val side = floor(min(w - 2 * margin, h - topBar - 2 * plateH - 2 * margin))
        val left = ((w - side) / 2).roundToInt().toFloat()
        // Plates + board + plates as one block, centred in the space under the top bar.
        val top = (topBar + plateH + ((h - topBar - 2 * plateH - side) / 2).coerceAtLeast(0f) * 0.85f).roundToInt().toFloat()
        val view = View(left, top, side / ART_CELLS, stage.viewTurns)
        val boardImg = rememberBoard(st, side.toInt())

        Canvas(
            Modifier.fillMaxSize().pointerInput(stage, view.turns, side) {
                detectTapGestures { o -> if (!stage.paused) director.tapBoard(view.toGrid(o)) }
            },
        ) {
            stage.frameCount // redraw every frame
            drawBackdrop(st, stage.time)
            drawScene(stage, view, boardImg, sceneFonts)
        }

        // Player plates in the corners outside the board, next to each player's own yard.
        if (!director.showcase) {
            val rv = director.rankVersion
            director.seats.forEachIndexed { seat, cfg ->
                val corner = Geo.rotate(Geo.baseCentre(cfg.color), stage.viewTurns)
                val isLeft = corner.x < 7.5f
                val isTop = corner.y < 7.5f
                val pw = with(density) { (w / density.density / 2 - 10).dp }
                val x = if (isLeft) margin else w - margin - with(density) { pw.toPx() }
                val y = if (isTop) top - plateH + with(density) { 4.dp.toPx() } else top + side + with(density) { 4.dp.toPx() }
                Box(Modifier.offset { IntOffset(x.roundToInt(), y.roundToInt()) }.width(pw).height(with(density) { (plateH - with(density) { 8.dp.toPx() }).toDp() })) {
                    PlayerPlate(
                        name = cfg.name, cpu = cfg.cpu, color = st.seat[cfg.color], deep = st.seatDeep[cfg.color], light = st.seatLight[cfg.color], collar = st.collar,
                        pieceColor = st.pieceSeat[cfg.color], pieceDeep = st.pieceDeep[cfg.color], pieceLight = st.pieceLight[cfg.color],
                        active = director.current == seat && !director.over, hint = if (director.current == seat) director.hint else "",
                        rank = director.rankOf(seat).takeIf { rv >= 0 && it > 0 && (director.state?.isActive(seat) == false) } ?: 0,
                        seatCount = director.seats.size, alignRight = !isLeft, seriesPoints = seriesPoints?.getOrNull(seat) ?: -1, softLast = director.softLastPlace,
                        onTap = { if (director.current == seat && !cfg.cpu) director.requestRoll() },
                    )
                }
            }
        }

        // Top bar: pause on the left, short announcements in the middle (never over the board).
        Row(Modifier.fillMaxWidth().height(with(density) { topBar.toDp() }).padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            RoundIcon(if (director.showcase) onQuit else onPause, size = 50.dp) { if (director.showcase) closeIcon(it) else pauseIcon(it) }
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { ToastView(director) }
            Box(Modifier.width(96.dp), contentAlignment = Alignment.CenterEnd) {
                if (seriesLabel != null) {
                    Box(Modifier.clip(RoundedCornerShape(50)).background(Color(0xCC0B2129)).border(1.5.dp, Palette.gold.copy(alpha = 0.7f), RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 4.dp)) {
                        Label(seriesLabel, 15.sp, Palette.gold, display = true, align = TextAlign.Center)
                    }
                }
            }
        }

        AnimatedVisibility(paused && !director.showResults, enter = fadeIn(), exit = fadeOut()) {
            PauseOverlay(onResume, onRestart, onQuit, restartLabel)
        }
        AnimatedVisibility(director.showResults, enter = fadeIn(tween(400)), exit = fadeOut()) {
            if (endOverlay != null) endOverlay() else ResultsOverlay(director, onRestart, onQuit)
        }
    }
}

@Composable
private fun ToastView(director: MatchDirector) {
    val t = director.toast ?: return
    val pop = remember(t.id) { Animatable(0f) }
    var visible by remember(t.id) { mutableStateOf(true) }
    LaunchedEffect(t.id) {
        pop.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = Spring.StiffnessMediumLow))
        // Counted in frames (not wall-clock time) so it pauses with the game and works in headless renders.
        var start = -1L
        var done = false
        while (!done) withFrameNanos { n -> if (start < 0) start = n; done = (n - start) / 1e9f >= t.seconds }
        pop.animateTo(0f, tween(200))
        visible = false
    }
    if (!visible) return
    Box(
        Modifier.scale(0.6f + 0.4f * pop.value).clip(RoundedCornerShape(50))
            .background(Brush.verticalGradient(listOf(Color(0xF2193D49), Color(0xF20B232B))))
            .border(2.dp, t.color.copy(alpha = 0.9f), RoundedCornerShape(50))
            .padding(horizontal = 18.dp, vertical = 6.dp),
    ) { Label(t.text, 24.sp, lerp(t.color, Color.White, 0.35f), display = true, shadow = true) }
}

@Composable
private fun PlayerPlate(
    name: String, cpu: Boolean, color: Color, deep: Color, light: Color, collar: Color,
    pieceColor: Color, pieceDeep: Color, pieceLight: Color, active: Boolean, hint: String,
    rank: Int, seatCount: Int, alignRight: Boolean, seriesPoints: Int, softLast: Boolean, onTap: () -> Unit,
) {
    val app = LocalApp.current
    val glow by animateFloatAsState(if (active) 1f else 0f, tween(250))
    val shape = RoundedCornerShape(20.dp)
    Row(
        Modifier.fillMaxSize().clip(shape)
            .background(Brush.horizontalGradient(if (alignRight) listOf(Color(0xE60B2129), lerp(Color(0xE60B2129), deep, 0.55f * glow + 0.2f)) else listOf(lerp(Color(0xE60B2129), deep, 0.55f * glow + 0.2f), Color(0xE60B2129))))
            .border((2 + 2 * glow).dp, lerp(Color.White.copy(alpha = 0.25f), color.copy(alpha = 1f), glow), shape)
            .clickable(remember { MutableInteractionSource() }, null, onClick = onTap)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (alignRight) Arrangement.End else Arrangement.Start,
    ) {
        val avatar: @Composable () -> Unit = {
            Canvas(Modifier.size(52.dp)) {
                drawCircle(Brush.radialGradient(listOf(lerp(color, Color.White, 0.25f), deep), center, size.minDimension / 2), size.minDimension / 2)
                drawCircle(Color.White.copy(alpha = 0.85f), size.minDimension / 2, style = Stroke(2.5.dp.toPx()))
                val kind = app.settings.pieceStyle
                pawn(Offset(center.x, center.y + size.height * (if (kind == PIECE_COIN) 0.18f else 0.3f)), size.height * (if (kind == PIECE_COIN) 0.9f else 0.62f), pieceColor, pieceDeep, pieceLight, collar, 1f, kind = kind)
            }
        }
        if (!alignRight) { avatar(); Spacer(Modifier.width(10.dp)) }
        Column(Modifier.weight(1f), horizontalAlignment = if (alignRight) Alignment.End else Alignment.Start) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Label(name, 22.sp, Color.White, display = true, align = if (alignRight) TextAlign.End else TextAlign.Start)
                if (seriesPoints >= 0) {
                    Spacer(Modifier.width(6.dp))
                    // Series points so far: a small gold badge, so everyone always knows how close it is.
                    Box(Modifier.clip(RoundedCornerShape(50)).background(Palette.gold).padding(horizontal = 7.dp)) {
                        Label("$seriesPoints", 16.sp, Color(0xFF3A1F00), display = true)
                    }
                }
            }
            val sub = when {
                rank > 0 -> if (rank == seatCount) (if (softLast) "Next round!" else "Last place") else listOf("", "Winner!", "2nd place", "3rd place")[rank.coerceAtMost(3)]
                hint.isNotEmpty() -> hint
                active -> if (cpu) "Thinking..." else "Your turn"
                else -> if (cpu) "Computer" else "Player"
            }
            Label(sub, 15.sp, if (active) Palette.gold else Palette.textDim, align = if (alignRight) TextAlign.End else TextAlign.Start)
        }
        if (alignRight) { Spacer(Modifier.width(10.dp)); avatar() }
    }
}

@Composable
private fun PauseOverlay(onResume: () -> Unit, onRestart: () -> Unit, onQuit: () -> Unit, restartLabel: String) {
    val app = LocalApp.current
    Box(Modifier.fillMaxSize().background(Color(0xB3000000)).clickable(remember { MutableInteractionSource() }, null) {}, contentAlignment = Alignment.Center) {
        Panel(Modifier.widthIn(max = 420.dp).padding(20.dp)) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Label("Paused", 40.sp, Palette.gold, display = true, shadow = true)
                BigButton("Resume", onResume, Modifier.fillMaxWidth(), icon = { playIcon(it) })
                ToggleRow("Sound", null, app.settings.sound) { app.settings.sound = it }
                ToggleRow("Vibration", null, app.settings.haptics) { app.settings.haptics = it }
                ToggleRow("Calm effects", "Fewer particles, no camera moves", app.settings.reducedMotion) { app.settings.reducedMotion = it }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    BigButton(restartLabel, onRestart, Modifier.weight(1f), 56.dp, Color(0xFF4FD1C5), Color(0xFF1C8C83), Color(0xFF05302C), 22.sp)
                    BigButton("Menu", onQuit, Modifier.weight(1f), 56.dp, Color(0xFFE2E8F0), Color(0xFF94A3B8), Color(0xFF1E293B), 22.sp)
                }
            }
        }
    }
}

@Composable
private fun ResultsOverlay(director: MatchDirector, onRestart: () -> Unit, onQuit: () -> Unit) {
    val st = director.stage.style
    val s = director.state ?: return
    val order = (0 until s.seatCount).sortedBy { s.rank[it] }
    Box(Modifier.fillMaxSize().background(Color(0x99000000)).clickable(remember { MutableInteractionSource() }, null) {}, contentAlignment = Alignment.Center) {
        Panel(Modifier.widthIn(max = 440.dp).padding(20.dp)) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val winner = director.seats[order[0]]
                Label("${winner.name} wins!", 38.sp, lerp(st.seat[winner.color], Color.White, 0.3f), display = true, shadow = true, align = TextAlign.Center)
                for (seat in order) {
                    val cfg = director.seats[seat]
                    val r = s.rank[seat]
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color(0x44000000)).padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Canvas(Modifier.size(44.dp)) {
                            val (a, b) = when (r) {
                                1 -> Color(0xFFFFE27A) to Color(0xFFC98A0B)
                                2 -> Color(0xFFF1F5F9) to Color(0xFF8B95A5)
                                3 -> Color(0xFFFFC08A) to Color(0xFF9A5A2A)
                                else -> Color(0xFF64748B) to Color(0xFF334155)
                            }
                            drawCircle(Brush.linearGradient(listOf(a, b)), size.minDimension / 2)
                            drawCircle(Color.White.copy(alpha = 0.7f), size.minDimension / 2, style = Stroke(2.dp.toPx()))
                        }
                        Label("$r", 26.sp, Color.White, display = true, modifier = Modifier.offset(x = (-30).dp).width(16.dp), align = TextAlign.Center)
                        Box(Modifier.size(18.dp).clip(CircleShape).background(st.seat[cfg.color]).border(2.dp, Color.White, CircleShape))
                        Spacer(Modifier.width(10.dp))
                        Label(cfg.name, 22.sp, Color.White, display = true, modifier = Modifier.weight(1f))
                        Label(if (r == s.seatCount) "Loser" else if (cfg.cpu) "CPU" else "", 16.sp, Palette.textDim)
                    }
                }
                Spacer(Modifier.height(6.dp))
                BigButton("Play again", onRestart, Modifier.fillMaxWidth())
                BigButton("Menu", onQuit, Modifier.fillMaxWidth(), 56.dp, Color(0xFFE2E8F0), Color(0xFF94A3B8), Color(0xFF1E293B), 22.sp)
            }
        }
    }
}
