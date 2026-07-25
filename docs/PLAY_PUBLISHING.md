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
- **Dynamic feature module: `:feature_findmyphone` — CURRENTLY DISABLED.** The find-my-phone /
  lockdown feature is not shipped right now: the module is excluded from `settings.gradle.kts` and the
  app's `dynamicFeatures`, and `FindMyPhone.ENABLED` is `false`. So the published app declares no
  `CAMERA` / `USE_FULL_SCREEN_INTENT` / `BIND_DEVICE_ADMIN` and there is nothing extra to declare in
  the Play Console for it. The module's code remains in the repo. Re-enable by re-adding the module in
  both Gradle files and flipping `FindMyPhone.ENABLED`. The description below applies when enabled.
- **Dynamic feature module: `:feature_findmyphone` (on-demand, fused into APKs).** The **entire**
  find-my-phone / lockdown feature — the tracking foreground service, the PIN-gate lock screen, the
  Device Admin receiver, and the panic-mode intruder camera (CameraX) — is delivered as one on-demand
  module so the **base install declares none of that feature's sensitive surface** (`CAMERA`,
  `USE_FULL_SCREEN_INTENT`, the Device Admin receiver, the tracking FGS) until the user sets up
  find-my-phone. Flow:
  - `app/build.gradle.kts` lists `dynamicFeatures += setOf(":feature_findmyphone")`; the module
    (`com.android.dynamic-feature`, namespace `com.hereliesaz.quicloc.lockdown`) holds the CameraX +
    `android-smsmms` deps, the `CAMERA` / `USE_FULL_SCREEN_INTENT` permissions, the three lockdown
    components, and `dist:on-demand` delivery.
  - The base talks to it **only** through `FindMyPhone` (`app/.../FindMyPhone.kt`), which addresses the
    module by `ComponentName` string and degrades gracefully when the split isn't installed:
    `requestInstall` downloads it via `SplitInstall` (Play Feature Delivery), `trigger` starts the
    tracking service, and `isAdminActive`/`adminComponent` check Device Admin without referencing the
    receiver class. `QuicLocApp : SplitCompatApplication` + `SplitCompat.installActivity` make the
    freshly-installed code loadable in-process. There is no longer a reflective `IntruderCamera`
    boundary — the camera lives in the same split as its caller, `TrackingLockActivity`.
  - **`<dist:fusing dist:include="true"/>`** means the module is **fused into the monolithic
    `assembleDebug`/`assembleRelease` APKs**, so sideload users (the GitHub-Releases APK) still get
    find-my-phone with its components present at install time. The Play **AAB** path keeps it
    on-demand: it's downloaded via `SplitInstall` during find-my-phone setup and won't fetch from a
    bare `assembleRelease` APK.
  - **Setup is install-first.** Device Admin and `CAMERA` are only grantable *after* the module is
    installed (their declarations must be in the merged manifest before
    `ACTION_ADD_DEVICE_ADMIN` / the runtime CAMERA prompt can resolve). So `MainActivity`'s
    find-my-phone setup sequences: grant SMS/location → `FindMyPhone.requestInstall` → on installed,
    request `CAMERA` then prompt for Device Admin.
  - Needs **device testing** (cannot be covered by CI): on the **AAB** path confirm the module
    downloads at setup, the Device Admin prompt resolves post-install, a passphrase trigger launches
    the tracking service via `ComponentName`, and a build *without* the module degrades (a
    `loc <passphrase>` just doesn't start tracking; core `loc` still replies). On the **APK** path
    confirm the fused feature is present and works. Also verify the base AAB manifest carries no
    `CAMERA`/`USE_FULL_SCREEN_INTENT` and no lockdown components.
  - **Note for R8:** if `isMinifyEnabled` is ever turned on, the module's components are addressed by
    `ComponentName` string from the base, so keep them:
    `-keep class com.hereliesaz.quicloc.lockdown.** { *; }`.
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

| `keystore.properties` key | Gradle env var (CI sets it) | GitHub secret it comes from (CI) |
|---------------------------|-----------------------------|----------------------------------|
| `storeFile`               | `QUICLOC_KEYSTORE_FILE`      | `KEYSTORE_RAW` (or rebuilt from `KEYSTORE_PRIVATE`/`KEYSTORE_PUBLIC`) |
| `storePassword`           | `QUICLOC_KEYSTORE_PASSWORD`  | `KEYSTORE_PASSWORD`              |
| `keyAlias`                | `QUICLOC_KEY_ALIAS`          | `KEY_ALIAS`                      |
| `keyPassword`             | `QUICLOC_KEY_PASSWORD`       | `KEY_PASSWORD`                   |
| `storeType`               | `QUICLOC_KEYSTORE_TYPE`      | — (detected from the keystore's magic bytes) |

The Gradle env-var names are internal to the build; the workflows set them from the GitHub secrets in the third column.

There is one more env var with no `keystore.properties` equivalent: **`QUICLOC_REQUIRE_SIGNING=true`**.
Set it and an incomplete signing config becomes a build failure instead of an unsigned artifact. Every
publishing job sets it. Do not set it for PR CI, where unsigned is the correct outcome — fork PRs
cannot read secrets at all.

### How the keystore reaches the build

Two composite actions do this work, so all three workflows behave identically:

- **`.github/actions/android-keystore`** materialises the keystore, then proves it is the right one
  *before* anything is signed. It accepts the keystore whole (`KEYSTORE_RAW`, base64 or raw; JKS,
  JCEKS and PKCS#12 are detected from their magic bytes) or assembles a PKCS#12 from
  `KEYSTORE_PRIVATE`/`KEYSTORE_RSA` (private key) plus `KEYSTORE_PUBLIC` (certificate) and optionally
  `KEYSTORE_CHAIN`, in PEM or base64-wrapped PEM. It then reads the certificate back out with
  `keytool` and compares it against `KEYSTORE_SHA256` / `KEYSTORE_SHA1` / `KEYSTORE_OWNER`. A
  fingerprint mismatch fails the job: signing with the wrong key produces an APK that no existing
  install can upgrade to, and that cannot be undone after publication.
- **`.github/actions/verify-android-signature`** runs after the build and before anything is
  published. It runs `apksigner verify --print-certs` on an APK, or reads the signer certificate out
  of the `META-INF/*.RSA` block of an AAB, and fails if the artifact is unsigned or carries an
  unexpected certificate.

Together they close the gap that let an unsigned artifact reach a GitHub Release: Gradle silently
skips signing when the config is incomplete, so nothing downstream used to notice.

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

`versionCode`/`versionName` are driven **solely by `app/version.properties`** (`A.B.C.D`) — it is the
single source of truth. `versionCode = VERSION_D`, `versionName = A.B.C.D`. The counter
auto-increments on `assemble*`/`bundle*` (and writes `version.properties` back); no other task bumps
it. **There is no `-PversionBuild` override and no offset** — CI builds use exactly what
`version.properties` says.

To set the version, edit `app/version.properties` directly (e.g. bump `VERSION_D`) and commit it.

> **Play requires a strictly increasing `versionCode`.** Whatever `VERSION_D` you ship must be higher
> than the highest code Google Play has already accepted for the app. If a release is rejected with
> `Version code N has already been used`, raise `VERSION_D` in `version.properties` above that number
> and commit it. (For example, if Play already has `255`, set `VERSION_D` so the built code clears it.)

## Running the workflow

**Actions → "Release to Play" → Run workflow.** Run it from `main` (the `versionCode` derives
from the checked-out commit's history). Inputs:

| Input     | Default    | Meaning |
|-----------|------------|---------|
| `track`   | `internal` | `internal` / `alpha` / `beta` / `production` |
| `status`  | `draft`    | `draft` (review in console before going live) / `completed` |
| `publish` | `false`    | **off** = build + upload the `.aab` as a workflow artifact only; **on** = also upload to Play |

The workflow verifies the bundle's signature itself before the artifact is uploaded, and fails if it
is unsigned or signed by an unexpected certificate — so a green run means a correctly signed bundle.
To check by hand anyway:

```bash
jarsigner -verify -verbose -certs app-release.aab   # or: bundletool validate --bundle=...
```

## Required GitHub secrets

| Secret | Purpose |
|--------|---------|
| `KEYSTORE_RAW`              | `base64 -w0 upload.jks` — the upload keystore, base64-encoded |
| `KEYSTORE_PASSWORD`         | keystore (store) password |
| `KEY_ALIAS`                 | key alias |
| `KEY_PASSWORD`              | key password (leave unset if it is the same as the store password) |

Optional, and worth setting — they are what turns "the build signed something" into "the build signed
it with *our* key":

| Secret | Value |
|---|---|
| `KEYSTORE_SHA256`           | `keytool -list -v -keystore upload.jks -alias <alias>` → the `SHA256:` line. Checked before signing and again after. |
| `KEYSTORE_SHA1`             | the `SHA1:` line from the same output |
| `KEYSTORE_OWNER`            | the `Owner:` DN from the same output (mismatch warns rather than fails — the fingerprints are authoritative) |

Optional, only needed if you would rather store the key material split up than as a whole keystore.
`KEYSTORE_RAW` takes precedence when it is set:

| Secret | Value |
|---|---|
| `KEYSTORE_PRIVATE`          | the private key, PEM (`-----BEGIN PRIVATE KEY-----`) or base64-wrapped PEM or DER |
| `KEYSTORE_RSA`              | an alternative private-key source, used when `KEYSTORE_PRIVATE` is empty |
| `KEYSTORE_PUBLIC`           | the signing certificate, PEM |
| `KEYSTORE_CHAIN`            | any intermediate/root certificates, PEM |
| `PLAY_SERVICE_ACCOUNT_JSON` | Google Play service-account JSON (only needed when `publish=true`) |

`base64 -w0 upload.jks | pbcopy` (macOS) or `base64 -w0 upload.jks` (Linux) to get the `KEYSTORE_RAW` value.

## One-time maintainer setup

1. **Upload keystore** — generate it (above), keep it safe (losing it complicates updates
   unless Play App Signing is enabled), and add the `KEYSTORE_RAW` / `KEYSTORE_PASSWORD` /
   `KEY_ALIAS` / `KEY_PASSWORD` secrets.
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
