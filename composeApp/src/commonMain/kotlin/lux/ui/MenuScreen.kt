package lux.ui

import kotlinx.coroutines.launch
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import lux.app.CaptureStyle
import lux.board.drawBackdrop
import lux.board.drawCube
import lux.board.paintBoard
import lux.board.PIECE_COIN
import lux.board.PIECE_NAMES
import lux.board.pawn
import lux.theme.BoardStyles
import kotlin.math.sin

@Composable
fun MenuScreen(onPlay: () -> Unit, onContinue: () -> Unit, onEffects: () -> Unit, onFamily: () -> Unit, onRanked: () -> Unit, onShop: () -> Unit, onAdvertise: () -> Unit = {}, startSheet: String = "") {
    val app = LocalApp.current
    val s = app.settings
    val st = BoardStyles.get(s.board)
    var showSettings by remember { mutableStateOf(false) }
    var showSetup by remember { mutableStateOf(startSheet == "setup") }
    var showStyle by remember { mutableStateOf(startSheet == "style") }
    val spin = rememberInfiniteTransition()
    val t by spin.animateFloat(0f, 1000f, infiniteRepeatable(tween(1_000_000, easing = LinearEasing)))
    val snakes = s.game == lux.app.Settings.GAME_SNAKES

    BoxWithConstraints(Modifier.fillMaxSize()) {
        Canvas(Modifier.fillMaxSize()) { drawBackdrop(st, t) }
        // Home: a calm welcome. Choose a game, press PLAY; everything else waits in a sheet until it is wanted.
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp).padding(top = 12.dp, bottom = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(Modifier.fillMaxWidth().widthIn(max = 560.dp), verticalAlignment = Alignment.CenterVertically) {
                PillButton("Shop", onShop) { bagIcon(it) }
                Spacer(Modifier.weight(1f))
                RoundIcon({ showSettings = true }, size = 50.dp) { gearIcon(it) }
            }
            Spacer(Modifier.weight(0.6f))
            Logo(st, t, snakes)
            Spacer(Modifier.weight(0.5f))
            Column(Modifier.widthIn(max = 560.dp), verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                GamePicker(st, t)
                // What PLAY will start, in one friendly line (tap it to change).
                val summaryClick = tap { showSetup = true }
                Label(setupSummary(s), 15.sp, Palette.textDim, align = TextAlign.Center, modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = summaryClick).padding(horizontal = 10.dp, vertical = 4.dp))
            }
            Spacer(Modifier.weight(1f))
            Column(Modifier.fillMaxWidth().widthIn(max = 560.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // A series left half-way is one tap away.
                val saved = (if (snakes) app.snakesSeries else app.series).current?.takeIf { !it.isOver }
                if (saved != null) {
                    val click = tap(onContinue)
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))
                            .background(Brush.horizontalGradient(listOf(Color(0xFF0F766E), Color(0xFF115E59))))
                            .border(2.dp, Palette.teal, RoundedCornerShape(18.dp)).clickable(onClick = click).padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Label(if (snakes) "Continue Snake & Ladders" else "Continue series", 22.sp, Color.White, display = true)
                            val leader = saved.seats[saved.standings()[0]]
                            Label(
                                if (saved.gameIndex == 0) "Game 1 of ${saved.games}" else "Game ${saved.gameIndex + 1} of ${saved.games}  ·  ${leader.name} leads with ${saved.total(saved.standings()[0])}",
                                14.sp, Color(0xFFCCFBF1),
                            )
                        }
                        ColorDots(st, saved.seats.map { it.color })
                        Spacer(Modifier.width(10.dp))
                        Canvas(Modifier.size(22.dp)) { playIcon(Color.White) }
                    }
                }
                val pulse = 1f + 0.025f * sin(t * 3.2f)
                BigButton("PLAY", { showSetup = true }, Modifier.fillMaxWidth().scale(pulse), 80.dp, textSize = 40.sp, icon = { playIcon(it) })
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    SecondaryButton("Family", onFamily, Modifier.weight(1f)) { familyIcon(it) }
                    SecondaryButton("Ranked", onRanked, Modifier.weight(1f)) { trophyIcon(it) }
                    SecondaryButton("Style", { showStyle = true }, Modifier.weight(1f)) { paletteIcon(it) }
                }
            }
        }
        Sheet(showSetup, if (snakes) "Snake & Ladders" else "Ludo", { showSetup = false }) {
            SetupContent(snakes)
            Spacer(Modifier.height(6.dp))
            BigButton("START", { showSetup = false; onPlay() }, Modifier.fillMaxWidth(), 72.dp, textSize = 34.sp, icon = { playIcon(it) })
        }
        Sheet(showStyle, "Style", { showStyle = false }) {
            StyleContent(st, t, snakes, onShop)
            val reel = tap { showStyle = false; onEffects() }
            Label("Watch every effect  ›", 18.sp, Palette.gold, display = true, align = TextAlign.Center, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = reel).padding(10.dp))
        }
        AnimatedVisibility(showSettings, enter = fadeIn(), exit = fadeOut()) { SettingsOverlay(onAdvertise = { showSettings = false; onAdvertise() }) { showSettings = false } }
    }
}

