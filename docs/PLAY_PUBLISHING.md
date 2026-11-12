# Play Store publishing (signed AAB)

QuicLoc uses **the generalized release executors in [HereLiesAz/workflows](https://github.com/HereLiesAz/workflows)**, not repository-specific release jobs. QuicLoc's own [Android Play Release](../.github/workflows/android-play-release.yml) and [Android GitHub Release](../.github/workflows/android-github-release.yml) files are short *trackers*; they do not build, publish or dispatch anything. Signed AABs and GitHub universal APKs are built centrally from the triggering QuicLoc commit.

The central registry entry is `HereLiesAz/workflows/registry/1177478037/manifest.json`; its Play and GitHub sources preserve the original trigger contracts. The publisher profiles in `HereLiesAz/workflows/scripts/semantic_catalog.py` supply QuicLoc's signing, build and artifact paths.

## TL;DR

- **Play:** A push to `main` builds a signed AAB. On successful Play approval/API submission, it publishes on internal testing and stages alpha, beta and production as drafts. Manual dispatch defaults to **build-only** unless `publish` is set.
- **GitHub:** Push a `v*` tag or dispatch the GitHub Release tracker. The central publisher builds a signed AAB, then uses QuicLoc's existing bundletool action to produce a **fused universal APK** including `:feature_findmyphone`.
- **Signing:** Central executors use the project-verified local `.github/actions/android-keystore` and `verify-android-signature` actions, with signing secrets from the QuicLoc environment of `HereLiesAz/workflows`.
- **Version:** `app/version.properties` remains authoritative. The central version tool observes Play's versionCode floor, and the Gradle build performs its existing version increment.
- **Play blocker:** The app still declares optional `RECEIVE_SMS`. Google Play's restricted-permission policy may reject the bundle. A passing CI build does **not** establish Play policy eligibility.

## Why AAB (and what about modular delivery)

- **App Bundle auto-splits.** Play generates per-device APKs split by **screen density** and
  **language** automatically from the single `.aab` — no extra artifacts or config. (There are
  no native libraries in this app, so there's no ABI split to worry about.)
- **Dynamic feature module: `:feature_findmyphone` (on-demand, fused into APKs).** The **entire**
  find-my-phone / lockdown feature — the tracking foreground service, the PIN-gate lock screen, the
  Device Admin receiver, and the panic-mode intruder camera (CameraX) — is delivered as one on-demand
  module so the **base install declares none of that feature's sensitive surface** (`CAMERA`,
  `USE_FULL_SCREEN_INTENT`, the Device Admin receiver, the tracking FGS) until the user sets up
  find-my-phone. Flow:
  - `app/build.gradle.kts` lists `dynamicFeatures += setOf(":feature_findmyphone")`; the module
    (`com.android.dynamic-feature`, namespace `com.hereliesaz.quicloc.lockdown`) holds the CameraX deps,
    the `CAMERA` / `USE_FULL_SCREEN_INTENT` permissions, the three lockdown components, and
    `dist:on-demand` delivery. `android-smsmms` (used by `TrackingService`, which lives in this module)
    is the one exception: it's declared in **both** this module and the base — see the next bullet and
    the comment on the dependency in `app/build.gradle.kts`.
  - **Content providers aren't supported in on-demand dynamic feature modules.** `android-smsmms`'s AAR
    manifest declares a `<provider>` (`MmsFileProvider`), and Android instantiates every declared
    provider unconditionally at process start, regardless of whether the owning on-demand split is
    installed. Declaring the dependency only in `:feature_findmyphone` merged that provider into the
    base app's manifest without guaranteeing its dex was present, so every launch before the module was
    installed crashed with `ClassNotFoundException: ...MmsFileProvider` at `handleBindApplication` — for
    any user who hadn't set up find-my-phone yet, i.e. most fresh installs. The fix: also declare
    `android-smsmms` (same pinned version, via the version catalog) as an `implementation` dependency of
    the base `:app` module, so the provider and its dex ship in the base install unconditionally. It
    stays declared in `:feature_findmyphone` too, purely so `TrackingService.kt` still compiles there.
  - The base talks to it **only** through `FindMyPhone` (`app/.../FindMyPhone.kt`), which addresses the
    module by `ComponentName` string and degrades gracefully when the split isn't installed:
    `requestInstall` downloads it via `SplitInstall` (Play Feature Delivery), `trigger` starts the
    tracking service, and `isAdminActive`/`adminComponent` check Device Admin without referencing the
    receiver class. `QuicLocApp : SplitCompatApplication` + `SplitCompat.installActivity` make the
    freshly-installed code loadable in-process. There is no longer a reflective `IntruderCamera`
    boundary — the camera lives in the same split as its caller, `TrackingLockActivity`.
  - **`<dist:fusing dist:include="true"/>`** marks the module for fusing, but that attribute is read by
    **bundletool, not by AGP's `assembleRelease`/`assembleDebug`** — `./gradlew assembleRelease` on the
    base module only ever builds the base module's own code, dynamic-feature modules never get folded
    in regardless of `dist:fusing`. This used to mean the plain base APK that was attached straight to
    GitHub Releases shipped with **zero** find-my-phone/lockdown code for every sideload user, silently,
    even in a build where the feature was otherwise fully wired up. The fix: build the signed `.aab`
    (`bundleRelease` — this already correctly contains every module) and then run it through
    bundletool's **universal mode** (`build-apks --mode=universal`, wrapped as the composite action
    [`.github/actions/build-universal-apk`](../.github/actions/build-universal-apk/action.yml)), which
    reads `dist:fusing` itself and produces one signed APK with every fusing-marked module folded in.
    The centralized Android GitHub Release executor builds the AAB then uses this action to produce the
    GitHub-Release APK — see "Sideload APK build" below. The Play **AAB** path was never affected by
    this bug: it keeps the module on-demand, downloaded via `SplitInstall` during find-my-phone setup.
  - **Setup is install-first.** Device Admin and `CAMERA` are only grantable *after* the module is
    installed (their declarations must be in the merged manifest before
    `ACTION_ADD_DEVICE_ADMIN` / the runtime CAMERA prompt can resolve). So `MainActivity`'s
    find-my-phone setup sequences: grant SMS/location → `FindMyPhone.requestInstall` → on installed,
    request `CAMERA` then prompt for Device Admin.
  - Needs **device testing** (cannot be covered by CI): on the **AAB/Play** path confirm the module
    downloads at setup, the Device Admin prompt resolves post-install, a passphrase trigger launches
    the tracking service via `ComponentName`, and (if you build a variant without the module) a build
    *without* it degrades gracefully (a `loc <passphrase>` just doesn't start tracking; core `loc` still
    replies). On the **sideload APK** path — the universal APK bundletool produces, not a plain
    `assembleRelease` build — confirm the fused feature is actually present and reachable at install
    time (find-my-phone setup completes without ever downloading anything, since the module's already
    in the APK) and works end-to-end. Also verify the base AAB manifest carries no
    `CAMERA`/`USE_FULL_SCREEN_INTENT` and no lockdown components until the module installs.
  - **Note for R8:** `isMinifyEnabled` is true, and the module's components are addressed by
    `ComponentName` string from the base, not a compile-time class reference, so R8 has no call site to
    infer keep rules from. `app/proguard-rules.pro` keeps them explicitly:
    `-keep class com.hereliesaz.quicloc.lockdown.** { *; }` (same reasoning for
    `-keep class com.klinker.android.send_message.** { *; }`, the MMS library, which drives sending via
    reflection). Losing either rule doesn't fail the build — it fails at runtime, with the module's
    classes present but unreachable by name once R8 has renamed/stripped them.
  - **androidx.tracing / guava:listenablefuture version pinning.** With the module enabled, R8 used to
    fail the release/bundle build outright with `Type ... is defined multiple times` — `:app` and
    `:feature_findmyphone` were pulling in different transitive versions of `androidx.tracing` (and
    guava's empty `listenablefuture` shim), so R8 saw two copies of the same class at merge time. Both
    modules now `implementation` the exact same pinned versions
    (`libs.androidx.tracing`, `libs.guava.listenablefuture` in `gradle/libs.versions.toml`), so R8 sees
    one copy of each. Without this fix, the feature literally could not ship — `bundleRelease` failed
    before producing an artifact at all.
  - The base declares outgoing `SEND_SMS`, location, and **optional** `RECEIVE_SMS`. During
    setup either direct SMS reception or Notification Access may satisfy incoming SMS listening.
    Notification Access is required for non-SMS messaging services. Android users can decline
    `RECEIVE_SMS`, but Play still evaluates it as a restricted declared permission. `READ_CONTACTS` is not declared anywhere in the app — the "Pick from
    Contacts" button uses `Intent.ACTION_PICK`, which needs no permission (see
    [PERMISSIONS.md](PERMISSIONS.md)). The `BIND_*` signature permissions (Notification Listener,
    Device Admin) were intentionally left in the base/module — see the PR discussion.
