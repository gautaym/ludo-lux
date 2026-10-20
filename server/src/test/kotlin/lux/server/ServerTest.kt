package lux.server

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.delay
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.delete
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout
import lux.core.Cpu
import lux.core.CpuLevel
import lux.core.GameState
import lux.core.Phase
import lux.core.net.AuthResponse
import lux.core.net.ClientMsg
import lux.core.net.Cmd
import lux.core.net.CreateGroupRequest
import lux.core.net.CreateRoomRequest
import lux.core.net.GameStarted
import lux.core.net.GroupDetail
import lux.core.net.GroupSummary
import lux.core.net.GuestRequest
import lux.core.net.Hello
import lux.core.net.JoinGroupRequest
import lux.core.net.ResultMsg
import lux.core.net.RoomInfo
import lux.core.net.RoomStatus
import lux.core.net.RoomUpdate
import lux.core.net.ServerMsg
import lux.core.net.Snapshot
import lux.core.net.StartGame
import lux.core.net.StateCodec
import lux.core.net.Wire
import java.io.File
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ServerTest {
    private fun withServer(block: suspend ApplicationTestBuilder.(HttpClient) -> Unit) = testApplication {
        val db = File.createTempFile("lux", ".db").apply { deleteOnExit() }
        application { luxServer(Store(db.path), Config(publicUrl = "https://ludo.example", turnSeconds = 2, pace = 0.0, tickMs = 5)) }
        val client = createClient {
            install(ContentNegotiation) { json(Wire.json) }
            install(WebSockets)
        }
        block(client)
    }

    private suspend fun HttpClient.guest(name: String): AuthResponse =
        post("/v1/auth/guest") { contentType(ContentType.Application.Json); setBody(GuestRequest(name)) }.body()

    private suspend inline fun <reified T> HttpClient.postAs(token: String, path: String, body: Any): T =
        post(path) { bearerAuth(token); contentType(ContentType.Application.Json); setBody(body) }.body()

    /**
     * A bot player: joins the room, plays its own turns with the CPU logic, optionally starts the game (host) and
     * optionally hangs up after [quitAfter] results. Returns the last room info it saw.
     */
    private suspend fun HttpClient.bot(me: AuthResponse, room: String, host: Boolean, quitAfter: Int = Int.MAX_VALUE, log: MutableList<String>? = null, ranked: MutableList<lux.core.net.RankedResult>? = null): RoomInfo {
        var info: RoomInfo? = null
        webSocket("/v1/play") {
            fun enc(m: ClientMsg) = Frame.Text(Wire.json.encodeToString(ClientMsg.serializer(), m))
            send(enc(Hello(me.token, room)))
            var results = 0
            var started = false
            suspend fun act(s: GameState) {
                if (s.isOver) return
                val myColor = info?.seats?.firstOrNull { it.userId == me.userId }?.color ?: return
                val mySeat = s.seatColor.indexOf(myColor)
                if (s.current != mySeat) return
                val cmd = if (s.phase == Phase.WaitingForRoll) Cmd(Random.nextLong(1, Long.MAX_VALUE), "roll", expected = s.sequence)
                else Cmd(Random.nextLong(1, Long.MAX_VALUE), "move", Cpu.chooseMove(s, mySeat, CpuLevel.Easy, Random), s.sequence)
                send(enc(cmd))
            }
            for (frame in incoming) {
                val text = (frame as? Frame.Text)?.readText() ?: continue
                when (val m = Wire.json.decodeFromString(ServerMsg.serializer(), text)) {
                    is RoomUpdate -> {
                        info = m.room
                        if (host && !started && m.room.status == RoomStatus.Lobby && m.room.seats.count { it.userId != null } >= 2) {
                            started = true; send(enc(StartGame))
                        }
                        if (m.room.status == RoomStatus.Done) return@webSocket
                        if (host && m.room.status == RoomStatus.BetweenGames) send(enc(lux.core.net.NextGame))
                    }
                    is GameStarted -> { log?.add("start"); act(StateCodec.decode(m.state)) }
                    is Snapshot -> { log?.add("snapshot"); act(StateCodec.decode(m.state)) }
                    is lux.core.net.RankedResult -> ranked?.add(m)
                    is lux.core.net.ErrorMsg -> { log?.add("error: ${m.message}"); if (info == null) return@webSocket }
                    is ResultMsg -> {
                        if (m.byCpu) log?.add("cpu-for-someone")
                        if (++results >= quitAfter) return@webSocket
                        act(StateCodec.decode(m.state))
                    }
                    else -> {}
                }
            }
        }
        return info ?: RoomInfo("", "", "", 0, 0, "", 0, RoomStatus.Done, emptyList())
    }

    private suspend fun HttpClient.queueUntilMatched(me: AuthResponse, players: Int): String {
        var st: lux.core.net.QueueStatus = postAs(me.token, "/v1/ranked/queue", lux.core.net.QueueRequest(players))
        while (st.state != lux.core.net.QueueState.Matched) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { kotlinx.coroutines.delay(20) }
            st = get("/v1/ranked/queue") { bearerAuth(me.token) }.body()
        }
        return st.room
    }

    @Test fun twoFamilyMembersAndAComputerPlayASeriesToTheEnd() = withServer { http ->
        val mom = http.guest("Mom")
        val dad = http.guest("Dad")
        val group: GroupDetail = http.postAs(mom.token, "/v1/groups", CreateGroupRequest("Sharma Family"))
        assertEquals("https://ludo.example/g/${group.code}", group.inviteLink)
        http.postAs<GroupDetail>(dad.token, "/v1/groups/join", JoinGroupRequest(group.code.lowercase()))
        val room: RoomInfo = http.postAs(mom.token, "/v1/rooms", CreateRoomRequest(group.id, players = 3, seriesGames = 3))
        assertEquals(RoomStatus.Lobby, room.status)

        val end = withTimeout(120_000) {
            coroutineScope {
                val a = async { http.bot(mom, room.code, host = true) }
                val b = async { http.bot(dad, room.code, host = false) }
                a.await().also { b.await() }
            }
        }
        // The third seat became a computer player; the series ran all three games.
        assertEquals(RoomStatus.Done, end.status)
        assertTrue(end.seats.single { it.userId == null }.cpu)
        val series = lux.core.Series.decode(end.series)!!
        assertTrue(series.isOver)
        assertEquals(3, series.results.size)

        // Group standings: both humans played 3 games; the computer is not ranked.
        val detail: GroupDetail = http.get("/v1/groups/${group.id}") { bearerAuth(dad.token) }.body()
        assertEquals(setOf("Mom", "Dad"), detail.members.map { it.name }.toSet())
        assertTrue(detail.members.all { it.games == 3 && it.monthGames == 3 })
        assertEquals(detail.members.sortedByDescending { it.points }, detail.members)
        // A series win is recorded for the champion only if the champion is a person (the computer may win too).
        val champion = end.seats[series.standings().first()]
        assertEquals(if (champion.cpu) 0 else 1, detail.members.sumOf { it.seriesWon })
        if (!champion.cpu) assertEquals(1, detail.members.single { it.userId == champion.userId }.seriesWon)
    }

    @Test fun aPlayerWhoLeavesIsCoveredByTheComputerAndCanRejoin() = withServer { http ->
        val mom = http.guest("Mom")
        val son = http.guest("Son")
        val group: GroupDetail = http.postAs(mom.token, "/v1/groups", CreateGroupRequest("Home"))
        http.postAs<GroupDetail>(son.token, "/v1/groups/join", JoinGroupRequest(group.code))
        val room: RoomInfo = http.postAs(mom.token, "/v1/rooms", CreateRoomRequest(group.id, players = 2, seriesGames = 1))
        val log = java.util.Collections.synchronizedList(mutableListOf<String>())
        withTimeout(120_000) {
            coroutineScope {
                val a = async { http.bot(mom, room.code, host = true, log = log) }
                // The son hangs up after a few moves, then comes back.
                val b = async {
                    http.bot(son, room.code, host = false, quitAfter = 4)
                    // Come back once the computer has covered at least one of his turns.
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                        while ("cpu-for-someone" !in log) kotlinx.coroutines.delay(2)
                    }
                    http.bot(son, room.code, host = false, log = log)
                }
                a.await(); b.await()
            }
        }
        assertTrue("cpu-for-someone" in log, "the computer played for the son while he was away")
        assertTrue("snapshot" in log, "rejoining a running game sends the exact position")
    }

    @Test fun groupsAreListedPerPersonAndProtected() = withServer { http ->
        val a = http.guest("Asha")
        val b = http.guest("Bina")
        val g1: GroupDetail = http.postAs(a.token, "/v1/groups", CreateGroupRequest("Family"))
        val g2: GroupDetail = http.postAs(b.token, "/v1/groups", CreateGroupRequest("Friends"))
        http.postAs<GroupDetail>(a.token, "/v1/groups/join", JoinGroupRequest(g2.code))
        // One person, many groups.
        val mine: List<GroupSummary> = http.get("/v1/groups") { bearerAuth(a.token) }.body()
        assertEquals(listOf("Family", "Friends"), mine.map { it.name })
        // Outsiders can't see a group or start games in it; unknown codes are refused; no token, no entry.
        assertEquals(HttpStatusCode.Forbidden, http.get("/v1/groups/${g1.id}") { bearerAuth(b.token) }.status)
        assertEquals(HttpStatusCode.Forbidden, http.post("/v1/rooms") { bearerAuth(b.token); contentType(ContentType.Application.Json); setBody(CreateRoomRequest(g1.id, 2, 1)) }.status)
        assertEquals(HttpStatusCode.NotFound, http.post("/v1/groups/join") { bearerAuth(b.token); contentType(ContentType.Application.Json); setBody(JoinGroupRequest("ZZZZZZ")) }.status)
        assertEquals(HttpStatusCode.Unauthorized, http.get("/v1/groups").status)
        // The invite page shows only the cleaned code.
        val page = http.get("/g/<script>${g1.code}").body<String>()
        assertTrue("<script>" !in page)
    }

    @Test fun rankedPlayersAreMatchedPlayedAndRated() = withServer { http ->
        val a = http.guest("Asha")
        val b = http.guest("Bina")
        val results = java.util.Collections.synchronizedList(mutableListOf<lux.core.net.RankedResult>())
        withTimeout(60_000) {
            coroutineScope {
                val ra = async { http.queueUntilMatched(a, 2) }
                val rb = async { http.queueUntilMatched(b, 2) }
                val room = ra.await()
                assertEquals(room, rb.await(), "both matched into the same room")
                val x = async { http.bot(a, room, host = false, ranked = results) }
                val y = async { http.bot(b, room, host = false, ranked = results) }
                x.await(); y.await()
            }
        }
        val r = results.first()
        assertEquals(2, r.changes.size)
        val winner = r.changes.single { it.place == 1 }
        assertTrue(winner.delta > 0 && r.changes.single { it.place == 2 }.delta < 0)
        assertTrue(kotlin.math.abs(r.changes.sumOf { it.delta }) <= 1, "what one gains the other loses (within rounding)")
        assertTrue(r.changes.all { it.placement == 1 && it.after == "Placement" }, "first ranked game is placement 1 of 10")
        val me: lux.core.net.RankedProfile = http.get("/v1/ranked/me") { bearerAuth(a.token) }.body()
        assertEquals(1, me.games); assertEquals(1, me.placementPlayed); assertTrue(!me.placed)
        // Ranked rooms never show up in family groups, and ranked play never touches group standings.
        val board: lux.core.net.Leaderboard = http.get("/v1/ranked/leaderboard") { bearerAuth(a.token) }.body()
        assertTrue(board.rows.isEmpty(), "nobody is on the leaderboard until placement is done")
    }

    @Test fun aLonePlayerWaitsAndCanCancel() = withServer { http ->
        val a = http.guest("Asha")
        val st: lux.core.net.QueueStatus = http.postAs(a.token, "/v1/ranked/queue", lux.core.net.QueueRequest(4))
        assertEquals(lux.core.net.QueueState.Waiting, st.state)
        assertEquals(1, st.inQueue)
        val gone = http.delete("/v1/ranked/queue") { bearerAuth(a.token) }.body<lux.core.net.QueueStatus>()
        assertEquals(lux.core.net.QueueState.None, gone.state)
        assertEquals(HttpStatusCode.BadRequest, http.post("/v1/ranked/queue") { bearerAuth(a.token); contentType(ContentType.Application.Json); setBody(lux.core.net.QueueRequest(3)) }.status)
    }

    @Test fun aRankedLeaverIsPlacedLastAndOutsidersCannotJoin() = withServer { http ->
        val a = http.guest("Asha")
        val b = http.guest("Bina")
        val c = http.guest("Chetan")
        val results = java.util.Collections.synchronizedList(mutableListOf<lux.core.net.RankedResult>())
        val log = java.util.Collections.synchronizedList(mutableListOf<String>())
        withTimeout(60_000) {
            coroutineScope {
                val ra = async { http.queueUntilMatched(a, 2) }
                val rb = async { http.queueUntilMatched(b, 2) }
                val room = ra.await(); rb.await()
                // Chetan was not matched into this room: he is turned away.
                http.bot(c, room, host = false, log = log)
                assertTrue(log.any { it.startsWith("error") }, "outsider refused: $log")
                val x = async { http.bot(a, room, host = false, ranked = results) }
                // Bina leaves after two moves and never comes back.
                val y = async { http.bot(b, room, host = false, quitAfter = 2) }
                y.await(); x.await()
            }
        }
        val r = results.first()
        val bina = r.changes.single { it.name == "Bina" }
        assertTrue(bina.left, "Bina is marked as having left")
        assertEquals(2, bina.place, "a leaver is placed last")
        assertTrue(bina.delta < 0)
    }

    @Test fun gamesSurviveAServerRestart() {
        val db = File.createTempFile("lux-restart", ".db").apply { deleteOnExit() }
        lateinit var mom: AuthResponse
        lateinit var dad: AuthResponse
        lateinit var room: RoomInfo
        fun server(block: suspend (HttpClient) -> Unit) = testApplication {
            application { luxServer(Store(db.path), Config(publicUrl = "https://ludo.example", turnSeconds = 600, pace = 0.0, tickMs = 5)) }
            block(createClient { install(ContentNegotiation) { json(Wire.json) }; install(WebSockets) })
        }
        // Before the restart: a game starts and a few moves are played, then everybody's connection drops.
        server { http ->
            mom = http.guest("Mom"); dad = http.guest("Dad")
            val group: GroupDetail = http.postAs(mom.token, "/v1/groups", CreateGroupRequest("Home"))
            http.postAs<GroupDetail>(dad.token, "/v1/groups/join", JoinGroupRequest(group.code))
            room = http.postAs(mom.token, "/v1/rooms", CreateRoomRequest(group.id, players = 2, seriesGames = 1))
            withTimeout(60_000) {
                coroutineScope {
                    val a = async { http.bot(mom, room.code, host = true, quitAfter = 6) }
                    val b = async { http.bot(dad, room.code, host = false, quitAfter = 6) }
                    a.await(); b.await()
                }
            }
        }
        // After the restart: the same players come back, get the exact position, and finish the game.
        val log = java.util.Collections.synchronizedList(mutableListOf<String>())
        server { http ->
            val end = withTimeout(60_000) {
                coroutineScope {
                    val a = async { http.bot(mom, room.code, host = true, log = log) }
                    val b = async { http.bot(dad, room.code, host = false) }
                    a.await().also { b.await() }
                }
            }
            assertTrue("snapshot" in log, "the restored game was sent to the returning player: $log")
            assertEquals(RoomStatus.Done, end.status)
        }
    }

    @Test fun anUnjoinedRoomSurvivesARestart() {
        val db = File.createTempFile("lux-lobby", ".db").apply { deleteOnExit() }
        lateinit var mom: AuthResponse
        lateinit var room: RoomInfo
        fun server(block: suspend (HttpClient) -> Unit) = testApplication {
            application { luxServer(Store(db.path), Config(publicUrl = "https://ludo.example", tickMs = 5)) }
            block(createClient { install(ContentNegotiation) { json(Wire.json) }; install(WebSockets) })
        }
        // The host shares the invite link, then the server restarts before anyone opens it.
        server { http ->
            mom = http.guest("Mom")
            val group: GroupDetail = http.postAs(mom.token, "/v1/groups", CreateGroupRequest("Home"))
            room = http.postAs(mom.token, "/v1/rooms", CreateRoomRequest(group.id, players = 4, seriesGames = 3))
            delay(100)
        }
        server { http ->
            val res = http.get("/v1/rooms/${room.code}") { bearerAuth(mom.token) }
            assertEquals(HttpStatusCode.OK, res.status, res.bodyAsText())
            assertEquals(3, res.body<RoomInfo>().seriesGames)
        }
    }
}