/** "4 players · vs Computer (Medium) · 3 games": what PLAY will start. */
private fun setupSummary(s: lux.app.Settings): String {
    val who = if (s.vsComputer) {
        if (s.game == lux.app.Settings.GAME_SNAKES) "Computer" else "${listOf("Easy", "Medium", "Hard")[s.cpuLevel]} computer"
    } else "Friends"
    val games = if (s.seriesLength == 1) "1 game" else "${s.seriesLength} games"
    return "${s.players} players  ·  $who  ·  $games  ✎"
}

/** Who plays, how many games, and (for Snake & Ladders) the house rules. */
@Composable
private fun SetupContent(snakes: Boolean) {
    val s = LocalApp.current.settings
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Section("Players") { Segmented(listOf(2, 3, 4), s.players, { "$it" }, { s.players = it }) }
        Section("Play with") { Segmented(listOf(true, false), s.vsComputer, { if (it) "Computer" else "Friends" }, { s.vsComputer = it }) }
        AnimatedVisibility(s.vsComputer && !snakes, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            Section("Computer level") { Segmented(listOf(0, 1, 2), s.cpuLevel, { listOf("Easy", "Medium", "Hard")[it] }, { s.cpuLevel = it }) }
        }
        Section("Games in a series") { Segmented(listOf(1, 3, 5), s.seriesLength, { if (it == 1) "1 game" else "$it games" }, { s.seriesLength = it }) }
        if (snakes) Section("House rules") {
            Column {
                ToggleRow("Exact roll for 100", "Off: a bigger roll bounces back", s.snakesExact) { s.snakesExact = it }
                ToggleRow("Six rolls again", "Three sixes in a row and the turn passes", s.snakesSixAgain) { s.snakesSixAgain = it }
                ToggleRow("Six (or one) to start", "Off: everyone starts straight away", s.snakesNeedSix) { s.snakesNeedSix = it }
            }
        }
    }
}

/** The look of the game: board, pieces and (in Ludo) the capture effect. */
@Composable
private fun StyleContent(st: lux.theme.BoardStyle, t: Float, snakes: Boolean, onShop: () -> Unit) {
    val app = LocalApp.current
    val s = app.settings
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Section("Board") { BoardPicker(onShop) }
        Section("Pieces") { PiecePicker(st, t) }
        if (!snakes) Section("Capture effect") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val names = CaptureStyle.entries.map { it.title } + "Surprise"
                for (row in names.indices.chunked(4)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (i in row) {
                            val open = app.shop.unlocked(lux.core.Catalog.captureItem(i))
                            Chip(if (open) names[i] else "${names[i]} 🔒", s.capture == i && open, { if (open) s.capture = i else onShop() })
                        }
                    }
                }
            }
        }
    }
}

/**
 * A panel that slides up from the bottom over a dimmed screen (tap outside or the cross to close). Its content scrolls
 * when it is taller than the screen.
 */
