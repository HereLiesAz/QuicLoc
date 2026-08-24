# Reading an obfuscated crash

Release builds are minified (`isMinifyEnabled = true`), so every class in a
crash report from a shipped build arrives as a short generated name:

```
java.lang.NoClassDefFoundError: Failed resolution of: Lwf2;
	at pb2.<clinit>(r8-map-id-dead479d…:1)
	at ti3.t0(r8-map-id-dead479d…:136)
	at ul.run(r8-map-id-dead479d…:16)
```

`wf2`, `pb2`, `ti3` and `ul` mean nothing on their own. **Do not diagnose one
of these from the shape of the name or from which library "feels" involved.**
That has been tried twice on this repo and shipped two fixes that did not work
(see the note at the end).

## Step 1 — get the mapping file for the exact build

`r8-map-id-<hash>` in the stack frames is R8's build fingerprint. The same id
appears at the top of the `mapping.txt` that build produced, so a report can
always be matched to its build with certainty. Sources, in order of speed:

1. **Play Console** — Quality → Android vitals → Crashes shows the
   deobfuscated trace directly, because AGP embeds the map in the `.aab`
   (`BUNDLE-METADATA/com.android.tools.build.obfuscation/proguard.map`) and
   Play applies it automatically. Covers only crashes Play itself received.
2. **Play Console → App bundle explorer** → pick the versionCode → Downloads →
   the deobfuscation/mapping file. Use this for a trace reported out-of-band
   (a user pasting a stack trace, an in-app logger).
3. **The workflow run's `quicloc-mapping-…` artifact** — attached to every
   release build by `release-play.yml` and `build-apk.yml`, named with the
   versionCode, kept 90 days.

## Step 2 — deobfuscate, don't guess

```bash
# $ANDROID_HOME/cmdline-tools/latest/bin/retrace, or from R8's jar
retrace mapping.txt crash.txt
```

To resolve a single name by hand, the mapping is plain text, `original -> obfuscated`:

```bash
grep ' -> wf2:' mapping.txt     # what wf2 really is
grep ' -> pb2:' mapping.txt     # what class's <clinit> failed
```

If a name is **absent from mapping.txt**, that is itself the finding: the class
was never renamed (a `-keep` covers it) and something else is wrong.

## Step 3 — only then decide what kind of failure it is

- `ClassNotFoundException` thrown at a reflective call site (`Class.forName`,
  `Parcel.readParcelable`) → a **naming** problem; a keep rule is the fix.
- `NoClassDefFoundError: Failed resolution of: L…;` while linking a direct
  reference (typically in `<clinit>`) → the class is **genuinely absent from
  the running process**, not merely renamed. In an app with an on-demand
  dynamic feature (`:feature_findmyphone`), the usual cause is code in one
  split referencing a class R8 placed in another split that is not installed.
  Adding keep rules does not fix this and will not.

Adding `-keep` for a class that was never renamed changes nothing. Confirm
which of the two you have *before* writing a rule.

## Why this file exists

`NoClassDefFoundError: Failed resolution of: Lyf2;` was diagnosed as R8
renaming Play Feature Delivery classes, and fixed with
`-keep class com.google.android.play.core.** { *; }` (versionCode 2016). The
crash returned on that build as `Lwf2;` — and could not have been a Play Core
class, because `-keep` also prevents renaming, so no class under that package
was obfuscated in the build that crashed. The premise was wrong, and nothing
in the pipeline could have shown that, because `mapping.txt` was discarded with
the runner. The fix was verified only against its own hypothesis — that the
keep rule stopped those classes being renamed — never against the crash.

Verify against the failure, not against your explanation of it.

## A third report, still no mapping (`Leg2;`, r8-map-id `3c8b52ca…`)

A report matching this shape (`NoClassDefFoundError: Failed resolution of:
Leg2;` at `qb2.<clinit>`, r8-map-id `3c8b52ca65e9c22d78d3e6f0b34af6110a6c9174c76333e6c8e291b8870671e9`)
came in after the CI mapping-retention fix (`e1ca9be`) landed, so Step 1 of
this doc was actually followed instead of skipped:

- The only CI-retained mapping at the time (`quicloc-mapping-1.3.18.2021-vc2021`,
  from the `release-play.yml`/`build-apk.yml` runs at commit `18799bed`) has
  `pg_map_id: 57ac4f6982c30f673d525e60fc01b8c12a79603e4ce15d7cf8feb6ab8cc0ed2e` —
  it does not match. That mapping is for versionCode 2021, a build that never
  successfully published (every release-play run against `18799bed` failed).
- Every build before `e1ca9be` (versionCode ≤ ~2020, including 2017 — the
  last build that *did* publish successfully) has no mapping.txt anywhere in
  CI: the artifact-upload step didn't exist yet when those ran.

So this report's build is older than the mapping-retention fix, and its map
was never saved by CI. Per Step 1, the only remaining source is Play Console
— Android vitals if Play received this crash itself, or App Bundle
Explorer's per-versionCode download otherwise (Google keeps that copy for as
long as the bundle exists, unlike this repo's 90-day CI artifact). Nobody
added a third speculative keep rule for this one; do the same for the next
report shaped like this until someone actually retraces it.

### How it was actually found, without the mapping

Play Console access wasn't available either, so instead of waiting, the
reporter supplied context the trace itself didn't carry: the crash happens
on or immediately after the biometric-unlock screen, on a background thread,
with a logcat line showing `NotificationListener`'s connection dying
alongside the rest of the process (a symptom of the whole process dying, not
a cause). `MainActivity.autoDetectMyNumberOnFirstShow` calls
`Identity.getSignInClient(...).getPhoneNumberHintIntent(...)` the moment the
first-launch screen shows with no number set yet — right after
`unlock()` — which fit.

Confirmed by pulling `play-services-auth`'s AAR directly from Google's Maven
(`dl.google.com/android/maven2/...`) and disassembling its `classes.jar`
with `javap`, no app build required: `SignInCredential` — part of the same
Identity API surface `getPhoneNumberHintIntent` belongs to — declares a
field of type `com.google.android.gms.fido.fido2.api.common.PublicKeyCredential`,
and its `<clinit>` builds that class's Parcelable `CREATOR`. This app never
depended on `play-services-fido`, so that type is either absent from the
dex or gets shrunk as apparently-unreachable — either way, exercising the
Identity API can throw `NoClassDefFoundError` linking it, on whatever
executor thread Play Services' client library runs on, which is fatal to
the whole process by default since nothing on that thread can catch it.
Matches every detail: `NoClassDefFoundError` at a `<clinit>` (not a
reflective call site — no keep rule would have fixed this, consistent with
Step 3's case 2), background thread, right after biometric unlock. Fixed by
adding the `play-services-fido` dependency.

The lesson isn't "the mapping doesn't matter" — it's that when the mapping
truly can't be gotten, concrete circumstantial evidence (exact repro timing,
what code path runs there) plus verifying the hypothesis against the actual
dependency bytecode is a legitimate substitute for guessing blind. The two
failed fixes this file exists because of were guesses with *no* such
verification.
