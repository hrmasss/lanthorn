# Player

A local-first Android video player built on libmpv. No ads, no tracking. GPL-3.0.

"Player" is a codename.

## Build

JDK 21 and the Android SDK (platform 37) are required.

```
./gradlew assembleDebug        # debug APKs per ABI in app/build/outputs/apk/debug
./gradlew testDebugUnitTest lint
./gradlew assembleRelease      # signed when a release key is configured, see below
```

Release signing reads `keystore.properties` at the repo root (never committed) or the
`PLAYER_KEYSTORE`, `PLAYER_KEYSTORE_PASSWORD`, `PLAYER_KEY_ALIAS`, `PLAYER_KEY_PASSWORD` env vars.
Without them the release APK is unsigned.

## Modules

| Module | What |
|---|---|
| `app` | Entry point, navigation, wiring. |
| `core/engine` | libmpv wrapper and the playback surface. Features use its public API only. |
| `core/data` | Persistence (Room, DataStore) and the library index. |
| `core/designsystem` | Theme, tokens and shared components. |
| `feature/*` | One user-facing feature each. A feature never depends on another feature. |
| `baselineprofile` | Baseline profile generator and startup benchmark. |
| `build-logic` | Gradle convention plugins. |

## Licence

GPL-3.0-or-later. See `LICENSE` and `NOTICE`.