@Composable
fun Sheet(open: Boolean, title: String, onClose: () -> Unit, content: @Composable () -> Unit) {
    AnimatedVisibility(open, enter = fadeIn(tween(180)), exit = fadeOut(tween(180))) {
        Box(Modifier.fillMaxSize().background(Color(0xB3000000)).clickable(remember { MutableInteractionSource() }, null, onClick = onClose))
    }
    AnimatedVisibility(
        open,
        enter = androidx.compose.animation.slideInVertically(tween(260)) { it } + fadeIn(tween(200)),
        exit = androidx.compose.animation.slideOutVertically(tween(200)) { it } + fadeOut(tween(160)),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            Column(
                Modifier.widthIn(max = 600.dp).fillMaxWidth().heightIn(max = 760.dp)
                    .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                    .background(Brush.verticalGradient(listOf(Color(0xFF15414B), Color(0xFF0A222A))))
                    .border(1.5.dp, Palette.panelEdge, RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                    .clickable(remember { MutableInteractionSource() }, null) {}
                    .padding(horizontal = 18.dp).padding(top = 10.dp, bottom = 18.dp),
            ) {
                // A grab handle, then the title row.
                Box(Modifier.align(Alignment.CenterHorizontally).size(44.dp, 5.dp).clip(RoundedCornerShape(3.dp)).background(Color(0x55FFFFFF)))
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Label(title, 30.sp, Palette.gold, display = true, modifier = Modifier.weight(1f))
                    RoundIcon(onClose, size = 44.dp) { closeIcon(it) }
                }
                Spacer(Modifier.height(10.dp))
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) { content() }
            }
        }
    }
}

/** A quieter button in the theme's teal, for everything that isn't PLAY. */
@Composable
private fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: androidx.compose.ui.graphics.drawscope.DrawScope.(Color) -> Unit) {
    val click = tap(onClick)
    Column(
        modifier.height(76.dp).clip(RoundedCornerShape(20.dp))
            .background(Brush.verticalGradient(listOf(Color(0xFF1F5A64), Color(0xFF0E3239))))
            .border(1.5.dp, Color(0xFF2DD4BF).copy(alpha = 0.55f), RoundedCornerShape(20.dp))
            .clickable(onClick = click).padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        Canvas(Modifier.size(28.dp)) { icon(Color(0xFFCCFBF1)) }
        Spacer(Modifier.height(4.dp))
        Label(text, 19.sp, Color.White, display = true)
    }
}

