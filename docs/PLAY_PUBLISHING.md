# Play Store publishing (signed AAB)

QuicLoc publishes to Google Play as a **signed Android App Bundle (`.aab`)**, built and
uploaded by the [`release-play.yml`](../.github/workflows/release-play.yml) workflow. The
GitHub-Releases APK pipeline (`build-apk.yml` / `ci-cd.yml`) is unchanged and still produces
debug APKs for sideloading.

## TL;DR

- Local signed bundle: create `keystore.properties` (below) and run `./gradlew bundleRelease`.
- CI: run the **Release to Play** workflow (`workflow_dispatch`). It builds a signed `.aab`
  with a commit-count `versionCode`, uploads it as an artifact, and — only if you tick
  `publish` — pushes it to Play (default: `internal` track, `draft` status).

## Why AAB (and what about modular delivery)

- **App Bundle auto-splits.** Play generates per-device APKs split by **screen density** and
  **language** automatically from the single `.aab` — no extra artifacts or config. (There are
  no native libraries in this app, so there's no ABI split to worry about.)
- **Dynamic feature module: `:feature_camera` (on-demand).** The panic-mode intruder photo
  (CameraX) is delivered as an on-demand module so the **base install never declares the
  `CAMERA` permission** until the user sets up find-my-phone. Flow:
  - `app/build.gradle.kts` lists `dynamicFeatures += setOf(":feature_camera")`; the module
    (`com.android.dynamic-feature`) holds the CameraX deps + `CAMERA` permission +
    `dist:on-demand` delivery.
  - The base talks to it only through the `IntruderCamera` interface; `IntruderCameraLoader`
    downloads it via `SplitInstall` (Play Feature Delivery) and loads `IntruderCameraImpl`
    reflectively. `QuicLocApp : SplitCompatApplication` + `SplitCompat.installActivity` make the
    freshly-installed code loadable in-process.
  - Requires the app to be **bundle-delivered** (Play internal track or `bundletool`) for
    `SplitInstall` to actually fetch the module — it won't download from a bare `assembleRelease`
    APK. Needs **device testing**: confirm the module downloads at find-my-phone setup, that the
    intruder photo is captured, and that a build *without* the module still locks (no photo).
  - **Note for R8:** if `isMinifyEnabled` is ever turned on, add
    `-keep class com.hereliesaz.quicloc.camera.IntruderCameraImpl { *; }` — it's loaded by
    reflection and would otherwise be stripped/renamed.
  - The core SMS + location permissions stay in the base on purpose: they're driven by a
    manifest `SmsReceiver` the system delivers broadcasts to, and the app must answer `loc` the
    moment it's installed. Other optional perms (`READ_CONTACTS`, the `BIND_*` signature
    permissions) were intentionally left in the base — see the PR discussion.
- **R8 / resource shrinking: deferred.** `isMinifyEnabled` is still `false`. Enabling R8 +
  `shrinkResources` is the realistic size win, but this is a safety app that uses
  `android-smsmms` (third-party, may use reflection) and Tink-backed `EncryptedSharedPreferences`,
  so it must be **device-tested** before shipping. Start from a conservative keep set:
  ```
  -keep class com.klinker.android.send_message.** { *; }   # android-smsmms
  -keep class com.google.crypto.tink.** { *; }              # EncryptedSharedPreferences
  -keep class com.hereliesaz.quicloc.** extends android.content.BroadcastReceiver { *; }
  -keep class com.hereliesaz.quicloc.** extends android.app.Service { *; }
  ```
  (Compose and CameraX ship their own consumer ProGuard rules.)
- **Play in-app updates (Play Core):** an optional future enhancement; not wired up.

## Signing

`app/build.gradle.kts` defines a `release` signing config that reads, in order:

1. a git-ignored **`keystore.properties`** at the repo root (local development), or
2. **environment variables** (CI).

Signing is only attached to the `release` build type when this material is present, so an
unsigned `assembleRelease` (e.g. a PR build with no secrets) still succeeds.

| `keystore.properties` key | Environment variable        |
|---------------------------|-----------------------------|
| `storeFile`               | `QUICLOC_KEYSTORE_FILE`      |
| `storePassword`           | `QUICLOC_KEYSTORE_PASSWORD`  |
| `keyAlias`                | `QUICLOC_KEY_ALIAS`          |
| `keyPassword`             | `QUICLOC_KEY_PASSWORD`       |

