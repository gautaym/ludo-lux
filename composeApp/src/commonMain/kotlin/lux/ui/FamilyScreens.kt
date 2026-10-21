package lux.ui

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import lux.app.Nav
import lux.app.Screen
import lux.board.PIECE_COIN
import lux.board.drawBackdrop
import lux.board.pawn
import lux.core.RuleOptions
import lux.core.Series
import lux.core.net.CreateRoomRequest
import lux.core.net.GameStarted
import lux.core.net.GroupDetail
import lux.core.net.GroupSummary
import lux.core.net.MemberStanding
import lux.core.net.NextGame
import lux.core.net.RoomInfo
import lux.core.net.RoomStatus
import lux.core.net.ServerMsg
import lux.core.net.SetCpu
import lux.core.net.Snapshot
import lux.core.net.StartGame
import lux.core.net.StateCodec
import lux.core.net.Wire
import lux.game.MatchDirector
import lux.game.SeatConfig
import lux.game.Stage
import lux.net.NetError
import lux.net.RoomLink
import lux.theme.BoardStyles

private val Teal = Color(0xFF5EEAD4)
private val TealDeep = Color(0xFF0F766E)
private val TealInk = Color(0xFF032E2A)
private val names = listOf("Red", "Green", "Yellow", "Blue")

/** Screen frame for the online pages: the board's table behind, a back button and a title. */
@Composable
fun OnlinePage(title: String, onBack: () -> Unit, content: @Composable () -> Unit) {
    val st = BoardStyles.get(LocalApp.current.settings.board)
    Box(Modifier.fillMaxSize()) {
        Canvas(Modifier.fillMaxSize()) { drawBackdrop(st, 0f) }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(Modifier.fillMaxWidth().widthIn(max = 560.dp), verticalAlignment = Alignment.CenterVertically) {
                RoundIcon(onBack, size = 48.dp) { backIcon(it) }
                Spacer(Modifier.width(12.dp))
                Label(title, 30.sp, Palette.gold, display = true, shadow = true, modifier = Modifier.weight(1f))
            }
            Spacer(Modifier.height(14.dp))
            Column(Modifier.widthIn(max = 560.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) { content() }
        }
    }
}

fun androidx.compose.ui.graphics.drawscope.DrawScope.backIcon(c: Color) {
    val w = size.width
    drawLine(c, Offset(w * 0.7f, 0f), Offset(w * 0.2f, size.height / 2), w * 0.16f, androidx.compose.ui.graphics.StrokeCap.Round)
    drawLine(c, Offset(w * 0.2f, size.height / 2), Offset(w * 0.7f, size.height), w * 0.16f, androidx.compose.ui.graphics.StrokeCap.Round)
}

/** A large, friendly text box. */
@Composable
fun BigField(value: String, onChange: (String) -> Unit, placeholder: String, code: Boolean = false, modifier: Modifier = Modifier) {
    val f = LocalFonts.current
    Box(
        modifier.fillMaxWidth().height(64.dp).clip(RoundedCornerShape(16.dp)).background(Color(0xFFFDF8EC))
            .border(2.dp, Palette.gold, RoundedCornerShape(16.dp)).padding(horizontal = 16.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        val style = TextStyle(color = Color(0xFF0B1B24), fontSize = if (code) 30.sp else 24.sp, fontFamily = f.display, letterSpacing = if (code) 6.sp else 0.sp, textAlign = if (code) TextAlign.Center else TextAlign.Start)
        if (value.isEmpty()) Label(placeholder, 20.sp, Color(0xFF8A9499), align = if (code) TextAlign.Center else TextAlign.Start, modifier = Modifier.fillMaxWidth())
        BasicTextField(
            value, { onChange(if (code) Wire.cleanCode(it) else it.take(Wire.NAME_MAX)) }, Modifier.fillMaxWidth(), singleLine = true, textStyle = style,
            cursorBrush = SolidColor(Palette.goldDeep),
            keyboardOptions = KeyboardOptions(capitalization = if (code) KeyboardCapitalization.Characters else KeyboardCapitalization.Words),
        )
    }
}

@Composable
fun ErrorNote(text: String?) {
    if (text == null) return
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color(0xCC7F1D1D)).padding(12.dp)) {
        Label(text, 16.sp, Color.White, align = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    }
}

// ====================================================================== my groups

