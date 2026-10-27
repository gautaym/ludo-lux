import SwiftUI
import ComposeApp

/// The iPhone / iPad app: a full-screen window showing the shared Kotlin game.
@main
struct LudoLuxApp: App {
    var body: some Scene {
        WindowGroup {
            GameView().ignoresSafeArea(.all)
        }
    }
}

/// Wraps the Compose view controller (composeApp/src/iosMain/kotlin/lux/MainViewController.kt) for SwiftUI.
struct GameView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        MainViewControllerKt.MainViewController()
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}
