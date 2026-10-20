package lux.server

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.origin
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.header
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.pingPeriod
import io.ktor.server.websocket.timeout
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import lux.core.net.AuthResponse
import lux.core.net.ClientMsg
import lux.core.net.CreateGroupRequest
import lux.core.net.CreateRoomRequest
import lux.core.net.ErrorMsg
import lux.core.net.ErrorResponse
import lux.core.net.GroupDetail
import lux.core.net.GroupSummary
import lux.core.net.GoogleSignInRequest
import lux.core.net.GuestRequest
import lux.core.net.Hello
import lux.core.net.JoinGroupRequest
import lux.core.net.RenameRequest
import lux.core.net.ServerMsg
import lux.core.net.Wire
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.seconds

/**
 * Configuration comes from environment variables so the same jar runs anywhere:
 * PORT (8080), DB_PATH (ludolux.db), PUBLIC_URL (used in invite links), TURN_SECONDS (45).
 */
fun main() {
    val env = System.getenv()
    val port = env["PORT"]?.toIntOrNull() ?: 8080
    val cfg = Config(
        publicUrl = env["PUBLIC_URL"] ?: "http://localhost:$port",
        turnSeconds = env["TURN_SECONDS"]?.toIntOrNull() ?: 45,
    )
    val store = Store(env["DB_PATH"] ?: "ludolux.db")
    // GOOGLE_CLIENT_IDS: comma-separated OAuth client ids whose sign-in tokens we accept (the app's web client id).
    val real = GoogleVerifier(env["GOOGLE_CLIENT_IDS"].orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet())
    // AUTH_MODE=test (home testing only): also accept "test:<id>:<name>" tokens from debug builds, before Google is set up.
    val google = if (env["AUTH_MODE"] == "test") TokenVerifier { t ->
        if (t.startsWith("test:")) t.split(':').let { GoogleIdentity("test-" + it.getOrElse(1) { "x" }, it.getOrElse(2) { "Player" }, "") } else real.verify(t)
    } else real
    // BILLING_MODE: "google" (needs PLAY_PACKAGE and PLAY_SERVICE_ACCOUNT, a JSON key file), "test" (home testing only), or off.
    val billing = when (env["BILLING_MODE"]) {
        "google" -> GooglePlayBilling(env["PLAY_PACKAGE"] ?: "com.ludolux.kmp", java.nio.file.Path.of(env["PLAY_SERVICE_ACCOUNT"] ?: "play-service-account.json"))
        "test" -> TestBilling
        else -> NoBilling
    }
    embeddedServer(CIO, port = port, host = "0.0.0.0") { luxServer(store, cfg, google, billing) }.start(wait = true)
}

class HttpError(val status: HttpStatusCode, message: String) : Exception(message)

/** Small fixed-window limiter (per key per window), so nobody can flood account creation or group joins. */
class Limiter(private val max: Int, private val windowMs: Long) {
    private val hits = ConcurrentHashMap<String, Pair<Long, Int>>()
    fun allow(key: String, now: Long = System.currentTimeMillis()): Boolean {
        val (start, n) = hits[key]?.takeIf { now - it.first < windowMs } ?: (now to 0)
        hits[key] = start to n + 1
        return n < max
    }
}

