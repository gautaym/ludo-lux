package lux

import androidx.compose.ui.window.ComposeUIViewController
import lux.app.AppContext
import lux.app.Nav
import lux.app.Screen
import lux.app.Settings
import platform.Foundation.NSUserDefaults
import lux.ui.LuxApp
import platform.UIKit.UIViewController

/** The whole game as one view controller; the Swift app (iosApp/) shows it full screen. */
fun MainViewController(): UIViewController {
    val platform = IosPlatform()
    val app = AppContext(platform)
    val nav = Nav()
    platform.watchLifecycle(nav)
    // Automated screenshots (the simulator cannot tap): launched with "-luxDemo <mode>", which iOS hands over as a
    // one-off setting. Never set in normal use.
    when (NSUserDefaults.standardUserDefaults.stringForKey("luxDemo")) {
        "setup" -> nav.menuSheet = "setup"
        "style" -> nav.menuSheet = "style"
        "snakes" -> app.settings.game = Settings.GAME_SNAKES
        "ludo" -> app.settings.game = Settings.GAME_LUDO
        "showcase" -> nav.screen = Screen.Showcase
    }
    return ComposeUIViewController { LuxApp(app, nav) }
}
