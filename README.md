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

## Credits

Fonts: Poppins and Lilita One (SIL Open Font License). Licence texts are in `licenses/`. Sound samples: Kenney (CC0). Everything else - board art,
pieces, dice, effects and synthesised sounds - is drawn or generated in code.
