package lux.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import lux.app.AppContext
import lux.app.Nav
import lux.app.Screen
import lux.core.CpuLevel
import lux.core.GameResult
import lux.core.Series
import lux.core.SeriesSeat
import lux.core.MatchSetup
import lux.core.RuleOptions
import lux.game.MatchDirector
import lux.game.SeatConfig
import lux.game.Stage
import lux.theme.BoardStyles

/** Seats for a new match from the menu's remembered choices. */
fun seatsFromSettings(app: AppContext): List<SeatConfig> {
    val s = app.settings
    val names = listOf("Red", "Green", "Yellow", "Blue")
    val colors = MatchSetup.default(s.players).colors
    return colors.mapIndexed { i, c ->
        SeatConfig(c, cpu = s.vsComputer && i != 0, level = CpuLevel.entries[s.cpuLevel], name = if (s.vsComputer && i == 0) s.name else names[c])
    }
}

@Composable
fun LuxApp(app: AppContext, nav: Nav) {
    // Ads for this area (pictures only), fetched quietly in the background; none when offline.
    LaunchedEffect(Unit) { app.ads.refresh() }
    // The menu tune: only on the menu, only with sound and music on; it stops the moment a game starts.
    val tune = nav.screen == Screen.Menu && app.settings.sound && app.settings.music
    LaunchedEffect(tune) { app.platform.music(tune) }
    ProvideLux(app) {
        Box(Modifier.fillMaxSize().background(Color(0xFF06161B))) {
            AnimatedContent(nav.screen, transitionSpec = {
                // Going somewhere slides in from the right; coming back to the menu slides in from the left.
                val back = targetState == Screen.Menu
                val dir = if (back) -1 else 1
                (androidx.compose.animation.slideInHorizontally(androidx.compose.animation.core.tween(320)) { it * dir / 6 } + fadeIn(androidx.compose.animation.core.tween(320))) togetherWith
                    (androidx.compose.animation.slideOutHorizontally(androidx.compose.animation.core.tween(260)) { -it * dir / 6 } + fadeOut(androidx.compose.animation.core.tween(220)))
            }) { screen ->
                Box(Modifier.fillMaxSize()) {
                    when (screen) {
                        Screen.Menu -> Box(Modifier.safeDrawingPadding()) {
                            MenuScreen(
                                // PLAY always starts a new series; "Continue" picks up the saved one.
                                onPlay = {
                                    nav.matchKey++
                                    if (app.settings.game == lux.app.Settings.GAME_SNAKES) { app.snakesSeries.save(null); nav.screen = Screen.Snakes }
                                    else { app.series.save(null); nav.screen = Screen.Match }
                                },
                                onContinue = { nav.matchKey++; nav.screen = if (app.settings.game == lux.app.Settings.GAME_SNAKES) Screen.Snakes else Screen.Match },
                                onEffects = { nav.screen = Screen.Showcase },
                                onFamily = { nav.screen = Screen.Family },
                                onRanked = { nav.screen = Screen.Ranked },
                                onShop = { nav.screen = Screen.Shop },
                                onAdvertise = { nav.screen = Screen.Advertise },
                                startSheet = nav.menuSheet.also { nav.menuSheet = "" },
                            )
                        }
                        Screen.Match -> key(nav.matchKey) { MatchHost(app, nav, showcase = false) }
                        Screen.Snakes -> key(nav.matchKey) { SnakesHost(app, nav) }
                        Screen.Showcase -> MatchHost(app, nav, showcase = true)
                        Screen.Family -> Box(Modifier.safeDrawingPadding()) { FamilyHome(nav) }
                        is Screen.Group -> Box(Modifier.safeDrawingPadding()) { GroupScreen(nav, screen.id) }
                        is Screen.Room -> RoomScreen(nav, screen.code, screen.groupId)
                        Screen.Ranked -> Box(Modifier.safeDrawingPadding()) { RankedScreen(nav) }
                        Screen.Shop -> Box(Modifier.safeDrawingPadding()) { ShopScreen(nav) }
                        Screen.Advertise -> Box(Modifier.safeDrawingPadding()) { AdvertiseScreen(nav) }
                    }
                }
            }
        }
    }
}

/** A new series from the menu's remembered choices. */
fun newSeries(app: AppContext) = Series(app.settings.seriesLength, seatsFromSettings(app).map { SeriesSeat(it.color, it.cpu, it.level.ordinal, it.name) })