@Composable
fun FamilyHome(nav: Nav) {
    val app = LocalApp.current
    val net = app.net
    val scope = rememberCoroutineScope()
    var groups by remember { mutableStateOf<List<GroupSummary>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var dialog by remember { mutableStateOf<String?>(null) }     // "create" | "join" | "server"
    var refresh by remember { mutableIntStateOf(0) }
    nav.onBack = { nav.screen = Screen.Menu }

    LaunchedEffect(net.signedIn, refresh) {
        if (!net.signedIn) return@LaunchedEffect
        try { groups = net.groups(); error = null } catch (e: NetError) { error = e.message }
    }

    OnlinePage("Family online", { nav.screen = Screen.Menu }) {
        if (!net.signedIn) {
            NameStep("Play Ludo with your family, wherever they are.", "What should your family call you?")
        } else {
            Row(verticalAlignment = Alignment.Bottom) {
                Label("Hello, ${net.name}!", 22.sp, Color.White, display = true)
                if (net.tag > 0) Label("  #${net.tag}", 15.sp, Palette.textDim)
            }
            val list = groups
            when {
                list == null && error == null -> Label("Loading your groups...", 18.sp, Palette.textDim)
                list != null && list.isEmpty() -> Panel(Modifier.fillMaxWidth()) {
                    Label("You are not in a group yet. Create one for your family, or join with the code someone sent you.", 18.sp)
                }
                list != null -> for (g in list) GroupCard(g) { nav.screen = Screen.Group(g.id) }
            }
            ErrorNote(error)
            SaveAccountBanner()
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                BigButton("Create group", { dialog = "create" }, Modifier.weight(1f), 60.dp, Teal, TealDeep, TealInk, 21.sp)
                BigButton("Join with code", { dialog = "join" }, Modifier.weight(1f), 60.dp, textSize = 21.sp)
            }
        }
        if (LocalApp.current.settings.developer) Label("Server: ${net.server}", 12.sp, Palette.textDim, align = TextAlign.Center, modifier = Modifier.fillMaxWidth().clickable { dialog = "server" })
    }

    AnimatedVisibility(dialog != null, enter = fadeIn(), exit = fadeOut()) {
        val kind = dialog ?: "create"
        var text by remember(kind) { mutableStateOf(if (kind == "server") net.server else "") }
        Dialog(onClose = { dialog = null }) {
            Label(when (kind) { "create" -> "New family group"; "join" -> "Join a group"; else -> "Game server" }, 30.sp, Palette.gold, display = true)
            Label(when (kind) {
                "create" -> "Give it a name your family will recognise, like \"Sharma Family\"."
                "join" -> "Type the 6-letter code from the invite."
                else -> "Address of the game server (for testing at home)."
            }, 16.sp, Palette.textDim)
            BigField(text, { text = it }, when (kind) { "create" -> "Group name"; "join" -> "CODE"; else -> "http://..." }, code = kind == "join")
            BigButton(if (busy) "One moment..." else "OK", {
                if (busy) return@BigButton
                if (kind == "server") { net.changeServer(text); dialog = null; refresh++; return@BigButton }
                busy = true
                scope.launch {
                    try {
                        val g = if (kind == "create") net.createGroup(Wire.cleanName(text)) else net.joinGroup(text)
                        dialog = null
                        nav.screen = Screen.Group(g.id)
                    } catch (e: NetError) { error = e.message; dialog = null }
                    busy = false
                }
            }, Modifier.fillMaxWidth(), 60.dp, Teal, TealDeep, TealInk, 24.sp)
        }
    }
}

/**
 * First visit online. "Continue with Google" is the main way in: one tap, works in every country, and the same
 * account comes back on any phone. Playing as a guest stays possible (and can be saved with Google later).
 */
@Composable
fun NameStep(intro: String, ask: String) {
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(app.settings.name.takeIf { it != "You" } ?: "") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var asGuest by remember { mutableStateOf(false) }
    Panel(Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Label(intro, 20.sp)
            if (!asGuest) {
                GoogleButton(busy) {
                    busy = true
                    scope.launch {
                        try {
                            val idToken = app.platform.googleIdToken()
                            if (idToken != null) { app.net.signInWithGoogle(idToken); app.settings.name = app.net.name }
                            error = null
                        } catch (e: NetError) { error = e.message }
                        busy = false
                    }
                }
                Label("Keeps your groups and rank on any phone.", 14.sp, Palette.textDim, align = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                val click = tap { asGuest = true }
                Label("Play as guest instead", 17.sp, Palette.gold, align = TextAlign.Center, modifier = Modifier.fillMaxWidth().clickable(onClick = click).padding(6.dp))
                return@Column
            }
            Label(ask, 16.sp, Palette.textDim)
            BigField(name, { name = it }, "Your name")
            BigButton(if (busy) "One moment..." else "Continue", {
                if (busy || Wire.cleanName(name).isEmpty()) return@BigButton
                busy = true
                scope.launch {
                    try { app.net.signIn(Wire.cleanName(name)); app.settings.name = Wire.cleanName(name); error = null } catch (e: NetError) { error = e.message }
                    busy = false
                }
            }, Modifier.fillMaxWidth(), 64.dp, Teal, TealDeep, TealInk, 26.sp)
        }
    }
    ErrorNote(error)
}

