package lux.net

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import lux.app.Prefs
import lux.core.net.AuthResponse
import lux.core.net.ClientMsg
import lux.core.net.CreateGroupRequest
import lux.core.net.CreateRoomRequest
import lux.core.net.ErrorResponse
import lux.core.net.GroupDetail
import lux.core.net.GroupSummary
import lux.core.net.GuestRequest
import lux.core.net.Hello
import lux.core.net.JoinGroupRequest
import lux.core.net.Ping
import lux.core.net.RoomInfo
import lux.core.net.RoomUpdate
import lux.core.net.ServerMsg
import lux.core.net.Wire

/** A friendly error to show the player (no stack traces, no jargon). */
class NetError(message: String) : Exception(message)

/** The default server: the family PC on home Wi-Fi during testing (changeable in the app). */
const val DEFAULT_SERVER = "http://192.168.1.5:8080"

/**
 * Talks to the Ludo Lux server: the account (a guest account kept on this phone), family groups, and rooms.
 * Live games go through [RoomLink].
 */
class LuxClient(private val prefs: Prefs) {
    var server by mutableStateOf(prefs.getString("server", DEFAULT_SERVER))
        private set
    var token by mutableStateOf(prefs.getString("token", ""))
        private set
    var userId by mutableStateOf(prefs.getString("userId", ""))
        private set
    var name by mutableStateOf(prefs.getString("onlineName", ""))
        private set
    /** Random 4-digit tag shown after the name ("Ravi #4821"). */
    var tag by mutableStateOf(prefs.getInt("onlineTag", 0))
        private set
    /** True once the account is saved with Google (it then works on any phone). */
    var google by mutableStateOf(prefs.getInt("onlineGoogle", 0) == 1)
        private set
    val signedIn get() = token.isNotEmpty()

    val http = HttpClient {
        install(ContentNegotiation) { json(Wire.json) }
        install(WebSockets)
        install(HttpTimeout) { connectTimeoutMillis = 8_000; requestTimeoutMillis = 15_000 }
        expectSuccess = false
    }

    fun changeServer(url: String) {
        server = url.trim().trimEnd('/').ifEmpty { DEFAULT_SERVER }
        prefs.putString("server", server)
    }

    private fun url(path: String) = server + path

    private suspend inline fun <reified T> call(block: () -> HttpResponse): T {
        val r = try { block() } catch (e: Exception) {
            throw NetError("Can't reach the game server. Check the internet (or Wi-Fi) and try again.")
        }
        if (!r.status.isSuccess()) {
            val msg = runCatching { r.body<ErrorResponse>().error }.getOrNull() ?: "Something went wrong (${r.status.value})."
            if (r.status.value == 401) { token = ""; prefs.putString("token", "") }
            throw NetError(msg)
        }
        return r.body()
    }

    private fun keep(a: AuthResponse) {
        token = a.token; userId = a.userId; name = a.name; tag = a.tag; google = a.google
        prefs.putString("token", a.token); prefs.putString("userId", a.userId); prefs.putString("onlineName", a.name)
        prefs.putInt("onlineTag", a.tag); prefs.putInt("onlineGoogle", if (a.google) 1 else 0)
    }

    /** Creates this phone's guest account (once). */
    suspend fun signIn(displayName: String) {
        keep(call { http.post(url("/v1/auth/guest")) { contentType(ContentType.Application.Json); setBody(GuestRequest(displayName)) } })
    }

    /**
     * Signs in with a Google ID token. Sends this phone's guest token too (if any), so the guest account is upgraded
     * and keeps its groups and rank; on a new phone it simply returns to the same account.
     */
    suspend fun signInWithGoogle(idToken: String) {
        keep(call {
            http.post(url("/v1/auth/google")) {
                if (token.isNotEmpty()) bearerAuth(token)
                contentType(ContentType.Application.Json); setBody(lux.core.net.GoogleSignInRequest(idToken))
            }
        })
    }