**Local `keystore.properties` template** (repo root — git-ignored, never commit it):

```properties
storeFile=/absolute/path/to/upload.jks
storePassword=********
keyAlias=upload
keyPassword=********
```

Generate an upload keystore once:

```bash
keytool -genkeypair -v -keystore upload.jks -alias upload \
  -keyalg RSA -keysize 2048 -validity 9125
```

Then `./gradlew bundleRelease` → signed bundle at `app/build/outputs/bundle/release/app-release.aab`.

## versionCode

Locally, `versionCode`/`versionName` come from `app/version.properties` (`A.B.C.D`), with the
counter auto-incrementing on `assemble*`/`bundle*` — unchanged.

For Play, **pass an explicit, monotonic code** so uploads are never rejected for a duplicate
or lower `versionCode`:

```bash
./gradlew bundleRelease -PversionBuild=$(git rev-list --count HEAD)
```

When `-PversionBuild=<n>` is set, that value is used as the `versionCode` (and
`versionName` becomes `A.B.C.<n>`) and **`version.properties` is not modified** — the build is
deterministic and the tree stays clean. The CI workflow does exactly this.

> The commit count is larger than the current file-based code (52) — that one-time jump up is
> fine. Once you publish using the commit-count scheme, keep using it: `versionCode` may only
> ever increase.

## Running the workflow

**Actions → "Release to Play" → Run workflow.** Run it from `main` (the `versionCode` derives
from the checked-out commit's history). Inputs:

| Input     | Default    | Meaning |
|-----------|------------|---------|
| `track`   | `internal` | `internal` / `alpha` / `beta` / `production` |
| `status`  | `draft`    | `draft` (review in console before going live) / `completed` |
| `publish` | `false`    | **off** = build + upload the `.aab` as a workflow artifact only; **on** = also upload to Play |

Recommended first run: leave `publish` off, download the artifact, and confirm it's signed:

```bash
jarsigner -verify -verbose -certs app-release.aab   # or: bundletool validate --bundle=...
```

## Required GitHub secrets

| Secret | Purpose |
|--------|---------|
| `UPLOAD_KEYSTORE_BASE64`    | `base64 -w0 upload.jks` — the upload keystore |
| `UPLOAD_KEYSTORE_PASSWORD`  | keystore password |
| `UPLOAD_KEY_ALIAS`          | key alias |
| `UPLOAD_KEY_PASSWORD`       | key password |
| `PLAY_SERVICE_ACCOUNT_JSON` | Google Play service-account JSON (only needed when `publish=true`) |

`base64 -w0 upload.jks | pbcopy` (macOS) or `base64 -w0 upload.jks` (Linux) to get the value.

## One-time maintainer setup

1. **Upload keystore** — generate it (above), keep it safe (losing it complicates updates
   unless Play App Signing is enabled), and add the four `UPLOAD_*` secrets.
2. **Enable Play App Signing** for the app (recommended) so Google manages the app signing key
   and you only hold the upload key.
3. **Service account** — in Google Cloud, create a service account; in the **Play Console →
   Users & permissions** (and **Setup → API access**), grant it permission to manage releases.
   Download its JSON key and store it as `PLAY_SERVICE_ACCOUNT_JSON`.
4. **First release must be manual.** For a brand-new app, the very first `.aab` has to be
   uploaded **by hand** in the Play Console once (create the app + first internal release)
   before the API is allowed to publish subsequent builds. After that, `release-play.yml`
   handles uploads.

## Data safety / privacy

- The app has **no ads, no analytics, no crash reporting, and no third-party trackers** (only
  `play-services-location` and `play-services-auth`), so it declares **no advertising ID** —
  there is intentionally no `com.google.android.gms.permission.AD_ID` permission.
- Location is **shared device-to-device** with the contact who requested it, via SMS/MMS — it
  is **not collected by the developer** and never sent to any server. Reflect this in the Play
  **Data safety** form (location *shared*, not *collected*; user-initiated). See
  [`DECLARATIONS.md`](../DECLARATIONS.md) and [`PRIVACY_POLICY.md`](../PRIVACY_POLICY.md).
- `INTERNET` is used for Play Services location and MMS transport, not for telemetry.