fun Application.luxServer(store: Store, cfg: Config, google: TokenVerifier = TokenVerifier { null }, billing: PurchaseVerifier = NoBilling) {
    val log = LoggerFactory.getLogger("lux")
    val scope = CoroutineScope(SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
    // Stop rooms, timers and matchmaking when the server stops (live rooms are already saved).
    monitor.subscribe(io.ktor.server.application.ApplicationStopped) { scope.cancel() }
    val rooms = Rooms(store, cfg, scope)
    val guestLimit = Limiter(20, 3_600_000)
    val joinLimit = Limiter(30, 600_000)
    val matchmaker = Matchmaker(store, rooms)
    rooms.restoreAll().takeIf { it > 0 }?.let { log.info("restored $it live room(s) after restart") }
    scope.launch { while (true) { delay(60_000); rooms.sweep() } }
    scope.launch { while (true) { delay(1_000); runCatching { matchmaker.match() }.onFailure { log.error("matchmaking", it) } } }

    install(ContentNegotiation) { json(Wire.json) }
    install(CallLogging)
    install(WebSockets) {
        pingPeriod = 20.seconds
        timeout = 60.seconds
        maxFrameSize = 16 * 1024
    }
    install(StatusPages) {
        exception<HttpError> { call, e -> call.respond(e.status, ErrorResponse(e.message ?: "error")) }
        exception<kotlinx.serialization.SerializationException> { call, _ -> call.respond(HttpStatusCode.BadRequest, ErrorResponse("Bad request.")) }
        exception<Throwable> { call, e -> log.error("unhandled", e); call.respond(HttpStatusCode.InternalServerError, ErrorResponse("Something went wrong.")) }
    }

    fun ApplicationCall.user(): User {
        val token = request.header("Authorization")?.removePrefix("Bearer ")?.trim()
            ?: throw HttpError(HttpStatusCode.Unauthorized, "Not signed in.")
        return store.userByToken(token) ?: throw HttpError(HttpStatusCode.Unauthorized, "Not signed in.")
    }

    fun detail(g: Group): GroupDetail = GroupDetail(
        g.id, g.name, g.code, "${cfg.publicUrl}/g/${g.code}", store.standings(g.id), rooms.inGroup(g.id).map { it.snapshotInfo() },
    )

    fun name(raw: String) = Wire.cleanName(raw).ifEmpty { throw HttpError(HttpStatusCode.BadRequest, "Please enter a name.") }

    routing {
        get("/health") { call.respondText("ok") }
        // Prometheus-style numbers for monitoring. The reverse proxy keeps this private (see deploy/Caddyfile).
        get("/metrics") {
            val c = rooms.counts()
            val w = matchmaker.waiting()
            call.respondText(buildString {
                for (st in lux.core.net.RoomStatus.entries) appendLine("ludolux_rooms{status=\"${st.name.lowercase()}\"} ${c[st] ?: 0}")
                for (n in listOf(2, 4)) appendLine("ludolux_ranked_waiting{players=\"$n\"} ${w[n] ?: 0}")
            })
        }

        // ---- accounts
        post("/v1/auth/guest") {
            if (!guestLimit.allow(call.request.origin.remoteHost)) throw HttpError(HttpStatusCode.TooManyRequests, "Too many new accounts. Try again later.")
            val req = call.receive<GuestRequest>()
            val (user, token) = store.createGuest(name(req.name))
            call.respond(AuthResponse(user.id, token, user.name, user.tag))
        }
        /**
         * Sign in with Google: the same Google account is the same Ludo Lux account on every phone. If this phone
         * already has a guest account (its token comes along), that guest account is upgraded and keeps everything.
         */
        post("/v1/auth/google") {
            if (!guestLimit.allow("g:" + call.request.origin.remoteHost)) throw HttpError(HttpStatusCode.TooManyRequests, "Too many tries. Try again later.")
            val id = google.verify(call.receive<GoogleSignInRequest>().idToken) ?: throw HttpError(HttpStatusCode.Unauthorized, "Google sign-in did not work. Please try again.")
            val current = call.request.header("Authorization")?.removePrefix("Bearer ")?.trim()?.let { store.userByToken(it) }
            val known = store.userByGoogle(id.sub)
            val user: User
            val token: String
            when {
                known != null -> { user = known; token = store.newSession(known.id) }              // returning player, maybe on a new phone
                current != null -> { store.linkGoogle(current.id, id.sub); user = current.copy(google = true); token = store.newSession(current.id) }  // save the guest
                else -> { val made = store.createUser(Wire.cleanName(id.name).ifEmpty { "Player" }, id.sub); user = made.first; token = made.second }
            }
            call.respond(AuthResponse(user.id, token, user.name, user.tag, true))
        }
        get("/v1/me") { val u = call.user(); call.respond(AuthResponse(u.id, "", u.name, u.tag, u.google)) }
        put("/v1/me") {
            val u = call.user()
            val n = name(call.receive<RenameRequest>().name)
            store.rename(u.id, n)
            call.respond(AuthResponse(u.id, "", n, u.tag, u.google))
        }

        // ---- family groups (a person can be in any number of them)
        get("/v1/groups") {
            val u = call.user()
            call.respond(store.groupsOf(u.id).map { g ->
                val rank = store.standings(g.id).indexOfFirst { it.userId == u.id } + 1
                GroupSummary(g.id, g.name, g.code, store.memberCount(g.id), rank, rooms.inGroup(g.id).size)
            })
        }
        post("/v1/groups") {
            val u = call.user()
            val n = name(call.receive<CreateGroupRequest>().name)
            val code = rooms.newCode { store.codeTaken(it) || rooms.get(it) != null }
            call.respond(detail(store.createGroup(n, u.id, code)))
        }
        post("/v1/groups/join") {
            val u = call.user()
            if (!joinLimit.allow(u.id)) throw HttpError(HttpStatusCode.TooManyRequests, "Too many tries. Wait a few minutes.")
            val code = Wire.cleanCode(call.receive<JoinGroupRequest>().code)
            // A group code, or a room code shared from a group (joining the room's group too).
            val g = store.groupByCode(code) ?: rooms.get(code)?.let { store.group(it.groupId) }
                ?: throw HttpError(HttpStatusCode.NotFound, "No group or game with that code.")
            store.addMember(g.id, u.id)
            call.respond(detail(g))
        }
        get("/v1/groups/{id}") {
            val u = call.user()
            val g = store.group(call.parameters["id"]!!) ?: throw HttpError(HttpStatusCode.NotFound, "No such group.")
            if (!store.isMember(g.id, u.id)) throw HttpError(HttpStatusCode.Forbidden, "You are not in this group.")
            call.respond(detail(g))
        }

        // ---- rooms (live games inside a group)
        post("/v1/rooms") {
            val u = call.user()
            val req = call.receive<CreateRoomRequest>()
            if (!store.isMember(req.groupId, u.id)) throw HttpError(HttpStatusCode.Forbidden, "You are not in this group.")
            if (req.players !in 2..4 || req.seriesGames !in listOf(1, 3, 5) || req.capture !in 0..7) throw HttpError(HttpStatusCode.BadRequest, "Bad game settings.")
            val room = rooms.create(req.groupId, u, req.players, req.seriesGames, req.board.take(16), req.capture)
            call.respond(room.snapshotInfo())
        }
        get("/v1/rooms/{code}") {
            val u = call.user()
            val room = rooms.get(Wire.cleanCode(call.parameters["code"]!!)) ?: throw HttpError(HttpStatusCode.NotFound, "That game has ended.")
            store.addMember(room.groupId, u.id)
            call.respond(room.snapshotInfo())
        }

        // ---- shop: everything sold is cosmetic; purchases are checked with Google before anything unlocks
        fun entitlements(u: User): lux.core.net.Entitlements {
            val owned = store.owned(u.id)
            val pass = owned[lux.core.Catalog.FAMILY_PASS]
            return lux.core.net.Entitlements(owned.keys.toList(), store.coveredBy(u.id).orEmpty(), pass != null, store.familyPassGroup(u.id).orEmpty(), pass ?: 0)
        }
        get("/v1/me/entitlements") { call.respond(entitlements(call.user())) }
        post("/v1/purchases") {
            val u = call.user()
            // Purchases need a saved account, so they can never be lost with a phone.
            if (!u.google) throw HttpError(HttpStatusCode.Forbidden, "Please save your account with Google first, so your purchase is never lost.")
            val req = call.receive<lux.core.net.PurchaseRequest>()
            val product = lux.core.Catalog.product(req.productId) ?: throw HttpError(HttpStatusCode.BadRequest, "Unknown item.")
            val ok = billing.verify(product.id, req.purchaseToken, product.subscription) ?: throw HttpError(HttpStatusCode.PaymentRequired, "The purchase could not be confirmed. If you were charged, it will be refunded automatically.")
            if (!store.recordPurchase(u.id, product.id, req.purchaseToken, ok.orderId, ok.expires)) throw HttpError(HttpStatusCode.Conflict, "This purchase belongs to another account.")
            log.info("purchase ${product.id} by ${u.id} (${ok.orderId})")
            call.respond(entitlements(u))
        }
        post("/v1/family-pass") {
            val u = call.user()
            val req = call.receive<lux.core.net.FamilyPassRequest>()
            if (store.owned(u.id)[lux.core.Catalog.FAMILY_PASS] == null) throw HttpError(HttpStatusCode.Forbidden, "You don't have an active Family Pass.")
            if (!store.isMember(req.groupId, u.id)) throw HttpError(HttpStatusCode.Forbidden, "You are not in this group.")
            store.setFamilyPassGroup(u.id, req.groupId)
            call.respond(entitlements(u))
        }

        // ---- ranked (separate from family groups)
        get("/v1/ranked/me") {
            val u = call.user()
            val season = Seasons.current()
            val r = store.ranked(u.id, season)
            val d = r.division
            call.respond(lux.core.net.RankedProfile(
                season, Seasons.daysLeft(), r.placed, r.games.coerceAtMost(lux.core.Ranked.PLACEMENT_GAMES), lux.core.Ranked.PLACEMENT_GAMES,
                d?.name ?: "", d?.tier ?: 0, d?.step ?: 0, r.progress, r.shield, r.games, r.wins,
                if (r.peak > 0 || r.placed) lux.core.Ranked.Division.ofIndex(r.peak).name else "",
                if (r.placed) store.rankedPosition(season, r.rating) else 0,
            ))
        }
        get("/v1/ranked/leaderboard") {
            val u = call.user()
            val season = Seasons.current()
            val top = store.leaderboard(season, 50)
            val rows = top.mapIndexed { i, (id, name, r) -> lux.core.net.LeaderRow(i + 1, name, r.division!!.name, r.division!!.tier, r.wins, r.games, id == u.id, store.tagOf(id)) }
            val me = store.ranked(u.id, season)
            val mine = if (me.placed && rows.none { it.me }) lux.core.net.LeaderRow(store.rankedPosition(season, me.rating), u.name, me.division!!.name, me.division!!.tier, me.wins, me.games, true, u.tag) else null
            call.respond(lux.core.net.Leaderboard(season, rows, mine))
        }
        post("/v1/ranked/queue") {
            val u = call.user()
            val req = call.receive<lux.core.net.QueueRequest>()
            if (req.players != 2 && req.players != 4) throw HttpError(HttpStatusCode.BadRequest, "Ranked games are for 2 or 4 players.")
            call.respond(matchmaker.join(u, req.players))
        }
        get("/v1/ranked/queue") { call.respond(matchmaker.status(call.user())) }
        delete("/v1/ranked/queue") { val u = call.user(); matchmaker.leave(u); call.respond(lux.core.net.QueueStatus(lux.core.net.QueueState.None)) }

        // ---- invite links (WhatsApp etc.): a simple page with the code until app links are set up
        get("/g/{code}") { call.invitePage("Join the family group on Ludo Lux", Wire.cleanCode(call.parameters["code"]!!)) }
        get("/r/{code}") { call.invitePage("Join the game on Ludo Lux", Wire.cleanCode(call.parameters["code"]!!)) }

        // ---- live play
        webSocket("/v1/play") {
            val out = Channel<ServerMsg>(256)
            val writer = launch { for (m in out) send(Frame.Text(Wire.json.encodeToString(ServerMsg.serializer(), m))) }
            // Never block the room: a client that falls 256 messages behind just misses some (it resyncs from the next state).
            fun reply(m: ServerMsg) { out.trySend(m) }
            val hello = withTimeoutOrNull(10.seconds) { (incoming.receive() as? Frame.Text)?.readText() }
                ?.let { runCatching { Wire.json.decodeFromString(ClientMsg.serializer(), it) }.getOrNull() } as? Hello
            val user = hello?.let { store.userByToken(it.token) }
            val room = hello?.let { rooms.get(Wire.cleanCode(it.room)) }
            // Family rooms: any member of the group. Ranked rooms: only the matched players.
            val allowed = user != null && room != null && (if (room.ranked) room.seated(user.id) else store.isMember(room.groupId, user.id))
            if (user == null || room == null || !allowed) {
                reply(ErrorMsg(if (room == null) "That game has ended." else "Not allowed."))
                out.close(); writer.join()
                return@webSocket
            }
            val conn = Conn(user) { reply(it) }
            room.inbox.trySend(Room.Join(conn))
            try {
                for (frame in incoming) {
                    if (frame !is Frame.Text) continue
                    val msg = runCatching { Wire.json.decodeFromString(ClientMsg.serializer(), frame.readText()) }.getOrNull() ?: continue
                    room.inbox.trySend(Room.Msg(conn, msg))
                }
            } finally {
                room.inbox.trySend(Room.Leave(conn))
                out.close()
            }
        }
    }
}

private suspend fun ApplicationCall.invitePage(title: String, code: String) {
    // Only the cleaned code is echoed into the page, so nothing a visitor types can be injected.
    val html = """<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>$title</title><style>body{font-family:system-ui,sans-serif;background:#0b2129;color:#fdf8ec;text-align:center;padding:40px 16px}
.code{font-size:44px;letter-spacing:6px;color:#ffc94a;font-weight:800;margin:16px 0}p{font-size:18px;line-height:1.5}</style></head>
<body><h1>$title</h1><p>Open Ludo Lux, tap <b>Family</b>, then <b>Join with code</b> and enter:</p><div class="code">$code</div>
<p>Don't have the app yet? Ask the person who sent this link for it.</p></body></html>"""
    respondText(html, ContentType.Text.Html)
}