/** A small rounded button with an icon and a word (the Shop at the top). */
@Composable
private fun PillButton(text: String, onClick: () -> Unit, icon: androidx.compose.ui.graphics.drawscope.DrawScope.(Color) -> Unit) {
    val click = tap(onClick)
    Row(
        Modifier.height(50.dp).clip(RoundedCornerShape(25.dp)).background(Color(0x66000000)).border(1.5.dp, Palette.panelEdge, RoundedCornerShape(25.dp))
            .clickable(onClick = click).padding(start = 12.dp, end = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Canvas(Modifier.size(26.dp)) { icon(Palette.gold) }
        Spacer(Modifier.width(8.dp))
        Label(text, 19.sp, Palette.gold, display = true)
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.familyIcon(c: Color) {
    val w = size.width; val h = size.height
    // Two grown-ups and a child.
    for ((x, r, top) in listOf(Triple(0.28f, 0.13f, 0.18f), Triple(0.72f, 0.13f, 0.18f), Triple(0.5f, 0.1f, 0.42f))) {
        drawCircle(c, w * r, Offset(w * x, h * (top + r)))
        val bodyTop = h * (top + 2 * r + 0.04f)
        drawRoundRect(c, Offset(w * (x - r * 1.35f), bodyTop), androidx.compose.ui.geometry.Size(w * r * 2.7f, h * 0.95f - bodyTop), androidx.compose.ui.geometry.CornerRadius(w * r * 1.2f))
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.trophyIcon(c: Color) {
    val w = size.width; val h = size.height
    val cup = androidx.compose.ui.graphics.Path().apply {
        moveTo(w * 0.22f, h * 0.1f); lineTo(w * 0.78f, h * 0.1f)
        cubicTo(w * 0.78f, h * 0.5f, w * 0.62f, h * 0.6f, w * 0.5f, h * 0.6f)
        cubicTo(w * 0.38f, h * 0.6f, w * 0.22f, h * 0.5f, w * 0.22f, h * 0.1f); close()
    }
    drawPath(cup, c)
    for (sx in listOf(-1f, 1f)) drawCircle(c, w * 0.12f, Offset(w * (0.5f + sx * 0.3f), h * 0.25f), style = androidx.compose.ui.graphics.drawscope.Stroke(w * 0.06f))
    drawRect(c, Offset(w * 0.45f, h * 0.58f), androidx.compose.ui.geometry.Size(w * 0.1f, h * 0.2f))
    drawRoundRect(c, Offset(w * 0.28f, h * 0.78f), androidx.compose.ui.geometry.Size(w * 0.44f, h * 0.12f), androidx.compose.ui.geometry.CornerRadius(w * 0.04f))
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.paletteIcon(c: Color) {
    val w = size.width; val h = size.height
    drawCircle(c, w * 0.44f, Offset(w * 0.5f, h * 0.5f))
    val holes = listOf(Color(0xFFD61A29), Color(0xFF0A9952), Color(0xFFFFCC0F), Color(0xFF1F57E0))
    val spots = listOf(Offset(0.32f, 0.32f), Offset(0.55f, 0.24f), Offset(0.74f, 0.42f), Offset(0.3f, 0.6f))
    for ((i, p) in spots.withIndex()) drawCircle(holes[i], w * 0.09f, Offset(w * p.x, h * p.y))
    drawCircle(Color(0xFF0E3239), w * 0.1f, Offset(w * 0.6f, h * 0.68f))
}

@Composable
private fun Logo(st: lux.theme.BoardStyle, t: Float, snakes: Boolean) {
    // Same height for both games, so nothing below jumps when switching.
    Box(Modifier.height(140.dp), contentAlignment = Alignment.Center) {
        androidx.compose.animation.Crossfade(snakes) { sn ->
            if (sn) SnakesLogo(st, t) else LudoLogo(st, t)
        }
    }
}

/** The title in gold with a soft dark offset behind it. */
@Composable
private fun GoldTitle(text: String, size: androidx.compose.ui.unit.TextUnit) {
    Box {
        Label(text, size, Color(0xFF6B3A00), display = true, modifier = Modifier.padding(start = 3.dp, top = 4.dp))
        Label(text, size, Palette.gold, display = true)
    }
}

@Composable
private fun SnakesLogo(st: lux.theme.BoardStyle, t: Float) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(64.dp, 96.dp)) {
            // A little wooden ladder.
            val h = size.height
            for (sx in listOf(-1f, 1f)) drawLine(Color(0xFFC98B4A), Offset(center.x + sx * h * 0.14f, h * 0.06f), Offset(center.x + sx * h * 0.14f, h * 0.94f), h * 0.06f, androidx.compose.ui.graphics.StrokeCap.Round)
            for (r in 1..5) drawLine(Color(0xFFE8B57A), Offset(center.x - h * 0.14f, h * r * 0.16f), Offset(center.x + h * 0.14f, h * r * 0.16f), h * 0.045f)
        }
        Spacer(Modifier.width(6.dp))
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            GoldTitle("Snake &", 46.sp)
            GoldTitle("Ladders", 46.sp)
            Label("LUDOLUX", 18.sp, Color.White, display = true)
        }
        Spacer(Modifier.width(6.dp))
        Canvas(Modifier.size(64.dp, 96.dp)) {
            // A friendly snake, gently swaying.
            val path = androidx.compose.ui.graphics.Path()
            for (i in 0..30) {
                val q = i / 30f
                val x = size.width * 0.5f + sin(q * 8f + t * 2.6f) * size.width * 0.22f * (0.4f + q)
                val y = size.height * (0.12f + q * 0.82f)
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, Color(0xFF45206B), style = androidx.compose.ui.graphics.drawscope.Stroke(size.width * 0.2f, cap = androidx.compose.ui.graphics.StrokeCap.Round))
            drawPath(path, Color(0xFF8E54C9), style = androidx.compose.ui.graphics.drawscope.Stroke(size.width * 0.14f, cap = androidx.compose.ui.graphics.StrokeCap.Round))
            val hx = size.width * 0.5f + sin(t * 2.6f) * size.width * 0.22f * 0.4f
            drawCircle(Color(0xFF8E54C9), size.width * 0.15f, Offset(hx, size.height * 0.12f))
            for (sx in listOf(-1f, 1f)) {
                drawCircle(Color.White, size.width * 0.045f, Offset(hx + sx * size.width * 0.06f, size.height * 0.1f))
                drawCircle(Color(0xFF111111), size.width * 0.022f, Offset(hx + sx * size.width * 0.06f, size.height * 0.1f))
            }
        }
    }
}

@Composable
private fun LudoLogo(st: lux.theme.BoardStyle, t: Float) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(84.dp)) {
            // A real 3D die tumbling slowly beside the title.
            drawCube(center, size.minDimension * 0.33f, 25f + t * 37f, 35f + t * 23f, t * 11f, st.diceBody, st.dicePip)
        }
        Spacer(Modifier.width(8.dp))
        Column {
            GoldTitle("Ludo", 64.sp)
            Label("LUX", 26.sp, Color.White, display = true, modifier = Modifier.padding(start = 4.dp))
        }
        Spacer(Modifier.width(8.dp))
        Canvas(Modifier.size(70.dp, 84.dp)) {
            val bob = sin(t * 2.4f) * size.height * 0.04f
            pawn(Offset(center.x, size.height * 0.92f + bob), size.height * 0.85f, st.pieceSeat[0], st.pieceDeep[0], st.pieceLight[0], st.collar, 1f)
        }
    }
}

