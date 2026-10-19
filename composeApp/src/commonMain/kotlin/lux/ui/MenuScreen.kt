package lux.ui

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
fun MenuScreen(onPlay: () -> Unit, onContinue: () -> Unit, onEffects: () -> Unit, onFamily: () -> Unit, onRanked: () -> Unit) {
    val app = LocalApp.current
    val s = app.settings
    val st = BoardStyles.get(s.board)
    var showSettings by remember { mutableStateOf(false) }
    val spin = rememberInfiniteTransition()
    val t by spin.animateFloat(0f, 1000f, infiniteRepeatable(tween(1_000_000, easing = LinearEasing)))

    BoxWithConstraints(Modifier.fillMaxSize()) {
        Canvas(Modifier.fillMaxSize()) { drawBackdrop(st, t) }
        Column(Modifier.fillMaxSize()) {
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(top = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Spacer(Modifier.weight(1f))
                    RoundIcon({ showSettings = true }, size = 50.dp) { gearIcon(it) }
                }
                Logo(st, t)
                Spacer(Modifier.height(14.dp))
                Column(Modifier.widthIn(max = 560.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Panel(Modifier.fillMaxWidth()) {
                        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                            Section("Players") { Segmented(listOf(2, 3, 4), s.players, { "$it" }, { s.players = it }) }
                            Section("Games in a series") { Segmented(listOf(1, 3, 5), s.seriesLength, { if (it == 1) "1 game" else "$it games" }, { s.seriesLength = it }) }
                            Section("Play with") { Segmented(listOf(true, false), s.vsComputer, { if (it) "Computer" else "Friends" }, { s.vsComputer = it }) }
                            AnimatedVisibility(s.vsComputer, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                                Section("Computer level") { Segmented(listOf(0, 1, 2), s.cpuLevel, { listOf("Easy", "Medium", "Hard")[it] }, { s.cpuLevel = it }) }
                            }
                        }
                    }
                    Panel(Modifier.fillMaxWidth()) {
                        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                            Section("Board") { BoardPicker() }
                            Section("Pieces") { PiecePicker(st, t) }
                        }
                    }
                    Panel(Modifier.fillMaxWidth()) {
                        Section("Capture effect") {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                val names = CaptureStyle.entries.map { it.title } + "Surprise"
                                for (row in names.indices.chunked(4)) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        for (i in row) Chip(names[i], s.capture == i, { s.capture = i })
                                    }
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                }
            }
            // A series left half-way is one tap away.
            val saved = app.series.current?.takeIf { !it.isOver }
            if (saved != null) {
                val click = tap(onContinue)
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp).clip(RoundedCornerShape(18.dp))
                        .background(Brush.horizontalGradient(listOf(Color(0xFF0F766E), Color(0xFF115E59))))
                        .border(2.dp, Palette.teal, RoundedCornerShape(18.dp)).clickable(onClick = click).padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Label("Continue series", 22.sp, Color.White, display = true)
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
            // PLAY is pinned to the bottom: always visible, always the biggest thing on screen.
            Column(
                Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC051419)))).padding(16.dp).padding(bottom = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                val pulse = 1f + 0.025f * sin(t * 3.2f)
                BigButton("PLAY", onPlay, Modifier.fillMaxWidth().widthIn(max = 560.dp).scale(pulse), 76.dp, textSize = 38.sp, icon = { playIcon(it) })
                Row(Modifier.widthIn(max = 560.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    BigButton("Family", onFamily, Modifier.weight(1f), 60.dp, Color(0xFF5EEAD4), Color(0xFF0F766E), Color(0xFF032E2A), 21.sp)
                    BigButton("Ranked", onRanked, Modifier.weight(1f), 60.dp, Color(0xFFF0ABFC), Color(0xFF9333EA), Color(0xFF2E0A3D), 21.sp)
                    BigButton("Effects", onEffects, Modifier.weight(1f), 60.dp, Color(0xFF7DD3FC), Color(0xFF2563EB), Color(0xFF0B1E4A), 21.sp)
                }
            }
        }
        AnimatedVisibility(showSettings, enter = fadeIn(), exit = fadeOut()) { SettingsOverlay { showSettings = false } }
    }
}

