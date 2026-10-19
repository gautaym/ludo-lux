package lux

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import lux.app.AppContext
import lux.app.Nav
import lux.app.Screen
import lux.app.SilentPlatform
import lux.core.CpuLevel
import lux.core.Series
import lux.core.SeriesSeat
import lux.ui.SeriesChampion
import lux.ui.SeriesScoreboard
import lux.core.RuleOptions
import lux.game.MatchDirector
import lux.game.SeatConfig
import lux.game.Stage
import lux.theme.BoardStyles
import lux.ui.LuxApp
import lux.ui.MatchScreen
import lux.ui.ProvideLux
import lux.ui.StageClock
import org.jetbrains.skia.EncodedImageFormat
import java.io.File

/**
 * Headless screenshots of the real UI at phone resolution (1080 x 2340, density 2.75), for reviewing changes without
 * a device. Usage: gradlew :composeApp:shots -PshotArgs="menu boards showcase match"
 */
@OptIn(ExperimentalComposeUiApi::class)
fun record(name: String, seconds: Float, every: Float, saveAt: List<Float>? = null, w: Int = 1080, h: Int = 2340, density: Float = 2.75f, content: @Composable () -> Unit) {
    val dir = File("shots/$name").apply { deleteRecursively(); mkdirs() }
    ImageComposeScene(w, h, Density(density), content = content).use { scene ->
        val dt = 1f / 60
        var t = 0f
        var nextSave = 0f
        var n = 0
        val targets = saveAt?.toMutableList()
        while (t <= seconds) {
            val img = scene.render((t * 1e9).toLong())
            val save = if (targets != null) targets.isNotEmpty() && t >= targets[0] else t >= nextSave
            if (save) {
                if (targets != null) targets.removeAt(0) else nextSave += every
                val data = img.encodeToData(EncodedImageFormat.PNG) ?: error("encode")
                File(dir, "f%04d.png".format(n++)).writeBytes(data.bytes)
            }
            t += dt
        }
    }
    println("shots/$name: done")
}

@Composable
fun TestMatch(app: AppContext, board: String, seats: List<SeatConfig>, capture: Int = 7, script: suspend (MatchDirector) -> Unit) {
    ProvideLux(app) {
        val stage = remember { Stage(app, BoardStyles.get(board)) }
        val director = remember { MatchDirector(stage) }
        StageClock(stage)
        LaunchedEffect(Unit) {
            director.start(seats, RuleOptions(), capture, true)
            script(director)
        }
        MatchScreen(stage, director, false, {}, {}, {}, {})
    }
}

fun cpuSeats(n: Int = 4, human: Boolean = false): List<SeatConfig> {
    val names = listOf("Red", "Green", "Yellow", "Blue")
    val colors = when (n) { 2 -> listOf(0, 2); 3 -> listOf(0, 1, 2); else -> listOf(0, 1, 2, 3) }
    return colors.mapIndexed { i, c -> SeatConfig(c, cpu = !(human && i == 0), level = CpuLevel.Medium, name = if (human && i == 0) "You" else names[c]) }
}

