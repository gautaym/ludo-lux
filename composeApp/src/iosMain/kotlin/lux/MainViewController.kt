package lux

import androidx.compose.ui.window.ComposeUIViewController
import lux.app.AppContext
import lux.app.Nav
import lux.ui.LuxApp
import platform.UIKit.UIViewController

/** The whole game as one view controller; the Swift app (iosApp/) shows it full screen. */
fun MainViewController(): UIViewController {
    val platform = IosPlatform()
    val app = AppContext(platform)
    val nav = Nav()
    platform.watchLifecycle(nav)
    return ComposeUIViewController { LuxApp(app, nav) }
}