/** Which game PLAY starts: two big cards, each with a little live drawing. */
@Composable
private fun GamePicker(st: lux.theme.BoardStyle, t: Float) {
    val app = LocalApp.current
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        for ((id, title) in listOf(lux.app.Settings.GAME_LUDO to "Ludo", lux.app.Settings.GAME_SNAKES to "Snake & Ladders")) {
            val on = app.settings.game == id
            val k by animateFloatAsState(if (on) 1f else 0f)
            val click = tap { app.settings.game = id }
            Column(
                Modifier.weight(1f).height(150.dp).clip(RoundedCornerShape(22.dp))
                    .background(Brush.verticalGradient(listOf(lerp(Color(0x55000000), Color(0xFF7C4A03), k), lerp(Color(0x55000000), Color(0xFF3A2200), k))))
                    .border((1 + 2 * k).dp, lerp(Palette.panelEdge, Palette.gold, k), RoundedCornerShape(20.dp))
                    .clickable(onClick = click).padding(10.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
            ) {
                Canvas(Modifier.fillMaxWidth().height(80.dp)) {
                    val c = Offset(size.width / 2, size.height / 2)
                    if (id == lux.app.Settings.GAME_LUDO) {
                        drawCube(Offset(c.x - size.height * 0.55f, c.y), size.height * 0.26f, 25f + (if (on) t * 37f else 0f), 35f, 0f, st.diceBody, st.dicePip)
                        for (i in 0 until 2) pawn(Offset(c.x + size.height * (0.25f + i * 0.5f), size.height * 0.95f), size.height * 0.7f, st.pieceSeat[i * 2], st.pieceDeep[i * 2], st.pieceLight[i * 2], st.collar, 1f)
                    } else {
                        // A tiny ladder and a wavy snake.
                        val lx = c.x - size.height * 0.6f
                        for (sx in listOf(-1f, 1f)) drawLine(Color(0xFFC98B4A), Offset(lx + sx * size.height * 0.14f, size.height * 0.08f), Offset(lx + sx * size.height * 0.14f, size.height * 0.92f), size.height * 0.07f, androidx.compose.ui.graphics.StrokeCap.Round)
                        for (r in 1..4) drawLine(Color(0xFFE8B57A), Offset(lx - size.height * 0.14f, size.height * r * 0.19f), Offset(lx + size.height * 0.14f, size.height * r * 0.19f), size.height * 0.05f)
                        val path = androidx.compose.ui.graphics.Path()
                        for (i in 0..30) {
                            val q = i / 30f
                            val x = c.x + size.height * (0.0f + q * 0.9f)
                            val y = size.height * (0.2f + q * 0.62f) + kotlin.math.sin(q * 9f + (if (on) t * 3f else 0f)) * size.height * 0.1f
                            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                        }
                        drawPath(path, Color(0xFF145A2A), style = androidx.compose.ui.graphics.drawscope.Stroke(size.height * 0.16f, cap = androidx.compose.ui.graphics.StrokeCap.Round))
                        drawPath(path, Color(0xFF2E9E4F), style = androidx.compose.ui.graphics.drawscope.Stroke(size.height * 0.11f, cap = androidx.compose.ui.graphics.StrokeCap.Round))
                        drawCircle(Color(0xFF2E9E4F), size.height * 0.11f, Offset(c.x, size.height * 0.2f + kotlin.math.sin(if (on) t * 3f else 0f) * size.height * 0.1f))
                    }
                }
                Spacer(Modifier.height(4.dp))
                Label(title, 19.sp, if (on) Palette.gold else Palette.text, display = true, align = TextAlign.Center)
            }
        }
    }
}

