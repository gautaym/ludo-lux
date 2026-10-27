# Ludo Lux on iPhone and iPad

The iOS app is a small Swift shell around the same Kotlin game as Android (`composeApp/src/iosMain`). The Xcode project is
generated from `project.yml` by XcodeGen, so there is nothing to keep in sync by hand.

## 1. Without a Mac: the automatic build

Every push to `main` builds the app on a GitHub Mac, starts it in the iOS Simulator and takes a screenshot. Results are on
the **ios-latest** pre-release of the GitHub repository:

| File | What it is |
|---|---|
| `ios-home.png` | The app's home screen, photographed in the simulator |
| `run-status.txt` | `RUNNING` if the app was still alive 30 s after starting |
| `LudoLux-simulator.zip` | The app for the Xcode Simulator (does **not** install on a real iPhone) |
| `app-log.txt`, `build-log-tail.txt` | Logs, for when something breaks |

## 2. On a Mac (your own or a rented cloud Mac)

Install Xcode (App Store), then in Terminal:

```bash
brew install openjdk@17 xcodegen
git clone https://github.com/gautaym/ludo-lux.git
cd ludo-lux
./iosApp/run.sh
```

The first build takes 10-20 minutes (Gradle and Kotlin/Native download their tools). Later builds are much faster.
`./iosApp/run.sh open` only makes the project and opens it in Xcode.

## 3. On a real iPhone

* **Your iPhone next to a Mac (free Apple ID):** `./iosApp/run.sh open`, then in Xcode: target *LudoLux* > *Signing &
  Capabilities* > Team = your Apple ID; connect the iPhone by cable, pick it at the top, press Run. On the phone allow it
  under *Settings > General > VPN & Device Management*. Works for 7 days, then run again.
* **TestFlight (Apple Developer account, works with a cloud Mac):** set `DEVELOPMENT_TEAM` in `project.yml`, then in Xcode
  *Product > Archive* > *Distribute App* > *TestFlight*. Testers install the **TestFlight** app and accept the invite.

## What is different on iPhone (for now)

* Google sign-in and purchases are not wired up yet. Online family rooms work as a guest. Before the App Store, Apple
  requires **Sign in with Apple** next to Google sign-in, and purchases through Apple's own system.
* Testing against the home server (`http://192.168.x.x:8080`) is allowed (local network). iOS asks once for permission to
  find devices on the local network: allow it.