- **R8 / resource shrinking: enabled.** `isMinifyEnabled` and `isShrinkResources` are true in `release` builds to optimize performance and reduce APK size. ProGuard rules are configured in `app/proguard-rules.pro`.
  (Compose and CameraX ship their own consumer ProGuard rules.)
- **Play in-app updates (Play Core):** an optional future enhancement; not wired up.

### Sideload APK build (GitHub Releases)

The GitHub Release APK is **not** `assembleRelease`'s output. The shared Android GitHub Release executor (on a `v*` tag or manual dispatch) instead:

1. `./gradlew bundleRelease` — build the signed `.aab`, which already contains every module correctly.
2. Run [`.github/actions/build-universal-apk`](../.github/actions/build-universal-apk/action.yml), which
   downloads a pinned `bundletool` release and runs
   `build-apks --bundle=<aab> --mode=universal --ks=<keystore> ...`, then unzips the single
   `universal.apk` bundletool produces — already signed with the same keystore Gradle used, no separate
   `apksigner` step needed.
3. The resulting universal APK is verified with
   [`.github/actions/verify-android-signature`](../.github/actions/verify-android-signature) before it's
   attached to the release, same as the AAB.

This is what makes the sideload APK a genuinely fused build: it's the one artifact in the whole pipeline
built specifically by reading `dist:fusing`, rather than assuming AGP does it.

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

