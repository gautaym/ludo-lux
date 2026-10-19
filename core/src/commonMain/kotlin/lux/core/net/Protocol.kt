package lux.core.net

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import lux.core.GameEvent

/**
 * The online protocol, shared by the server and the app.
 *
 * REST (JSON, `Authorization: Bearer <token>`): accounts, family groups, creating rooms.
 * WebSocket `/v1/play`: one connection per room. The client sends [Hello] first, then lobby actions and game
 * commands; the server is the only authority (it rolls the dice and checks every move) and broadcasts every
 * accepted [Result] with the full state after it, so a client can never drift out of sync.
 */
object Wire {
    const val VERSION = 1
    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; classDiscriminator = "t" }

    /** Letters and digits that cannot be confused when read aloud or typed by older players (no 0/O, 1/I/L). */
    const val CODE_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"
    const val CODE_LENGTH = 6
    const val NAME_MAX = 20

    fun cleanName(raw: String) = raw.trim().replace(Regex("\\s+"), " ").filter { !it.isISOControl() }.take(NAME_MAX)
    fun cleanCode(raw: String) = raw.uppercase().filter { it in CODE_ALPHABET }.take(CODE_LENGTH)
}

// ---------------------------------------------------------------- REST

@Serializable data class GuestRequest(val name: String)
/**
 * [tag] is a random 4-digit number shown after the name ("Ravi #4821") so people with the same name can be told apart.
 * [google] is true once the account is saved with Google (then it works on any phone).
 */
@Serializable data class AuthResponse(val userId: String, val token: String, val name: String, val tag: Int = 0, val google: Boolean = false)
/** Sign in with Google. Sent with the current guest token (if any) so the guest account is kept and upgraded. */
@Serializable data class GoogleSignInRequest(val idToken: String)
@Serializable data class RenameRequest(val name: String)

@Serializable data class CreateGroupRequest(val name: String)
@Serializable data class JoinGroupRequest(val code: String)

/** A group in the "my groups" list. [myRank] is this user's all-time place in the group (1 = top). */
@Serializable
data class GroupSummary(val id: String, val name: String, val code: String, val members: Int, val myRank: Int, val liveRooms: Int)

/** A member's standing in one group. Points are the same per-game points as a home series (place + pieces home). */
@Serializable
data class MemberStanding(
    val userId: String,
    val name: String,
    val points: Int,
    val games: Int,
    val wins: Int,
    val seriesWon: Int,
    val monthPoints: Int,
    val monthGames: Int,
    val owner: Boolean = false,
    val tag: Int = 0,
)

@Serializable
data class GroupDetail(
    val id: String,
    val name: String,
    val code: String,
    /** Link to share (e.g. on WhatsApp). Opens a page with the code; opening the app directly comes later. */
    val inviteLink: String,
    val members: List<MemberStanding>,
    val rooms: List<RoomInfo>,
)

@Serializable
data class CreateRoomRequest(
    val groupId: String,
    val players: Int,
    val seriesGames: Int,
    val board: String = "royal",
    val capture: Int = 7,
)

@Serializable data class ErrorResponse(val error: String)

// ---------------------------------------------------------------- ranked

/** My ranked standing this season. The skill rating itself stays hidden; only the tier and progress are shown. */
@Serializable
data class RankedProfile(
    val season: String,
    val seasonDaysLeft: Int,
    val placed: Boolean,
    val placementPlayed: Int,
    val placementGames: Int,
    /** e.g. "Gold II" (empty during placement). */
    val division: String,
    /** 0 Bronze .. 5 Champion, and the step 3/2/1 (0 for Champion). */
    val tier: Int,
    val step: Int,
    /** 0..1 through the current step. */
    val progress: Float,
    val shield: Int,
    val games: Int,
    val wins: Int,
    val peak: String,
    /** Place on the season leaderboard (0 while in placement). */
    val position: Int,
)

@Serializable data class LeaderRow(val place: Int, val name: String, val division: String, val tier: Int, val wins: Int, val games: Int, val me: Boolean, val tag: Int = 0)
@Serializable data class Leaderboard(val season: String, val rows: List<LeaderRow>, val mine: LeaderRow? = null)

@Serializable data class QueueRequest(val players: Int)
@Serializable enum class QueueState { Waiting, Matched, None }
/** Matchmaking ticket. Poll it while waiting; once [state] is Matched, open [room]. */
@Serializable data class QueueStatus(val state: QueueState, val players: Int = 0, val waited: Int = 0, val inQueue: Int = 0, val room: String = "")