/** The standard-looking white "Continue with Google" button, with the four-colour G drawn in code. */
@Composable
fun GoogleButton(busy: Boolean, onClick: () -> Unit) {
    val click = tap { if (!busy) onClick() }
    Row(
        Modifier.fillMaxWidth().height(64.dp).clip(RoundedCornerShape(32.dp)).background(Color.White)
            .border(1.dp, Color(0xFFDADCE0), RoundedCornerShape(32.dp)).clickable(onClick = click).padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center,
    ) {
        Canvas(Modifier.size(28.dp)) {
            val stroke = Stroke(size.width * 0.2f)
            val inset = size.width * 0.1f
            val arc = androidx.compose.ui.geometry.Size(size.width - 2 * inset, size.height - 2 * inset)
            val tl = Offset(inset, inset)
            drawArc(Color(0xFFEA4335), 200f, 110f, false, tl, arc, style = stroke)
            drawArc(Color(0xFFFBBC05), 135f, 65f, false, tl, arc, style = stroke)
            drawArc(Color(0xFF34A853), 45f, 90f, false, tl, arc, style = stroke)
            drawArc(Color(0xFF4285F4), -15f, 60f, false, tl, arc, style = stroke)
            drawLine(Color(0xFF4285F4), Offset(size.width * 0.52f, size.height * 0.5f), Offset(size.width - inset * 0.4f, size.height * 0.5f), size.width * 0.2f)
        }
        Spacer(Modifier.width(14.dp))
        Label(if (busy) "One moment..." else "Continue with Google", 22.sp, Color(0xFF1F1F1F), weight = androidx.compose.ui.text.font.FontWeight.Bold)
    }
}

/** For guests: one tap to keep the account for good (works on any phone afterwards). */
@Composable
fun SaveAccountBanner() {
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    if (!app.net.signedIn || app.net.google) return
    Panel(Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Label("Save your account", 20.sp, Color.White, display = true)
            Label("You are playing as a guest. Save with Google so your groups and rank are never lost, even on a new phone.", 14.sp, Palette.textDim)
            GoogleButton(busy) {
                busy = true
                scope.launch {
                    try { app.platform.googleIdToken()?.let { app.net.signInWithGoogle(it) }; error = null } catch (e: NetError) { error = e.message }
                    busy = false
                }
            }
        }
    }
    ErrorNote(error)
}

@Composable
private fun GroupCard(g: GroupSummary, onOpen: () -> Unit) {
    val click = tap(onOpen)
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Brush.horizontalGradient(listOf(Color(0xE6145448), Color(0xE60B2129))))
            .border(1.5.dp, Teal.copy(alpha = 0.6f), RoundedCornerShape(20.dp)).clickable(onClick = click).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Label(g.name, 26.sp, Color.White, display = true)
            Label("${g.members} ${if (g.members == 1) "member" else "members"}  ·  You are #${g.myRank}", 15.sp, Color(0xFFCCFBF1))
        }
        if (g.liveRooms > 0) {
            Box(Modifier.clip(RoundedCornerShape(50)).background(Palette.gold).padding(horizontal = 10.dp, vertical = 4.dp)) {
                Label("${g.liveRooms} game on", 14.sp, Color(0xFF3A1F00), display = true)
            }
            Spacer(Modifier.width(8.dp))
        }
        Canvas(Modifier.size(20.dp)) { playIcon(Color.White) }
    }
}

@Composable
private fun Dialog(onClose: () -> Unit, content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color(0xB3000000)).clickable(remember { MutableInteractionSource() }, null, onClick = onClose), contentAlignment = Alignment.Center) {
        Panel(Modifier.widthIn(max = 460.dp).padding(18.dp).clickable(remember { MutableInteractionSource() }, null) {}) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { content() }
        }
    }
}

// ====================================================================== one group