/** The piece designs, drawn live in all four colours. */
@Composable
private fun PiecePicker(st: lux.theme.BoardStyle, t: Float) {
    val app = LocalApp.current
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        for ((kind, name) in PIECE_NAMES.withIndex()) {
            val on = app.settings.pieceStyle == kind
            val k by animateFloatAsState(if (on) 1f else 0f)
            val click = tap { app.settings.pieceStyle = kind }
            Column(
                Modifier.weight(1f).clip(RoundedCornerShape(16.dp))
                    .background(lerp(Color(0x33000000), Color(0x55FFC94A), k))
                    .border((1 + 2 * k).dp, lerp(Palette.panelEdge, Palette.gold, k), RoundedCornerShape(16.dp))
                    .clickable(onClick = click).padding(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Canvas(Modifier.fillMaxWidth().height(64.dp)) {
                    val u = if (kind == PIECE_COIN) size.height * 0.95f else size.height * 0.78f
                    for (c in 0 until 4) {
                        val x = size.width * (0.14f + 0.24f * c)
                        val bob = if (on) kotlin.math.abs(sin(t * 4f + c)) * size.height * 0.08f else 0f
                        pawn(Offset(x, size.height * 0.96f - bob), u, st.pieceSeat[c], st.pieceDeep[c], st.pieceLight[c], st.collar, 1f, kind = kind)
                    }
                }
                Spacer(Modifier.height(4.dp))
                Label(name, 18.sp, if (on) Palette.gold else Palette.text, display = true)
            }
        }
    }
}