// ---------------------------------------------------------------- rooms

@Serializable
enum class RoomStatus { Lobby, Playing, BetweenGames, Done }

@Serializable
data class SeatInfo(
    val color: Int,
    /** null = empty (it becomes a computer player when the game starts). */
    val userId: String? = null,
    val name: String = "",
    val cpu: Boolean = false,
    val connected: Boolean = false,
    /** Ranked rooms: the player's visible rank (e.g. "Gold II", or "Placement"). */
    val rank: String = "",
)

@Serializable
data class RoomInfo(
    val code: String,
    val groupId: String,
    val hostId: String,
    val players: Int,
    val seriesGames: Int,
    val board: String,
    val capture: Int,
    val status: RoomStatus,
    val seats: List<SeatInfo>,
    /** The series so far ([lux.core.Series.encode]), or "" before the first game. */
    val series: String = "",
    val link: String = "",
    val ranked: Boolean = false,
)

// ---------------------------------------------------------------- live play (WebSocket)

@Serializable
sealed interface ClientMsg

/** First message on a connection: who I am and which room. */
@Serializable @SerialName("hello") data class Hello(val token: String, val room: String, val version: Int = Wire.VERSION) : ClientMsg
/** Lobby: the host turns an empty seat into a computer player (or back). */
@Serializable @SerialName("cpu") data class SetCpu(val color: Int, val cpu: Boolean) : ClientMsg
/** Lobby: move to another free seat. */
@Serializable @SerialName("seat") data class TakeSeat(val color: Int) : ClientMsg
/** The host starts the first game (empty seats become computer players). */
@Serializable @SerialName("start") data object StartGame : ClientMsg
/** Between games: anyone in the room starts the next one. */
@Serializable @SerialName("next") data object NextGame : ClientMsg
/** A game command. [kind] is "roll" or "move"; [expected] is the state sequence the client saw. */
@Serializable @SerialName("cmd") data class Cmd(val id: Long, val kind: String, val piece: Int = 0, val expected: Long) : ClientMsg
@Serializable @SerialName("ping") data object Ping : ClientMsg

@Serializable
sealed interface ServerMsg

/** Room changed (seats, status, series). Sent on join and on every change. */
@Serializable @SerialName("room") data class RoomUpdate(val room: RoomInfo) : ServerMsg
/** A new game began: its starting state ([lux.core.GameState.serialize], Base64). */
@Serializable @SerialName("game") data class GameStarted(val game: Int, val state: String) : ServerMsg
/**
 * An accepted command: what happened and the full state after it. [byCpu] marks moves the computer made (for a
 * computer seat, or for a player who timed out or dropped).
 */
@Serializable @SerialName("result") data class ResultMsg(val seq: Long, val events: List<GameEvent>, val state: String, val byCpu: Boolean = false) : ServerMsg
/** Sent when (re)joining a game in progress, so the client can lay the board out exactly. */
@Serializable @SerialName("snapshot") data class Snapshot(val game: Int, val state: String) : ServerMsg
/** The player whose turn it is has until [seconds] from now before the computer plays for them. */
@Serializable @SerialName("timer") data class TurnTimer(val color: Int, val seconds: Int) : ServerMsg
@Serializable @SerialName("error") data class ErrorMsg(val message: String) : ServerMsg

/** One player's ranked result. */
@Serializable
data class RankChange(
    val color: Int,
    val userId: String,
    val name: String,
    val place: Int,
    /** Rating change, rounded (shown as +18 / -9). */
    val delta: Int,
    val before: String,
    val after: String,
    val promoted: Boolean,
    val demoted: Boolean,
    /** Placement games played, if still in placement (0 otherwise). */
    val placement: Int,
    val left: Boolean,
)

/** After a ranked game: everyone's rating changes, tier moves and placement progress. */
@Serializable @SerialName("ranked") data class RankedResult(val changes: List<RankChange>) : ServerMsg
@Serializable @SerialName("pong") data object Pong : ServerMsg

/** Game states travel as Base64 of the compact binary snapshot ([lux.core.GameState.serialize]). */
@OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
object StateCodec {
    fun encode(s: lux.core.GameState): String = kotlin.io.encoding.Base64.encode(s.serialize())
    fun decode(text: String): lux.core.GameState = lux.core.GameState.deserialize(kotlin.io.encoding.Base64.decode(text))
}