/** Owns the effects reel, or the series being played: one stage and director per game, plus the pause state. */
@Composable
private fun MatchHost(app: AppContext, nav: Nav, showcase: Boolean) {
    val store = app.series
    val series = remember { if (showcase) null else store.current?.takeIf { !it.isOver } ?: newSeries(app).also { store.save(it) } }
    var round by remember { mutableIntStateOf(0) }
    /** Discard the saved position when a game is restarted from the pause menu. */
    var fresh by remember { mutableStateOf(false) }
    var last by remember { mutableStateOf<GameResult?>(null) }
    val style = BoardStyles.get(app.shop.board(app.settings.board))
    val stage = remember(round) { Stage(app, style) }
    val director = remember(round) { MatchDirector(stage) }
    var paused by remember(round) { mutableStateOf(false) }
    /** Between games of a series: a short "TV break" card (when there is one for this area). */
    var breakAd by remember { mutableStateOf(false) }
    StageClock(stage)
    LaunchedEffect(round) {
        if (series != null) app.ads.refreshSoon()
        if (series == null) { director.runShowcase { nav.screen = Screen.Menu }; return@LaunchedEffect }
        last = null
        val game = series.gameIndex
        val seats = series.seats.map { SeatConfig(it.color, it.cpu, CpuLevel.entries[it.level.coerceIn(0, 2)], it.name) }
        // Mid-series, last place in a game gets "Next round!"; the Loser sign waits for the end of the series.
        director.softLastPlace = !series.isFinal(game) && series.games > 1
        director.allowedCaptures = app.shop.allowedCaptures()
        director.onProgress = { st -> store.saveGame(game, st) }
        val resume = if (fresh) null else store.savedGame(game)
        fresh = false
        director.start(
            seats, RuleOptions(sixPityTurns = if (app.settings.luckySix) 4 else 0), app.shop.capture(app.settings.capture), app.settings.balancedDice,
            startSeat = series.startSeat(game), resume = resume,
        )
        // The top bar already shows "Game 2/3"; only the double-points final gets announced.
        if (series.isFinal(game)) director.announce("Final game - double points!")
        director.run()
        val s = director.state ?: return@LaunchedEffect
        last = series.record(s.rank.copyOf(), director.piecesHome())
        store.save(series)
        store.clearGame()
    }
    val totals = series?.let { sr -> IntArray(sr.players) { sr.total(it) } }
    // The system back button pauses a match (and closes the pause menu); in the reel it goes back to the menu.
    nav.onBack = {
        when {
            series == null -> nav.screen = Screen.Menu
            director.showResults -> nav.screen = Screen.Menu
            else -> paused = !paused
        }
    }
    nav.onBackground = { if (series != null && !director.over) paused = true }
    Box(Modifier.fillMaxSize().safeDrawingPadding()) {
        MatchScreen(
            stage, director, paused,
            onPause = { paused = true }, onResume = { paused = false },
            onRestart = { fresh = true; round++ }, onQuit = { nav.screen = Screen.Menu },
            seriesPoints = if (series != null && series.games > 1) totals else null,
            seriesLabel = series?.takeIf { it.games > 1 }?.let { "Game ${minOf(it.gameIndex + (if (last == null) 1 else 0), it.games)}/${it.games}" },
            restartLabel = if (series != null && series.games > 1) "Replay game" else "Restart",
            showAds = series != null,
            endOverlay = series?.let { sr ->
                {
                    val res = last
                    if (res != null) {
                        if (sr.isOver) SeriesChampion(sr, style, onNew = { store.save(null); nav.matchKey++ }, onMenu = { store.save(null); nav.screen = Screen.Menu })
                        else SeriesScoreboard(sr, res, style, onNext = { if (app.ads.ad(lux.core.Ads.Spot.Break) != null) breakAd = true else round++ }, onMenu = { nav.screen = Screen.Menu })
                    }
                }
            },
        )
        val brk = if (breakAd) app.ads.ad(lux.core.Ads.Spot.Break) else null
        if (breakAd && brk == null) LaunchedEffect(Unit) { breakAd = false; round++ }
        if (brk != null) BreakCard(brk.first, brk.second) { breakAd = false; round++ }
    }
}