@Composable
fun GroupScreen(nav: Nav, groupId: String) {
    val app = LocalApp.current
    val net = app.net
    val scope = rememberCoroutineScope()
    var g by remember { mutableStateOf<GroupDetail?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var month by remember { mutableStateOf(false) }
    var newGame by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    nav.onBack = { nav.screen = Screen.Family }

    // Keep the rankings and live games fresh while the page is open.
    LaunchedEffect(groupId) {
        while (true) {
            try { g = net.group(groupId); error = null } catch (e: NetError) { error = e.message }
            delay(5_000)
        }
    }

    OnlinePage(g?.name ?: "Group", { nav.screen = Screen.Family }) {
        val d = g
        ErrorNote(error)
        if (d == null) { Label("Loading...", 18.sp, Palette.textDim); return@OnlinePage }

        // Invite: the code, big, and one tap to WhatsApp.
        Panel(Modifier.fillMaxWidth()) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Label("Invite family with this code", 16.sp, Palette.textDim)
                Label(d.code, 44.sp, Palette.gold, display = true, shadow = true)
                BigButton("Share on WhatsApp", {
                    app.platform.share("Join our family group \"${d.name}\" on Ludo Lux!\nOpen Ludo Lux, tap Family online, then Join with code: ${d.code}\n${d.inviteLink}")
                }, Modifier.fillMaxWidth(), 58.dp, Color(0xFF4ADE80), Color(0xFF15803D), Color(0xFF052E16), 22.sp)
            }
        }

        // Live games in this group.
        for (r in d.rooms) LiveRoomCard(r, net.userId) { nav.screen = Screen.Room(r.code, groupId) }
        BigButton("Start a game", { newGame = true }, Modifier.fillMaxWidth(), 70.dp, textSize = 30.sp, icon = { playIcon(it) })

        // Rankings: only games played in this group (ranked play is separate).
        Panel(Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Section("Group ranking") { Segmented(listOf(false, true), month, { if (it) "This month" else "All time" }, { month = it }, height = 50.dp) }
                val rows = if (month) d.members.sortedWith(compareByDescending<MemberStanding> { it.monthPoints }.thenByDescending { it.points }) else d.members
                rows.forEachIndexed { i, m -> StandingRow(i + 1, m, month, m.userId == net.userId) }
                Label("Points per game: place points plus one for each piece brought home.", 12.sp, Palette.textDim)
            }
        }
    }

    AnimatedVisibility(newGame, enter = fadeIn(), exit = fadeOut()) {
        val s = app.settings
        var players by remember { mutableIntStateOf(s.players) }
        var games by remember { mutableIntStateOf(s.seriesLength) }
        Dialog(onClose = { newGame = false }) {
            Label("New game", 30.sp, Palette.gold, display = true)
            Section("Players") { Segmented(listOf(2, 3, 4), players, { "$it" }, { players = it }) }
            Section("Games in a series") { Segmented(listOf(1, 3, 5), games, { if (it == 1) "1 game" else "$it games" }, { games = it }) }
            Label("Seats nobody takes are played by the computer.", 14.sp, Palette.textDim)
            BigButton(if (busy) "One moment..." else "Create game", {
                if (busy) return@BigButton
                busy = true
                scope.launch {
                    try {
                        val room = net.createRoom(CreateRoomRequest(groupId, players, games, app.shop.board(s.board), app.shop.capture(s.capture)))
                        newGame = false
                        nav.screen = Screen.Room(room.code, groupId)
                    } catch (e: NetError) { error = e.message; newGame = false }
                    busy = false
                }
            }, Modifier.fillMaxWidth(), 64.dp, textSize = 26.sp)
        }
    }
}

@Composable
private fun StandingRow(place: Int, m: MemberStanding, month: Boolean, me: Boolean) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(if (me) Color(0x33FFC94A) else Color(0x33000000)).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(36.dp).clip(CircleShape).background(when (place) { 1 -> Color(0xFFE9B949); 2 -> Color(0xFFB8C0CC); 3 -> Color(0xFFC0814E); else -> Color(0xFF475569) }), contentAlignment = Alignment.Center) {
            Label("$place", 18.sp, Color.White, display = true, shadow = true)
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Label(m.name + if (me) " (you)" else "", 20.sp, Color.White, display = true)
                if (m.tag > 0) Label(" #${m.tag}", 12.sp, Palette.textDim)
            }
            Label(if (month) "${plural(m.monthGames, "game")} this month" else "${plural(m.games, "game")}  ·  ${plural(m.wins, "win")}  ·  ${m.seriesWon} series", 13.sp, Palette.textDim)
        }
        Label("${if (month) m.monthPoints else m.points}", 26.sp, Palette.gold, display = true)
    }
}

