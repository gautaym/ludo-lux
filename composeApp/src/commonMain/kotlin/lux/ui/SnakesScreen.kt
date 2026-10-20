package lux.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import lux.app.AppContext
import lux.app.Nav
import lux.app.Screen
import lux.board.ART_CELLS
import lux.board.SceneFonts
import lux.board.SnakesGeo
import lux.board.View
import lux.board.drawBackdrop
import lux.board.drawScene
import lux.board.rememberSnakesBoard
import lux.core.CpuLevel
import lux.core.GameResult
import lux.core.Series
import lux.core.SeriesSeat
import lux.core.SnakesRules
import lux.core.SnakesState
import lux.game.SeatConfig
import lux.game.SnakesDirector
import lux.game.Stage
import lux.theme.BoardStyles
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt

/** The house rules chosen in the menu. */
fun snakesRules(app: AppContext) = SnakesRules(app.settings.snakesExact, app.settings.snakesSixAgain, app.settings.snakesNeedSix)

/**
 * A Snake & Ladders game: the players' plates on top, the board, and the dice tray under it. With no choices to make,
 * a tap anywhere rolls the dice on your turn.
 */
@Composable
fun SnakesScreen(
    stage: Stage, director: SnakesDirector, paused: Boolean, onPause: () -> Unit, onResume: () -> Unit, onRestart: () -> Unit, onQuit: () -> Unit,
    seriesPoints: IntArray? = null, seriesLabel: String? = null, restartLabel: String = "Restart", endOverlay: (@Composable () -> Unit)? = null,
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
        val margin = with(density) { 6.dp.toPx() }
        val rows = if (director.seats.size > 2) 2 else 1
        val plateH = with(density) { 74.dp.toPx() }
        val plates = rows * plateH
        // Board + tray (1.45 squares deep) under the plates, as big as fits.
        val trayK = 1.45f * SnakesGeo.SQ / ART_CELLS
        val side = floor(min(w - 2 * margin, (h - topBar - plates - 3 * margin) / (1f + trayK)))
        val tray = side * trayK
        val adW = min(w - 2 * margin, with(density) { 560.dp.toPx() })
        val adH = adW / lux.core.Ads.Spot.Top.ratio
        val gap = with(density) { 8.dp.toPx() }
        val spare = h - topBar - plates - side - tray - 3 * margin
        val bottomAd = app.ads.ad(lux.core.Ads.Spot.Bottom)
        val showBottom = bottomAd != null && spare >= adH + gap
        val below = if (showBottom) adH + gap else 0f
        val left = ((w - side) / 2).roundToInt().toFloat()
        // Plates + board + tray as one block, centred (a little high) in the space under the top bar.
        val blockTop = topBar + ((h - topBar - plates - side - tray - below - 3 * margin) / 2).coerceAtLeast(0f) * 0.6f
        val top = (blockTop + plates + margin).roundToInt().toFloat()
        val view = View(left, top, side / ART_CELLS, 0)
        val board = rememberSnakesBoard(st, side.toInt(), fonts.display)

        Canvas(
            Modifier.fillMaxSize().pointerInput(stage) { detectTapGestures { if (!stage.paused) director.requestRoll() } },
        ) {
            stage.frameCount
            drawBackdrop(st, stage.time)
            // The tray under the board, where the dice is thrown and pieces wait to start.
            val ty = top + side + margin * 0.5f
            val r = CornerRadius(18.dp.toPx())
            drawRoundRect(Brush.verticalGradient(listOf(Color(0xE60B2129), Color(0xE6051419)), ty, ty + tray), Offset(left, ty), Size(side, tray - margin), r)
            drawRoundRect(Palette.panelEdge, Offset(left, ty), Size(side, tray - margin), r, style = androidx.compose.ui.graphics.drawscope.Stroke(1.5.dp.toPx()))
            drawScene(stage, view, board, sceneFonts)
        }

        // What's happening, written in the tray (between the waiting pieces and the dice).
        Box(
            Modifier.offset { IntOffset(left.roundToInt(), (top + side + margin * 0.5f).roundToInt()) }
                .size(with(density) { side.toDp() }, with(density) { (tray - margin).toDp() }),
            contentAlignment = Alignment.Center,
        ) {
            val turn = director.seats.getOrNull(director.current)
            val text = when {
                director.over -> ""
                director.hint.isNotEmpty() -> director.hint
                turn?.cpu == true -> "${turn.name} is rolling..."
                else -> ""
            }
            Label(text, 22.sp, if (director.hint.isNotEmpty()) Palette.gold else Palette.textDim, display = true, align = TextAlign.Center)
        }

        // Plates: two per row above the board.
        val rv = director.rankVersion
        director.seats.forEachIndexed { seat, cfg ->
            val pw = with(density) { (w / density.density / 2 - 10).dp }
            val isLeft = seat % 2 == 0
            val x = if (isLeft) margin else w - margin - with(density) { pw.toPx() }
            val y = blockTop + (seat / 2) * plateH
            Box(Modifier.offset { IntOffset(x.roundToInt(), y.roundToInt()) }.width(pw).height(with(density) { (plateH - 8.dp.toPx()).toDp() })) {
                val rank = director.rankOf(seat).takeIf { rv >= 0 && it > 0 } ?: 0
                PlayerPlate(
                    name = cfg.name, cpu = cfg.cpu, local = director.isLocal(seat), badge = cfg.badge, color = st.seat[cfg.color], deep = st.seatDeep[cfg.color], light = st.seatLight[cfg.color], collar = st.collar,
                    pieceColor = st.pieceSeat[cfg.color], pieceDeep = st.pieceDeep[cfg.color], pieceLight = st.pieceLight[cfg.color],
                    active = director.current == seat && !director.over,
                    hint = if (director.current == seat && !director.over) director.hint.ifEmpty { if (cfg.cpu) "Rolling..." else "" } else "",
                    rank = rank, seatCount = director.seats.size, alignRight = !isLeft, seriesPoints = seriesPoints?.getOrNull(seat) ?: -1, softLast = director.softLastPlace,
                    onTap = { if (director.current == seat && !cfg.cpu) director.requestRoll() },
                    sub = director.state?.pos?.getOrNull(seat)?.takeIf { rank == 0 }?.let { if (it == 0) "Not started" else "Square $it" },
                )
            }
        }

        if (showBottom) AdBanner(bottomAd!!.first, bottomAd.second, Modifier.offset { IntOffset(((w - adW) / 2).roundToInt(), (h - adH - gap / 2).roundToInt()) }.size(with(density) { adW.toDp() }, with(density) { adH.toDp() }))

        Row(Modifier.fillMaxWidth().height(with(density) { topBar.toDp() }).padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            RoundIcon(onPause, size = 50.dp) { pauseIcon(it) }
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { ToastView(director.toast) }
            Box(Modifier.width(96.dp), contentAlignment = Alignment.CenterEnd) {
                if (seriesLabel != null) {
                    Box(Modifier.clip(RoundedCornerShape(50)).background(Color(0xCC0B2129)).border(1.5.dp, Palette.gold.copy(alpha = 0.7f), RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 4.dp)) {
                        Label(seriesLabel, 15.sp, Palette.gold, display = true, align = TextAlign.Center)
                    }
                }
            }
        }

        AnimatedVisibility(paused && !director.showResults, enter = fadeIn(), exit = fadeOut()) { PauseOverlay(onResume, onRestart, onQuit, restartLabel) }
        AnimatedVisibility(director.showResults, enter = fadeIn(tween(400)), exit = fadeOut()) { endOverlay?.invoke() }
    }
}

