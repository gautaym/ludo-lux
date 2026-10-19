package lux.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import lux.audio.SfxId
import lux.core.GameState
import lux.core.Series
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/** Small key-value store that survives app restarts (SharedPreferences on Android, java.util.prefs on desktop). */
interface Prefs {
    fun getInt(key: String, default: Int): Int
    fun putInt(key: String, value: Int)
    fun getString(key: String, default: String): String
    fun putString(key: String, value: String)
}

enum class Haptic { Light, Medium, Heavy }

/** Everything the shared game needs from the device. Each app entry point supplies its own. */
interface Platform {
    val prefs: Prefs
    fun play(id: SfxId, volume: Float = 1f, pitch: Float = 1f)
    fun vibrate(strength: Haptic)
    /** Shares text (WhatsApp first if it is installed, otherwise the system share sheet). */
    fun share(text: String) {}
    /**
     * Shows the system "Sign in with Google" sheet and returns a Google ID token, or null if the person cancelled.
     * Throws [lux.net.NetError] with a friendly message if sign-in is not available.
     */
    suspend fun googleIdToken(): String? = throw lux.net.NetError("Google sign-in is not available here.")
}

/** Used by previews and screenshots: silent, and remembers settings only in memory. */
object SilentPlatform : Platform {
    private val map = HashMap<String, Any>()
    override val prefs = object : Prefs {
        override fun getInt(key: String, default: Int) = map[key] as? Int ?: default
        override fun putInt(key: String, value: Int) { map[key] = value }
        override fun getString(key: String, default: String) = map[key] as? String ?: default
        override fun putString(key: String, value: String) { map[key] = value }
    }
    override fun play(id: SfxId, volume: Float, pitch: Float) {}
    override fun vibrate(strength: Haptic) {}
}

/** Capture effect choices. [Surprise] picks a different one for every capture (the default). */
enum class CaptureStyle(val title: String) {
    Boom("Boom"), Confetti("Confetti"), Smoke("Smoke"), Zap("Zap"), Sword("Sword"), Kick("Kick"), Rewind("Rewind");

    companion object { const val SURPRISE = 7 }
}

/** Player preferences, observable by the UI and remembered between sessions. */
class Settings(private val prefs: Prefs) {
    private fun bool(key: String, default: Boolean) = mutableStateOf(prefs.getInt(key, if (default) 1 else 0) == 1)

    private val _sound = bool("sound", true)
    private val _haptics = bool("haptics", true)
    private val _reducedMotion = bool("reducedMotion", false)
    private val _balancedDice = bool("balancedDice", true)
    private val _luckySix = bool("luckySix", true)
    private val _vsComputer = bool("vsCpu", true)
    private val _players = mutableStateOf(prefs.getInt("players", 4).coerceIn(2, 4))
    private val _cpuLevel = mutableStateOf(prefs.getInt("cpuLevel", 1).coerceIn(0, 2))
    private val _capture = mutableStateOf(prefs.getInt("capture", CaptureStyle.SURPRISE).coerceIn(0, CaptureStyle.SURPRISE))
    private val _board = mutableStateOf(prefs.getString("board", "royal"))
    private val _name = mutableStateOf(prefs.getString("name", "You"))
    private val _pieceStyle = mutableStateOf(prefs.getInt("pieces", 0).coerceIn(0, 1))
    private val _seriesLength = mutableStateOf(prefs.getInt("seriesLength", 3).let { if (it in listOf(1, 3, 5)) it else 3 })

