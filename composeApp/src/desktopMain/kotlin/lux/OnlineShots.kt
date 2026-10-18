package lux

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.runBlocking
import lux.app.AppContext
import lux.app.Nav
import lux.app.Prefs
import lux.app.Screen
import lux.app.SilentPlatform
import lux.core.Cpu
import lux.core.CpuLevel
import lux.core.Phase
import lux.core.net.Cmd
import lux.core.net.CreateRoomRequest
import lux.core.net.GameStarted
import lux.core.net.ResultMsg
import lux.core.net.RoomStatus
import lux.core.net.Snapshot
import lux.core.net.StartGame
import lux.core.net.StateCodec
import lux.net.LuxClient
import lux.net.RoomLink
import lux.ui.LuxApp
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.random.Random

/** In-memory settings for a test player (each player needs their own account token). */
class MemPrefs : Prefs {
    private val m = HashMap<String, Any>()
    override fun getInt(key: String, default: Int) = m[key] as? Int ?: default
    override fun putInt(key: String, value: Int) { m[key] = value }
    override fun getString(key: String, default: String) = m[key] as? String ?: default
    override fun putString(key: String, value: String) { m[key] = value }
}

class TestPlatform : lux.app.Platform by SilentPlatform {
    override val prefs = MemPrefs()
    val shared = ArrayList<String>()
    override fun share(text: String) { shared += text }
}

/**
 * End-to-end online check against a running server: the rendered app is "Mom"; a bot "Dad" (same network client)
 * hosts a room and plays his own turns. Mom never taps, so the server's turn timer plays for her.
 * Usage: gradlew :composeApp:shots -PshotArgs="online http://localhost:8090"
 */
@OptIn(ExperimentalComposeUiApi::class, kotlinx.coroutines.DelicateCoroutinesApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
fun onlineShots(server: String, seconds: Float = 60f) {
    val ui = newSingleThreadContext("ui")
    val dir = File("shots/online").apply { deleteRecursively(); mkdirs() }
    val platform = TestPlatform()
    val app = AppContext(platform)
    app.net.changeServer(server)
    val nav = Nav()
    val bots = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val log = StringBuilder()

    runBlocking(ui) {
        app.net.signIn("Mom")
        val group = app.net.createGroup("Sharma Family")
        val dad = LuxClient(MemPrefs()).apply { changeServer(server); signIn("Dad"); joinGroup(group.code) }
        val room = dad.createRoom(CreateRoomRequest(group.id, players = 3, seriesGames = 1))
        log.appendLine("group ${group.code} room ${room.code}")
        nav.screen = Screen.Group(group.id)

        // Dad: host and player. Starts once Mom is in the room; plays his turns after a human-like pause.
        bots.launch {
            val link = RoomLink(dad, room.code, this)
            link.start()
            var started = false
            for (m in link.incoming) {
                val r = link.room
                if (!started && r?.status == RoomStatus.Lobby && r.seats.count { it.userId != null } >= 2) {
                    delay(2500); started = true; link.send(StartGame); log.appendLine("dad started the game")
                }
                val state = when (m) { is GameStarted -> m.state; is Snapshot -> m.state; is ResultMsg -> m.state; else -> null } ?: continue
                if (m is ResultMsg && m.byCpu) log.appendLine("server played for someone (timer/away)")
                val s = StateCodec.decode(state)
                val myColor = link.room?.seats?.firstOrNull { it.userId == dad.userId }?.color ?: continue
                if (s.isOver || s.seatColor[s.current] != myColor) continue
                delay(2200)
                val cmd = if (s.phase == Phase.WaitingForRoll) Cmd(Random.nextLong(1, Long.MAX_VALUE), "roll", expected = s.sequence)
                else Cmd(Random.nextLong(1, Long.MAX_VALUE), "move", Cpu.chooseMove(s, s.current, CpuLevel.Medium, Random), s.sequence)
                link.send(cmd)
            }
        }

        ImageComposeScene(540, 1170, Density(1.375f), coroutineContext = ui) { LuxApp(app, nav) }.use { scene ->
            val start = System.nanoTime()
            var n = 0
            var nextSave = 0f
            var joined = false
            while (true) {
                val t = (System.nanoTime() - start) / 1e9f
                if (t > seconds) break
                // After a look at the group page, Mom opens the game room (as if she tapped "Join").
                if (!joined && t > 4f) { joined = true; nav.screen = Screen.Room(room.code, group.id) }
                val img = scene.render(System.nanoTime() - start)
                if (t >= nextSave) {
                    nextSave += 0.5f
                    File(dir, "f%04d.png".format(n++)).writeBytes(img.encodeToData(EncodedImageFormat.PNG)!!.bytes)
                }
                delay(16)   // lets network and game coroutines run on this thread between frames
            }
        }
    }
    bots.cancel()
    log.appendLine("shared: ${platform.shared}")
    print(log)
    println("shots/online: done")
}
