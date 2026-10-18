package lux

import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import lux.app.AppContext
import lux.app.Nav
import lux.app.SilentPlatform
import lux.ui.LuxApp

/** Desktop preview of the phone app (portrait window). */
fun main() = application {
    val app = AppContext(SilentPlatform)
    val nav = Nav()
    Window(onCloseRequest = ::exitApplication, title = "Ludo Lux", state = rememberWindowState(width = 420.dp, height = 880.dp)) {
        LuxApp(app, nav)
    }
}
