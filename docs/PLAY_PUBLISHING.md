# Play Store publishing (signed AAB)

QuicLoc publishes to Google Play as a **signed Android App Bundle (`.aab`)**, built and
uploaded by the [`release-play.yml`](../.github/workflows/release-play.yml) workflow. The
GitHub-Releases APK pipeline (`build-apk.yml` / `ci-cd.yml`) is unchanged and still produces
debug APKs for sideloading.

## TL;DR

- Local signed bundle: create `keystore.properties` (below) and run `./gradlew bundleRelease`.
- CI: **automatic.** Publishing a GitHub release publishes to Play — the Play job is chained
  onto the release job (`needs:`), so the sideload APK goes out first and Play follows. It
  builds a signed `.aab`, **rolls it out on internal testing**, and stages the **same bundle
  as a draft** on closed testing, open testing and production. Nothing reaches the public
  until you promote one of those drafts.
- Both release paths chain it: **Build and Publish QuicLoc APK** (`workflow_dispatch`) and
  **CI/CD** on a `v*` tag. **Release to Play** can also still be run on its own.

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
    `ci-cd.yml` and `build-apk.yml` both build the AAB then hand it to this action to produce the
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
  - The core SMS + location permissions stay in the base on purpose: they're driven by a
    manifest `SmsReceiver` the system delivers broadcasts to, and the app must answer `loc` the
    moment it's installed. `READ_CONTACTS` is not declared anywhere in the app — the "Pick from
    Contacts" button uses `Intent.ACTION_PICK`, which needs no permission (see
    [PERMISSIONS.md](PERMISSIONS.md)). The `BIND_*` signature permissions (Notification Listener,
    Device Admin) were intentionally left in the base/module — see the PR discussion.
- **R8 / resource shrinking: enabled.** `isMinifyEnabled` and `isShrinkResources` are true in `release` builds to optimize performance and reduce APK size. ProGuard rules are configured in `app/proguard-rules.pro`.
  (Compose and CameraX ship their own consumer ProGuard rules.)
- **Play in-app updates (Play Core):** an optional future enhancement; not wired up.

### Sideload APK build (GitHub Releases)

The APK attached to a GitHub Release is **not** `assembleRelease`'s output. Both `ci-cd.yml` (on a `v*`
tag) and `build-apk.yml` (`workflow_dispatch`) instead:

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
> and commit it.

### Two ways this bites, and what the workflow does about it

A `versionCode` that is **equal** to one Play has seen is rejected at upload with a message that says
so: *"Version code N has already been used."* A `versionCode` that is merely **lower** than the live
one is accepted at upload and then rejected at rollout with something far less obvious:

> You cannot rollout this release because it does not allow any existing users to upgrade to the
> newly added APKs.

That is a version-code problem wearing a disguise — nobody on the higher code can "upgrade" to a
lower one. It is easy to hit after any change to how the code is computed. This repo has hit it
once already: Play held `255` from an early manual upload, a `+1000` offset was added to clear it
(uploads went out at ~`1193`), and when the offset was later removed in favour of `version.properties`
the counter dropped back to the 250s — below what was already live.

The publish workflow now asks Play directly rather than assuming:

1. **Preflight** lists every bundle and APK Play holds and records the highest `versionCode`.
2. **Before building**, it compares that against what `version.properties` would produce. In `auto`
   (the default, and what the chained callers use) `VERSION_D` is raised to Play's highest so the
   build produces *that + 1* — which cannot collide however far behind the file is. In `strict` it
   fails instead, naming the value to set.
3. **After building**, it re-checks the actual built code and refuses to upload if it can't clear
   Play.
4. **After publishing**, it **commits the consumed `versionCode` back to `main`** (`chore:
   versionCode N shipped to Play [skip ci]`). The build increments the counter on the runner only, so
   without this the file is stale the moment anything ships, and the next run would rebuild a number
   Play has already used. The commit is deliberately non-fatal — the bundle is published by then, so a
   protected branch or a push race must not turn a successful release red. It retries three times, and
   if it still can't push it says exactly what to set by hand.

Between (2) and (4), a stale `version.properties` can no longer block a release *or* silently repeat
a number: `auto` reads the floor from Play, and the commit-back makes the repo catch up.
`version.properties` also ships alongside the `.aab` artifact.

## Running the workflow

Usually you don't: publishing a GitHub release triggers it. Either

- **Actions → "Build and Publish QuicLoc APK" → Run workflow** — signed APK to a GitHub
  release, then Play; or
- **push a `v*` tag** — CI/CD builds the signed APK + AAB, attaches them to a GitHub release,
  then Play.

In both cases the Play publish is a separate job with `needs:` on the one that published the
release, so it cannot run first. (A release created by a workflow using the default
`GITHUB_TOKEN` does not itself trigger other workflows, which is why the chaining is explicit
rather than a `release: published` trigger.)

To run it alone: **Actions → "Release to Play" → Run workflow**. Inputs — the chained callers
pass `publish: true` and `version_code_mode: auto`:

| Input     | Default    | Meaning |
|-----------|------------|---------|
| `publish` | `true`     | **on** = build, upload to Play, roll out on internal, draft everywhere else; **off** = build + upload the `.aab` as a workflow artifact only, Play untouched |
| `completed_track` | `internal` | The one track that actually gets rolled out |
| `draft_tracks` | `alpha beta production` | Tracks that get the *same* bundle staged as a draft, space-separated. A track Play refuses is warned about and skipped |
| `release_notes` | empty | Optional en-US release notes attached to every track's release |
| `version_code_mode` | `auto` | `auto` = take the next code after whatever Play holds, so a stale `version.properties` can't block a release; `strict` = use `version.properties` exactly and fail if Play is ahead |

### One upload, every track

The bundle is uploaded **once**, inside a single Play "edit", and that one `versionCode` is then
assigned to every track before the edit is committed:

| Track | Status | Effect |
|---|---|---|
| internal | `completed` | rolled out to internal testers immediately |
| alpha (closed testing) | `draft` | staged in the console, nobody gets it |
| beta (open testing) | `draft` | staged in the console, nobody gets it |
| production | `draft` | staged in the console, nobody gets it |

So promoting a draft later ships **the exact artifact internal testers used** — same bytes, same
signature, same `versionCode`. There is no rebuild, and no second `versionCode` to burn.

This is why the publish step is hand-rolled against the Play API
([`.github/actions/play-publish`](../.github/actions/play-publish/action.yml)) instead of using
`r0adkll/upload-google-play`: that action opens its own edit and uploads the `.aab` per
invocation, so "the same bundle in four tracks" would mean four uploads of one `versionCode` —
and Play rejects a `versionCode` it has already accepted. The API is built for the single-edit
shape; the action isn't.

Failure behaviour is deliberate:

- The **completed track failing is fatal** — that's the one track a run exists to land. The edit
  is abandoned and nothing is published.
- A **draft track failing is a warning**. One track that isn't configured on the account
  shouldn't throw away a good internal rollout; the log and job summary say which ones landed.
- If the uploaded bundle's `versionCode` doesn't match what this run built, the edit is abandoned
  — that mismatch means a stale artifact.
- Play sometimes requires `changesNotSentForReview=true` on commit (apps whose changes can't be
  auto-submitted for review). The commit is retried with it automatically.

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