/** Live previews of every board (the real artwork, painted small). */
@Composable
private fun BoardPicker(onShop: () -> Unit) {
    val app = LocalApp.current
    val px = with(LocalDensity.current) { 104.dp.roundToPx() }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        for (row in BoardStyles.all.chunked(3)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                for (b in row) {
                    val open = app.shop.unlocked(lux.core.Catalog.boardItem(b.id))
                    val on = app.settings.board == b.id && open
                    val k by animateFloatAsState(if (on) 1f else 0f)
                    val img = remember(b.id, px) { paintBoard(b, px) }
                    val click = tap { if (open) app.settings.board = b.id else onShop() }
                    Column(
                        Modifier.weight(1f).clip(RoundedCornerShape(16.dp))
                            .background(lerp(Color(0x33000000), Color(0x55FFC94A), k))
                            .border((1 + 2 * k).dp, lerp(Palette.panelEdge, Palette.gold, k), RoundedCornerShape(16.dp))
                            .clickable(onClick = click).padding(8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Image(img, b.name, Modifier.fillMaxWidth().aspectRatio(1f).scale(0.94f + 0.06f * k), filterQuality = FilterQuality.High)
                        Spacer(Modifier.height(4.dp))
                        if (open) Label(b.name, 18.sp, if (on) Palette.gold else Palette.text, display = true)
                        else Row(verticalAlignment = Alignment.CenterVertically) {
                            Canvas(Modifier.size(14.dp)) { lockIcon(Palette.gold) }
                            Spacer(Modifier.width(4.dp))
                            Label(b.name, 18.sp, Palette.textDim, display = true)
                        }
                    }
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** A titled set of sound choices, three to a row. */
@Composable
private fun SoundChoice(title: String, names: List<String>, selected: Int, onPick: (Int) -> Unit) {
    Column(Modifier.padding(vertical = 6.dp, horizontal = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Label(title, 18.sp, Palette.text)
        for (row in names.indices.chunked(3)) Segmented(row, selected, { names[it] }, onPick, height = 48.dp)
    }
}

@Composable
fun SettingsOverlay(onAdvertise: (() -> Unit)? = null, onClose: () -> Unit) {
    val app = LocalApp.current
    val s = app.settings
    var areaOpen by remember { mutableStateOf(false) }
    var versionTaps by remember { mutableStateOf(0) }
    Box(Modifier.fillMaxSize().background(Color(0xB3000000)).clickable(remember { MutableInteractionSource() }, null, onClick = onClose), contentAlignment = Alignment.Center) {
        Panel(Modifier.widthIn(max = 460.dp).padding(18.dp).clickable(remember { MutableInteractionSource() }, null) {}) {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Label("Settings", 34.sp, Palette.gold, display = true, modifier = Modifier.weight(1f))
                    RoundIcon(onClose, size = 44.dp) { closeIcon(it) }
                }
                ToggleRow("Sound", null, s.sound) { s.sound = it }
                ToggleRow("Music", "A happy tune on this screen", s.music) { s.music = it }
                // The sound for every square a piece hops (choosing one plays a little walk of it), and for a catch.
                val scope = androidx.compose.runtime.rememberCoroutineScope()
                SoundChoice("Hop sound", lux.app.AppContext.HOP_SOUNDS, s.hopSound) { v ->
                    s.hopSound = v
                    scope.launch {
                        for (i in 0 until 6) { app.hop(i, 6, big = true); kotlinx.coroutines.delay(170) }
                        kotlinx.coroutines.delay(60); app.arrive(6)
                    }
                }
                SoundChoice("Caught sound", lux.app.AppContext.CAUGHT_SOUNDS, s.caughtSound) { v ->
                    s.caughtSound = v
                    app.caught()
                }
                ToggleRow("Vibration", null, s.haptics) { s.haptics = it }
                ToggleRow("Balanced dice", "Sixes come in time; nobody stays stuck", s.balancedDice) { s.balancedDice = it }
                ToggleRow("Lucky six", "After 4 tries with all pieces home, a six", s.luckySix) { s.luckySix = it }
                ToggleRow("Calm effects", "Fewer particles, no camera moves", s.reducedMotion) { s.reducedMotion = it }
                // Which state's local ads to show (automatic = from the internet connection, nothing else).
                val areaClick = tap { areaOpen = true }
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(onClick = areaClick).padding(vertical = 10.dp, horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Label("Area for local ads", 20.sp)
                        Label(lux.core.Ads.area(app.ads.chosenArea)?.name ?: "Automatic", 14.sp, Palette.textDim)
                    }
                    Label("Change", 15.sp, Palette.gold, display = true)
                }
                if (onAdvertise != null) {
                    // For local businesses: a quiet link, never in the way of playing.
                    val adClick = tap(onAdvertise)
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(onClick = adClick).padding(vertical = 10.dp, horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Label("Have a business?", 19.sp, Palette.text, display = true)
                            Label("Show your ad to players in your state", 14.sp, Palette.textDim)
                        }
                        Label("Advertise", 15.sp, Palette.gold, display = true)
                    }
                }
                Spacer(Modifier.height(4.dp))
                Label(
                    "Ludo Lux 0.3" + if (s.developer) "  ·  developer mode" else "", 13.sp, Palette.textDim, align = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().clickable(remember { MutableInteractionSource() }, null) {
                        versionTaps++
                        if (versionTaps >= 5) { versionTaps = 0; s.developer = !s.developer }
                    },
                )
                Label("Area lookup: IP Geolocation by DB-IP (db-ip.com)", 11.sp, Palette.textDim, align = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            }
        }
        if (areaOpen) AreaPicker(app.ads.chosenArea, automatic = true, onPick = { app.ads.chooseArea(it); app.ads.refreshSoon() }, onClose = { areaOpen = false })
    }
}