@Composable
private fun LiveRoomCard(r: RoomInfo, me: String, onOpen: () -> Unit) {
    val click = tap(onOpen)
    val st = BoardStyles.get(r.board)
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Color(0xE60B2129)).border(2.dp, Palette.gold.copy(alpha = 0.7f), RoundedCornerShape(18.dp))
            .clickable(onClick = click).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Label(if (r.status == RoomStatus.Lobby) "Waiting to start" else "Game on", 20.sp, Palette.gold, display = true)
            Label(r.seats.joinToString(", ") { it.name.ifEmpty { "empty seat" } }, 14.sp, Palette.textDim)
        }
        ColorDots(st, r.seats.map { it.color })
        Spacer(Modifier.width(10.dp))
        Box(Modifier.clip(RoundedCornerShape(50)).background(Teal).padding(horizontal = 12.dp, vertical = 6.dp)) {
            Label(if (r.seats.any { it.userId == me }) "Open" else "Join", 16.sp, TealInk, display = true)
        }
    }
}

// ====================================================================== a room: lobby, then the games

/** One online game in progress (a fresh stage and director per game of the series). */
private class OnlineGame(val stage: Stage, val director: MatchDirector, val index: Int)

@Composable
fun RoomScreen(nav: Nav, code: String, groupId: String) {
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    val link = remember(code) { RoomLink(app.net, code, scope) }
    var game by remember { mutableStateOf<OnlineGame?>(null) }
    var paused by remember { mutableStateOf(false) }
    var rankedResult by remember { mutableStateOf<lux.core.net.RankedResult?>(null) }
    val leave = { link.stop(); nav.screen = if (groupId.isEmpty()) Screen.Ranked else Screen.Group(groupId) }
    DisposableEffect(code) { link.start(); onDispose { link.stop() } }
    nav.onBack = { if (game != null && game?.director?.showResults == false) paused = !paused else leave() }

    // The only reader of the room's messages: lobby messages are already reflected in link.room; a new game (or a
    // snapshot when joining a running one) starts a director, which then reads the game's messages itself.
    LaunchedEffect(link) {
        var pending: ServerMsg? = null
        while (true) {
            val m = pending ?: link.incoming.receive()
            pending = null
            if (m is lux.core.net.RankedResult) { rankedResult = m; continue }
            val (index, stateText) = when (m) { is GameStarted -> m.game to m.state; is Snapshot -> m.game to m.state; else -> continue }
            val room = link.room ?: continue
            val style = BoardStyles.get(room.board)
            val stage = Stage(app, style)
            val director = MatchDirector(stage)
            val me = app.net.userId
            val seats = room.seats.map { s -> SeatConfig(s.color, cpu = s.cpu, name = if (s.cpu) names[s.color] else s.name.ifEmpty { names[s.color] }, badge = s.rank) }
            val local = room.seats.filter { it.userId == me }.map { it.color }.toSet()
            val series = Series.decode(room.series)
            director.softLastPlace = series != null && series.games > 1 && !series.isFinal(index)
            director.start(seats, RuleOptions(), room.capture, true, online = StateCodec.decode(stateText), localColors = local)
            if (series != null && series.isFinal(index)) director.announce("Final game - double points!")
            game = OnlineGame(stage, director, index)
            paused = false
            pending = director.runOnline(link, local)
        }
    }

    val room = link.room
    val g = game
    when {
        g != null -> {
            val sr = room?.series?.let { Series.decode(it) }
            StageClock(g.stage)
            Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                MatchScreen(
                    g.stage, g.director, paused,
                    onPause = { paused = true }, onResume = { paused = false }, onRestart = { paused = false }, onQuit = leave,
                    seriesPoints = sr?.takeIf { it.games > 1 }?.let { s -> IntArray(s.players) { s.total(it) } },
                    seriesLabel = sr?.takeIf { it.games > 1 }?.let { "Game ${g.index + 1}/${it.games}" },
                    restartLabel = "Back to game",
                    endOverlay = {
                        val s = sr
                        val st = g.stage.style
                        val rr = rankedResult
                        if (room?.ranked == true) {
                            if (rr != null) RankedResultOverlay(rr, st, app.net.userId, onAgain = { link.stop(); nav.screen = Screen.Ranked }, onDone = leave)
                        } else if (s != null && s.results.size > g.index && room.status != RoomStatus.Playing) {
                            if (s.isOver) SeriesChampion(s, st, onNew = leave, onMenu = leave)
                            else SeriesScoreboard(s, s.results.last(), st, onNext = { link.send(NextGame) }, onMenu = leave)
                        }
                    },
                )
                if (!link.connected) ReconnectingBanner()
            }
        }
        room?.ranked == true -> Box(Modifier.safeDrawingPadding()) { MatchFound(room, leave) }
        else -> Box(Modifier.safeDrawingPadding()) { Lobby(link, room, leave) }
    }
}

