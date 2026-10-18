# Ludo Lux

A family-friendly Ludo game for Android (iOS later), built with Kotlin Multiplatform and Compose Multiplatform.
Designed for older players: big pieces and buttons, high-contrast boards, no wrong-piece moves, and home series so
nobody is out after one lost game.

## Layout

- `core/` - the rules, with no UI: engine, game state snapshots, CPU players, balanced dice, match session, series scoring.
  Shared by the app and (later) the game server.
- `composeApp/` - the app. `commonMain` holds everything visual (board art, pieces, 3D die, effects, screens);
  `androidMain` is the Android entry point (sound, vibration, storage); `desktopMain` is a desktop preview and a headless
  screenshot tool.
- `tools/sheet.py` - builds contact sheets from rendered frames.

## Build and test

Needs JDK 17 and the Android SDK (path in `local.properties`, `sdk.dir=...`).

```
./gradlew :core:jvmTest                      # rules and series tests
./gradlew :composeApp:assembleRelease        # Android APK -> composeApp/build/outputs/apk/release/
./gradlew :composeApp:run                    # desktop preview
./gradlew :composeApp:shots -PshotArgs="menu boards"   # headless screenshots -> shots/
```

Shot scenarios: `menu`, `boards`, `match`, `showcase`, `series`, `flow`, `hop`, `contrast`, `pick`.

## Game server (online family play)

`server/` is a Ktor server using the same rules code: guest accounts, family groups (invite code + link), per-group
rankings, rooms with a lobby, computer seats, a turn timer, and authoritative play (the server rolls the dice and
checks every move). Data is one SQLite file.

```
./gradlew :server:test                       # bots play full series, drop out and rejoin
./gradlew :server:fatJar                     # -> server/build/libs/ludolux-server.jar
java -jar server/build/libs/ludolux-server.jar
```

Settings (environment variables): `PORT` (8080), `DB_PATH` (ludolux.db), `PUBLIC_URL` (used in invite links),
`TURN_SECONDS` (45). The app's server address is under Family online -> "Server: ..." at the bottom.

Testing at home: run the jar on a PC, allow it through the firewall, and set the app's server to
`http://<PC address>:8080`. Plain http is for home testing only; production goes behind TLS.

## Credits

Fonts: Poppins and Lilita One (SIL Open Font License). Licence texts are in `licenses/`. Sound samples: Kenney (CC0). Everything else - board art,
pieces, dice, effects and synthesised sounds - is drawn or generated in code.