@Composable
private fun Logo(st: lux.theme.BoardStyle, t: Float) {
    val fonts = LocalFonts.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(84.dp)) {
            // A real 3D die tumbling slowly beside the title.
            drawCube(center, size.minDimension * 0.33f, 25f + t * 37f, 35f + t * 23f, t * 11f, st.diceBody, st.dicePip)
        }
        Spacer(Modifier.width(8.dp))
        Column {
            Box {
                Label("Ludo", 64.sp, Color(0xFF6B3A00), display = true, modifier = Modifier.padding(start = 3.dp, top = 4.dp))
                Label("Ludo", 64.sp, Palette.gold, display = true)
            }
            Label("LUX", 26.sp, Color.White, display = true, modifier = Modifier.padding(start = 4.dp))
        }
        Spacer(Modifier.width(8.dp))
        Canvas(Modifier.size(70.dp, 84.dp)) {
            val bob = sin(t * 2.4f) * size.height * 0.04f
            pawn(Offset(center.x, size.height * 0.92f + bob), size.height * 0.85f, st.pieceSeat[0], st.pieceDeep[0], st.pieceLight[0], st.collar, 1f)
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
private fun BoardPicker() {
    val app = LocalApp.current
    val px = with(LocalDensity.current) { 104.dp.roundToPx() }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        for (row in BoardStyles.all.chunked(3)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                for (b in row) {
                    val on = app.settings.board == b.id
                    val k by animateFloatAsState(if (on) 1f else 0f)
                    val img = remember(b.id, px) { paintBoard(b, px) }
                    val click = tap { app.settings.board = b.id }
                    Column(
                        Modifier.weight(1f).clip(RoundedCornerShape(16.dp))
                            .background(lerp(Color(0x33000000), Color(0x55FFC94A), k))
                            .border((1 + 2 * k).dp, lerp(Palette.panelEdge, Palette.gold, k), RoundedCornerShape(16.dp))
                            .clickable(onClick = click).padding(8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Image(img, b.name, Modifier.fillMaxWidth().aspectRatio(1f).scale(0.94f + 0.06f * k), filterQuality = FilterQuality.High)
                        Spacer(Modifier.height(4.dp))
                        Label(b.name, 18.sp, if (on) Palette.gold else Palette.text, display = true)
                    }
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
fun SettingsOverlay(onClose: () -> Unit) {
    val s = LocalApp.current.settings
    Box(Modifier.fillMaxSize().background(Color(0xB3000000)).clickable(remember { MutableInteractionSource() }, null, onClick = onClose), contentAlignment = Alignment.Center) {
        Panel(Modifier.widthIn(max = 460.dp).padding(18.dp).clickable(remember { MutableInteractionSource() }, null) {}) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Label("Settings", 34.sp, Palette.gold, display = true, modifier = Modifier.weight(1f))
                    RoundIcon(onClose, size = 44.dp) { closeIcon(it) }
                }
                ToggleRow("Sound", null, s.sound) { s.sound = it }
                ToggleRow("Vibration", null, s.haptics) { s.haptics = it }
                ToggleRow("Balanced dice", "Sixes come in time; nobody stays stuck", s.balancedDice) { s.balancedDice = it }
                ToggleRow("Lucky six", "After 4 tries with all pieces home, a six", s.luckySix) { s.luckySix = it }
                ToggleRow("Calm effects", "Fewer particles, no camera moves", s.reducedMotion) { s.reducedMotion = it }
                Spacer(Modifier.height(4.dp))
                Label("Ludo Lux 0.2 - made with Kotlin", 13.sp, Palette.textDim, align = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}