/** A Snake & Ladders series from the menu's choices (same players as Ludo's). */
fun newSnakesSeries(app: AppContext) = Series(app.settings.seriesLength, seatsFromSettings(app).map { SeriesSeat(it.color, it.cpu, it.level.ordinal, it.name) })

/** Plays a Snake & Ladders series: one stage and director per game, saved after every roll. */
@Composable
fun SnakesHost(app: AppContext, nav: Nav) {
    val store = app.snakesSeries
    val series = remember { store.current?.takeIf { !it.isOver } ?: newSnakesSeries(app).also { store.save(it) } }
    var round by remember { mutableIntStateOf(0) }
    var fresh by remember { mutableStateOf(false) }
    var last by remember { mutableStateOf<GameResult?>(null) }
    var breakAd by remember { mutableStateOf(false) }
    val style = BoardStyles.get(app.shop.board(app.settings.board))
    val stage = remember(round) { Stage(app, style) }
    val director = remember(round) { SnakesDirector(stage) }
    var paused by remember(round) { mutableStateOf(false) }
    StageClock(stage)
    LaunchedEffect(round) {
        app.ads.refreshSoon()
        last = null
        val game = series.gameIndex
        director.softLastPlace = !series.isFinal(game) && series.games > 1
        director.onProgress = { st -> app.platform.prefs.putString("snakes.game", "$game:" + st.encode()) }
        val resume = if (fresh) null else app.platform.prefs.getString("snakes.game", "").let { raw ->
            raw.substringBefore(':').toIntOrNull()?.takeIf { it == game }?.let { SnakesState.decode(raw.substringAfter(':')) }
        }
        fresh = false
        director.start(series.seats.map { SeatConfig(it.color, it.cpu, CpuLevel.entries[it.level.coerceIn(0, 2)], it.name) }, snakesRules(app), series.startSeat(game), resume)
        director.run()
        val s = director.state ?: return@LaunchedEffect
        last = series.record(s.rank.copyOf(), IntArray(series.players))
        store.save(series)
        app.platform.prefs.putString("snakes.game", "")
    }
    val totals = IntArray(series.players) { series.total(it) }
    nav.onBack = { if (director.showResults) nav.screen = Screen.Menu else paused = !paused }
    nav.onBackground = { if (!director.over) paused = true }
    Box(Modifier.fillMaxSize().safeDrawingPadding()) {
        SnakesScreen(
            stage, director, paused, onPause = { paused = true }, onResume = { paused = false },
            onRestart = { fresh = true; round++ }, onQuit = { nav.screen = Screen.Menu },
            seriesPoints = if (series.games > 1) totals else null,
            seriesLabel = series.takeIf { it.games > 1 }?.let { "Game ${minOf(it.gameIndex + (if (last == null) 1 else 0), it.games)}/${it.games}" },
            restartLabel = if (series.games > 1) "Replay game" else "Restart",
            endOverlay = {
                val res = last
                if (res != null) {
                    if (series.isOver) SeriesChampion(series, style, onNew = { store.save(null); nav.matchKey++ }, onMenu = { store.save(null); nav.screen = Screen.Menu }, showHome = false)
                    else SeriesScoreboard(series, res, style, onNext = { if (app.ads.ad(lux.core.Ads.Spot.Break) != null) breakAd = true else round++ }, onMenu = { nav.screen = Screen.Menu }, showHome = false)
                }
            },
        )
        val brk = if (breakAd) app.ads.ad(lux.core.Ads.Spot.Break) else null
        if (breakAd && brk == null) LaunchedEffect(Unit) { breakAd = false; round++ }
        if (brk != null) BreakCard(brk.first, brk.second) { breakAd = false; round++ }
    }
}