@Composable
private fun ReconnectingBanner() {
    Box(Modifier.fillMaxWidth().padding(top = 70.dp), contentAlignment = Alignment.TopCenter) {
        Box(Modifier.clip(RoundedCornerShape(50)).background(Color(0xE67F1D1D)).padding(horizontal = 16.dp, vertical = 8.dp)) {
            Label("Connection lost - reconnecting...", 16.sp, Color.White, display = true)
        }
    }
}

@Composable
private fun Lobby(link: RoomLink, room: RoomInfo?, leave: () -> Unit) {
    val app = LocalApp.current
    val me = app.net.userId
    OnlinePage("Game room", leave) {
        if (room == null) {
            Label(link.problem ?: "Connecting...", 20.sp, if (link.problem != null) Color(0xFFFCA5A5) else Palette.textDim)
            return@OnlinePage
        }
        val st = BoardStyles.get(room.board)
        val host = room.hostId == me
        Panel(Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Label("${room.players} players  ·  ${if (room.seriesGames == 1) "1 game" else "${room.seriesGames} games"}  ·  ${st.name} board", 16.sp, Palette.textDim)
                for (s in room.seats) {
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color(0x33000000)).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        val kind = app.settings.pieceStyle
                        Canvas(Modifier.size(44.dp)) {
                            drawCircle(Brush.radialGradient(listOf(lerp(st.seat[s.color], Color.White, 0.25f), st.seatDeep[s.color]), center, size.minDimension / 2), size.minDimension / 2)
                            drawCircle(Color.White.copy(alpha = 0.85f), size.minDimension / 2, style = Stroke(2.dp.toPx()))
                            pawn(Offset(center.x, center.y + size.height * (if (kind == PIECE_COIN) 0.18f else 0.3f)), size.height * (if (kind == PIECE_COIN) 0.9f else 0.62f), st.pieceSeat[s.color], st.pieceDeep[s.color], st.pieceLight[s.color], st.collar, 1f, kind = kind)
                        }
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            val who = when {
                                s.userId == me -> "${s.name} (you)"
                                s.userId != null -> s.name
                                s.cpu -> "Computer"
                                else -> "Waiting for someone..."
                            }
                            Label(who, 20.sp, if (s.userId == null && !s.cpu) Palette.textDim else Color.White, display = true)
                            if (s.userId != null) Label(if (s.connected) "Here" else "Away", 13.sp, if (s.connected) Color(0xFF86EFAC) else Color(0xFFFCA5A5))
                        }
                        if (host && s.userId == null) {
                            val click = tap { link.send(SetCpu(s.color, !s.cpu)) }
                            Box(Modifier.clip(RoundedCornerShape(50)).background(if (s.cpu) Palette.gold else Color(0x55FFFFFF)).clickable(onClick = click).padding(horizontal = 12.dp, vertical = 6.dp)) {
                                Label(if (s.cpu) "Computer" else "Add computer", 14.sp, if (s.cpu) Color(0xFF3A1F00) else Color.White, display = true)
                            }
                        }
                    }
                }
            }
        }
        BigButton("Invite to this game", {
            app.platform.share("Come and play Ludo with me on Ludo Lux!\nOpen Ludo Lux, tap Family online, then Join with code: ${room.code}\n${room.link}")
        }, Modifier.fillMaxWidth(), 58.dp, Color(0xFF4ADE80), Color(0xFF15803D), Color(0xFF052E16), 22.sp)
        if (host) {
            BigButton("Start game", { link.send(StartGame) }, Modifier.fillMaxWidth(), 72.dp, textSize = 32.sp, icon = { playIcon(it) })
            Label("Empty seats will be played by the computer.", 14.sp, Palette.textDim, align = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        } else {
            val hostName = room.seats.firstOrNull { it.userId == room.hostId }?.name ?: "the host"
            Label("Waiting for $hostName to start...", 20.sp, Palette.gold, display = true, align = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        }
        Label("Game code: ${room.code}", 14.sp, Palette.textDim, align = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    }
}

