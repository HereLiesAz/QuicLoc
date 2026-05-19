# Contributing

## Project layout

```
QuicLoc/
├── app/
│   ├── build.gradle.kts
│   ├── version.properties             ← auto-incrementing build counter
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml
│       │   ├── java/com/hereliesaz/quicloc/   ← all Kotlin lives here
│       │   └── res/
│       │       ├── layout/             ← only the widget + an unused whitelist_item
│       │       ├── values/             ← strings, colors, themes
│       │       └── xml/
│       │           ├── backup_rules.xml
│       │           ├── data_extraction_rules.xml
│       │           ├── device_admin.xml
│       │           └── widget_info.xml
│       └── test/                       ← Robolectric unit tests
├── docs/                                ← engineering docs (you are here)
├── gradle/libs.versions.toml           ← single source of truth for dep versions
├── build.gradle.kts                    ← project-level build
├── settings.gradle.kts
├── DECLARATIONS.md                     ← Play Console text
├── PRIVACY_POLICY.md                   ← published policy
└── README.md                           ← user-facing
```

## Build commands

| Command | Result |
|---|---|
| `./gradlew assembleDebug` | Debug APK at `app/build/outputs/apk/debug/` |
| `./gradlew assembleRelease` | Release APK |
| `./gradlew testDebugUnitTest` | Run all unit tests |
| `./gradlew printVersionName` | Print the current `versionName` (A.B.C.D) |
| `./gradlew lint` | Run Android Lint |

The build auto-increments `version.properties` on every `assemble*` invocation. If you don't want a build counter bump, do `./gradlew compileDebugKotlin` (or any task without `assemble`/`bundle` in the name) instead.

## Versioning

Scheme is `A.B.C.D`:

- **A** — Major. Manual.
- **B** — Feature. Manual, bumped when a meaningful feature lands.
- **C** — Build count within current `B`. Auto-incremented on each `assemble*`.
- **D** — Absolute total build count. Auto-incremented on each `assemble*`. Also serves as `versionCode`.

Manual bumps: edit `app/version.properties` directly. Don't try to set `versionCode` from outside that file — the Gradle config reads it dynamically.

## Code style

No formal lint config beyond the Android Gradle Plugin defaults. Conventions in use:

- 4-space indent (Kotlin convention).
- Wildcard imports for `androidx.compose.foundation.layout.*`, `material3.*`, `runtime.*` (most files already do this; keep it for consistency).
- KDoc on every public class and non-trivial public function. Don't KDoc the obvious (e.g., a `getX()` that's literally `return x`).
- Comments explain WHY, not WHAT. The system-prompt guidance applies in this repo too.
- No new comment blocks that just repeat the code; if a future reader could figure it out by reading the lines, the comment is wrong.

## Project conventions

- **`AppSettings` is for non-sensitive prefs only.** Anything that should be Keystore-encrypted goes in `WhitelistManager`. Anything that should also survive backup-restore goes in either `AppSettings` (auto-back-up) OR `WhitelistManager` (gets included in the PIN-encrypted `BackupVault` blob).
- **Mutations to encrypted prefs must trigger `BackupVault.snapshotAsync(appContext)`.** New `WhitelistManager` setters: don't forget this. The debounce window collapses bursts so calling it unnecessarily is cheap.
- **Foreground services declare types explicitly.** Don't pass `0` for the type on Android 14+ — declare in both the manifest (`foregroundServiceType`) and `ServiceCompat.startForeground` (`FOREGROUND_SERVICE_TYPE_*`).
- **Anything that touches `NotificationListener`'s parsing logic needs a manual test with at least WhatsApp, Telegram, and Signal.** It's the most fragile surface in the app and the one users notice when broken.
- **Don't add `BackupAgent`.** The current Auto-Backup + custom-blob-in-files design works without it. A `BackupAgent` would force a custom upgrade path for users.
- **Don't add HTTP calls.** Privacy policy says we don't. Play Store declaration says we don't. If you need a backend, that's a strategic decision, not a quiet refactor.

## Adding a new tutorial

Tutorials are static data in [Tutorial.kt](../app/src/main/java/com/hereliesaz/quicloc/Tutorial.kt). Add a new `private val` of type `Tutorial`, then include it in the `all` list. The hub picks it up automatically. No navigation wiring needed.

## Adding a new setting

1. Sensitivity? If it's a UI flag or preference: `AppSettings`. If it's anything the user shouldn't have visible on a rooted device: `WhitelistManager`.
2. In `WhitelistManager`: add the getter/setter, then call `BackupVault.snapshotAsync(appContext)` from the setter.
3. In `BackupVault`: if it's in `WhitelistManager`, add it to the JSON in `snapshot()` and read it back in `applyJson()`.
4. Surface in `QuicLocScreen` — pass through `MainActivity`'s state plumbing.

## Adding a permission

1. Declare in `AndroidManifest.xml`.
2. If runtime: add to `MainActivity.REQUIRED_PERMISSIONS` or its own launcher.
3. Update [PERMISSIONS.md](PERMISSIONS.md).
4. Update [DECLARATIONS.md](../DECLARATIONS.md) if sensitive enough for Play Console review.
5. Update [PRIVACY_POLICY.md](../PRIVACY_POLICY.md) if it implies new data access.

## Releasing

CI is configured in `.github/workflows/` to build and publish release APKs to GitHub Releases on tag push.

```bash
git tag v1.2.0
git push origin v1.2.0
```

Don't tag a release without:

- Updated `README.md` if user-facing behavior changed
- Updated `DECLARATIONS.md` if permissions changed
- Manual test pass on at least one Pixel and one Samsung
- Note in the GitHub Release description of any backup-format changes (with required migration plan)

## Files you can ignore in the repo root

`patch_*.py`, `test_*.kt`, `test_*.py`, `mms_research.py`, `search_mms*.py`, `req.py`, `repo_backup.txt` — these are author-side scaffolding from earlier iterations. Not part of the build. Slated for cleanup; don't depend on them.
