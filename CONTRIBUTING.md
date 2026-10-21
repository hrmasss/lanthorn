# Contributing

Thanks for helping. Bug reports and feature requests go in GitHub Issues, using the templates.
For code, open a pull request against `main`.

## Build

JDK 21 and the Android SDK (platform 37) are required.

```
./gradlew assembleDebug        # debug APKs per ABI in app/build/outputs/apk/debug
```

The libmpv AAR is downloaded from [hrmasss/libmpv-android](https://github.com/hrmasss/libmpv-android)
releases and checked against `gradle/verification-metadata.xml`. If you add or update a dependency,
update that file too (`./gradlew --write-verification-metadata sha256 help`).

## Tests

```
./gradlew testDebugUnitTest lint                     # unit tests and lint, as CI runs them
./gradlew :feature:player:connectedDebugAndroidTest  # one module's on-device tests, device attached
```

CI runs the build, unit tests and lint on every push (`CI` workflow), and every module's on-device
tests on an API 36 emulator (`Instrumented` workflow). A pull request must be green on both.

Change something in the playback path, startup or the library scan? Say so in the pull request.
`perf/` measures those on a real phone; emulator numbers only catch regressions.

### Tag tests that prove a spec criterion

Requirements are written as EARS criteria with ids such as `US-17.2` or `NF-6`. A test that proves
one carries the id:

```kotlin
@Ears("US-19.1", "US-19.2")
@Test fun doubleTapSidesSkip() {
    // ...
    evidenceShot("after-skip")   // on-device tests: a screenshot at the moment that proves it
}
```

`@Ears` and `evidenceShot()` live in `core/testing`, which every module already has on its test
classpath. The `EARS` workflow turns the tags and the test results into one report per commit.

## Module rules

- **A feature never depends on another feature.** The `player.feature` convention plugin fails the
  build if one does. Shared code goes into a `core/*` module.
- **Features use `core/engine`'s public API only.** Never call libmpv directly. If the API cannot
  express what you need, open an issue. See [docs/architecture.md](docs/architecture.md).
- A feature adds UI to the player through `core/player-api` (`PlayerExtension`), and its settings
  through `core/settings-api` (`SettingsGroup`). `app` lists both.
- Nothing heavy on the main thread. Startup does no disk scan before the first frame.
- A new feature module: copy `feature/player/build.gradle.kts`, change the namespace, add it to
  `settings.gradle.kts`.

## Commits

[Conventional Commits](https://www.conventionalcommits.org/), lowercase:

```
feat(player): hold to play fast
fix(library): keep the sort after a restart
perf(engine): skip the second seek on release
docs: architecture diagram
build: bump compose bom
test(subtitles): encoding detection for big5
```

Common types: `feat`, `fix`, `perf`, `docs`, `build`, `ci`, `test`, `chore`. The scope is the
module or area.

## Licence and borrowed code

- The app is GPL-3.0-or-later. By contributing you agree that your contribution is licensed the same.
- Code from other GPL-3.0-compatible projects is welcome. Say where it came from and under which
  licence in the file header, and add the project to `NOTICE`.
- Do not add assets, icons or text you do not have the right to use.

## Privacy

Never add analytics, ads, telemetry, crash reporting, update checks or any other tracker, and no
network request the user did not start. CI runs a privacy check on the release APK
([.github/scripts/privacy-check.sh](.github/scripts/privacy-check.sh)): it fails on a library from
`.github/privacy/trackers.txt` and on any permission not listed, with a reason, in
`.github/privacy/permissions.txt`.