    var sound: Boolean
        get() = _sound.value
        set(v) { _sound.value = v; prefs.putInt("sound", if (v) 1 else 0) }
    var haptics: Boolean
        get() = _haptics.value
        set(v) { _haptics.value = v; prefs.putInt("haptics", if (v) 1 else 0) }
    /** Fewer particles and no camera moves; capture scenes are shortened. */
    var reducedMotion: Boolean
        get() = _reducedMotion.value
        set(v) { _reducedMotion.value = v; prefs.putInt("reducedMotion", if (v) 1 else 0) }
    /** Shaped-but-random dice that keep the game lively (see lux.core.BalancedDice). Applies to new games. */
    var balancedDice: Boolean
        get() = _balancedDice.value
        set(v) { _balancedDice.value = v; prefs.putInt("balancedDice", if (v) 1 else 0) }
    var luckySix: Boolean
        get() = _luckySix.value
        set(v) { _luckySix.value = v; prefs.putInt("luckySix", if (v) 1 else 0) }
    /** true = play against the computer; false = pass-and-play with friends on this phone. */
    var vsComputer: Boolean
        get() = _vsComputer.value
        set(v) { _vsComputer.value = v; prefs.putInt("vsCpu", if (v) 1 else 0) }
    var players: Int
        get() = _players.value
        set(v) { _players.value = v.coerceIn(2, 4); prefs.putInt("players", _players.value) }
    /** 0 Easy, 1 Medium, 2 Hard. */
    var cpuLevel: Int
        get() = _cpuLevel.value
        set(v) { _cpuLevel.value = v.coerceIn(0, 2); prefs.putInt("cpuLevel", _cpuLevel.value) }
    /** 0..6 = [CaptureStyle] ordinal, 7 = surprise. */
    var capture: Int
        get() = _capture.value
        set(v) { _capture.value = v.coerceIn(0, CaptureStyle.SURPRISE); prefs.putInt("capture", _capture.value) }
    var board: String
        get() = _board.value
        set(v) { _board.value = v; prefs.putString("board", v) }
    /** Piece design: 0 = pawn, 1 = coin. */
    var pieceStyle: Int
        get() = _pieceStyle.value
        set(v) { _pieceStyle.value = v.coerceIn(0, 1); prefs.putInt("pieces", _pieceStyle.value) }
    /** Games per series: 1, 3 (default) or 5. */
    var seriesLength: Int
        get() = _seriesLength.value
        set(v) { _seriesLength.value = v; prefs.putInt("seriesLength", v) }
    var name: String
        get() = _name.value
        set(v) { _name.value = v; prefs.putString("name", v) }
}

/**
 * The home series in progress, kept on the phone so it can be continued later - including the game being played,
 * saved after every move.
 */
@OptIn(ExperimentalEncodingApi::class)
class SeriesStore(private val prefs: Prefs) {
    var current by mutableStateOf(Series.decode(prefs.getString("series", "")))
        private set

    fun save(s: Series?) {
        current = s
        prefs.putString("series", s?.encode() ?: "")
        if (s == null) clearGame()
    }

    /** Saved position of the series game currently being played (null if none, or it belongs to another game). */
    fun savedGame(gameIndex: Int): GameState? {
        val raw = prefs.getString("seriesGame", "")
        val cut = raw.indexOf(':')
        if (cut < 0 || raw.substring(0, cut).toIntOrNull() != gameIndex) return null
        return runCatching { GameState.deserialize(Base64.decode(raw.substring(cut + 1))) }.getOrNull()
    }

    fun saveGame(gameIndex: Int, state: GameState) = prefs.putString("seriesGame", "$gameIndex:" + Base64.encode(state.serialize()))
    fun clearGame() = prefs.putString("seriesGame", "")
}

/** The app's services, created once by the entry point. */
class AppContext(val platform: Platform) {
    val settings = Settings(platform.prefs)
    val series = SeriesStore(platform.prefs)
    val net = lux.net.LuxClient(platform.prefs)

    fun sfx(id: SfxId, volume: Float = 1f, pitch: Float = 1f) { if (settings.sound) platform.play(id, volume, pitch) }
    fun haptic(s: Haptic) { if (settings.haptics) platform.vibrate(s) }
}

/** Screen-level navigation state. */
class Nav {
    var screen by mutableStateOf<Screen>(Screen.Menu)
    /** Bumped to start a fresh match screen even when already on it (e.g. "New series"). */
    var matchKey by mutableStateOf(0)
    /** What the system back button does on the current screen (null = leave the app). */
    var onBack: (() -> Unit)? = null
    /** Called when the app goes to the background (auto-pause). */
    var onBackground: (() -> Unit)? = null

    /** Returns false when back should close the app. */
    fun back(): Boolean {
        if (screen == Screen.Menu) return false
        onBack?.invoke() ?: run { screen = Screen.Menu }
        return true
    }
}

sealed interface Screen {
    data object Menu : Screen
    data object Match : Screen
    data object Showcase : Screen
    /** Online: my family groups. */
    data object Family : Screen
    data class Group(val id: String) : Screen
    /** Online: a room's lobby and its games (groupId is empty for ranked rooms). */
    data class Room(val code: String, val groupId: String) : Screen
    /** Ranked: my tier, find a match, the season leaderboard. */
    data object Ranked : Screen
}