QuicLoc's existing composite actions are invoked by both central release executors:

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

## versionCode and versionName

QuicLoc's authoritative version file is **`app/version.properties`**, with `VERSION_A`, `VERSION_B`, `VERSION_C` and `VERSION_D`. The Android Gradle build uses `VERSION_D` as the versionCode and `A.B.C.D` as the versionName. On each `assemble*` or `bundle*` task, it increments both `VERSION_C` and `VERSION_D` exactly once.

The central publisher's Android version action reads this file, compares `VERSION_D` with the highest versionCode held by Google Play, and selects the next available code. For QuicLoc only, the build profile temporarily seeds `VERSION_D` to one below that code, allowing Gradle's own increment to produce the intended value. After successful release, the central action records the published `VERSION_C` and `VERSION_D` back to `app/version.properties` on `main`. It does not create a competing root `version.properties`.

Play requires strictly increasing versionCodes, including drafts and previously uploaded bundles. An obsolete historical `+1000` offset is not reintroduced. The file's current code is recomputed by the central version action before each publishing build.

## Running the centralized workflows

The actual builders are [Android GitHub Release](https://github.com/HereLiesAz/workflows/blob/main/.github/workflows/android-github-release.yml) and [Android Play Release](https://github.com/HereLiesAz/workflows/blob/main/.github/workflows/android-play-release.yml).

- **Play:** A push to QuicLoc `main` starts the registered central Play workflow. Its manual `publish` input defaults to false. Internal is completed and alpha/beta/production are drafts when published.
- **GitHub:** A push of a QuicLoc tag matching `v*`, or a manual dispatch, starts the registered GitHub release workflow. The signed, bundletool-fused universal APK is published with its exact version in the asset name.

The QuicLoc Actions tracker merely points to the central commit status. GitHub reports the real build result on the triggering commit; success of the short tracker itself is not evidence that publishing succeeded.

### One upload, every track

The bundle is uploaded **once**, inside a single Play "edit", and that one `versionCode` is then
assigned to every track before the edit is committed:

With the default inputs:

| Track | Status | Effect |
|---|---|---|
| internal | `completed` | rolled out to internal testers immediately |
| alpha (closed testing) | `draft` | staged in the console, nobody gets it |
| beta (open testing) | `draft` | staged in the console, nobody gets it |
| production | `draft` | staged in the console, nobody gets it |

So promoting a draft later ships **the exact artifact internal testers used** — same bytes, same
signature, same `versionCode`. There is no rebuild, and no second `versionCode` to burn.

A draft is deliberately invisible: it sits in the console until a human promotes it, so a green
run does **not** mean testers on alpha/beta received anything. To roll a track out for real in the
same run, name it in `completed_tracks` (e.g. `internal alpha`) rather than leaving it in
`draft_tracks`.

This is why the publish step is hand-rolled against the Play API
([`.github/actions/play-publish`](../.github/actions/play-publish/action.yml)) instead of using
`r0adkll/upload-google-play`: that action opens its own edit and uploads the `.aab` per
invocation, so "the same bundle in four tracks" would mean four uploads of one `versionCode` —
and Play rejects a `versionCode` it has already accepted. The API is built for the single-edit
shape; the action isn't.

Failure behaviour is deliberate:

- A **completed track failing is fatal** — those are the tracks a run exists to land. The edit
  is abandoned and nothing is published.
- A **draft track failing is a warning**. One track that isn't configured on the account
  shouldn't throw away a good internal rollout; the log and job summary say which ones landed.
- If the uploaded bundle's `versionCode` doesn't match what this run built, the edit is abandoned
  — that mismatch means a stale artifact.
- Whether Play accepts `changesNotSentForReview=true` on commit depends on the app's current
  state, and **it changes over the app's lifetime**. Some apps' edits can't be auto-submitted for
  review and the flag is required; others (this one, as of August 2026) submit for review
  automatically and reject the flag outright with
  `"Changes are sent for review automatically. The query parameter changesNotSentForReview must
  not be set."` The commit therefore tries it, and drops it and retries if Play objects
  specifically to the flag. This matters more than it sounds: a rejected commit discards the
  **entire** edit, so hard-coding the wrong answer means neither the rollout nor the drafts land.

- After the commit, the tracks are read back and checked to actually carry the new `versionCode`.
  Track state is only readable *inside* an edit, so a throwaway edit is opened for the read and
  deleted without being committed. A completed track missing the version is fatal; a draft track
  missing it is a warning.

The central publisher invokes QuicLoc's signature verification composite action before uploading a bundle, and fails if the signing certificate does not match the expected signing key.
To check by hand anyway:

```bash
jarsigner -verify -verbose -certs app-release.aab   # or: bundletool validate --bundle=...
```

## Required GitHub secrets

Configure these in the **QuicLoc environment of `HereLiesAz/workflows`**, where both central jobs execute. The local actions use the same secret names; there is no separate release implementation in QuicLoc.

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
   before the API is allowed to publish subsequent builds. After that, the central Android Play Release workflow handles uploads.

## Data safety / privacy

- The app has **no ads, no analytics, no crash reporting, and no third-party trackers** (only
  `play-services-location` and `play-services-auth`), so it declares **no advertising ID** —
  there is intentionally no `com.google.android.gms.permission.AD_ID` permission.
- Location is **shared device-to-device** with the contact who requested it, via SMS/MMS — it
  is **not collected by the developer** and never sent to any server. Reflect this in the Play
  **Data safety** form (location *shared*, not *collected*; user-initiated). See
  [`DECLARATIONS.md`](../DECLARATIONS.md) and [`PRIVACY_POLICY.md`](../PRIVACY_POLICY.md).
- `INTERNET` is used for Play Services location and MMS transport, not for telemetry.