    suspend fun groups(): List<GroupSummary> = call { http.get(url("/v1/groups")) { bearerAuth(token) } }
    suspend fun group(id: String): GroupDetail = call { http.get(url("/v1/groups/$id")) { bearerAuth(token) } }
    suspend fun createGroup(name: String): GroupDetail =
        call { http.post(url("/v1/groups")) { bearerAuth(token); contentType(ContentType.Application.Json); setBody(CreateGroupRequest(name)) } }
    suspend fun joinGroup(code: String): GroupDetail =
        call { http.post(url("/v1/groups/join")) { bearerAuth(token); contentType(ContentType.Application.Json); setBody(JoinGroupRequest(code)) } }
    suspend fun createRoom(req: CreateRoomRequest): RoomInfo =
        call { http.post(url("/v1/rooms")) { bearerAuth(token); contentType(ContentType.Application.Json); setBody(req) } }

    // ---- ranked
    suspend fun rankedMe(): lux.core.net.RankedProfile = call { http.get(url("/v1/ranked/me")) { bearerAuth(token) } }
    suspend fun leaderboard(): lux.core.net.Leaderboard = call { http.get(url("/v1/ranked/leaderboard")) { bearerAuth(token) } }
    suspend fun queue(players: Int): lux.core.net.QueueStatus =
        call { http.post(url("/v1/ranked/queue")) { bearerAuth(token); contentType(ContentType.Application.Json); setBody(lux.core.net.QueueRequest(players)) } }
    suspend fun queueStatus(): lux.core.net.QueueStatus = call { http.get(url("/v1/ranked/queue")) { bearerAuth(token) } }
    suspend fun cancelQueue() { runCatching { http.delete(url("/v1/ranked/queue")) { bearerAuth(token) } } }

    fun wsUrl() = server.replaceFirst("http", "ws") + "/v1/play"
}

/**
 * The live connection to one room. Keeps itself connected (reconnecting with back-off after a drop, e.g. when the
 * phone switches networks) and hands every server message to [incoming] in order. [room] always holds the latest
 * room info for the UI.
 */
class RoomLink(private val client: LuxClient, val code: String, private val scope: CoroutineScope) {
    val incoming = Channel<ServerMsg>(Channel.UNLIMITED)
    private val outgoing = Channel<ClientMsg>(Channel.UNLIMITED)
    var room by mutableStateOf<RoomInfo?>(null)
        private set
    var connected by mutableStateOf(false)
        private set
    var problem by mutableStateOf<String?>(null)
        private set
    private var job: Job? = null

    fun start() {
        if (job != null) return
        job = scope.launch {
            var backoff = 500L
            while (isActive) {
                try {
                    client.http.webSocket(client.wsUrl()) {
                        send(Frame.Text(Wire.json.encodeToString(ClientMsg.serializer(), Hello(client.token, code))))
                        connected = true; problem = null; backoff = 500L
                        val writer = launch { for (m in this@RoomLink.outgoing) send(Frame.Text(Wire.json.encodeToString(ClientMsg.serializer(), m))) }
                        val pinger = launch { while (isActive) { delay(15_000); this@RoomLink.outgoing.trySend(Ping) } }
                        try {
                            for (frame in incoming) {
                                val text = (frame as? Frame.Text)?.readText() ?: continue
                                val msg = runCatching { Wire.json.decodeFromString(ServerMsg.serializer(), text) }.getOrNull() ?: continue
                                if (msg is RoomUpdate) room = msg.room
                                if (msg is lux.core.net.ErrorMsg && room == null) problem = msg.message
                                this@RoomLink.incoming.trySend(msg)
                            }
                        } finally {
                            writer.cancel(); pinger.cancel()
                        }
                    }
                } catch (e: Exception) {
                    if (!isActive) break
                }
                connected = false
                if (problem != null && room == null) break   // the room is gone or we may not join: stop trying
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(8_000)
            }
        }
    }

    fun send(m: ClientMsg) { outgoing.trySend(m) }

    fun stop() { job?.cancel(); job = null; connected = false }
}