fun main(args: Array<String>) {
    if (args.firstOrNull() == "ranked") { rankedShots(args[1], args.getOrNull(3)?.let { androidx.compose.ui.geometry.Offset(args[2].toFloat(), it.toFloat()) }, args.getOrNull(4)?.toFloat() ?: 4f); return }
    if (args.firstOrNull() == "online") { onlineShots(args.getOrElse(1) { "http://localhost:8090" }); return }
    val want = args.toSet().ifEmpty { setOf("menu", "boards") }
    if ("menu" in want) record("menu", 1.2f, 1f, listOf(1.1f)) { LuxApp(AppContext(SilentPlatform), Nav()) }
    if ("boards" in want) for (b in BoardStyles.all) record("board_${b.id}", 0.8f, 1f, listOf(0.7f)) {
        TestMatch(AppContext(SilentPlatform), b.id, cpuSeats(4, human = true)) { d ->
            d.debugSetProgress(0, 0, 9); d.debugSetProgress(0, 1, 30); d.debugSetProgress(1, 0, 14); d.debugSetProgress(2, 2, 54)
            d.debugSetProgress(3, 0, 3); d.debugSetProgress(3, 1, 3); d.debugSetProgress(1, 3, 57)
            d.run()
        }
    }
    if ("match" in want) record("match", 240f, 2f, w = 540, h = 1170, density = 1.375f) {
        TestMatch(AppContext(SilentPlatform), "royal", cpuSeats(4)) { d -> d.run() }
    }
    if ("series" in want) {
        fun family() = Series(3, listOf(SeriesSeat(3, false, 0, "Dad"), SeriesSeat(0, true, 1, "Red"), SeriesSeat(1, true, 1, "Green"), SeriesSeat(2, false, 0, "Mom")))
        val st = BoardStyles.royal
        val one = family().also { it.record(intArrayOf(2, 1, 3, 4), intArrayOf(3, 4, 1, 0)) }
        record("series_board", 2.2f, 1f, listOf(0.25f, 2.1f)) {
            ProvideLux(AppContext(SilentPlatform)) { SeriesScoreboard(one, one.results.last(), st, {}, {}) }
        }
        val done = family().also {
            it.record(intArrayOf(2, 1, 3, 4), intArrayOf(3, 4, 1, 0))
            it.record(intArrayOf(1, 3, 2, 4), intArrayOf(4, 1, 2, 0))
            it.record(intArrayOf(1, 4, 3, 2), intArrayOf(4, 0, 1, 3))
        }
        record("series_champion", 2.5f, 1f, listOf(2.4f)) {
            ProvideLux(AppContext(SilentPlatform)) { SeriesChampion(done, st, {}, {}) }
        }
        val app = AppContext(SilentPlatform).also { it.series.save(one) }
        record("series_menu", 1.2f, 1f, listOf(1.1f)) { LuxApp(app, Nav()) }
        // Soft sign on the board: a game mid-series where Blue finished last.
        record("series_soft", 2.5f, 1f, listOf(2.4f)) {
            TestMatch(AppContext(SilentPlatform), "sunny", cpuSeats(4, human = true)) { d ->
                d.softLastPlace = true
                d.stage.awards += lux.game.AwardSprite(lux.game.Geo.baseCentre(3), 4, true, BoardStyles.sunny.seat[3], soft = true)
                d.stage.awards += lux.game.AwardSprite(lux.game.Geo.baseCentre(1), 1, false, BoardStyles.sunny.seat[1])
                d.run()
            }
        }
    }
    if ("flow" in want) {
        // A saved 2-player series game near its end: resume it, let the computers finish, expect the scoreboard.
        val app = AppContext(SilentPlatform)
        val series = Series(3, listOf(SeriesSeat(0, true, 1, "Red"), SeriesSeat(2, true, 1, "Yellow")))
        app.series.save(series)
        val g = lux.core.Engine.create(lux.core.MatchSetup(listOf(0, 2)))
        intArrayOf(57, 57, 57, 54).copyInto(g.progress[0]); intArrayOf(57, 57, 56, 50).copyInto(g.progress[1])
        app.series.saveGame(0, g)
        val nav = Nav().apply { screen = Screen.Match }
        record("series_flow", 70f, 2f, w = 540, h = 1170, density = 1.375f) { LuxApp(app, nav) }
        val after = app.series.current
        println("series after: games played=${after?.gameIndex} totals=${after?.let { s -> (0 until s.players).map { s.total(it) } }} savedGame=${app.series.savedGame(0) != null}")
    }
    if ("hop" in want) for (kind in 0..1) {
        val app = AppContext(SilentPlatform).also { it.settings.pieceStyle = kind }
        record("hop_$kind", 3.4f, 1f / 30, w = 540, h = 1170, density = 1.375f) {
            TestMatch(app, "royal", cpuSeats(4)) { d -> d.dice.enqueue(6, 5, 1, 1, 1); d.run() }
        }
    }
    if ("contrast" in want) for (kind in 0..1) for (b in listOf("royal", "sunny", "bold")) {
        val app = AppContext(SilentPlatform).also { it.settings.pieceStyle = kind }
        record("contrast_${b}_$kind", 0.5f, 1f, listOf(0.45f)) {
            TestMatch(app, b, cpuSeats(4, human = true)) { d ->
                // Each colour: two in the yard, one on its own start square, one in its own home lane.
                for (s in 0 until 4) { d.debugSetProgress(s, 2, 1); d.debugSetProgress(s, 3, 53) }
                d.run()
            }
        }
    }
    if ("pick" in want) {
        val log = StringBuilder()
        record("pick", 6f, 0.25f, null, w = 540, h = 1170, density = 1.375f) {
            TestMatch(AppContext(SilentPlatform), "royal", cpuSeats(4, human = true)) { d ->
                d.debugSetProgress(0, 0, 10); d.debugSetProgress(0, 1, 11)
                d.dice.enqueue(3)
                d.stage.launch { d.run() }
                d.stage.wait(0.5f)
                d.requestRoll()
                while (d.hint != "Pick a piece") d.stage.frame()
                d.stage.wait(0.3f)
                val a = lux.game.Geo.piece(0, 0, 10); val b = lux.game.Geo.piece(0, 1, 11)
                d.tapBoard(lux.game.G((a.x + b.x) / 2, (a.y + b.y) / 2))
                d.stage.wait(0.8f)
                log.appendLine("after ambiguous tap: p0=${d.state!!.progress[0][0]} p1=${d.state!!.progress[0][1]} hint='${d.hint}'")
                d.tapBoard(lux.game.Geo.piece(0, 1, 14))
                d.stage.wait(1.5f)
                log.appendLine("after marker tap: p0=${d.state!!.progress[0][0]} p1=${d.state!!.progress[0][1]}")
            }
        }
        print(log)
    }
    if ("rankedresult" in want) {
        val r = lux.core.net.RankedResult(listOf(
            lux.core.net.RankChange(0, "me", "Asha", 1, 21, "Silver I", "Gold III", promoted = true, demoted = false, placement = 0, left = false),
            lux.core.net.RankChange(1, "b", "Bina", 2, 6, "Gold II", "Gold II", promoted = false, demoted = false, placement = 0, left = false),
            lux.core.net.RankChange(2, "c", "Chetan", 3, -9, "Gold III", "Gold III", promoted = false, demoted = false, placement = 0, left = false),
            lux.core.net.RankChange(3, "d", "Dev", 4, -18, "Silver II", "Silver III", promoted = false, demoted = true, placement = 0, left = true),
        ))
        record("ranked_result", 1.5f, 1f, listOf(1.4f)) {
            ProvideLux(AppContext(SilentPlatform)) { lux.ui.RankedResultOverlay(r, BoardStyles.royal, "me", {}, {}) }
        }
    }
    if ("showcase" in want) {
        val app = AppContext(SilentPlatform)
        val nav = Nav().apply { screen = Screen.Showcase }
        record("showcase", 75f, 0.1f, w = 540, h = 1170, density = 1.375f) { LuxApp(app, nav) }
    }
}
